package com.swiftdrop

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles Accept / Reject actions from the transfer consent notification.
 */
class TransferConsentReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ACCEPT = "com.swiftdrop.TRANSFER_ACCEPT"
        const val ACTION_REJECT = "com.swiftdrop.TRANSFER_REJECT"
        const val EXTRA_TRANSFER_ID = "transfer_id"
        const val EXTRA_NOTIF_ID = "notif_id"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val transferId = intent.getStringExtra(EXTRA_TRANSFER_ID) ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, 0)
        val accepted = intent.action == ACTION_ACCEPT

        val tr = State.transfers.firstOrNull { it.id == transferId && it.status == "pending" }
        if (tr != null) {
            tr.accepted = accepted
            tr.decision.countDown()
        }
        // Dismiss the notification.
        if (notifId != 0) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.cancel(notifId)
        }
    }
}
