package com.punch.reminder

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.AnimationDrawable
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.punch.reminder.databinding.ActivityMainBinding
import java.io.InputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // ── 图片选择器 ──────────────────────────────────────────────────
    private var pendingBgType = "static"

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            // 持久化读取权限，下次启动仍可用
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            Prefs.bgType = pendingBgType
            Prefs.bgUri  = uri.toString()
            applyBackground()
            updateBgPreview(uri)
            snack("背景已更新")
        }

    // ── 铃声选择器（系统 RingtoneManager） ─────────────────────────
    private val pickRingtoneLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val uri = result.data
                ?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                ?: return@registerForActivityResult
            val name = RingtoneManager.getRingtone(this, uri)?.getTitle(this) ?: uri.lastPathSegment ?: "自定义铃声"
            Prefs.ringtoneUri  = uri.toString()
            Prefs.ringtoneName = name
            binding.tvRingtoneLabel.text = "当前：$name"
            snack("铃声已设置：$name")
        }

    // ── 本地音乐文件选择器 ──────────────────────────────────────────
    private val pickMusicLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            val name = uri.lastPathSegment ?: "本地音乐"
            Prefs.ringtoneUri  = uri.toString()
            Prefs.ringtoneName = name
            binding.tvRingtoneLabel.text = "当前：$name"
            snack("音乐已设置：$name")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        applyBackground()
        checkPermissions()
        AlarmHelper.rescheduleAll(this)
        ReminderForegroundService.start(this)
    }

    override fun onResume() {
        super.onResume()
        refreshStatusLabels()
        refreshCustomList()
    }

    // ════════════════════════════════════════════════════════════════
    // UI Setup
    // ════════════════════════════════════════════════════════════════
    private fun setupUI() {
        val p = Prefs

        // ── 上班 ─────────────────────────────────────────────────────
        binding.tvCheckinTime.text = "时间：%02d:%02d".format(p.checkinHour, p.checkinMin)
        binding.btnEditCheckinTime.setOnClickListener {
            showTimePicker("修改上班提醒时间", p.checkinHour, p.checkinMin) { h, m ->
                p.checkinHour = h; p.checkinMin = m
                binding.tvCheckinTime.text = "时间：%02d:%02d".format(h, m)
                AlarmHelper.rescheduleAll(this); refreshStatusLabels()
                snack("上班时间已改为 %02d:%02d".format(h, m))
            }
        }
        binding.btnEditCheckinMsg.setOnClickListener {
            showMessageEditor("修改上班提示词", p.checkinTitle, p.checkinMsg) { t, m ->
                p.checkinTitle = t; p.checkinMsg = m
                AlarmHelper.rescheduleAll(this); snack("上班提示词已更新")
            }
        }
        binding.btnTestCheckin.setOnClickListener {
            startReminderActivity(p.checkinTitle, p.checkinMsg, AlarmHelper.TYPE_CHECKIN)
        }

        // ── 下班 ─────────────────────────────────────────────────────
        binding.tvCheckoutTime.text = "时间：%02d:%02d".format(p.checkoutHour, p.checkoutMin)
        binding.btnEditCheckoutTime.setOnClickListener {
            showTimePicker("修改下班提醒时间", p.checkoutHour, p.checkoutMin) { h, m ->
                p.checkoutHour = h; p.checkoutMin = m
                binding.tvCheckoutTime.text = "时间：%02d:%02d".format(h, m)
                AlarmHelper.rescheduleAll(this); refreshStatusLabels()
                snack("下班时间已改为 %02d:%02d".format(h, m))
            }
        }
        binding.btnEditCheckoutMsg.setOnClickListener {
            showMessageEditor("修改下班提示词", p.checkoutTitle, p.checkoutMsg) { t, m ->
                p.checkoutTitle = t; p.checkoutMsg = m
                AlarmHelper.rescheduleAll(this); snack("下班提示词已更新")
            }
        }
        binding.btnTestCheckout.setOnClickListener {
            startReminderActivity(p.checkoutTitle, p.checkoutMsg, AlarmHelper.TYPE_CHECKOUT)
        }

        // ── 自定义提醒 ────────────────────────────────────────────────
        binding.btnAddCustom.setOnClickListener {
            showCustomReminderDialog(null) { rule ->
                val list = Prefs.getCustomReminders().toMutableList()
                list.add(rule)
                Prefs.saveCustomReminders(list)
                AlarmHelper.rescheduleAll(this)
                refreshCustomList()
                snack("已添加：${rule.name}")
            }
        }
        refreshCustomList()

        // ── 背景图设置 ────────────────────────────────────────────────
        // 恢复 RadioGroup 状态
        when (Prefs.bgType) {
            "static" -> binding.rbBgStatic.isChecked = true
            "gif"    -> binding.rbBgGif.isChecked = true
            else     -> binding.rbBgNone.isChecked = true
        }
        binding.layoutBgPicker.visibility =
            if (Prefs.bgType == "none") View.GONE else View.VISIBLE
        if (Prefs.bgUri.isNotEmpty()) {
            updateBgPreview(Uri.parse(Prefs.bgUri))
        }

        binding.rgBgType.setOnCheckedChangeListener { _, checkedId ->
            val type = when (checkedId) {
                R.id.rbBgStatic -> "static"
                R.id.rbBgGif    -> "gif"
                else             -> "none"
            }
            if (type == "none") {
                Prefs.bgType = "none"; Prefs.bgUri = ""
                binding.layoutBgPicker.visibility = View.GONE
                binding.ivBackground.visibility = View.GONE
                binding.viewOverlay.visibility = View.GONE
                binding.scrollRoot.setBackgroundResource(R.color.bg_dark)
                snack("已恢复默认深色背景")
            } else {
                binding.layoutBgPicker.visibility = View.VISIBLE
            }
        }

        binding.btnPickBg.setOnClickListener {
            pendingBgType = when {
                binding.rbBgGif.isChecked    -> "gif"
                binding.rbBgStatic.isChecked -> "static"
                else                          -> "static"
            }
            pickImageLauncher.launch(arrayOf("image/*"))
        }

        binding.btnClearBg.setOnClickListener {
            Prefs.bgUri = ""; Prefs.bgType = "none"
            binding.rbBgNone.isChecked = true
            binding.ivBackground.visibility = View.GONE
            binding.viewOverlay.visibility = View.GONE
            binding.ivBgPreview.visibility = View.GONE
            binding.scrollRoot.setBackgroundResource(R.color.bg_dark)
            snack("背景已清除")
        }

        // ── 铃声设置 ──────────────────────────────────────────────────
        binding.tvRingtoneLabel.text = "当前：${Prefs.ringtoneName}"

        binding.btnPickRingtone.setOnClickListener {
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE,
                    RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_RINGTONE)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选择提醒铃声")
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                if (Prefs.ringtoneUri.isNotEmpty()) {
                    putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                        Uri.parse(Prefs.ringtoneUri))
                }
            }
            pickRingtoneLauncher.launch(intent)
        }

        binding.btnPickMusicFile.setOnClickListener {
            pickMusicLauncher.launch(arrayOf("audio/*"))
        }

        binding.btnResetRingtone.setOnClickListener {
            Prefs.ringtoneUri  = ""
            Prefs.ringtoneName = "系统默认闹钟铃声"
            binding.tvRingtoneLabel.text = "当前：系统默认闹钟铃声"
            snack("已重置为系统默认铃声")
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 背景应用
    // ════════════════════════════════════════════════════════════════
    private fun applyBackground() {
        val bgType = Prefs.bgType
        val bgUri  = Prefs.bgUri
        if (bgType == "none" || bgUri.isEmpty()) {
            binding.ivBackground.visibility = View.GONE
            binding.viewOverlay.visibility  = View.GONE
            binding.scrollRoot.setBackgroundResource(R.color.bg_dark)
            return
        }
        try {
            val uri = Uri.parse(bgUri)
            val stream: InputStream = contentResolver.openInputStream(uri) ?: return
            val bitmap: Bitmap = BitmapFactory.decodeStream(stream)
            stream.close()
            binding.ivBackground.setImageBitmap(bitmap)
            binding.ivBackground.visibility = View.VISIBLE
            binding.viewOverlay.visibility  = View.VISIBLE
            binding.scrollRoot.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        } catch (e: Exception) {
            // URI 失效，回退默认
            Prefs.bgType = "none"; Prefs.bgUri = ""
            binding.ivBackground.visibility = View.GONE
            binding.viewOverlay.visibility  = View.GONE
            binding.scrollRoot.setBackgroundResource(R.color.bg_dark)
        }
    }

    private fun updateBgPreview(uri: Uri) {
        try {
            val stream = contentResolver.openInputStream(uri) ?: return
            val bmp = BitmapFactory.decodeStream(stream); stream.close()
            binding.ivBgPreview.setImageBitmap(bmp)
            binding.ivBgPreview.visibility = View.VISIBLE
        } catch (e: Exception) { /* 忽略 */ }
    }

    // ════════════════════════════════════════════════════════════════
    // 状态刷新
    // ════════════════════════════════════════════════════════════════
    private fun refreshStatusLabels() {
        binding.tvCheckinStatus.text  = "上班打卡：⏳ 等待提醒 (%02d:%02d)".format(Prefs.checkinHour,  Prefs.checkinMin)
        binding.tvCheckoutStatus.text = "下班打卡：⏳ 等待提醒 (%02d:%02d)".format(Prefs.checkoutHour, Prefs.checkoutMin)
    }

    private fun refreshCustomList() {
        val container = binding.customReminderList
        container.removeAllViews()
        val list = Prefs.getCustomReminders()
        if (list.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "暂无自定义提醒"
                setTextColor(getColor(R.color.dark))
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                setPadding(0, 16, 0, 8)
            })
            return
        }
        list.forEachIndexed { idx, rule ->
            val row = LayoutInflater.from(this)
                .inflate(R.layout.item_custom_reminder, container, false)
            row.findViewById<TextView>(R.id.tvCustomName).text = rule.name
            row.findViewById<TextView>(R.id.tvCustomTime).text = rule.timeDesc()
            val sw = row.findViewById<SwitchMaterial>(R.id.switchEnabled)
            sw.isChecked = rule.enabled
            sw.setOnCheckedChangeListener { _, checked ->
                val updated = list.toMutableList().also { it[idx] = it[idx].copy(enabled = checked) }
                Prefs.saveCustomReminders(updated)
                AlarmHelper.rescheduleAll(this)
            }
            row.findViewById<Button>(R.id.btnDelete).setOnClickListener {
                val updated = list.toMutableList().also { it.removeAt(idx) }
                Prefs.saveCustomReminders(updated)
                AlarmHelper.rescheduleAll(this)
                refreshCustomList(); snack("已删除")
            }
            container.addView(row)
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 自定义提醒编辑对话框（使用独立 Layout，避免动态拼装问题）
    // ════════════════════════════════════════════════════════════════
    private fun showCustomReminderDialog(
        existing: CustomReminder?,
        onDone: (CustomReminder) -> Unit
    ) {
        val view = LayoutInflater.from(this)
            .inflate(R.layout.dialog_custom_reminder, null)

        // 基础字段
        val etName  = view.findViewById<EditText>(R.id.etReminderName)
        val rgType  = view.findViewById<RadioGroup>(R.id.rgType)
        val rbPoint = view.findViewById<RadioButton>(R.id.rbPoint)
        val rbRange = view.findViewById<RadioButton>(R.id.rbRange)
        val etTitle = view.findViewById<EditText>(R.id.etTitle)
        val etMsg   = view.findViewById<EditText>(R.id.etMsg)

        // 面板
        val panelPoint = view.findViewById<LinearLayout>(R.id.panelPoint)
        val panelRange = view.findViewById<LinearLayout>(R.id.panelRange)

        // 时间点控件
        val tvPH = view.findViewById<TextView>(R.id.tvPH)
        val tvPM = view.findViewById<TextView>(R.id.tvPM)

        // 时间段控件
        val tvRSH = view.findViewById<TextView>(R.id.tvRSH)
        val tvRSM = view.findViewById<TextView>(R.id.tvRSM)
        val tvREH = view.findViewById<TextView>(R.id.tvREH)
        val tvREM = view.findViewById<TextView>(R.id.tvREM)
        val tvIV  = view.findViewById<TextView>(R.id.tvIV)

        // 初始值
        var ph = existing?.hour ?: 9;    var pm = existing?.min ?: 0
        var rsh = existing?.startHour ?: 9; var rsm = existing?.startMin ?: 0
        var reh = existing?.endHour ?: 18;  var rem = existing?.endMin ?: 0
        var riv = existing?.intervalMin ?: 30

        tvPH.text = "%02d".format(ph); tvPM.text = "%02d".format(pm)
        tvRSH.text = "%02d".format(rsh); tvRSM.text = "%02d".format(rsm)
        tvREH.text = "%02d".format(reh); tvREM.text = "%02d".format(rem)
        tvIV.text = "$riv"

        existing?.let {
            etName.setText(it.name)
            etTitle.setText(it.title)
            etMsg.setText(it.msg)
            if (it.type == "range") {
                rbRange.isChecked = true
                panelPoint.visibility = View.GONE
                panelRange.visibility = View.VISIBLE
            }
        }

        // 类型切换
        rgType.setOnCheckedChangeListener { _, checkedId ->
            val isRange = checkedId == R.id.rbRange
            panelPoint.visibility = if (isRange) View.GONE  else View.VISIBLE
            panelRange.visibility = if (isRange) View.VISIBLE else View.GONE
        }

        // 时间点按钮
        fun bindSpin(upId: Int, downId: Int, stepH: Int = 1, stepM: Int = 5,
                     getV: () -> Int, setV: (Int) -> Unit,
                     maxV: Int, tv: TextView, fmt: String = "%02d") {
            view.findViewById<Button>(upId).setOnClickListener {
                setV((getV() + stepH) % (maxV + 1)); tv.text = fmt.format(getV())
            }
            view.findViewById<Button>(downId).setOnClickListener {
                setV((getV() + maxV) % (maxV + 1)); tv.text = fmt.format(getV())
            }
        }

        // 时间点 hour/min
        view.findViewById<Button>(R.id.btnPHUp).setOnClickListener   { ph = (ph+1)%24;  tvPH.text = "%02d".format(ph) }
        view.findViewById<Button>(R.id.btnPHDown).setOnClickListener { ph = (ph+23)%24; tvPH.text = "%02d".format(ph) }
        view.findViewById<Button>(R.id.btnPMUp).setOnClickListener   { pm = (pm+5)%60;  tvPM.text = "%02d".format(pm) }
        view.findViewById<Button>(R.id.btnPMDown).setOnClickListener { pm = (pm+55)%60; tvPM.text = "%02d".format(pm) }

        // 时间段 start
        view.findViewById<Button>(R.id.btnRSHUp).setOnClickListener   { rsh = (rsh+1)%24;  tvRSH.text = "%02d".format(rsh) }
        view.findViewById<Button>(R.id.btnRSHDown).setOnClickListener { rsh = (rsh+23)%24; tvRSH.text = "%02d".format(rsh) }
        view.findViewById<Button>(R.id.btnRSMUp).setOnClickListener   { rsm = (rsm+5)%60;  tvRSM.text = "%02d".format(rsm) }
        view.findViewById<Button>(R.id.btnRSMDown).setOnClickListener { rsm = (rsm+55)%60; tvRSM.text = "%02d".format(rsm) }

        // 时间段 end
        view.findViewById<Button>(R.id.btnREHUp).setOnClickListener   { reh = (reh+1)%24;  tvREH.text = "%02d".format(reh) }
        view.findViewById<Button>(R.id.btnREHDown).setOnClickListener { reh = (reh+23)%24; tvREH.text = "%02d".format(reh) }
        view.findViewById<Button>(R.id.btnREMUp).setOnClickListener   { rem = (rem+5)%60;  tvREM.text = "%02d".format(rem) }
        view.findViewById<Button>(R.id.btnREMDown).setOnClickListener { rem = (rem+55)%60; tvREM.text = "%02d".format(rem) }

        // 间隔
        view.findViewById<Button>(R.id.btnIVUp).setOnClickListener   { riv = minOf(120, riv+5); tvIV.text = "$riv" }
        view.findViewById<Button>(R.id.btnIVDown).setOnClickListener { riv = maxOf(1, riv-5);   tvIV.text = "$riv" }

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "添加自定义提醒" else "编辑提醒")
            .setView(view)
            .setPositiveButton("✅ 保存") { _, _ ->
                val isRange = rbRange.isChecked
                onDone(CustomReminder(
                    id          = existing?.id ?: UUID.randomUUID().toString().take(8),
                    name        = etName.text.toString().ifBlank { "未命名" },
                    enabled     = true,
                    type        = if (isRange) "range" else "point",
                    hour = ph, min = pm,
                    startHour = rsh, startMin = rsm,
                    endHour = reh,   endMin = rem,
                    intervalMin = riv,
                    title = etTitle.text.toString().ifBlank { "自定义提醒" },
                    msg   = etMsg.text.toString().ifBlank { "提醒时间到了！" }
                ))
            }
            .setNegativeButton("取消", null)
            .show()
            .also { dlg ->
                dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(getColor(R.color.green))
            }
    }

    // ════════════════════════════════════════════════════════════════
    // 时间选择器对话框
    // ════════════════════════════════════════════════════════════════
    private fun showTimePicker(title: String, initH: Int, initM: Int,
                               onConfirm: (Int, Int) -> Unit) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_time_picker, null)
        view.findViewById<TextView>(R.id.tvDialogTitle).text = title
        val tvH = view.findViewById<TextView>(R.id.tvHour)
        val tvM = view.findViewById<TextView>(R.id.tvMin)
        var h = initH; var m = initM
        tvH.text = "%02d".format(h); tvM.text = "%02d".format(m)
        view.findViewById<Button>(R.id.btnHourUp).setOnClickListener   { h = (h+1)%24;  tvH.text = "%02d".format(h) }
        view.findViewById<Button>(R.id.btnHourDown).setOnClickListener { h = (h+23)%24; tvH.text = "%02d".format(h) }
        view.findViewById<Button>(R.id.btnMinUp).setOnClickListener    { m = (m+5)%60;  tvM.text = "%02d".format(m) }
        view.findViewById<Button>(R.id.btnMinDown).setOnClickListener  { m = (m+55)%60; tvM.text = "%02d".format(m) }
        AlertDialog.Builder(this).setView(view)
            .setPositiveButton("✅ 确认") { _, _ -> onConfirm(h, m) }
            .setNegativeButton("取消", null)
            .show()
            .also { it.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(getColor(R.color.green)) }
    }

    // ════════════════════════════════════════════════════════════════
    // 提示词编辑对话框
    // ════════════════════════════════════════════════════════════════
    private fun showMessageEditor(title: String, initTitle: String, initMsg: String,
                                  onConfirm: (String, String) -> Unit) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 8)
        }
        val etT = EditText(this).apply { setText(initTitle); hint = "弹窗标题"; setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFF888888.toInt()) }
        val etM = EditText(this).apply { setText(initMsg); hint = "提醒正文"; minLines = 2; setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFF888888.toInt()) }
        fun label(t: String) = TextView(this).apply { text = t; setTextColor(0xFFAAAAAA.toInt()); textSize = 13f }
        layout.addView(label("弹窗标题：")); layout.addView(etT)
        layout.addView(label("提醒正文：").apply { setPadding(0,12,0,0) }); layout.addView(etM)
        AlertDialog.Builder(this).setTitle(title).setView(layout)
            .setPositiveButton("✅ 确认") { _, _ -> onConfirm(etT.text.toString(), etM.text.toString()) }
            .setNegativeButton("取消", null)
            .show()
            .also { it.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(getColor(R.color.green)) }
    }

    // ════════════════════════════════════════════════════════════════
    // 权限检查
    // ════════════════════════════════════════════════════════════════
    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(android.app.AlarmManager::class.java)
            if (!am.canScheduleExactAlarms()) {
                AlertDialog.Builder(this)
                    .setTitle("需要精确闹钟权限")
                    .setMessage("为确保按时提醒，请在下一页授予「闹钟和提醒」权限")
                    .setPositiveButton("前往设置") { _, _ ->
                        startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:$packageName")))
                    }
                    .setNegativeButton("稍后", null).show()
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
