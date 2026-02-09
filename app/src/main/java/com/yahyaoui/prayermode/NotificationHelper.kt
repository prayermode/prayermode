package com.yahyaoui.prayermode

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

object NotificationHelper {
    private const val CHANNEL_ID = "prayer_channel"

    fun sendNotification(context: Context, titleResId: Int, messageResId: Int, notificationId: Int, vararg formatArgs: Any) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val title = LocaleHelper.getLocalizedString(context, titleResId)
        val message = LocaleHelper.getLocalizedString(context, messageResId, *formatArgs)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = LocaleHelper.getLocalizedString(context, R.string.app_name)
            val channel = NotificationChannel(CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_DEFAULT)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_prayer_mat_vector)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .build()
        notificationManager.notify(notificationId, notification)
    }
    fun sendNotificationWithAction(
        context: Context,
        titleResId: Int,
        messageResId: Int,
        notificationId: Int,
        channelId: String = CHANNEL_ID,
        pendingIntent: PendingIntent? = null,
        actionTitleResId: Int? = null,
        actionPendingIntent: PendingIntent? = null,
        autoCancel: Boolean = true,
        vararg formatArgs: Any) {

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val title = LocaleHelper.getLocalizedString(context, titleResId)
        val message = LocaleHelper.getLocalizedString(context, messageResId, *formatArgs)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = LocaleHelper.getLocalizedString(context, R.string.app_name)
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT)
            notificationManager.createNotificationChannel(channel)
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_prayer_mat_vector)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(autoCancel)

        pendingIntent?.let {
            builder.setContentIntent(it)
        }

        if (actionTitleResId != null && actionPendingIntent != null) {
            val actionTitle = LocaleHelper.getLocalizedString(context, actionTitleResId)
            builder.addAction(R.drawable.ic_prayer_mat_vector, actionTitle, actionPendingIntent)
        }

        notificationManager.notify(notificationId, builder.build())
    }
    fun cancelNotification(context: Context, id: Int) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(id)
    }
}