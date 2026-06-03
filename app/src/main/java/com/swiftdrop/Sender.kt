package com.swiftdrop

import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

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
                if (encrypted) setRequestProperty("X-Encrypted", "aes-gcm")
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

    /** InputStream wrapper that updates a Transfer's sent counter and checks for cancellation. */
    private class CountingInputStream(
        private val inner: InputStream,
        private val transfer: Transfer
    ) : InputStream() {
        override fun read(): Int {
            if (transfer.canceled) throw java.io.IOException("canceled")
            val b = inner.read()
            if (b >= 0) transfer.sent.incrementAndGet()
            return b
        }
        override fun read(buf: ByteArray, off: Int, len: Int): Int {
            if (transfer.canceled) throw java.io.IOException("canceled")
            val n = inner.read(buf, off, len)
            if (n > 0) transfer.sent.addAndGet(n.toLong())
            return n
        }
        override fun close() = inner.close()
    }
}
