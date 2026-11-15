package com.xjyzs.operator.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.xjyzs.operator.R

object NotificationUtils {
    const val CHANNEL_REAL_TIME = "real_time"
    const val CHANNEL_FINISH = "finish"
    const val NOTIFICATION_ID = 1001

    private var startTime: Long = 0L

    @Volatile
    private var showTime: Boolean = false

    private var shortCriticalText = ""
    private var title = ""

    fun resetTimer() {
        startTime = System.currentTimeMillis()
        showTime = true
    }

    fun hideTime() {
        showTime = false
    }

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REAL_TIME, "Agent 执行状态", NotificationManager.IMPORTANCE_LOW
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_FINISH, "任务完成通知", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    fun build(context: Context, shortCriticalText: String?, title: String?): Notification {
        if (shortCriticalText != null) this.shortCriticalText = shortCriticalText
        if (title != null) this.title = title

        val builder =
            NotificationCompat.Builder(context, CHANNEL_REAL_TIME).setSmallIcon(R.drawable.icon)
                .setContentTitle(this.title).setOngoing(true).setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setRequestPromotedOngoing(true).setShortCriticalText(this.shortCriticalText)

        if (Build.VERSION.SDK_INT >= 36) {
            builder.setStyle(NotificationCompat.ProgressStyle().setProgressIndeterminate(true))
        }

        if (showTime) {
            builder.setWhen(startTime).setShowWhen(true).setUsesChronometer(true)
        } else {
            builder.setShowWhen(false).setUsesChronometer(false)
        }
        return builder.build()
    }

    fun update(context: Context, shortCriticalText: String?=null, title: String?=null) {
        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, build(context, shortCriticalText, title))
    }
}