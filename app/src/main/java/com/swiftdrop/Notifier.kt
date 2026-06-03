package com.swiftdrop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper

/** Notification channels + dynamic service notification that reflects transfer state. */
object Notifier {
    const val SERVICE_CHANNEL = "swiftdrop_service"
    const val ALERT_CHANNEL = "swiftdrop_alerts"
    const val SERVICE_ID = 1
    private var alertId = 1000

    private var ctx: Context? = null
    private val handler = Handler(Looper.getMainLooper())
    private var polling = false
    private var lastSentBytes = 0L
    private var lastPollTime = 0L
    // IDs of transfers that were active during the current poll session
    private val activeSessionIds = mutableSetOf<String>()

    fun ensureChannels(ctx: Context) {
        this.ctx = ctx.applicationContext
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(SERVICE_CHANNEL, "SwiftDrop running", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL, "Transfers", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    /** PendingIntent that opens the main activity when a notification is tapped. */
    private fun launchIntent(ctx: Context): android.app.PendingIntent {
        val intent = android.content.Intent(ctx, MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return android.app.PendingIntent.getActivity(
            ctx, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Build the foreground service notification reflecting current transfer state. */
    fun serviceNotification(ctx: Context): Notification {
        val active = State.transfers.filter { it.status == "sending" }
        val sending = active.count { it.dir == "send" }
        val receiving = active.count { it.dir == "recv" }

        val builder = Notification.Builder(ctx, SERVICE_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setOngoing(true)
            .setContentIntent(launchIntent(ctx))

        if (active.isEmpty()) {
            val ip = State.localIp()
            val text = if (ip.isNotEmpty()) "Ready on $ip" else "Ready to send and receive"
            builder.setContentTitle("SwiftDrop · ${State.deviceName}")
                .setContentText(text)
        } else {
            val totalSize = active.sumOf { it.size }
            val totalSent = active.sumOf { it.sent.get() }
            val pct = if (totalSize > 0) (totalSent * 100 / totalSize).toInt() else 0

            // Calculate speed
            val now = System.currentTimeMillis()
            val speed = if (lastPollTime > 0 && now > lastPollTime) {
                val dt = (now - lastPollTime) / 1000.0
                if (dt > 0) ((totalSent - lastSentBytes) / dt).toLong() else 0L
            } else 0L
            lastSentBytes = totalSent
            lastPollTime = now

            val title = when {
                sending > 0 && receiving > 0 -> "Sending $sending · Receiving $receiving"
                sending > 0 -> "Sending $sending file${if (sending > 1) "s" else ""}"
                else -> "Receiving $receiving file${if (receiving > 1) "s" else ""}"
            }
            val sub = if (speed > 0) "$pct% · ${humanSize(speed)}/s" else "$pct%"

            builder.setContentTitle(title)
                .setContentText(sub)
                .setProgress(100, pct, false)
                .setSmallIcon(if (sending > 0) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_download)
        }

        return builder.build()
    }

    /** Start polling transfers and updating the service notification while transfers are active. */
    fun refreshServiceNotification() {
        val c = ctx ?: return
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.notify(SERVICE_ID, serviceNotification(c))

        // Keep polling while there are active transfers.
        val hasActive = State.transfers.any { it.status == "sending" }
        if (hasActive && !polling) {
            polling = true
            lastSentBytes = 0L
            lastPollTime = 0L
            activeSessionIds.clear()
            activeSessionIds.addAll(State.transfers.filter { it.status == "sending" }.map { it.id })
            pollLoop()
        } else if (hasActive) {
            // Add any new active transfers to the current session
            activeSessionIds.addAll(State.transfers.filter { it.status == "sending" }.map { it.id })
        }
    }

    private fun pollLoop() {
        handler.postDelayed({
            val c = ctx ?: return@postDelayed
            val nm = c.getSystemService(NotificationManager::class.java)
            val hasActive = State.transfers.any { it.status == "sending" }
            if (hasActive) {
                nm.notify(SERVICE_ID, serviceNotification(c))
                pollLoop()
            } else {
                polling = false
                lastSentBytes = 0L
                lastPollTime = 0L
                // Reset to idle notification (no progress bar)
                nm.notify(SERVICE_ID, serviceNotification(c))
                // Fire completion notification
                showDone(c)
            }
        }, 1000)
    }

    private fun showDone(ctx: Context) {
        // Only count transfers that were part of this polling session
        val sessionTransfers = State.transfers.filter { it.id in activeSessionIds }
        val done = sessionTransfers.filter { it.status == "done" }
        val failed = sessionTransfers.filter { it.status == "error" || it.status == "canceled" }
        activeSessionIds.clear()
        if (done.isEmpty() && failed.isEmpty()) return

        val parts = mutableListOf<String>()
        val sent = done.count { it.dir == "send" }
        val recv = done.count { it.dir == "recv" }
        if (sent > 0) parts += "$sent sent"
        if (recv > 0) parts += "$recv received"
        if (failed.isNotEmpty()) parts += "${failed.size} failed"

        val text = parts.joinToString(" · ")
        val n = Notification.Builder(ctx, ALERT_CHANNEL)
            .setContentTitle("Transfer complete")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentIntent(launchIntent(ctx))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(alertId++, n)
    }

    fun show(ctx: Context, text: String) {
        val n = Notification.Builder(ctx, ALERT_CHANNEL)
            .setContentTitle("SwiftDrop")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentIntent(launchIntent(ctx))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(alertId++, n)
    }

    /** Show a consent notification with Accept / Reject action buttons. */
    fun showConsentNotification(ctx: Context, transferId: String, from: String, fileName: String, size: String): Int {
        val notifId = alertId++
        val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE

        val acceptIntent = android.content.Intent(ctx, TransferConsentReceiver::class.java).apply {
            action = TransferConsentReceiver.ACTION_ACCEPT
            putExtra(TransferConsentReceiver.EXTRA_TRANSFER_ID, transferId)
            putExtra(TransferConsentReceiver.EXTRA_NOTIF_ID, notifId)
        }
        val acceptPI = android.app.PendingIntent.getBroadcast(ctx, notifId * 2, acceptIntent, flags)

        val rejectIntent = android.content.Intent(ctx, TransferConsentReceiver::class.java).apply {
            action = TransferConsentReceiver.ACTION_REJECT
            putExtra(TransferConsentReceiver.EXTRA_TRANSFER_ID, transferId)
            putExtra(TransferConsentReceiver.EXTRA_NOTIF_ID, notifId)
        }
        val rejectPI = android.app.PendingIntent.getBroadcast(ctx, notifId * 2 + 1, rejectIntent, flags)

        val n = Notification.Builder(ctx, ALERT_CHANNEL)
            .setContentTitle("Incoming file from $from")
            .setContentText("$fileName ($size)")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setAutoCancel(false)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Accept", acceptPI).build())
            .addAction(Notification.Action.Builder(null, "Reject", rejectPI).build())
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(notifId, n)
        return notifId
    }

    private fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB")
        var value = bytes / 1024.0
        var u = 0
        while (value >= 1024 && u < units.size - 1) { value /= 1024; u++ }
        return "%.1f %s".format(value, units[u])
    }
}
