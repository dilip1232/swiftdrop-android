package com.swiftdrop

import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Streams a file (given as a content URI) straight to a peer's /inbox using a
 * fixed-length HTTP body — no buffering of the whole file, so large transfers
 * run at full LAN speed. Progress is reported through the [Transfer].
 *
 * When the peer is paired, the stream is encrypted with AES-256-GCM (integrity
 * comes from GCM, so no separate SHA-256 hash is needed).
 */
object Sender {
    private const val BUF = 256 * 1024
    private const val CHUNK_PLAIN = 256 * 1024
    private const val NONCE_SIZE = 12
    private const val TAG_OVERHEAD = 16

    /** Compute the on-wire size of an encrypted stream, matching Go's EncryptedSize(). */
    private fun encryptedSize(plain: Long): Long {
        if (plain <= 0) return -1
        val nChunks = (plain + CHUNK_PLAIN - 1) / CHUNK_PLAIN
        // nonce + (4-byte len header + ciphertext per chunk) + 4-byte end marker
        return NONCE_SIZE + nChunks * (4 + CHUNK_PLAIN + TAG_OVERHEAD) -
                // last chunk may be shorter
                (nChunks * CHUNK_PLAIN - plain) + 4
    }

    fun sendUri(peer: Peer, uri: Uri) {
        val cr = State.appContext.contentResolver

        var name = ""
        var size = -1L
        runCatching {
            cr.query(uri, null, null, null, null)?.use { c ->
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst()) {
                    if (ni >= 0) c.getString(ni)?.let { name = it }
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        // Fall back to the URI's last path segment (e.g. for file:// URIs that
        // don't expose DISPLAY_NAME) so received files are never just "file".
        if (name.isBlank()) {
            name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file"
        }

        val key = PairStore.isPaired(peer.id)
        val encrypted = key != null

        val t = State.newTransfer(name, if (size < 0) 0 else size, peer.name, "send").also {
            it.uri = uri.toString()
            it.peerId = peer.id
        }
        Notifier.refreshServiceNotification()
        PowerLocks.begin()
        var conn: HttpURLConnection? = null
        try {
            val wireSize = if (encrypted) encryptedSize(size) else size

            conn = (URL("http://${peer.host}/inbox").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/octet-stream")
                setRequestProperty("X-Filename", name)
                setRequestProperty("X-From", State.deviceName)
                setRequestProperty("X-From-ID", State.deviceId)
                if (size > 0) setRequestProperty("X-File-Size", size.toString())
                if (encrypted) setRequestProperty("X-Encrypted", "aes-gcm-v2")
                // HMAC sender authentication.
                if (key != null) {
                    val ts = (System.currentTimeMillis() / 1000).toString()
                    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
                    mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
                    val sig = mac.doFinal("${State.deviceId}|$name|$ts".toByteArray())
                    setRequestProperty("X-Auth-HMAC", sig.joinToString("") { "%02x".format(it) })
                    setRequestProperty("X-Auth-Time", ts)
                }
                connectTimeout = 8000
                readTimeout = 0 // no timeout for large encrypted writes
                if (wireSize >= 0) setFixedLengthStreamingMode(wireSize) else setChunkedStreamingMode(BUF)
            }
            t.conn = conn

            cr.openInputStream(uri).use { input ->
                requireNotNull(input) { "cannot open file" }
                if (encrypted) {
                    // Wrap input with a counting stream for progress.
                    val counting = CountingInputStream(input, t)
                    val bufferedOut = BufferedOutputStream(conn.outputStream, BUF)
                    Crypto.encryptStream(bufferedOut, counting, key!!)
                    bufferedOut.flush()
                } else {
                    BufferedOutputStream(conn.outputStream, BUF).use { out ->
                        val buf = ByteArray(BUF)
                        while (true) {
                            if (t.canceled) { conn.disconnect(); return }
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            t.sent.addAndGet(n.toLong())
                        }
                        out.flush()
                    }
                }
            }

            val code = conn.responseCode
            conn.disconnect()
            if (code != 200) {
                t.status = "error"; t.err = "peer returned $code"
            } else {
                t.status = "done"
            }
        } catch (e: Exception) {
            if (t.canceled) t.status = "canceled"
            else { t.status = "error"; t.err = e.message }
        } finally {
            t.conn = null
            Notifier.refreshServiceNotification()
            PowerLocks.end()
        }
    }

    /**
     * Zips a document tree (from OpenDocumentTree) on-the-fly and streams it
     * to the peer as a single transfer with X-Folder: zip.
     */
    fun sendFolder(peer: Peer, treeUri: Uri, folderName: String, totalSize: Long, fileCount: Int) {
        val cr = State.appContext.contentResolver
        val key = PairStore.isPaired(peer.id)
        val encrypted = key != null

        val t = State.newTransfer(folderName, totalSize, peer.name, "send").also {
            it.uri = treeUri.toString()
            it.peerId = peer.id
        }
        Notifier.refreshServiceNotification()
        PowerLocks.begin()
        var conn: HttpURLConnection? = null
        try {
            // Pipe: zip writer in background thread → reader consumed by HTTP.
            val pipedOut = PipedOutputStream()
            val pipedIn = PipedInputStream(pipedOut, BUF)
            val zipError = arrayOfNulls<Exception>(1)
            val zipThread = Thread {
                try {
                    ZipOutputStream(BufferedOutputStream(pipedOut, BUF)).use { zos ->
                        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
                        zipTreeChildren(cr, treeUri, rootDocId, "", zos, t)
                    }
                } catch (e: Exception) {
                    zipError[0] = e
                } finally {
                    runCatching { pipedOut.close() }
                }
            }
            zipThread.start()

            conn = (URL("http://${peer.host}/inbox").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/octet-stream")
                setRequestProperty("X-Filename", folderName)
                setRequestProperty("X-From", State.deviceName)
                setRequestProperty("X-From-ID", State.deviceId)
                if (totalSize > 0) setRequestProperty("X-File-Size", totalSize.toString())
                setRequestProperty("X-Folder", "zip")
                if (encrypted) setRequestProperty("X-Encrypted", "aes-gcm-v2")
                if (key != null) {
                    val ts = (System.currentTimeMillis() / 1000).toString()
                    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
                    mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
                    val sig = mac.doFinal("${State.deviceId}|$folderName|$ts".toByteArray())
                    setRequestProperty("X-Auth-HMAC", sig.joinToString("") { "%02x".format(it) })
                    setRequestProperty("X-Auth-Time", ts)
                }
                connectTimeout = 8000
                readTimeout = 0
                setChunkedStreamingMode(BUF) // zip size is unknown
            }
            t.conn = conn

            // Progress is tracked by raw bytes read in zipTreeChildren,
            // not by zip/encrypted output bytes, so it matches totalSize.
            if (encrypted) {
                val bufferedOut = BufferedOutputStream(conn.outputStream, BUF)
                Crypto.encryptStream(bufferedOut, pipedIn, key!!)
                bufferedOut.flush()
            } else {
                BufferedOutputStream(conn.outputStream, BUF).use { out ->
                    val buf = ByteArray(BUF)
                    while (true) {
                        if (t.canceled) { conn.disconnect(); return }
                        val n = pipedIn.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                    out.flush()
                }
            }
            pipedIn.close()
            zipThread.join()
            if (zipError[0] != null) throw zipError[0]!!

            val code = conn.responseCode
            conn.disconnect()
            if (code != 200) {
                t.status = "error"; t.err = "peer returned $code"
            } else {
                t.status = "done"
            }
        } catch (e: Exception) {
            if (t.canceled) t.status = "canceled"
            else { t.status = "error"; t.err = e.message }
        } finally {
            t.conn = null
            Notifier.refreshServiceNotification()
            PowerLocks.end()
        }
    }

    /** Recursively add files from a document tree into a ZipOutputStream. */
    private fun zipTreeChildren(
        cr: android.content.ContentResolver, treeUri: Uri,
        parentDocId: String, prefix: String,
        zos: ZipOutputStream, t: Transfer
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        cr.query(childrenUri, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        ), null, null, null)?.use { c ->
            val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (c.moveToNext()) {
                if (t.canceled) return
                val docId = c.getString(idIdx)
                val entryName = c.getString(nameIdx) ?: docId
                val mime = c.getString(mimeIdx)
                val entryPath = if (prefix.isEmpty()) entryName else "$prefix/$entryName"
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    zos.putNextEntry(ZipEntry("$entryPath/"))
                    zos.closeEntry()
                    zipTreeChildren(cr, treeUri, docId, entryPath, zos, t)
                } else {
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    zos.putNextEntry(ZipEntry(entryPath))
                    cr.openInputStream(docUri)?.use { input ->
                        val buf = ByteArray(BUF)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            zos.write(buf, 0, n)
                            t.sent.addAndGet(n.toLong())
                        }
                    }
                    zos.closeEntry()
                }
            }
        }
    }

    /** InputStream wrapper that updates a Transfer's sent counter and checks for cancellation. */
    private class CountingInputStream(
        private val inner: InputStream,
        private val transfer: Transfer
    ) : InputStream() {
        override fun read(): Int {
            transfer.awaitIfPaused()
            if (transfer.canceled) throw java.io.IOException("canceled")
            val b = inner.read()
            if (b >= 0) transfer.sent.incrementAndGet()
            return b
        }
        override fun read(buf: ByteArray, off: Int, len: Int): Int {
            transfer.awaitIfPaused()
            if (transfer.canceled) throw java.io.IOException("canceled")
            val n = inner.read(buf, off, len)
            if (n > 0) transfer.sent.addAndGet(n.toLong())
            return n
        }
        override fun close() = inner.close()
    }
}
