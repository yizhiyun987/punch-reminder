[app]

title = 打卡提醒
package.name = punchreminder
package.domain = com.reminder.punch

source.dir = .
source.include_exts = py,png,jpg,jpeg,kv,atlas,mp3,wav,ogg,m4a,bmp,webp

version = 1.2.0

requirements = python3,kivy

orientation = portrait

# 存储读写（选音频/图片）、振动、保活
android.permissions = READ_EXTERNAL_STORAGE,WRITE_EXTERNAL_STORAGE,VIBRATE,WAKE_LOCK,RECEIVE_BOOT_COMPLETED,READ_MEDIA_AUDIO,READ_MEDIA_IMAGES,READ_MEDIA_VIDEO

android.minapi = 21
android.api = 33
android.ndk = 25b

android.wakelock = True
android.accept_sdk_license = True
android.archs = arm64-v8a, armeabi-v7a

[buildozer]
log_level = 2
warn_on_root = 1
