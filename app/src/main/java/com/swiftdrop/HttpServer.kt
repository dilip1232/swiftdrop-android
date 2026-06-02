package com.swiftdrop

import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.graphics.Bitmap
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * The peer-facing + UI HTTP server. Mirrors the Mac app's contract:
 *   /inbox          receive a streamed file -> Downloads/SwiftDrop (MediaStore)
 *   /api/me         this device's identity
 *   /api/devices    discovered peers
 *   /api/transfers  live send progress
 *   /api/send-path  start sending the given content URIs to a peer
 *   /               the web UI (from assets)
 */
class HttpServer : NanoHTTPD(State.PORT) {

    override fun serve(session: IHTTPSession): Response = try {
        val uri = session.uri
        when {
            // Public endpoints: peers call these
            uri == "/inbox" && session.method == Method.POST -> handleInbox(session)
            uri == "/api/me" -> json(meJson())
            uri == "/health" -> newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "ok")
            // Token endpoint: only from localhost (the embedded WebView)
            uri == "/api/token" -> handleToken(session)
            // Protected endpoints: require API token
            uri == "/api/devices" -> withToken(session) { json(devicesJson()) }
            uri == "/api/transfers" -> withToken(session) { json(transfersJson()) }
            uri == "/api/transfers/clear" -> withToken(session) { clearFinished(); json("""{"ok":true}""") }
            uri == "/api/transfers/cancel" -> withToken(session) { cancelTransfer(session); json("""{"ok":true}""") }
            uri == "/api/send-path" && session.method == Method.POST -> withToken(session) { handleSend(session) }
            uri == "/api/peers/add" && session.method == Method.POST -> handleAddPeer(session) // public — peers announce themselves
            uri == "/api/peers/remove" && session.method == Method.POST -> withToken(session) { handleRemovePeer(session) }
            // Pairing
            uri == "/api/pair/begin" -> withToken(session) { handlePairBegin() }
            uri == "/api/pair/status" -> withToken(session) { handlePairStatus(session) }
            uri == "/api/pair/submit" && session.method == Method.POST -> withToken(session) { handlePairSubmit(session) }
            uri == "/api/pair/unpair" -> withToken(session) {
                val id = session.parameters["id"]?.firstOrNull() ?: ""
                if (id.isNotEmpty()) {
                    PairStore.unpair(id)
                    // Notify remote device so it also unpairs us.
                    val peer = State.peer(id)
                    if (peer != null) {
                        Thread { runCatching { java.net.URL("http://${peer.host}/api/pair/remote-unpair?id=${State.deviceId}").openConnection().let { (it as java.net.HttpURLConnection).apply { requestMethod = "POST"; connectTimeout = 3000; responseCode; disconnect() } } } }.start()
                    }
                }
                json("""{"ok":true}""")
            }
            uri == "/api/pair/remote-unpair" -> {
                val id = session.parameters["id"]?.firstOrNull() ?: ""
                if (id.isNotEmpty()) PairStore.unpair(id)
                json("""{"ok":true}""")
            }
            uri == "/api/pair/claim" && session.method == Method.POST -> handlePairClaim(session)
            // QR-based pairing
            uri == "/api/pair/qr-begin" -> withToken(session) { handleQRBegin() }
            uri == "/api/pair/qr-claim" && session.method == Method.POST -> handleQRClaim(session)
            uri == "/" || uri == "/index.html" -> asset("web/index.html", "text/html")
            else -> asset("web$uri", mimeFor(uri))
        }
    } catch (e: Exception) {
        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "error")
    }

    private fun handleToken(session: IHTTPSession): Response {
        val remote = session.remoteIpAddress ?: ""
        if (remote.isNotEmpty() && remote != "127.0.0.1" && remote != "::1" && remote != "0:0:0:0:0:0:0:1") {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "forbidden")
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, State.apiToken)
    }

    private fun withToken(session: IHTTPSession, block: () -> Response): Response {
        val tok = session.headers["x-api-token"] ?: session.parameters["token"]?.firstOrNull() ?: ""
        if (tok != State.apiToken) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "unauthorized")
        }
        return block()
    }

    // ---- receiving -------------------------------------------------------

    private fun handleInbox(session: IHTTPSession): Response {
        val name = safeName(session.headers["x-filename"] ?: "received-file")
        val from = session.headers["x-from"] ?: "a device"
        val fromID = session.headers["x-from-id"] ?: ""
        val len = session.headers["content-length"]?.toLongOrNull() ?: -1L

        // Reject files from unpaired senders.
        if (fromID.isEmpty() || PairStore.isPaired(fromID) == null) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "not paired — pair first")
        }

        // Check free disk space using original file size.
        val originalSize = session.headers["x-file-size"]?.toLongOrNull() ?: len
        val checkSize = if (originalSize > 0) originalSize else len
        if (checkSize > 0) {
            val stat = android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path)
            val free = stat.availableBytes
            if (checkSize > free - 100 * 1024 * 1024) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "not enough disk space")
            }
        }

        // Encrypted transfers are chunked (no Content-Length), so use
        // the original file size from X-File-Size for progress tracking.
        val trackSize = if (len > 0) len else (session.headers["x-file-size"]?.toLongOrNull() ?: 0L)

        val cr = State.appContext.contentResolver
        val tr = State.newTransfer(name, trackSize, from, "recv")
        Notifier.refreshServiceNotification()
        PowerLocks.begin()

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/SwiftDrop")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val dest: Uri = cr.insert(collection, values)
            ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "insert failed")

        val encrypted = session.headers["x-encrypted"] == "aes-gcm"
        val senderId = session.headers["x-from-id"] ?: ""

        try {
            cr.openOutputStream(dest).use { out ->
                requireNotNull(out) { "cannot open output" }
                val input = session.inputStream

                if (encrypted) {
                    val key = PairStore.isPaired(senderId)
                        ?: throw IllegalStateException("not paired with sender")
                    // Wrap output to track decrypted bytes for progress.
                    val counting = object : java.io.OutputStream() {
                        override fun write(b: Int) { out.write(b); tr.sent.incrementAndGet() }
                        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); tr.sent.addAndGet(len.toLong()) }
                        override fun flush() = out.flush()
                    }
                    Crypto.decryptStream(counting, input, key)
                } else {
                    val buf = ByteArray(256 * 1024)
                    var remaining = if (len >= 0) len else Long.MAX_VALUE
                    while (remaining > 0) {
                        val want = if (len >= 0) minOf(buf.size.toLong(), remaining).toInt() else buf.size
                        val n = input.read(buf, 0, want)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        remaining -= n
                        tr.sent.addAndGet(n.toLong())
                    }
                }
                out.flush()
            }
            // Verify SHA-256 integrity if the sender included a hash.
            val expected = session.headers["x-sha256"]
            if (!expected.isNullOrBlank()) {
                val md = java.security.MessageDigest.getInstance("SHA-256")
                cr.openInputStream(dest)?.use { verifyInput ->
                    val vbuf = ByteArray(256 * 1024)
                    while (true) {
                        val n = verifyInput.read(vbuf)
                        if (n < 0) break
                        md.update(vbuf, 0, n)
                    }
                }
                val actual = md.digest().joinToString("") { "%02x".format(it) }
                if (actual != expected) {
                    runCatching { cr.delete(dest, null, null) }
                    tr.status = "error"; tr.err = "hash mismatch"
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "integrity check failed")
                }
            }

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            cr.update(dest, values, null, null)
            tr.status = "done"
            Notifier.show(State.appContext, "Received $name")
            return json("""{"ok":true}""")
        } catch (e: Exception) {
            tr.status = "error"; tr.err = e.message
            runCatching { cr.delete(dest, null, null) }
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "write failed")
        } finally {
            Notifier.refreshServiceNotification()
            PowerLocks.end()
        }
    }

    // ---- sending ---------------------------------------------------------

    private fun handleSend(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val obj = JSONObject(body["postData"] ?: "{}")
        val to = obj.optString("to")
        val peer = State.peer(to)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "unknown device")
        if (PairStore.isPaired(peer.id) == null) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "pair with this device first")
        }

        val paths = obj.optJSONArray("paths") ?: JSONArray()
        for (i in 0 until paths.length()) {
            val uri = Uri.parse(paths.getString(i))
            Thread { Sender.sendUri(peer, uri) }.start()
        }
        return json("""{"ok":true}""")
    }

    // ---- manual peers (add by IP) ---------------------------------------

    private fun handleAddPeer(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        var host = JSONObject(body["postData"] ?: "{}").optString("host").trim()
        if (host.isEmpty()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "host required")
        }
        if (!host.contains(":")) host = "$host:${State.PORT}"

        val peer = probePeer(host)
            ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "could not reach device")
        State.addManual(peer)
        return json(JSONObject().apply {
            put("id", peer.id); put("name", peer.name); put("platform", peer.platform)
            put("host", peer.host); put("manual", true)
        }.toString())
    }

    private fun handleRemovePeer(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val id = JSONObject(body["postData"] ?: "{}").optString("id")
        PairStore.unpair(id)
        State.removeDevice(id)
        return json("""{"ok":true}""")
    }

    /** Probe host/api/me to confirm a manually entered device and learn its id. */
    private fun probePeer(host: String): Peer? = try {
        val conn = (java.net.URL("http://$host/api/me").openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 4000; readTimeout = 4000
        }
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val o = JSONObject(text)
        Peer(o.getString("id"), o.optString("name", "Device"), o.optString("platform", "device"), host, manual = true)
    } catch (e: Exception) {
        null
    }

    // ---- json helpers ----------------------------------------------------

    private fun meJson(): String = JSONObject().apply {
        put("id", State.deviceId); put("name", State.deviceName)
        put("platform", State.PLATFORM); put("port", State.PORT)
        put("ip", State.localIp())
    }.toString()

    private fun devicesJson(): String {
        val arr = JSONArray()
        for (p in State.peers.values) {
            arr.put(JSONObject().apply {
                put("id", p.id); put("name", p.name)
                put("platform", p.platform); put("host", p.host); put("manual", p.manual)
            })
        }
        return arr.toString()
    }

    private fun clearFinished() {
        State.transfers.removeAll { it.status != "sending" }
    }

    private fun cancelTransfer(session: IHTTPSession) {
        val id = session.parameters["id"]?.firstOrNull() ?: return
        State.transfers.firstOrNull { it.id == id && it.status == "sending" }?.let {
            it.canceled = true
            it.status = "canceled"
        }
    }

    private fun transfersJson(): String {
        val arr = JSONArray()
        for (t in State.transfers) {
            arr.put(JSONObject().apply {
                put("id", t.id); put("name", t.name); put("size", t.size)
                put("sent", t.sent.get()); put("status", t.status); put("peer", t.peer); put("dir", t.dir)
                t.err?.let { put("err", it) }
            })
        }
        return arr.toString()
    }

    // ---- misc ------------------------------------------------------------

    private fun json(s: String): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", s)

    private fun asset(path: String, mime: String): Response = try {
        if (path == "web/index.html") {
            val raw = State.appContext.assets.open(path).bufferedReader().readText()
            val injected = raw.replace(
                """let apiToken = "";""",
                """let apiToken = "${State.apiToken}";"""
            )
            newFixedLengthResponse(Response.Status.OK, mime, injected)
        } else {
            val stream = State.appContext.assets.open(path)
            newChunkedResponse(Response.Status.OK, mime, stream)
        }
    } catch (e: Exception) {
        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
    }

    private fun mimeFor(uri: String): String = when {
        uri.endsWith(".html") -> "text/html"
        uri.endsWith(".css") -> "text/css"
        uri.endsWith(".js") -> "application/javascript"
        uri.endsWith(".svg") -> "image/svg+xml"
        else -> "application/octet-stream"
    }

    private fun safeName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        return if (base.isBlank() || base == "." || base == "..") "received-file" else base
    }

    // ---- pairing -----------------------------------------------------------

    private fun handlePairBegin(): Response {
        val pin = PairStore.generatePIN()
        return json("""{"pin":"$pin"}""")
    }

    private fun handlePairStatus(session: IHTTPSession): Response {
        val id = session.parameters["id"]?.firstOrNull() ?: ""
        val paired = PairStore.isPaired(id) != null
        return json("""{"paired":$paired}""")
    }

    private fun handlePairSubmit(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val obj = JSONObject(body["postData"] ?: "{}")
        val deviceId = obj.optString("device_id")
        val pin = obj.optString("pin")
        val peer = State.peer(deviceId)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "unknown device")

        // POST PIN to remote peer's /api/pair/claim
        val payload = JSONObject().apply {
            put("pin", pin); put("id", State.deviceId); put("name", State.deviceName)
        }.toString()
        val conn = (java.net.URL("http://${peer.host}/api/pair/claim").openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 5000; readTimeout = 5000
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }
        conn.outputStream.use { it.write(payload.toByteArray()) }
        if (conn.responseCode != 200) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "pairing rejected"
            conn.disconnect()
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, err)
        }
        val result = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        conn.disconnect()
        val keyHex = result.optString("key")
        val keyBytes = PairStore.run {
            val b = ByteArray(keyHex.length / 2)
            for (i in b.indices) b[i] = ((Character.digit(keyHex[2 * i], 16) shl 4) + Character.digit(keyHex[2 * i + 1], 16)).toByte()
            b
        }
        if (keyBytes.size != 32) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "invalid key")
        }
        PairStore.storeKey(deviceId, keyBytes)
        return json("""{"ok":true}""")
    }

    private fun handleQRBegin(): Response {
        val token = PairStore.generateQRToken()
        val ip = State.localIp()
        val host = "$ip:${State.PORT}"
        val payload = JSONObject().apply {
            put("host", host); put("id", State.deviceId); put("token", token)
        }.toString()
        // Generate QR code PNG.
        val writer = QRCodeWriter()
        val matrix = writer.encode(payload, BarcodeFormat.QR_CODE, 256, 256)
        val w = matrix.width; val h = matrix.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        for (x in 0 until w) for (y in 0 until h)
            bmp.setPixel(x, y, if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
        val pngB64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        return json(JSONObject().apply {
            put("qr_png", pngB64); put("token", token); put("payload", payload)
        }.toString())
    }

    private fun handleQRClaim(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val obj = JSONObject(body["postData"] ?: "{}")
        val token = obj.optString("token")
        val peerId = obj.optString("id")
        val key = PairStore.claimQRToken(token, peerId)
            ?: return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "invalid or expired token")
        return json("""{"key":"${PairStore.bytesToHex(key)}"}"""
        )
    }

    private fun handlePairClaim(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val obj = JSONObject(body["postData"] ?: "{}")
        val pin = obj.optString("pin")
        val peerId = obj.optString("id")
        val key = PairStore.claimPIN(pin, peerId)
            ?: return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "invalid or expired PIN")
        return json("""{"key":"${PairStore.bytesToHex(key)}"}""")
    }
}
