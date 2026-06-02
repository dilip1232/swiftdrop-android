package com.swiftdrop

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Periodically scans the local /24 subnet for SwiftDrop peers on the default
 * port. This is a fallback for when mDNS is unavailable — common on Android
 * hotspots where multicast is blocked.
 *
 * Uses fast TCP dial checks (200ms timeout) to find open ports, then probes
 * only the responsive hosts via /api/me. Runs every 15 seconds.
 */
class LANScanner {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread {
            // Give mDNS a chance first.
            try { Thread.sleep(5000) } catch (_: InterruptedException) { return@Thread }
            scanSubnet()

            while (running) {
                try { Thread.sleep(15_000) } catch (_: InterruptedException) { break }
                scanSubnet()
            }
        }.also { it.isDaemon = true; it.start() }
        Log.i("SwiftDrop", "lanscan: subnet scanner started (fallback discovery)")
    }

    fun stop() {
        running = false
        thread?.interrupt()
    }

    private fun scanSubnet() {
        val localIP = State.localIp()
        if (localIP.isBlank()) return

        val parts = localIP.split(".")
        if (parts.size != 4) return
        val prefix = "${parts[0]}.${parts[1]}.${parts[2]}."
        val port = State.PORT

        val executor = Executors.newFixedThreadPool(50)
        for (i in 1..254) {
            val target = "$prefix$i"
            if (target == localIP) continue
            val host = "$target:$port"

            executor.submit {
                if (!running) return@submit
                try {
                    // Fast TCP check — skip hosts that aren't listening.
                    Socket().use { sock ->
                        sock.connect(InetSocketAddress(target, port), 200)
                    }
                    // Host is listening on our port — probe its identity.
                    val peer = probe(host) ?: return@submit
                    if (peer.id == State.deviceId || State.ignored(peer.id)) return@submit
                    State.peers[peer.id] = peer
                    State.remember(peer)
                    try { announceToRemote(host) } catch (_: Exception) {}
                } catch (_: Exception) {
                    // not listening — skip
                }
            }
        }
        executor.shutdown()
        try { executor.awaitTermination(10, TimeUnit.SECONDS) } catch (_: Exception) {}
    }

    private fun announceToRemote(peerHost: String) {
        val selfIP = State.localIp()
        if (selfIP.isBlank()) return
        val selfHost = "$selfIP:${State.PORT}"
        val c = (URL("http://$peerHost/api/peers/add").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 2000; readTimeout = 2000
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }
        c.outputStream.use { it.write("""{"host":"$selfHost"}""".toByteArray()) }
        runCatching { c.inputStream.close() }
        c.disconnect()
    }

    private fun probe(host: String): Peer? = try {
        val c = (URL("http://$host/api/me").openConnection() as HttpURLConnection).apply {
            connectTimeout = 2000; readTimeout = 2000
        }
        val text = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        val o = JSONObject(text)
        Peer(o.getString("id"), o.optString("name", "Device"), o.optString("platform", "device"), host, false)
    } catch (_: Exception) {
        null
    }
}
