package com.android.r0dumpmanager.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.android.r0dumpmanager.MainActivity
import com.android.r0dumpmanager.R

class ActiveSessionNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    fun show(targetPackage: String, runId: String) {
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
        val contentIntent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(R.drawable.ic_r0dump_manager)
            .setContentTitle(context.getString(R.string.notification_active_title))
            .setContentText(context.getString(R.string.notification_active_text, targetPackage, runId))
            .setSubText(context.getString(R.string.notification_enabled_at, runId.substringBefore('-').ifBlank { context.getString(R.string.unknown) }))
            .setContentIntent(contentIntent).setOngoing(true).setOnlyAlertOnce(true).setSilent(true).build())
    }
    fun showRecovery(generation: Long, detail: String) {
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
        val contentIntent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(R.drawable.ic_r0dump_manager)
            .setContentTitle(context.getString(R.string.notification_recovery_title))
            .setContentText(context.getString(R.string.notification_recovery_text, generation))
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(contentIntent).setAutoCancel(true).setOnlyAlertOnce(true).setSilent(true).build())
    }
    fun cancel() = manager.cancel(NOTIFICATION_ID)
    companion object { const val CHANNEL_ID = "r0dump_active_session"; const val NOTIFICATION_ID = 4100 }
}
