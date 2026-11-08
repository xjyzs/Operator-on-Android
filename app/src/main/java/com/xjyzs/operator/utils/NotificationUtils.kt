package com.xjyzs.operator.utils

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.xjyzs.operator.R

fun updateNotification(context: Context, txt: String) {
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val notification = NotificationCompat.Builder(context, "real_time").setContentTitle("Operator")
        .setSmallIcon(R.drawable.icon).setOngoing(true).setRequestPromotedOngoing(true)
        .setShortCriticalText(txt).build()
    notificationManager.notify(1001, notification)
}
