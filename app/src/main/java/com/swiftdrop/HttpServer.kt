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

    private val replayCache = ReplayCache()

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
            uri == "/api/transfers/pause" -> withToken(session) { pauseTransfer(session) }
            uri == "/api/transfers/resume" -> withToken(session) { resumeTransfer(session) }
            uri == "/api/transfers/retry" && session.method == Method.POST -> withToken(session) { retryTransfer(session) }
            uri == "/api/transfers/accept" -> withToken(session) { handleAcceptReject(session, true) }
            uri == "/api/transfers/reject" -> withToken(session) { handleAcceptReject(session, false) }
            uri == "/api/send-path" && session.method == Method.POST -> withToken(session) { handleSend(session) }
            uri == "/transfer-signal" && session.method == Method.POST -> handleTransferSignal(session)
            uri == "/chat-inbox" && session.method == Method.POST -> handleChatInbox(session)
            uri == "/api/chat/send" && session.method == Method.POST -> withToken(session) { handleChatSend(session) }
            uri == "/api/chat/messages" -> withToken(session) { handleChatMessages(session) }
            uri == "/api/chat/notify" -> withToken(session) { handleChatNotify() }
            uri == "/api/chat/notify/ack" -> withToken(session) { handleChatNotifyAck() }
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
            uri == "/api/pair/remote-unpair" -> handleRemoteUnpair(session)
            uri == "/api/pair/claim" && session.method == Method.POST -> handlePairClaim(session)
            uri == "/api/pair/pake-confirm" && session.method == Method.POST -> handlePAKEConfirm(session)
            // QR-based pairing
            uri == "/api/pair/qr-begin" -> withToken(session) { handleQRBegin() }
            uri == "/api/pair/qr-claim" && session.method == Method.POST -> handleQRClaim(session)
            uri == "/" || uri == "/index.html" -> localOnly(session) { asset("web/index.html", "text/html") }
            else -> localOnly(session) { asset("web$uri", mimeFor(uri)) }
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

    private fun localOnly(session: IHTTPSession, block: () -> Response): Response {
        val ip = session.remoteIpAddress ?: ""
        if (ip.isNotEmpty() && ip != "127.0.0.1" && ip != "::1" && ip != "0:0:0:0:0:0:0:1")
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "forbidden")
        return block()
    }

    private fun withToken(session: IHTTPSession, block: () -> Response): Response {
        val ip = session.remoteIpAddress ?: ""
        if (ip.isNotEmpty() && ip != "127.0.0.1" && ip != "::1" && ip != "0:0:0:0:0:0:0:1")
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "forbidden")
        val tok = session.headers["x-api-token"] ?: ""
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
        val pairKey = PairStore.isPaired(fromID)
        if (fromID.isEmpty() || pairKey == null) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "not paired — pair first")
        }

        // Verify HMAC sender authentication — mandatory for paired senders.
        val authHMAC = session.headers["x-auth-hmac"]
        if (authHMAC.isNullOrEmpty()) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "HMAC authentication required")
        }
        val authTime = session.headers["x-auth-time"] ?: ""
        val ts = authTime.toLongOrNull() ?: 0L
        val delta = kotlin.math.abs(System.currentTimeMillis() / 1000 - ts)
        if (delta > 300) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "auth timestamp expired")
        }
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(pairKey, "HmacSHA256"))
        val expected = mac.doFinal("$fromID|$name|$authTime".toByteArray()).joinToString("") { "%02x".format(it) }
        if (authHMAC != expected) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "authentication failed")
        }
        // Reject replayed HMAC values.
        if (!replayCache.check(authHMAC)) {
            android.util.Log.w("SwiftDrop", "inbox HMAC replay detected from $fromID")
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "replay detected")
        }

        // Check free disk space using original file size (require positive size).
        val originalSize = session.headers["x-file-size"]?.toLongOrNull() ?: len
        val checkSize = if (originalSize > 0) originalSize else len
        if (checkSize <= 0) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "X-File-Size or Content-Length required")
        }
        val stat = android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path)
        val free = stat.availableBytes
        val headroom = 100L * 1024 * 1024
        // Saturating add to prevent overflow.
        val needed = if (checkSize > Long.MAX_VALUE - headroom) Long.MAX_VALUE else checkSize + headroom
        if (needed > free) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "not enough disk space")
        }

        // Encrypted transfers are chunked (no Content-Length), so use
        // the original file size from X-File-Size for progress tracking.
        val trackSize = if (len > 0) len else (session.headers["x-file-size"]?.toLongOrNull() ?: 0L)

        // ── Receiver consent: block until the user accepts or 60s timeout ──
        val tr = State.newPendingTransfer(name, trackSize, from)
        val sizeStr = humanSize(trackSize)
        val activity = State.foregroundActivity
        if (activity != null) {
            // In-app styled dialog when the Activity is visible.
            activity.runOnUiThread {
                showConsentDialog(activity, tr, from, name, sizeStr)
            }
        } else {
            // Background: use notification with action buttons.
            Notifier.showConsentNotification(State.appContext, tr.id, from, name, sizeStr)
        }
        val responded = tr.decision.await(60, java.util.concurrent.TimeUnit.SECONDS)
        if (!responded || !tr.accepted) {
            tr.status = "error"; tr.err = if (responded) "rejected by user" else "no response — auto-rejected"
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, tr.err)
        }
        tr.status = "sending"

        val cr = State.appContext.contentResolver
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

        val encHdr = session.headers["x-encrypted"] ?: ""
        val encrypted = encHdr == "aes-gcm-v2" || encHdr == "aes-gcm"
        val senderId = session.headers["x-from-id"] ?: ""

        // Hash on-the-fly so we never re-read the file from disk.
        val md = java.security.MessageDigest.getInstance("SHA-256")
        try {
            cr.openOutputStream(dest).use { rawOut ->
                requireNotNull(rawOut) { "cannot open output" }
                val input = session.inputStream

                if (encrypted) {
                    val key = PairStore.isPaired(senderId)
                        ?: throw IllegalStateException("not paired with sender")
                    // BufferedOutputStream + counting + hashing wrapper.
                    val buffered = java.io.BufferedOutputStream(rawOut, 256 * 1024)
                    val counting = object : java.io.OutputStream() {
                        override fun write(b: Int) { buffered.write(b); md.update(b.toByte()); tr.sent.incrementAndGet() }
                        override fun write(b: ByteArray, off: Int, len: Int) { buffered.write(b, off, len); md.update(b, off, len); tr.sent.addAndGet(len.toLong()) }
                        override fun flush() = buffered.flush()
                    }
                    if (encHdr == "aes-gcm-v2") {
                        Crypto.decryptStream(counting, input, key)
                    } else {
                        Crypto.decryptStreamV1(counting, input, key)
                    }
                    buffered.flush()
                } else {
                    val buffered = java.io.BufferedOutputStream(rawOut, 256 * 1024)
                    val buf = ByteArray(256 * 1024)
                    var remaining = if (len >= 0) len else Long.MAX_VALUE
                    while (remaining > 0) {
                        val want = if (len >= 0) minOf(buf.size.toLong(), remaining).toInt() else buf.size
                        val n = input.read(buf, 0, want)
                        if (n < 0) break
                        buffered.write(buf, 0, n)
                        md.update(buf, 0, n)
                        remaining -= n
                        tr.sent.addAndGet(n.toLong())
                    }
                    buffered.flush()
                }
            }
            // Verify SHA-256 integrity if the sender included a hash (hashed on-the-fly above).
            val expected = session.headers["x-sha256"]
            if (!expected.isNullOrBlank()) {
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
        for (p in State.allPeers()) {
            arr.put(JSONObject().apply {
                put("id", p.id); put("name", p.name)
                put("platform", p.platform); put("host", p.host); put("manual", p.manual)
            })
        }
        return arr.toString()
    }

    private fun clearFinished() {
        State.transfers.removeAll { it.status != "sending" && it.status != "pending" && it.status != "paused" }
    }

    private fun cancelTransfer(session: IHTTPSession) {
        val id = session.parameters["id"]?.firstOrNull() ?: return
        State.transfers.firstOrNull { it.id == id && (it.status == "sending" || it.status == "paused") }?.let {
            if (it.paused) it.resume() // unblock the streaming thread first
            it.canceled = true
            it.status = "canceled"
            // Force-disconnect the HTTP connection to abort the transfer immediately
            Thread { runCatching { it.conn?.disconnect() } }.start()
        }
    }

    private fun retryTransfer(session: IHTTPSession): Response {
        val id = session.parameters["id"]?.firstOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "id required")
        val old = State.transfers.firstOrNull { it.id == id && (it.status == "error" || it.status == "canceled") && it.dir == "send" }
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "transfer not found or not retryable")
        val uriStr = old.uri
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "no URI stored")
        val peerId = old.peerId
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "no peer stored")
        val peer = State.peer(peerId)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "peer no longer available")
        State.transfers.remove(old)
        val uri = Uri.parse(uriStr)
        Thread { Sender.sendUri(peer, uri) }.start()
        return json("""{"ok":true}""")
    }

    private fun pauseTransfer(session: IHTTPSession): Response {
        val id = session.parms["id"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "missing id")
        val t = State.transfers.firstOrNull { it.id == id && it.status == "sending" }
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found or not sending")
        t.pause()
        t.peerId?.let { pid -> Thread { notifyPeerSignal(pid, t.name, "pause") }.start() }
        return json("""{"ok":true}""")
    }

    private fun resumeTransfer(session: IHTTPSession): Response {
        val id = session.parms["id"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "missing id")
        val t = State.transfers.firstOrNull { it.id == id && it.status == "paused" }
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found or not paused")
        t.resume()
        t.peerId?.let { pid -> Thread { notifyPeerSignal(pid, t.name, "resume") }.start() }
        return json("""{"ok":true}""")
    }

    private fun handleTransferSignal(session: IHTTPSession): Response {
        val map = HashMap<String, String>()
        session.parseBody(map)
        val body = JSONObject(map["postData"] ?: "{}")
        val file = body.optString("file", "")
        val action = body.optString("action", "")
        val peerName = session.headers["x-from-name"] ?: ""
        if (file.isEmpty() || action.isEmpty() || peerName.isEmpty())
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "bad request")
        // Find the matching recv transfer and update its status.
        for (t in State.transfers) {
            if (t.dir != "recv" || t.name != file || t.peer != peerName) continue
            when (action) {
                "pause" -> if (t.status == "sending") t.status = "paused"
                "resume" -> if (t.status == "paused") t.status = "sending"
            }
            break
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "ok")
    }

    private fun notifyPeerSignal(peerId: String, fileName: String, action: String) {
        val peer = State.peer(peerId) ?: return
        try {
            val url = java.net.URL("http://${peer.host}/transfer-signal")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 3000; conn.readTimeout = 3000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-From-Name", State.deviceName)
            conn.doOutput = true
            conn.outputStream.use { it.write("""{"file":"$fileName","action":"$action"}""".toByteArray()) }
            conn.responseCode // trigger the request
            conn.disconnect()
        } catch (_: Exception) { /* best-effort */ }
    }

    // ---- Chat ----

    private fun handleChatInbox(session: IHTTPSession): Response {
        val map = HashMap<String, String>()
        session.parseBody(map)
        val body = JSONObject(map["postData"] ?: "{}")
        val text = body.optString("text", "")
        val from = body.optString("from", "Unknown")
        val fromId = body.optString("fromId", "")
        if (text.isEmpty() || fromId.isEmpty())
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "bad request")
        // Reject chat from unpaired senders to prevent spoofing.
        if (PairStore.isPaired(fromId) == null)
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "not paired")
        State.chatStore.add(fromId, ChatMsg(text = text, from = from, dir = "recv"))
        State.chatNotifyPeer = fromId
        State.chatNotifyName = from
        val snippet = if (text.length > 80) text.take(80) + "…" else text
        Notifier.show(State.appContext, "Message from $from: $snippet")
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "ok")
    }

    private fun handleChatSend(session: IHTTPSession): Response {
        val map = HashMap<String, String>()
        session.parseBody(map)
        val body = JSONObject(map["postData"] ?: "{}")
        val peerId = body.optString("peer", "")
        val text = body.optString("text", "")
        if (peerId.isEmpty() || text.isEmpty())
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "bad request")
        val peer = State.peer(peerId)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "peer not found")
        val msg = State.chatStore.add(peerId, ChatMsg(text = text, from = State.deviceName, dir = "sent"))
        Thread {
            try {
                val url = java.net.URL("http://${peer.host}/chat-inbox")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"; conn.connectTimeout = 5000; conn.readTimeout = 5000
                conn.setRequestProperty("Content-Type", "application/json"); conn.doOutput = true
                val payload = JSONObject().put("from", State.deviceName).put("fromId", State.deviceId).put("text", text).toString()
                conn.outputStream.use { it.write(payload.toByteArray()) }
                conn.responseCode; conn.disconnect()
            } catch (_: Exception) {}
        }.start()
        return json("""{"ok":true,"id":"${msg.id}"}""")
    }

    private fun handleChatMessages(session: IHTTPSession): Response {
        val peerId = session.parms["peer"] ?: ""
        val since = session.parms["since"]?.toLongOrNull() ?: 0L
        val msgs = State.chatStore.since(peerId, since)
        val arr = JSONArray()
        for (m in msgs) {
            arr.put(JSONObject().put("id", m.id).put("text", m.text).put("from", m.from).put("dir", m.dir).put("ts", m.ts))
        }
        return json(arr.toString())
    }

    private fun handleChatNotify(): Response {
        return json(JSONObject().put("peer", State.chatNotifyPeer ?: "").put("name", State.chatNotifyName ?: "").toString())
    }

    private fun handleChatNotifyAck(): Response {
        State.chatNotifyPeer = null
        State.chatNotifyName = null
        return json("""{"ok":true}""")
    }

    private fun transfersJson(): String {
        val arr = JSONArray()
        for (t in State.transfers) {
            arr.put(JSONObject().apply {
                put("id", t.id); put("name", t.name); put("size", t.size)
                put("sent", t.sent.get()); put("status", t.status); put("peer", t.peer); put("dir", t.dir)
                t.err?.let { put("err", it) }
                if (t.paused) put("paused", true)
                if (t.dir == "send" && (t.status == "error" || t.status == "canceled") && t.uri != null) put("retryable", true)
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

    private fun handleAcceptReject(session: IHTTPSession, accept: Boolean): Response {
        val id = session.parameters["id"]?.firstOrNull() ?: ""
        val tr = State.transfers.firstOrNull { it.id == id && it.status == "pending" }
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found or not pending")
        tr.accepted = accept
        tr.decision.countDown()
        return json("""{"ok":true}""")
    }

    private fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB")
        var value = bytes / 1024.0
        var u = 0
        while (value >= 1024 && u < units.size - 1) { value /= 1024; u++ }
        return "%.1f %s".format(value, units[u])
    }

    private fun safeName(raw: String): String {
        // First pass: strip path separators.
        var name = raw.substringAfterLast('/').substringAfterLast('\\')
        // Second pass: URL-decode then strip again to block %2F / %5C traversal.
        try {
            val decoded = java.net.URLDecoder.decode(name, "UTF-8")
            name = decoded.substringAfterLast('/').substringAfterLast('\\')
        } catch (_: Exception) { /* keep as-is if decode fails */ }
        return if (name.isBlank() || name == "." || name == "..") "received-file" else name
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

        // SPAKE2 PAKE: PIN never crosses the wire.
        val spakeState = Spake2.clientStart(pin)
        val pakePayload = JSONObject().apply {
            put("pake_msg", bytesToHex(spakeState.msgA))
            put("id", State.deviceId); put("name", State.deviceName)
        }.toString()
        val conn = (java.net.URL("http://${peer.host}/api/pair/claim").openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 5000; readTimeout = 5000
            setRequestProperty("Content-Type", "application/json"); doOutput = true
        }
        conn.outputStream.use { it.write(pakePayload.toByteArray()) }
        if (conn.responseCode != 200) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "pairing rejected"
            conn.disconnect()
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, err)
        }
        val result = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        conn.disconnect()
        val spakeKey = try {
            spakeState.finish(hexToBytes(result.optString("pake_msg")), hexToBytes(result.optString("pake_confirm")))
        } catch (_: Exception) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "wrong PIN")
        }
        val keyBytes = try {
            Spake2.aesGcmUnwrap(spakeKey, hexToBytes(result.optString("encrypted_key")))
        } catch (_: Exception) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "wrong PIN")
        }
        if (keyBytes.size != 32)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "invalid key length")

        // Phase 2: send client confirmation so server knows the PIN was correct.
        val clientConfirm = Spake2.confirmMac(spakeKey, "client")
        val confirmPayload = JSONObject().apply {
            put("id", State.deviceId); put("pake_confirm", bytesToHex(clientConfirm))
        }.toString()
        val confirmConn = (java.net.URL("http://${peer.host}/api/pair/pake-confirm").openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 5000; readTimeout = 5000
            setRequestProperty("Content-Type", "application/json"); doOutput = true
        }
        confirmConn.outputStream.use { it.write(confirmPayload.toByteArray()) }
        if (confirmConn.responseCode != 200) {
            val err = confirmConn.errorStream?.bufferedReader()?.use { it.readText() } ?: "confirmation failed"
            confirmConn.disconnect()
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, err)
        }
        confirmConn.disconnect()

        PairStore.storeKey(deviceId, keyBytes)
        android.util.Log.i("SwiftDrop", "SPAKE2 paired with ${peer.name} ($deviceId)")
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

    @Suppress("DEPRECATION")
    private fun showConsentDialog(activity: android.app.Activity, tr: Transfer, from: String, fileName: String, size: String) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(16))
        }

        // Header row: icon + title/sender
        val header = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
        }
        val iconBg = android.widget.FrameLayout(activity).apply {
            val bg = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF1E3A5F.toInt()); cornerRadius = dp(10).toFloat()
            }
            background = bg
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) }
        }
        val icon = android.widget.ImageView(activity).apply {
            setImageResource(android.R.drawable.stat_sys_download)
            setColorFilter(0xFF60A5FA.toInt())
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(20), dp(20), android.view.Gravity.CENTER)
        }
        iconBg.addView(icon)
        header.addView(iconBg)
        val titleCol = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
        }
        titleCol.addView(android.widget.TextView(activity).apply {
            text = "Incoming file"; setTextColor(0xFFFFFFFF.toInt()); textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        titleCol.addView(android.widget.TextView(activity).apply {
            text = "from $from"; setTextColor(0xFF8B8B8E.toInt()); textSize = 12f
        })
        header.addView(titleCol)
        root.addView(header)

        // File info pill
        val pill = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            val bg = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF2C2C2E.toInt()); cornerRadius = dp(10).toFloat()
            }
            background = bg
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) }
        }
        pill.addView(android.widget.TextView(activity).apply {
            text = fileName; setTextColor(0xFFE5E5E5.toInt()); textSize = 13f
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
        })
        pill.addView(android.widget.TextView(activity).apply {
            text = size; setTextColor(0xFF8B8B8E.toInt()); textSize = 12f
            layoutParams = android.widget.LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) }
        })
        root.addView(pill)

        // Buttons
        val row = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2)
        }
        val dlg = android.app.Dialog(activity).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE); setCancelable(false)
        }
        row.addView(android.widget.TextView(activity).apply {
            text = "Reject"; setTextColor(0xFFFF6961.toInt()); textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) }
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF3A2A2A.toInt()); cornerRadius = dp(10).toFloat()
            }
            setOnClickListener { tr.accepted = false; tr.decision.countDown(); dlg.dismiss() }
        })
        row.addView(android.widget.TextView(activity).apply {
            text = "Accept"; setTextColor(0xFFFFFFFF.toInt()); textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(5) }
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF3478F6.toInt()); cornerRadius = dp(10).toFloat()
            }
            setOnClickListener { tr.accepted = true; tr.decision.countDown(); dlg.dismiss() }
        })
        root.addView(row)

        dlg.setContentView(root)
        dlg.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF1C1C1E.toInt()); cornerRadius = dp(16).toFloat()
            })
            setLayout((activity.resources.displayMetrics.widthPixels * 0.82).toInt(), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.5f)
        }
        dlg.show()
    }

    private fun handlePairClaim(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val obj = JSONObject(body["postData"] ?: "{}")
        val peerId = obj.optString("id")
        val peerName = obj.optString("name")
        val pakeMsg = obj.optString("pake_msg")
        if (pakeMsg.isEmpty())
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "pake_msg required")
        val pin = PairStore.pendingPIN
            ?: return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "no pending pairing")
        if (System.currentTimeMillis() > PairStore.pendingExpiry)
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "pairing expired")
        val clientMsg = try { hexToBytes(pakeMsg) } catch (_: Exception) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "invalid pake_msg")
        }
        val result = try { Spake2.serverFinish(pin, clientMsg) } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "SPAKE2 failed: ${e.message}")
        }
        val pairKey = PairStore.pendingKey
            ?: return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "no pending key")
        val wrapped = Spake2.aesGcmWrap(result.sharedKey, pairKey)
        // Hold — do NOT commit yet. Wait for client confirmation in Phase 2.
        PairStore.holdPAKE(peerId, result.sharedKey, pairKey)
        android.util.Log.i("SwiftDrop", "SPAKE2 exchange with $peerName ($peerId) — awaiting confirmation")
        return json("""{"pake_msg":"${bytesToHex(result.msgB)}","pake_confirm":"${bytesToHex(result.confirm)}","encrypted_key":"${bytesToHex(wrapped)}"}""")
    }

    private fun handlePAKEConfirm(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val obj = JSONObject(body["postData"] ?: "{}")
        val peerId = obj.optString("id")
        val confirm = obj.optString("pake_confirm")
        if (confirm.isEmpty())
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "pake_confirm required")
        if (!PairStore.confirmPAKE(peerId, hexToBytes(confirm)))
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "wrong PIN or expired")
        android.util.Log.i("SwiftDrop", "SPAKE2 pairing confirmed for $peerId")
        return json("""{"ok":true}""")
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        return data
    }

    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun handleRemoteUnpair(session: IHTTPSession): Response {
        val id = session.parameters["id"]?.firstOrNull() ?: ""
        if (id.isNotEmpty()) {
            // Verify the caller's IP matches the registered peer to prevent spoofing.
            val callerIp = normalizeIp(session.remoteIpAddress ?: "")
            val peer = State.peer(id)
            if (peer != null) {
                val peerIp = normalizeIp(peer.host.substringBeforeLast(":"))
                if (callerIp != peerIp) {
                    return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "forbidden")
                }
            }
            PairStore.unpair(id)
        }
        return json("""{"ok":true}""")
    }

    /** Strip ::ffff: prefix from IPv6-mapped IPv4 so comparisons work. */
    private fun normalizeIp(ip: String): String {
        val stripped = ip.removePrefix("::ffff:")
        return try {
            val addr = java.net.InetAddress.getByName(stripped)
            if (addr is java.net.Inet4Address) addr.hostAddress ?: stripped else stripped
        } catch (_: Exception) { stripped }
    }
}

/** Prevents HMAC replay attacks by remembering recent auth signatures. */
class ReplayCache {
    private val seen = java.util.concurrent.ConcurrentHashMap<String, Long>()

    init {
        // Background cleanup every 60 s.
        val timer = java.util.Timer("ReplayCacheCleanup", true)
        timer.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() {
                val now = System.currentTimeMillis()
                seen.entries.removeAll { it.value < now }
            }
        }, 60_000L, 60_000L)
    }

    /** Returns true if this HMAC has not been seen (first use). False on replay. */
    fun check(hmacHex: String): Boolean {
        val expiry = System.currentTimeMillis() + 6 * 60 * 1000 // 5 min window + 1 min buffer
        return seen.putIfAbsent(hmacHex, expiry) == null
    }
}
