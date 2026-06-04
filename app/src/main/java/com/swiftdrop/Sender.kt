package com.swiftdrop

import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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
     * Sends a folder to a peer using the parallel file-by-file protocol:
     * 1) Announce → get consent + session token
     * 2) Walk tree → send each file in parallel (4 threads)
     * 3) Signal done
     */
    fun sendFolder(peer: Peer, treeUri: Uri, folderName: String, totalSize: Long, fileCount: Int) {
        val cr = State.appContext.contentResolver
        val key = PairStore.isPaired(peer.id)

        val t = State.newTransfer("\uD83D\uDCC1 $folderName", totalSize, peer.name, "send").also {
            it.uri = treeUri.toString()
            it.peerId = peer.id
        }
        Notifier.refreshServiceNotification()
        PowerLocks.begin()
        try {
            // Phase 1: announce → session token.
            t.status = "preparing"
            val session = announceFolderToPeer(peer, key, folderName, totalSize, fileCount)
                ?: run { t.status = "error"; t.err = "announce rejected"; return }
            t.status = "sending"

            // Phase 2: walk tree, collect files, send in parallel (4 threads).
            data class FileEntry(val docUri: Uri, val relPath: String, val size: Long)
            val files = mutableListOf<FileEntry>()
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            fun walk(parentDocId: String, prefix: String) {
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
                cr.query(childrenUri, arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE
                ), null, null, null)?.use { c ->
                    val idIdx  = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    while (c.moveToNext()) {
                        val docId = c.getString(idIdx)
                        val entryName = c.getString(nameIdx) ?: docId
                        val mime = c.getString(mimeIdx)
                        val entryPath = if (prefix.isEmpty()) entryName else "$prefix/$entryName"
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            walk(docId, entryPath)
                        } else {
                            val sz = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L
                            files.add(FileEntry(
                                DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                                entryPath, sz
                            ))
                        }
                    }
                }
            }
            walk(rootDocId, "")

            val firstErr = AtomicReference<String?>(null)
            val okCount = AtomicInteger(0)
            val pool = Executors.newFixedThreadPool(4)
            for (fe in files) {
                pool.submit {
                    if (t.canceled) return@submit
                    try {
                        sendFolderFile(peer, key, fe.docUri, fe.relPath, fe.size, session, t)
                        okCount.incrementAndGet()
                    } catch (e: Exception) {
                        firstErr.compareAndSet(null, e.message ?: "send failed")
                        android.util.Log.w("SwiftDrop", "folder-file ${fe.relPath} failed: ${e.message}")
                    }
                }
            }
            pool.shutdown()
            pool.awaitTermination(Long.MAX_VALUE, java.util.concurrent.TimeUnit.MILLISECONDS)

            // Phase 3: signal done (always, even on partial failure/cancel).
            sendFolderDone(peer, key, folderName, session, cancelled = t.canceled)

            if (t.canceled) {
                t.status = "canceled"
            } else if (firstErr.get() != null && okCount.get() < files.size) {
                t.status = "error"
                t.err = "${okCount.get()}/${files.size} files sent: ${firstErr.get()}"
            } else {
                t.status = "done"
            }
        } catch (e: Exception) {
            if (t.canceled) t.status = "canceled"
            else { t.status = "error"; t.err = e.message }
        } finally {
            Notifier.refreshServiceNotification()
            PowerLocks.end()
        }
    }

    /** Send folder announce → returns session token or null if rejected. */
    private fun announceFolderToPeer(
        peer: Peer, key: ByteArray?, folderName: String, totalSize: Long, fileCount: Int
    ): String? {
        val conn = (URL("http://${peer.host}/inbox").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = false  // no body
            setRequestProperty("X-Filename", folderName)
            setRequestProperty("X-From", State.deviceName)
            setRequestProperty("X-From-ID", State.deviceId)
            setRequestProperty("X-File-Size", totalSize.toString())
            setRequestProperty("X-Folder-Announce", "true")
            setRequestProperty("X-Folder-Count", fileCount.toString())
            if (key != null) {
                val ts = (System.currentTimeMillis() / 1000).toString()
                val mac = javax.crypto.Mac.getInstance("HmacSHA256")
                mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
                val sig = mac.doFinal("${State.deviceId}|$folderName|$ts".toByteArray())
                setRequestProperty("X-Auth-HMAC", sig.joinToString("") { "%02x".format(it) })
                setRequestProperty("X-Auth-Time", ts)
            }
            setRequestProperty("Content-Length", "0")
            connectTimeout = 8000
            readTimeout = 65_000 // receiver waits up to 60s for consent
        }
        return try {
            if (conn.responseCode != 200) { conn.disconnect(); return null }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            org.json.JSONObject(body).optString("session", "").takeIf { it.isNotEmpty() }
        } catch (_: Exception) { conn.disconnect(); null }
    }

    /** Send a single file belonging to a folder session. */
    private fun sendFolderFile(
        peer: Peer, key: ByteArray?, docUri: Uri, relPath: String,
        fileSize: Long, session: String, t: Transfer
    ) {
        val cr = State.appContext.contentResolver
        val encrypted = key != null
        // Use sanitized relPath as X-Filename for unique HMAC (matches Go sender).
        val hmacName = relPath.replace('/', '_')

        val wireSize = if (encrypted) encryptedSize(fileSize) else fileSize
        val conn = (URL("http://${peer.host}/inbox").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("X-Filename", hmacName)
            setRequestProperty("X-From", State.deviceName)
            setRequestProperty("X-From-ID", State.deviceId)
            setRequestProperty("X-File-Size", fileSize.toString())
            setRequestProperty("X-Folder-Session", session)
            setRequestProperty("X-Folder-Rel", relPath)
            if (encrypted) setRequestProperty("X-Encrypted", "aes-gcm-v2")
            if (key != null) {
                val ts = (System.currentTimeMillis() / 1000).toString()
                val mac = javax.crypto.Mac.getInstance("HmacSHA256")
                mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
                val sig = mac.doFinal("${State.deviceId}|$hmacName|$ts".toByteArray())
                setRequestProperty("X-Auth-HMAC", sig.joinToString("") { "%02x".format(it) })
                setRequestProperty("X-Auth-Time", ts)
            }
            connectTimeout = 8000
            readTimeout = 0
            if (wireSize >= 0) setFixedLengthStreamingMode(wireSize) else setChunkedStreamingMode(BUF)
        }
        try {
            cr.openInputStream(docUri).use { input ->
                requireNotNull(input) { "cannot open $relPath" }
                if (encrypted) {
                    val counting = CountingInputStream(input, t)
                    val bufferedOut = BufferedOutputStream(conn.outputStream, BUF)
                    Crypto.encryptStream(bufferedOut, counting, key!!)
                    bufferedOut.flush()
                } else {
                    BufferedOutputStream(conn.outputStream, BUF).use { out ->
                        val buf = ByteArray(BUF)
                        while (true) {
                            if (t.canceled) throw java.io.IOException("canceled")
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
            if (code != 200) throw java.io.IOException("peer returned $code")
        } finally {
            conn.disconnect()
        }
    }

    /** Signal the receiver that the folder transfer is complete. */
    private fun sendFolderDone(peer: Peer, key: ByteArray?, folderName: String, session: String, cancelled: Boolean = false) {
        val conn = (URL("http://${peer.host}/inbox").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = false
            setRequestProperty("X-Filename", folderName)
            setRequestProperty("X-From", State.deviceName)
            setRequestProperty("X-From-ID", State.deviceId)
            setRequestProperty("X-Folder-Done", session)
            if (cancelled) setRequestProperty("X-Folder-Cancelled", "true")
            if (key != null) {
                val ts = (System.currentTimeMillis() / 1000).toString()
                val mac = javax.crypto.Mac.getInstance("HmacSHA256")
                mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
                val sig = mac.doFinal("${State.deviceId}|$folderName|$ts".toByteArray())
                setRequestProperty("X-Auth-HMAC", sig.joinToString("") { "%02x".format(it) })
                setRequestProperty("X-Auth-Time", ts)
            }
            setRequestProperty("Content-Length", "0")
            connectTimeout = 5000
            readTimeout = 5000
        }
        try { conn.responseCode; conn.disconnect() } catch (_: Exception) { conn.disconnect() }
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
