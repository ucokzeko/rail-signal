package com.railsignal.radio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.railsignal.R

/**
 * Always-available recovery floor (ADR 0001): a high-priority notification telling the user
 * to toggle airplane mode, deep-linked straight to the setting. No special permission needed.
 */
class GuidedPromptActuator(private val ctx: Context) : RadioActuator {

    override fun capabilities() = Capabilities(canAuto = false, label = "Guided · tap to reset")

    override suspend fun recover(strategy: RecoveryStrategy): RecoveryResult {
        ensureChannel()
        val tap = PendingIntent.getActivity(
            ctx, 7,
            Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ALERT)
            .setContentTitle("Signal stuck — tap to reset")
            .setContentText("Toggle Airplane mode off/on to force a re-register.")
            .setSmallIcon(R.drawable.ic_signal)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ALERT_ID, notif)
        return RecoveryResult(true, "guided prompt posted")
    }

    private fun ensureChannel() {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ALERT) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERT, "Signal alerts", NotificationManager.IMPORTANCE_HIGH),
            )
        }
    }

    companion object {
        const val CHANNEL_ALERT = "alerts"
        private const val ALERT_ID = 99
    }
}
