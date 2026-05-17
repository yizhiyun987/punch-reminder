package com.punch.reminder

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object Prefs {
    private const val NAME = "punch_prefs"
    private var _sp: SharedPreferences? = null

    fun init(ctx: Context) { _sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE) }

    private fun sp() = _sp!!
    private fun edit() = sp().edit()

    // ── 上/下班时间 ──────────────────────────────────────────────────
    var checkinHour:  Int    get() = sp().getInt("checkin_hour", 8)  ;  set(v) { edit().putInt("checkin_hour", v).apply() }
    var checkinMin:   Int    get() = sp().getInt("checkin_min", 40)  ;  set(v) { edit().putInt("checkin_min", v).apply() }
    var checkoutHour: Int    get() = sp().getInt("checkout_hour", 18);  set(v) { edit().putInt("checkout_hour", v).apply() }
    var checkoutMin:  Int    get() = sp().getInt("checkout_min", 0)  ;  set(v) { edit().putInt("checkout_min", v).apply() }

    var checkinTitle:  String get() = sp().getString("checkin_title",  "上班打卡提醒 🏢") ?: "上班打卡提醒 🏢";  set(v) { edit().putString("checkin_title", v).apply() }
    var checkinMsg:    String get() = sp().getString("checkin_msg",    "现在是上班时间，记得打卡！") ?: "现在是上班时间，记得打卡！"; set(v) { edit().putString("checkin_msg", v).apply() }
    var checkoutTitle: String get() = sp().getString("checkout_title", "下班打卡提醒 🏠") ?: "下班打卡提醒 🏠"; set(v) { edit().putString("checkout_title", v).apply() }
    var checkoutMsg:   String get() = sp().getString("checkout_msg",   "现在是下班时间，记得打卡！") ?: "现在是下班时间，记得打卡！"; set(v) { edit().putString("checkout_msg", v).apply() }

    // ── 自定义提醒 ───────────────────────────────────────────────────
    var customRemindersJson: String
        get() = sp().getString("custom_reminders", "[]") ?: "[]"
        set(v) { edit().putString("custom_reminders", v).apply() }

    fun getCustomReminders(): List<CustomReminder> = try {
        val arr = JSONArray(customRemindersJson)
        (0 until arr.length()).map { CustomReminder.fromJson(arr.getJSONObject(it)) }
    } catch (e: Exception) { emptyList() }

    fun saveCustomReminders(list: List<CustomReminder>) {
        val arr = JSONArray(); list.forEach { arr.put(it.toJson()) }
        customRemindersJson = arr.toString()
    }

    // ── 背景图 (URI 字符串；空串 = 默认深色) ─────────────────────────
    // bgType: "none" | "static" | "gif"
    var bgType: String get() = sp().getString("bg_type", "none") ?: "none"; set(v) { edit().putString("bg_type", v).apply() }
    var bgUri:  String get() = sp().getString("bg_uri", "") ?: "";           set(v) { edit().putString("bg_uri", v).apply() }

    // ── 铃声 (URI 字符串；空串 = 系统默认) ───────────────────────────
    // ringtoneUri: 空 = 系统默认闹钟; 非空 = 用户选定
    var ringtoneUri:   String get() = sp().getString("ringtone_uri", "") ?: "";  set(v) { edit().putString("ringtone_uri", v).apply() }
    var ringtoneName:  String get() = sp().getString("ringtone_name","系统默认闹钟铃声") ?: "系统默认闹钟铃声"; set(v) { edit().putString("ringtone_name", v).apply() }
}

data class CustomReminder(
    val id: String,
    val name: String,
    var enabled: Boolean,
    val type: String,          // "point" | "range"
    val hour: Int,   val min: Int,
    val startHour: Int, val startMin: Int,
    val endHour: Int,   val endMin: Int,
    val intervalMin: Int,
    val title: String,
    val msg: String
) {
    companion object {
        fun fromJson(o: JSONObject) = CustomReminder(
            id          = o.optString("id", ""),
            name        = o.optString("name", "未命名"),
            enabled     = o.optBoolean("enabled", true),
            type        = o.optString("type", "point"),
            hour        = o.optInt("hour", 12),   min         = o.optInt("min", 0),
            startHour   = o.optInt("start_hour", 9),  startMin  = o.optInt("start_min", 0),
            endHour     = o.optInt("end_hour", 18),   endMin    = o.optInt("end_min", 0),
            intervalMin = o.optInt("interval_min", 30),
            title       = o.optString("title", "自定义提醒"),
            msg         = o.optString("msg", "提醒时间到了！")
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("enabled", enabled); put("type", type)
        put("hour", hour); put("min", min)
        put("start_hour", startHour); put("start_min", startMin)
        put("end_hour", endHour);     put("end_min", endMin)
        put("interval_min", intervalMin)
        put("title", title); put("msg", msg)
    }

    fun timeDesc(): String = if (type == "point") "%02d:%02d".format(hour, min)
    else "%02d:%02d ~ %02d:%02d 每%d分".format(startHour, startMin, endHour, endMin, intervalMin)
}
