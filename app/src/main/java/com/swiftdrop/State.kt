package com.swiftdrop

import android.content.Context
import android.os.Build
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** A SwiftDrop device discovered on the network. */
data class Peer(
    val id: String,
    val name: String,
    val platform: String,
    val host: String,
    val manual: Boolean = false
)

/** Live state of one outbound file; [sent] is updated as bytes stream out. */
class Transfer(val id: String, val name: String, val size: Long, val peer: String, val dir: String) {
    val sent = AtomicLong(0)
    @Volatile var status: String = "sending" // "sending" | "done" | "error" | "canceled"
    @Volatile var err: String? = null
    @Volatile var canceled: Boolean = false  // set by /api/transfers/cancel
    @Volatile var conn: java.net.HttpURLConnection? = null  // for forced disconnect on cancel
    // For retry: stored only for outbound sends
    @Volatile var uri: String? = null
    @Volatile var peerId: String? = null
}

/**
 * Process-wide state shared between the foreground service (server + discovery)
 * and the UI. Holds this device's identity, discovered peers, and transfers.
 */
object State {
    const val PORT = 53317
    const val PLATFORM = "android"

    lateinit var appContext: Context
    lateinit var deviceId: String
    var deviceName: String = Build.MODEL ?: "Android"
    val apiToken: String = UUID.randomUUID().toString().replace("-", "").take(16)

    val peers = ConcurrentHashMap<String, Peer>()        // currently visible (reachable)
    val manualPeers = ConcurrentHashMap<String, Peer>()  // added by IP
    val known = ConcurrentHashMap<String, Peer>()        // every device ever seen; probed to auto-(re)appear
    val ignore = ConcurrentHashMap<String, Long>()       // id -> suppress-until (ms)
    val transfers = CopyOnWriteArrayList<Transfer>()
    private var seq = 0L

    /** Record a device (keyed by id) so the prober keeps it visible / re-finds it. */
    fun remember(p: Peer) {
        val clean = p.copy(manual = false)
        if (known[p.id] != clean) { known[p.id] = clean; saveKnown() }
    }

    /** True if this id was recently removed by the user and should stay hidden. */
    fun ignored(id: String): Boolean {
        val until = ignore[id] ?: return false
        if (System.currentTimeMillis() >= until) { ignore.remove(id); return false }
        return true
    }

    /**
     * Briefly hide a device (not a permanent forget): it stays in [known], so
     * the prober brings it back within a few seconds if still reachable. This
     * makes the list self-healing / auto-redisplay.
     */
    fun removeDevice(id: String) {
        peers.remove(id)
        if (manualPeers.remove(id) != null) saveManual()
        ignore[id] = System.currentTimeMillis() + 10_000
    }

    /** Drop all currently-visible peers (keeps manual + known). Used on network change. */
    fun clearMDNS() { peers.clear() }

    fun isManual(id: String) = manualPeers.containsKey(id)

    /** Look up a peer by id from either source (mDNS takes priority). */
    fun peer(id: String): Peer? = peers[id] ?: manualPeers[id]

    /** All peers for the device list; mDNS entry wins if the same id is in both. */
    fun allPeers(): List<Peer> {
        val out = LinkedHashMap<String, Peer>()
        manualPeers.forEach { (k, v) -> out[k] = v }
        peers.forEach { (k, v) -> out[k] = v }
        return out.values.toList()
    }

    fun init(ctx: Context) {
        if (::appContext.isInitialized) return
        appContext = ctx.applicationContext
        val prefs = appContext.getSharedPreferences("swiftdrop", Context.MODE_PRIVATE)
        deviceId = prefs.getString("device_id", null) ?: run {
            val id = UUID.randomUUID().toString().take(8)
            prefs.edit().putString("device_id", id).apply()
            id
        }
        prefs.getString("device_name", null)?.let { deviceName = it }
        loadKnown()
        loadManual()
        PairStore.init(appContext)
    }

    // ---- manual peers: add/remove + persistence ----

    fun addManual(p: Peer) {
        val m = p.copy(manual = true)
        manualPeers[p.id] = m
        peers[p.id] = m              // show immediately (don't wait for the next probe)
        ignore.remove(p.id)          // explicit add overrides a prior removal
        saveManual()
        remember(p)
    }

    private fun prefs() = appContext.getSharedPreferences("swiftdrop", Context.MODE_PRIVATE)

    private fun saveManual() {
        val arr = org.json.JSONArray()
        for (p in manualPeers.values) {
            arr.put(org.json.JSONObject().apply {
                put("id", p.id); put("name", p.name); put("platform", p.platform); put("host", p.host)
            })
        }
        prefs().edit().putString("manual_peers", arr.toString()).apply()
    }

    private fun loadManual() {
        val s = prefs().getString("manual_peers", null) ?: return
        runCatching {
            val arr = org.json.JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val p = Peer(o.getString("id"), o.optString("name"), o.optString("platform"), o.getString("host"), true)
                manualPeers[p.id] = p
                known.putIfAbsent(p.id, p.copy(manual = false))
            }
        }
    }

    private fun peersToJson(map: Map<String, Peer>): String {
        val arr = org.json.JSONArray()
        for (p in map.values) arr.put(org.json.JSONObject().apply {
            put("id", p.id); put("name", p.name); put("platform", p.platform); put("host", p.host)
        })
        return arr.toString()
    }

    private fun saveKnown() { prefs().edit().putString("known_peers", peersToJson(known)).apply() }

    private fun loadKnown() {
        val s = prefs().getString("known_peers", null) ?: return
        runCatching {
            val arr = org.json.JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                known[o.getString("id")] = Peer(o.getString("id"), o.optString("name"), o.optString("platform"), o.getString("host"), false)
            }
        }
    }

    fun setName(name: String) {
        deviceName = name
        appContext.getSharedPreferences("swiftdrop", Context.MODE_PRIVATE)
            .edit().putString("device_name", name).apply()
    }

    /** This device's LAN IPv4 address (for showing in the UI), or "" if none. */
    fun localIp(): String {
        runCatching {
            val ifaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return ""
            for (nif in ifaces) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) return ip
                    }
                }
            }
        }
        return ""
    }

    @Synchronized
    fun newTransfer(name: String, size: Long, peer: String, dir: String): Transfer {
        seq++
        val t = Transfer("${System.currentTimeMillis()}-$seq", name, size, peer, dir)
        transfers.add(t)
        while (transfers.size > 50) transfers.removeAt(0)
        return t
    }
}
