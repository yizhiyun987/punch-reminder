package com.punch.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

object AlarmHelper {

    const val EXTRA_TYPE    = "alarm_type"
    const val EXTRA_TITLE   = "alarm_title"
    const val EXTRA_MESSAGE = "alarm_message"
    const val EXTRA_ID      = "alarm_id"

    const val TYPE_CHECKIN  = "checkin"
    const val TYPE_CHECKOUT = "checkout"
    const val TYPE_CUSTOM   = "custom"
    const val TYPE_DELAY    = "delay"

    // 请求码：0=上班 1=下班 100+ = 自定义
    private const val REQ_CHECKIN  = 0
    private const val REQ_CHECKOUT = 1
    private const val REQ_DELAY    = 99

    private fun alarmManager(ctx: Context) =
        ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun canScheduleExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return alarmManager(ctx).canScheduleExactAlarms()
    }

    /**
     * 设置每日重复闹钟（通过 setAlarmClock / setExactAndAllowWhileIdle 模拟重复）
     */
    fun scheduleDailyAlarm(ctx: Context, requestCode: Int, hour: Int, min: Int,
                           type: String, title: String, msg: String) {
        val am = alarmManager(ctx)
        val intent = Intent(ctx, AlarmReceiver::class.java).apply {
            action = "com.punch.reminder.ALARM"
            putExtra(EXTRA_TYPE,    type)
            putExtra(EXTRA_TITLE,   title)
            putExtra(EXTRA_MESSAGE, msg)
        }
        val pi = PendingIntent.getBroadcast(ctx, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, min)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.set(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        } else {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(cal.timeInMillis, pi), pi)
        }
    }

    fun cancelAlarm(ctx: Context, requestCode: Int) {
        val am = alarmManager(ctx)
        val intent = Intent(ctx, AlarmReceiver::class.java).apply {
            action = "com.punch.reminder.ALARM"
        }
        val pi = PendingIntent.getBroadcast(ctx, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.cancel(pi)
    }

    fun scheduleDelay(ctx: Context, type: String, title: String, msg: String,
                      delayMs: Long = 3_600_000L) {
        val am = alarmManager(ctx)
        val intent = Intent(ctx, AlarmReceiver::class.java).apply {
            action = "com.punch.reminder.ALARM"
            putExtra(EXTRA_TYPE,    type)
            putExtra(EXTRA_TITLE,   title)
            putExtra(EXTRA_MESSAGE, msg)
        }
        val pi = PendingIntent.getBroadcast(ctx, REQ_DELAY, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val triggerAt = System.currentTimeMillis() + delayMs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    /** 重新注册所有闹钟（开机/修改时间后调用） */
    fun rescheduleAll(ctx: Context) {
        val p = Prefs
        scheduleDailyAlarm(ctx, REQ_CHECKIN,
            p.checkinHour, p.checkinMin,
            TYPE_CHECKIN, p.checkinTitle, p.checkinMsg)
        scheduleDailyAlarm(ctx, REQ_CHECKOUT,
            p.checkoutHour, p.checkoutMin,
            TYPE_CHECKOUT, p.checkoutTitle, p.checkoutMsg)

        // 自定义（仅时间点类型可 exact alarm）
        Prefs.getCustomReminders().forEachIndexed { i, r ->
            if (r.enabled && r.type == "point") {
                val reqCode = 100 + i
                scheduleDailyAlarm(ctx, reqCode,
                    r.hour, r.min,
                    TYPE_CUSTOM, r.title, r.msg)
            }
        }
    }
}
