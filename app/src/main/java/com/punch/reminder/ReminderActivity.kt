package com.punch.reminder

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.punch.reminder.databinding.ActivityReminderBinding

class ReminderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReminderBinding
    private var mediaPlayer: MediaPlayer? = null
    private var autoHandler: Handler? = null
    private val AUTO_DISMISS_MS = 5 * 60 * 1000L  // 5 分钟自动关闭

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 锁屏/息屏时强制亮屏显示
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            km.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        binding = ActivityReminderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val title   = intent.getStringExtra(AlarmHelper.EXTRA_TITLE)   ?: "⏰ 打卡提醒"
        val message = intent.getStringExtra(AlarmHelper.EXTRA_MESSAGE) ?: "记得打卡！"
        val type    = intent.getStringExtra(AlarmHelper.EXTRA_TYPE)    ?: ""

        binding.tvReminderTitle.text   = title
        binding.tvReminderMessage.text = message

        vibrate()
        // 播放系统默认闹钟铃声
        playDefaultAlarm()

        // 收到
        binding.btnReceived.setOnClickListener {
            stopMediaAndVibrate()
            autoHandler?.removeCallbacksAndMessages(null)
            finish()
        }

        // 延迟 1 小时
        binding.btnDelay.setOnClickListener {
            stopMediaAndVibrate()
            autoHandler?.removeCallbacksAndMessages(null)
            AlarmHelper.scheduleDelay(this, type, title, message, 3_600_000L)
            finish()
        }

        // 5 分钟无操作 → 关闭此界面，1 分钟后再次弹出
        autoHandler = Handler(Looper.getMainLooper())
        autoHandler?.postDelayed({
            stopMediaAndVibrate()
            // 1 分钟后再次提醒
            AlarmHelper.scheduleDelay(this, type, title, message, 60_000L)
            finish()
        }, AUTO_DISMISS_MS)
    }

    private fun vibrate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 400), -1)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 400), -1))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(longArrayOf(0, 400, 200, 400, 200, 400), -1)
                }
            }
        } catch (e: Exception) { /* 忽略权限异常 */ }
    }

    private fun playDefaultAlarm() {
        try {
            val uri = android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@ReminderActivity, uri)
                isLooping = false
                prepare()
                start()
            }
        } catch (e: Exception) { /* 无法播放时静音 */ }
    }

    private fun stopMediaAndVibrate() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) { }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.cancel()
            } else {
                @Suppress("DEPRECATION")
                (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel()
            }
        } catch (e: Exception) { }
    }

    override fun onDestroy() {
        stopMediaAndVibrate()
        autoHandler?.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val title   = intent.getStringExtra(AlarmHelper.EXTRA_TITLE)   ?: "⏰ 打卡提醒"
        val message = intent.getStringExtra(AlarmHelper.EXTRA_MESSAGE) ?: "记得打卡！"
        binding.tvReminderTitle.text   = title
        binding.tvReminderMessage.text = message
    }
}
