# 打卡提醒助手 📱

安卓手机打卡提醒小程序，基于 Python + Kivy 开发。

## 功能说明

| 功能 | 说明 |
|------|------|
| 上班打卡提醒 | 每天 **08:40** 弹出提醒 |
| 下班打卡提醒 | 每天 **18:00** 弹出提醒 |
| 收到按钮 | 当天不再提醒 |
| 延迟按钮 | **1小时后**再次提醒 |
| 自动重提醒 | **5分钟**未点击，自动再次弹出 |

---

## 安装方式

### 方式一：直接打包 APK（推荐）

#### 环境要求
- Linux / macOS 电脑（或 WSL2）
- Python 3.8+
- buildozer

#### 步骤

```bash
# 1. 安装 buildozer
pip install buildozer

# 2. 安装 Android 依赖（Ubuntu/Debian）
sudo apt-get install -y \
    git zip unzip openjdk-11-jdk \
    autoconf libtool pkg-config zlib1g-dev \
    libncurses5-dev libncursesw5-dev libtinfo5 \
    libffi-dev libssl-dev

# 3. 进入项目目录
cd clock-reminder

# 4. 打包 APK（首次约 30~60 分钟，需要下载 Android SDK/NDK）
buildozer android debug

# 5. APK 生成位置
# bin/打卡提醒-1.0.0-debug.apk
```

#### 安装到手机
1. 将 APK 文件传到手机（微信/数据线均可）
2. 手机开启「允许安装未知来源应用」
3. 点击 APK 安装

---

### 方式二：Termux 直接运行（无需打包）

如果不想打包 APK，可以在手机上安装 Termux 后直接运行：

```bash
# 在 Termux 中安装依赖
pkg install python
pip install kivy

# 运行
python main.py
```

> 注意：Termux 方式下，关闭 Termux 后提醒会停止。

---

## 文件结构

```
clock-reminder/
├── main.py           # 主程序
├── buildozer.spec    # Android 打包配置
└── README.md         # 说明文档
```

---

## 自定义提醒时间

编辑 `main.py` 中的以下变量：

```python
CHECKIN_HOUR = 8     # 上班提醒小时
CHECKIN_MIN = 40     # 上班提醒分钟
CHECKOUT_HOUR = 18   # 下班提醒小时
CHECKOUT_MIN = 0     # 下班提醒分钟
REMIND_AGAIN_SECS = 5 * 60   # 未点击后重提醒间隔（秒）
DELAY_SECS = 60 * 60          # 延迟按钮间隔（秒）
```
