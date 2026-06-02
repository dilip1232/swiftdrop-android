package com.swiftdrop

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.IBinder
import android.util.Log

/**
 * Keeps the HTTP server and mDNS discovery alive in the background as a
 * foreground service, so the device can send/receive even when the UI is
 * closed. Holds a multicast lock so mDNS works reliably on Wi-Fi.
 */
class SwiftDropService : Service() {
    private var server: HttpServer? = null
    private var discovery: Discovery? = null
    private var keepalive: Keepalive? = null
    private var lanScanner: LANScanner? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var connectivity: ConnectivityManager? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var currentNetwork: Network? = null

    override fun onCreate() {
        super.onCreate()
        State.init(this)
        Notifier.ensureChannels(this)
        startForeground(Notifier.SERVICE_ID, Notifier.serviceNotification(this))

        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("swiftdrop").apply {
            setReferenceCounted(false)
            acquire()
        }

        try {
            server = HttpServer().also { it.start(NanoTimeout, false) }
        } catch (e: Exception) {
            Log.e("SwiftDrop", "server start failed", e)
        }
        discovery = Discovery(this).also { it.start() }
        keepalive = Keepalive().also { it.start() }
        lanScanner = LANScanner().also { it.start() }

        // Restart discovery on network change so devices/IPs refresh (and stale
        // peers from the old network are dropped).
        connectivity = getSystemService(ConnectivityManager::class.java)
        netCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (currentNetwork != null && network != currentNetwork) {
                    Log.i("SwiftDrop", "network changed; restarting discovery")
                    State.clearMDNS()
                    runCatching { discovery?.stop() }
                    runCatching { lanScanner?.stop() }
                    discovery = Discovery(this@SwiftDropService).also { it.start() }
                    lanScanner = LANScanner().also { it.start() }
                }
                currentNetwork = network
            }
        }
        runCatching { connectivity?.registerDefaultNetworkCallback(netCallback!!) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        runCatching { netCallback?.let { connectivity?.unregisterNetworkCallback(it) } }
        runCatching { server?.stop() }
        runCatching { discovery?.stop() }
        runCatching { keepalive?.stop() }
        runCatching { lanScanner?.stop() }
        runCatching { multicastLock?.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        // Socket read timeout for NanoHTTPD; 0 = no timeout (large uploads).
        private const val NanoTimeout = 0

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, SwiftDropService::class.java))
        }
    }
}
