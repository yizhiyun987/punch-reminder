package com.punch.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val type    = intent.getStringExtra(AlarmHelper.EXTRA_TYPE)    ?: return
        val title   = intent.getStringExtra(AlarmHelper.EXTRA_TITLE)   ?: "打卡提醒"
        val message = intent.getStringExtra(AlarmHelper.EXTRA_MESSAGE) ?: "记得打卡！"

        // 重新安排明天的闹钟（模拟每日重复）
        rescheduleForTomorrow(ctx, type, title, message)

        // 启动 ReminderActivity（全屏提醒）
        val actIntent = Intent(ctx, ReminderActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(AlarmHelper.EXTRA_TYPE,    type)
            putExtra(AlarmHelper.EXTRA_TITLE,   title)
            putExtra(AlarmHelper.EXTRA_MESSAGE, message)
        }
        ctx.startActivity(actIntent)
    }

    private fun rescheduleForTomorrow(ctx: Context, type: String, title: String, msg: String) {
        val p = Prefs
        when (type) {
            AlarmHelper.TYPE_CHECKIN  ->
                AlarmHelper.scheduleDailyAlarm(ctx, 0,
                    p.checkinHour, p.checkinMin, type, title, msg)
            AlarmHelper.TYPE_CHECKOUT ->
                AlarmHelper.scheduleDailyAlarm(ctx, 1,
                    p.checkoutHour, p.checkoutMin, type, title, msg)
            AlarmHelper.TYPE_CUSTOM -> {
                // 根据 title+msg 找对应规则重新注册
                Prefs.getCustomReminders().forEachIndexed { i, r ->
                    if (r.title == title && r.enabled && r.type == "point") {
                        AlarmHelper.scheduleDailyAlarm(ctx, 100 + i,
                            r.hour, r.min, type, r.title, r.msg)
                    }
                }
            }
        }
    }
}
