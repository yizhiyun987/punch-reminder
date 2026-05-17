package com.punch.reminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * 可选前台服务：在部分厂商 ROM 上保持进程存活，确保闹钟能触发。
 * 主流场景下 AlarmManager 已足够，此服务仅在后台被杀后重新注册闹钟。
 */
class ReminderForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "punch_fg_channel"
        const val NOTIF_ID   = 9999

        fun start(ctx: android.content.Context) {
            val intent = Intent(ctx, ReminderForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        AlarmHelper.rescheduleAll(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AlarmHelper.rescheduleAll(this)
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "打卡提醒后台",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "保持打卡提醒服务运行"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("打卡提醒运行中")
                .setContentText("按时提醒您上下班打卡")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("打卡提醒运行中")
                .setContentText("按时提醒您上下班打卡")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .build()
        }
    }
}
