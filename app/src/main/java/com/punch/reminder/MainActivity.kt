package com.punch.reminder

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.punch.reminder.databinding.ActivityMainBinding
import java.util.Calendar
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        checkPermissions()
        AlarmHelper.rescheduleAll(this)
        ReminderForegroundService.start(this)
    }

    override fun onResume() {
        super.onResume()
        refreshStatusLabels()
        refreshCustomList()
    }

    private fun setupUI() {
        val p = Prefs

        // --- 上班时间 ---
        binding.tvCheckinTime.text = "时间：%02d:%02d".format(p.checkinHour, p.checkinMin)
        binding.btnEditCheckinTime.setOnClickListener {
            showTimePicker("修改上班提醒时间", p.checkinHour, p.checkinMin) { h, m ->
                p.checkinHour = h; p.checkinMin = m
                binding.tvCheckinTime.text = "时间：%02d:%02d".format(h, m)
                AlarmHelper.rescheduleAll(this)
                refreshStatusLabels()
                snack("上班时间已改为 %02d:%02d".format(h, m))
            }
        }
        binding.btnEditCheckinMsg.setOnClickListener {
            showMessageEditor("修改上班提示词",
                p.checkinTitle, p.checkinMsg) { t, m ->
                p.checkinTitle = t; p.checkinMsg = m
                AlarmHelper.rescheduleAll(this)
                snack("上班提示词已更新")
            }
        }
        binding.btnTestCheckin.setOnClickListener {
            startReminderActivity(Prefs.checkinTitle, Prefs.checkinMsg, AlarmHelper.TYPE_CHECKIN)
        }

        // --- 下班时间 ---
        binding.tvCheckoutTime.text = "时间：%02d:%02d".format(p.checkoutHour, p.checkoutMin)
        binding.btnEditCheckoutTime.setOnClickListener {
            showTimePicker("修改下班提醒时间", p.checkoutHour, p.checkoutMin) { h, m ->
                p.checkoutHour = h; p.checkoutMin = m
                binding.tvCheckoutTime.text = "时间：%02d:%02d".format(h, m)
                AlarmHelper.rescheduleAll(this)
                refreshStatusLabels()
                snack("下班时间已改为 %02d:%02d".format(h, m))
            }
        }
        binding.btnEditCheckoutMsg.setOnClickListener {
            showMessageEditor("修改下班提示词",
                p.checkoutTitle, p.checkoutMsg) { t, m ->
                p.checkoutTitle = t; p.checkoutMsg = m
                AlarmHelper.rescheduleAll(this)
                snack("下班提示词已更新")
            }
        }
        binding.btnTestCheckout.setOnClickListener {
            startReminderActivity(Prefs.checkoutTitle, Prefs.checkoutMsg, AlarmHelper.TYPE_CHECKOUT)
        }

        // --- 自定义提醒 ---
        binding.btnAddCustom.setOnClickListener {
            showCustomReminderEditor(null) { newRule ->
                val list = Prefs.getCustomReminders().toMutableList()
                list.add(newRule)
                Prefs.saveCustomReminders(list)
                AlarmHelper.rescheduleAll(this)
                refreshCustomList()
                snack("已添加：${newRule.name}")
            }
        }

        refreshCustomList()
    }

    private fun refreshStatusLabels() {
        val p = Prefs
        binding.tvCheckinStatus.text = "上班打卡：⏳ 等待提醒 (%02d:%02d)".format(p.checkinHour, p.checkinMin)
        binding.tvCheckoutStatus.text = "下班打卡：⏳ 等待提醒 (%02d:%02d)".format(p.checkoutHour, p.checkoutMin)
    }

    private fun refreshCustomList() {
        val container = binding.customReminderList
        container.removeAllViews()
        val list = Prefs.getCustomReminders()

        val tvEmpty = container.findViewWithTag<TextView>("empty")
        if (list.isEmpty()) {
            val tv = TextView(this).apply {
                text = "暂无自定义提醒"
                setTextColor(getColor(R.color.dark))
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                setPadding(0, 16, 0, 8)
                tag = "empty"
            }
            container.addView(tv)
        } else {
            list.forEachIndexed { idx, rule ->
                val row = LayoutInflater.from(this)
                    .inflate(R.layout.item_custom_reminder, container, false)

                row.findViewById<TextView>(R.id.tvCustomName).text = rule.name
                row.findViewById<TextView>(R.id.tvCustomTime).text = rule.timeDesc()

                val sw = row.findViewById<SwitchMaterial>(R.id.switchEnabled)
                sw.isChecked = rule.enabled
                sw.setOnCheckedChangeListener { _, checked ->
                    val updated = list.toMutableList()
                    updated[idx] = updated[idx].copy(enabled = checked)
                    Prefs.saveCustomReminders(updated)
                    AlarmHelper.rescheduleAll(this)
                }

                row.findViewById<Button>(R.id.btnDelete).setOnClickListener {
                    val updated = list.toMutableList()
                    updated.removeAt(idx)
                    Prefs.saveCustomReminders(updated)
                    AlarmHelper.rescheduleAll(this)
                    refreshCustomList()
                    snack("已删除")
                }

                container.addView(row)
            }
        }
    }

    // ── 时间选择器对话框 ──────────────────────────────────────────────
    private fun showTimePicker(title: String, initH: Int, initM: Int,
                               onConfirm: (Int, Int) -> Unit) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_time_picker, null)
        val tvTitle = view.findViewById<TextView>(R.id.tvDialogTitle)
        val tvHour  = view.findViewById<TextView>(R.id.tvHour)
        val tvMin   = view.findViewById<TextView>(R.id.tvMin)
        tvTitle.text = title

        var h = initH; var m = initM
        tvHour.text = "%02d".format(h)
        tvMin.text  = "%02d".format(m)

        view.findViewById<Button>(R.id.btnHourUp).setOnClickListener {
            h = (h + 1) % 24; tvHour.text = "%02d".format(h)
        }
        view.findViewById<Button>(R.id.btnHourDown).setOnClickListener {
            h = (h + 23) % 24; tvHour.text = "%02d".format(h)
        }
        view.findViewById<Button>(R.id.btnMinUp).setOnClickListener {
            m = (m + 5) % 60; tvMin.text = "%02d".format(m)
        }
        view.findViewById<Button>(R.id.btnMinDown).setOnClickListener {
            m = (m + 55) % 60; tvMin.text = "%02d".format(m)
        }

        AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("✅ 确认") { _, _ -> onConfirm(h, m) }
            .setNegativeButton("取消", null)
            .show()
            .also { dlg ->
                dlg.getButton(AlertDialog.BUTTON_POSITIVE)
                    ?.setTextColor(getColor(R.color.green))
            }
    }

    // ── 提示词编辑对话框 ──────────────────────────────────────────────
    private fun showMessageEditor(title: String, initTitle: String, initMsg: String,
                                  onConfirm: (String, String) -> Unit) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val etTitle = EditText(this).apply {
            hint = "弹窗标题"
            setText(initTitle)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF888888.toInt())
        }
        val etMsg = EditText(this).apply {
            hint = "提醒正文"
            setText(initMsg)
            minLines = 2
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF888888.toInt())
        }
        layout.addView(TextView(this).apply {
            text = "弹窗标题："; setTextColor(0xFFAAAAAA.toInt()); textSize = 13f
        })
        layout.addView(etTitle)
        layout.addView(TextView(this).apply {
            text = "提醒正文："; setTextColor(0xFFAAAAAA.toInt()); textSize = 13f
            setPadding(0, 12, 0, 0)
        })
        layout.addView(etMsg)

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton("✅ 确认") { _, _ ->
                onConfirm(etTitle.text.toString(), etMsg.text.toString())
            }
            .setNegativeButton("取消", null)
            .show()
            .also { dlg ->
                dlg.getButton(AlertDialog.BUTTON_POSITIVE)
                    ?.setTextColor(getColor(R.color.green))
            }
    }

    // ── 自定义提醒编辑器 ──────────────────────────────────────────────
    private fun showCustomReminderEditor(existing: CustomReminder?,
                                         onDone: (CustomReminder) -> Unit) {
        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }
        scrollView.addView(layout)

        fun tv(t: String) = TextView(this).apply {
            text = t; setTextColor(0xFFAAAAAA.toInt()); textSize = 13f
        }
        fun et(init: String, hint: String = "") = EditText(this).apply {
            setText(init); this.hint = hint
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF888888.toInt())
        }

        layout.addView(tv("提醒名称："))
        val etName = et(existing?.name ?: "", "例如：午休提醒")
        layout.addView(etName)

        // 类型
        layout.addView(tv("类型："))
        val typeGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rbPoint = RadioButton(this).apply { text = "📍 时间点"; setTextColor(0xFFFFFFFF.toInt()) }
        val rbRange = RadioButton(this).apply { text = "🔁 时间段"; setTextColor(0xFFFFFFFF.toInt()) }
        typeGroup.addView(rbPoint)
        typeGroup.addView(rbRange)
        val isRange = existing?.type == "range"
        rbPoint.isChecked = !isRange
        rbRange.isChecked = isRange
        layout.addView(typeGroup)

        // 时间点
        val pointLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        layout.addView(tv("时间："))
        var ph = existing?.hour ?: 12; var pm = existing?.min ?: 0
        val tvPH = TextView(this).apply { text = "%02d".format(ph); setTextColor(0xFFFFFFFF.toInt()); textSize = 20f; setTypeface(null, android.graphics.Typeface.BOLD) }
        val tvPM = TextView(this).apply { text = "%02d".format(pm); setTextColor(0xFFFFFFFF.toInt()); textSize = 20f; setTypeface(null, android.graphics.Typeface.BOLD) }
        val btnPHU = Button(this).apply { text = "▲"; textSize = 12f }
        val btnPHD = Button(this).apply { text = "▼"; textSize = 12f }
        val btnPMU = Button(this).apply { text = "▲"; textSize = 12f }
        val btnPMD = Button(this).apply { text = "▼"; textSize = 12f }
        btnPHU.setOnClickListener { ph = (ph+1)%24; tvPH.text = "%02d".format(ph) }
        btnPHD.setOnClickListener { ph = (ph+23)%24; tvPH.text = "%02d".format(ph) }
        btnPMU.setOnClickListener { pm = (pm+5)%60; tvPM.text = "%02d".format(pm) }
        btnPMD.setOnClickListener { pm = (pm+55)%60; tvPM.text = "%02d".format(pm) }
        for (v in listOf(btnPHD, tvPH, btnPHU, TextView(this).apply { text=":"; setTextColor(0xFFFFFFFF.toInt()); textSize=20f }, btnPMD, tvPM, btnPMU))
            pointLayout.addView(v)
        layout.addView(pointLayout)

        // 时间段
        val rangeLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (isRange) View.VISIBLE else View.GONE
        }
        var rsh = existing?.startHour ?: 9; var rsm = existing?.startMin ?: 0
        var reh = existing?.endHour   ?: 18; var rem = existing?.endMin  ?: 0
        var riv = existing?.intervalMin ?: 30
        val tvRSH = TextView(this).apply { text = "%02d".format(rsh); setTextColor(0xFFFFFFFF.toInt()); textSize = 18f }
        val tvRSM = TextView(this).apply { text = "%02d".format(rsm); setTextColor(0xFFFFFFFF.toInt()); textSize = 18f }
        val tvREH = TextView(this).apply { text = "%02d".format(reh); setTextColor(0xFFFFFFFF.toInt()); textSize = 18f }
        val tvREM = TextView(this).apply { text = "%02d".format(rem); setTextColor(0xFFFFFFFF.toInt()); textSize = 18f }
        val tvRIV = TextView(this).apply { text = "$riv"; setTextColor(0xFFFFFFFF.toInt()); textSize = 18f }

        fun makeRow(label: String, hView: TextView, mView: TextView, getH: () -> Int, setH: (Int) -> Unit, getM: () -> Int, setM: (Int) -> Unit): LinearLayout {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            val lbl = TextView(this).apply { text = label; setTextColor(0xFFAAAAAA.toInt()); textSize = 13f }
            val bhu = Button(this).apply { text = "▲"; textSize = 11f }
            val bhd = Button(this).apply { text = "▼"; textSize = 11f }
            val sep = TextView(this).apply { text = ":"; setTextColor(0xFFFFFFFF.toInt()); textSize = 18f }
            val bmu = Button(this).apply { text = "▲"; textSize = 11f }
            val bmd = Button(this).apply { text = "▼"; textSize = 11f }
            bhu.setOnClickListener { setH((getH()+1)%24); hView.text = "%02d".format(getH()) }
            bhd.setOnClickListener { setH((getH()+23)%24); hView.text = "%02d".format(getH()) }
            bmu.setOnClickListener { setM((getM()+5)%60); mView.text = "%02d".format(getM()) }
            bmd.setOnClickListener { setM((getM()+55)%60); mView.text = "%02d".format(getM()) }
            for (v in listOf(lbl, bhd, hView, bhu, sep, bmd, mView, bmu)) row.addView(v)
            return row
        }

        rangeLayout.addView(makeRow("开始：", tvRSH, tvRSM, { rsh }, { rsh=it }, { rsm }, { rsm=it }))
        rangeLayout.addView(makeRow("结束：", tvREH, tvREM, { reh }, { reh=it }, { rem }, { rem=it }))

        val ivRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val ivLabel = TextView(this).apply { text = "间隔(分钟)："; setTextColor(0xFFAAAAAA.toInt()); textSize = 13f }
        val btnIVD = Button(this).apply { text = "−"; textSize = 14f }
        val btnIVU = Button(this).apply { text = "+"; textSize = 14f }
        btnIVD.setOnClickListener { riv = maxOf(1, riv-5); tvRIV.text = "$riv" }
        btnIVU.setOnClickListener { riv = minOf(120, riv+5); tvRIV.text = "$riv" }
        for (v in listOf(ivLabel, btnIVD, tvRIV, btnIVU)) ivRow.addView(v)
        rangeLayout.addView(ivRow)
        layout.addView(rangeLayout)

        pointLayout.visibility = if (!isRange) View.VISIBLE else View.GONE
        typeGroup.setOnCheckedChangeListener { _, id ->
            val r = id == rbRange.id
            pointLayout.visibility = if (!r) View.VISIBLE else View.GONE
            rangeLayout.visibility  = if (r) View.VISIBLE else View.GONE
        }

        layout.addView(tv("弹窗标题："))
        val etTitle = et(existing?.title ?: "自定义提醒")
        layout.addView(etTitle)
        layout.addView(tv("提醒正文："))
        val etMsg = et(existing?.msg ?: "提醒时间到了！").apply { minLines = 2 }
        layout.addView(etMsg)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "添加自定义提醒" else "编辑提醒")
            .setView(scrollView)
            .setPositiveButton("✅ 保存") { _, _ ->
                val isR = rbRange.isChecked
                val rule = CustomReminder(
                    id          = existing?.id ?: UUID.randomUUID().toString().take(8),
                    name        = etName.text.toString().ifBlank { "未命名" },
                    enabled     = true,
                    type        = if (isR) "range" else "point",
                    hour        = ph, min = pm,
                    startHour   = rsh, startMin = rsm,
                    endHour     = reh, endMin = rem,
                    intervalMin = riv,
                    title       = etTitle.text.toString(),
                    msg         = etMsg.text.toString()
                )
                onDone(rule)
            }
            .setNegativeButton("取消", null)
            .show()
            .also { dlg ->
                dlg.getButton(AlertDialog.BUTTON_POSITIVE)
                    ?.setTextColor(getColor(R.color.green))
            }
    }

    // ── 权限检查 ──────────────────────────────────────────────────────
    private fun checkPermissions() {
        // 通知权限 (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
        // 精确闹钟权限 (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(android.app.AlarmManager::class.java)
            if (!am.canScheduleExactAlarms()) {
                AlertDialog.Builder(this)
                    .setTitle("需要精确闹钟权限")
                    .setMessage("为确保按时提醒，请在下一页授予"闹钟和提醒"权限")
                    .setPositiveButton("前往设置") { _, _ ->
                        startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:$packageName")))
                    }
                    .setNegativeButton("稍后", null)
                    .show()
            }
        }
    }

    private fun startReminderActivity(title: String, message: String, type: String) {
        startActivity(Intent(this, ReminderActivity::class.java).apply {
            putExtra(AlarmHelper.EXTRA_TYPE,    type)
            putExtra(AlarmHelper.EXTRA_TITLE,   title)
            putExtra(AlarmHelper.EXTRA_MESSAGE, message)
        })
    }

    private fun snack(msg: String) =
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
}
