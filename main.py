"""
打卡提醒 App - 安卓版 v1.3
功能：
- 上班打卡 / 下班打卡（时间可调整）
- 收到 / 延迟 / 5分钟自动重提醒
- 自定义提醒时间和提示词
- 多时间点 / 时间段自定义提醒规则
- 自定义提醒音频（上班/下班分别设置）
- 自定义背景：静态壁纸 / 动态壁纸 / 纯色背景
"""

from kivy.app import App
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.floatlayout import FloatLayout
from kivy.uix.scrollview import ScrollView
from kivy.uix.label import Label
from kivy.uix.button import Button
from kivy.uix.popup import Popup
from kivy.uix.image import Image
from kivy.uix.textinput import TextInput
from kivy.uix.switch import Switch
from kivy.uix.widget import Widget
from kivy.clock import Clock
from kivy.core.window import Window
from kivy.utils import get_color_from_hex
from kivy.core.audio import SoundLoader
from kivy.graphics import Color, Rectangle
import os, json, shutil, uuid
from datetime import datetime, date

# ═══════════════════════════════════════════════════════
#  颜色
# ═══════════════════════════════════════════════════════
C_BG     = get_color_from_hex("#1A1A2E")
C_CARD   = get_color_from_hex("#16213E")
C_ACCENT = get_color_from_hex("#0F3460")
C_GREEN  = get_color_from_hex("#4CAF50")
C_ORANGE = get_color_from_hex("#FF9800")
C_RED    = get_color_from_hex("#F44336")
C_WHITE  = get_color_from_hex("#FFFFFF")
C_GRAY   = get_color_from_hex("#B0B0B0")
C_DARK   = get_color_from_hex("#444466")
C_PURPLE = get_color_from_hex("#7C4DFF")
C_TEAL   = get_color_from_hex("#009688")

# ═══════════════════════════════════════════════════════
#  常量
# ═══════════════════════════════════════════════════════
REMIND_AGAIN_SECS = 5 * 60
DELAY_SECS        = 60 * 60

DATA_DIR    = os.path.join(os.path.expanduser("~"), ".punchreminder")
AUDIO_DIR   = os.path.join(DATA_DIR, "audio")
WP_DIR      = os.path.join(DATA_DIR, "wallpaper")
CONFIG_FILE = os.path.join(DATA_DIR, "config.json")
for _d in (DATA_DIR, AUDIO_DIR, WP_DIR):
    os.makedirs(_d, exist_ok=True)

# ═══════════════════════════════════════════════════════
#  配置
# ═══════════════════════════════════════════════════════
def load_config():
    default = {
        "checkin_hour":  8,
        "checkin_min":   40,
        "checkout_hour": 18,
        "checkout_min":  0,
        "checkin_title": "上班打卡提醒 🏢",
        "checkin_msg":   "现在是 08:40，记得打上班卡！",
        "checkout_title": "下班打卡提醒 🏠",
        "checkout_msg":  "现在是 18:00，记得打下班卡！",
        "checkin_audio":  "",
        "checkout_audio": "",
        "wallpaper_type": "color",
        "wallpaper_path": "",
        "wallpaper_frames": [],
        "wallpaper_fps":  8,
        "bg_color": "#1A1A2E",
        "custom_reminders": [],
    }
    if os.path.exists(CONFIG_FILE):
        try:
            with open(CONFIG_FILE, "r", encoding="utf-8") as f:
                saved = json.load(f)
                default.update(saved)
        except Exception:
            pass
    return default

def save_config(cfg):
    with open(CONFIG_FILE, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)

config = load_config()

# ═══════════════════════════════════════════════════════
#  音频
# ═══════════════════════════════════════════════════════
_current_sound = None

def play_audio(path):
    global _current_sound
    stop_audio()
    if path and os.path.exists(path):
        _current_sound = SoundLoader.load(path)
        if _current_sound:
            _current_sound.play()

def stop_audio():
    global _current_sound
    if _current_sound:
        try:
            _current_sound.stop()
        except Exception:
            pass
        _current_sound = None

# ═══════════════════════════════════════════════════════
#  动态壁纸控件
# ═══════════════════════════════════════════════════════
class AnimatedWallpaper(Image):
    def __init__(self, frames, fps=8, **kw):
        super().__init__(**kw)
        self._frames = frames
        self._idx = 0
        self._event = None
        self.allow_stretch = True
        self.keep_ratio = False
        if frames:
            self.source = frames[0]
            self._event = Clock.schedule_interval(
                self._next_frame, 1.0 / max(fps, 1)
            )

    def _next_frame(self, dt):
        if not self._frames:
            return
        self._idx = (self._idx + 1) % len(self._frames)
        self.source = self._frames[self._idx]

    def stop(self):
        if self._event:
            self._event.cancel()
            self._event = None

# ═══════════════════════════════════════════════════════
#  背景层管理
# ═══════════════════════════════════════════════════════
_bg_widget   = None
_anim_wp     = None
_root_layout = None

def apply_wallpaper(root_fl):
    global _bg_widget, _anim_wp
    if _anim_wp:
        _anim_wp.stop()
        _anim_wp = None
    if _bg_widget and _bg_widget.parent:
        root_fl.remove_widget(_bg_widget)
        _bg_widget = None

    wtype = config.get("wallpaper_type", "color")
    if wtype == "static":
        path = config.get("wallpaper_path", "")
        if path and os.path.exists(path):
            img = Image(source=path, allow_stretch=True, keep_ratio=False,
                        size_hint=(1, 1), pos_hint={"x": 0, "y": 0})
            root_fl.add_widget(img, index=len(root_fl.children))
            _bg_widget = img
            return
    elif wtype == "animated":
        frames = config.get("wallpaper_frames", [])
        fps    = config.get("wallpaper_fps", 8)
        frames = [f for f in frames if os.path.exists(f)]
        if frames:
            aw = AnimatedWallpaper(frames, fps=fps,
                                   size_hint=(1, 1), pos_hint={"x": 0, "y": 0})
            root_fl.add_widget(aw, index=len(root_fl.children))
            _bg_widget = aw
            _anim_wp   = aw
            return
    color = config.get("bg_color", "#1A1A2E")
    Window.clearcolor = get_color_from_hex(color)

# ═══════════════════════════════════════════════════════
#  Toast
# ═══════════════════════════════════════════════════════
def show_toast(msg, duration=2):
    lbl = Label(text=msg, font_size='13sp', color=C_WHITE,
                halign='center', valign='middle')
    p = Popup(title='', content=lbl,
              size_hint=(0.75, None), height=72,
              background_color=get_color_from_hex("#333366"),
              auto_dismiss=True, separator_height=0)
    p.open()
    Clock.schedule_once(lambda dt: p.dismiss(), duration)

# ═══════════════════════════════════════════════════════
#  提醒状态
# ═══════════════════════════════════════════════════════
class ReminderState:
    def __init__(self):
        self.reset()

    def reset(self):
        self._date = date.today()
        self.checkin_done         = False
        self.checkout_done        = False
        self.checkin_popup_open   = False
        self.checkout_popup_open  = False
        self.custom_done          = {}   # rid -> bool
        self.custom_popup_open    = {}   # rid -> bool

    def ensure_fresh(self):
        if date.today() != self._date:
            self.reset()

state = ReminderState()
_active_popup = None

def dismiss_active_popup():
    global _active_popup
    if _active_popup:
        try:
            _active_popup.dismiss()
        except Exception:
            pass
        _active_popup = None

# ═══════════════════════════════════════════════════════
#  提醒弹窗
# ═══════════════════════════════════════════════════════
def show_reminder_popup(title, message, audio_path,
                        on_received, on_delay, remind_again_callback):
    global _active_popup
    dismiss_active_popup()
    play_audio(audio_path)

    content = BoxLayout(orientation='vertical', spacing=14, padding=[20, 16, 20, 16])
    lbl_t = Label(text=title, font_size='22sp', bold=True,
                  color=C_WHITE, size_hint_y=None, height=44)
    lbl_m = Label(text=message, font_size='15sp', color=C_GRAY,
                  size_hint_y=None, height=40, halign='center')
    lbl_m.bind(size=lbl_m.setter('text_size'))
    row = BoxLayout(orientation='horizontal', spacing=12,
                    size_hint_y=None, height=50)
    btn_ok  = Button(text='✅  收到', font_size='17sp', bold=True,
                     background_color=C_GREEN, background_normal='', color=C_WHITE)
    btn_dly = Button(text='⏰  延迟', font_size='17sp', bold=True,
                     background_color=C_ORANGE, background_normal='', color=C_WHITE)
    row.add_widget(btn_ok)
    row.add_widget(btn_dly)
    content.add_widget(lbl_t)
    content.add_widget(lbl_m)
    content.add_widget(row)

    popup = Popup(title='⏰ 打卡提醒', content=content,
                  size_hint=(0.88, None), height=220,
                  auto_dismiss=False,
                  background_color=C_CARD,
                  title_color=C_WHITE, title_size='17sp')
    _active_popup = popup
    ev = [Clock.schedule_once(
        lambda dt: (stop_audio(), popup.dismiss(), remind_again_callback()),
        REMIND_AGAIN_SECS
    )]

    def on_ok(i):
        ev[0].cancel(); stop_audio(); popup.dismiss(); on_received()

    def on_dl(i):
        ev[0].cancel(); stop_audio(); popup.dismiss(); on_delay()

    btn_ok.bind(on_press=on_ok)
    btn_dly.bind(on_press=on_dl)
    popup.open()

# ═══════════════════════════════════════════════════════
#  内置提醒触发
# ═══════════════════════════════════════════════════════
def trigger_checkin_reminder(*a):
    state.ensure_fresh()
    if state.checkin_done or state.checkin_popup_open:
        return
    state.checkin_popup_open = True

    def recv():
        state.checkin_done = True
        state.checkin_popup_open = False
        update_status_display()

    def dly():
        state.checkin_popup_open = False
        Clock.schedule_once(trigger_checkin_reminder, DELAY_SECS)
        update_status_display()

    def again():
        state.checkin_popup_open = False
        Clock.schedule_once(trigger_checkin_reminder, 1)

    show_reminder_popup(
        config.get("checkin_title", "上班打卡提醒 🏢"),
        config.get("checkin_msg",   "现在是 08:40，记得打上班卡！"),
        config.get("checkin_audio", ""),
        recv, dly, again
    )

def trigger_checkout_reminder(*a):
    state.ensure_fresh()
    if state.checkout_done or state.checkout_popup_open:
        return
    state.checkout_popup_open = True

    def recv():
        state.checkout_done = True
        state.checkout_popup_open = False
        update_status_display()

    def dly():
        state.checkout_popup_open = False
        Clock.schedule_once(trigger_checkout_reminder, DELAY_SECS)
        update_status_display()

    def again():
        state.checkout_popup_open = False
        Clock.schedule_once(trigger_checkout_reminder, 1)

    show_reminder_popup(
        config.get("checkout_title", "下班打卡提醒 🏠"),
        config.get("checkout_msg",   "现在是 18:00，记得打下班卡！"),
        config.get("checkout_audio", ""),
        recv, dly, again
    )

# ═══════════════════════════════════════════════════════
#  自定义提醒触发
# ═══════════════════════════════════════════════════════
def trigger_custom_reminder(rule, *a):
    state.ensure_fresh()
    rid = rule.get("id", "")
    if state.custom_done.get(rid) or state.custom_popup_open.get(rid):
        return
    state.custom_popup_open[rid] = True

    def recv():
        state.custom_done[rid] = True
        state.custom_popup_open[rid] = False
        update_status_display()

    def dly():
        state.custom_popup_open[rid] = False
        Clock.schedule_once(lambda dt: trigger_custom_reminder(rule), DELAY_SECS)
        update_status_display()

    def again():
        state.custom_popup_open[rid] = False
        Clock.schedule_once(lambda dt: trigger_custom_reminder(rule), 1)

    show_reminder_popup(
        rule.get("title", "自定义提醒"),
        rule.get("msg",   "该提醒了！"),
        rule.get("audio", ""),
        recv, dly, again
    )

# ═══════════════════════════════════════════════════════
#  定时检测（每30秒）
# ═══════════════════════════════════════════════════════
def schedule_daily_reminders(dt=None):
    state.ensure_fresh()
    now = datetime.now()
    h, m = now.hour, now.minute

    ci_h = config.get("checkin_hour", 8)
    ci_m = config.get("checkin_min",  40)
    co_h = config.get("checkout_hour", 18)
    co_m = config.get("checkout_min",  0)

    if h == ci_h and m == ci_m and not state.checkin_done:
        Clock.schedule_once(trigger_checkin_reminder, 0)
    if h == co_h and m == co_m and not state.checkout_done:
        Clock.schedule_once(trigger_checkout_reminder, 0)

    # 自定义规则
    for rule in config.get("custom_reminders", []):
        if not rule.get("enabled", True):
            continue
        rid = rule.get("id", "")
        rtype = rule.get("type", "point")
        if rtype == "point":
            rh = rule.get("hour", 0)
            rm = rule.get("min",  0)
            if h == rh and m == rm and not state.custom_done.get(rid):
                r = dict(rule)
                Clock.schedule_once(lambda dt, r=r: trigger_custom_reminder(r), 0)
        elif rtype == "range":
            sh = rule.get("start_hour", 0)
            sm = rule.get("start_min",  0)
            eh = rule.get("end_hour",   23)
            em = rule.get("end_min",    59)
            iv = rule.get("interval_min", 30)
            start_total = sh * 60 + sm
            end_total   = eh * 60 + em
            cur_total   = h  * 60 + m
            if start_total <= cur_total <= end_total:
                if iv > 0 and (cur_total - start_total) % iv == 0:
                    r = dict(rule)
                    Clock.schedule_once(lambda dt, r=r: trigger_custom_reminder(r), 0)

    update_status_display()

# ═══════════════════════════════════════════════════════
#  UI 全局引用
# ═══════════════════════════════════════════════════════
_lbl_ci          = None
_lbl_co          = None
_lbl_aci         = None
_lbl_aco         = None
_lbl_wp          = None
_builtin_section = None   # 内置提醒区容器（用于刷新时间显示）
_custom_section  = None   # 自定义提醒列表容器

# ═══════════════════════════════════════════════════════
#  状态刷新
# ═══════════════════════════════════════════════════════
def update_status_display(*a):
    state.ensure_fresh()
    ci_h = config.get("checkin_hour",  8)
    ci_m = config.get("checkin_min",  40)
    co_h = config.get("checkout_hour", 18)
    co_m = config.get("checkout_min",   0)
    if _lbl_ci:
        if state.checkin_done:
            _lbl_ci.text  = '上班打卡：✅ 已确认'
            _lbl_ci.color = C_GREEN
        else:
            _lbl_ci.text  = f'上班打卡：⏳ 等待提醒 ({ci_h:02d}:{ci_m:02d})'
            _lbl_ci.color = C_GRAY
    if _lbl_co:
        if state.checkout_done:
            _lbl_co.text  = '下班打卡：✅ 已确认'
            _lbl_co.color = C_GREEN
        else:
            _lbl_co.text  = f'下班打卡：⏳ 等待提醒 ({co_h:02d}:{co_m:02d})'
            _lbl_co.color = C_GRAY
    _refresh_audio_labels()
    _refresh_wp_label()

def _refresh_audio_labels():
    for lbl, key in ((_lbl_aci, "checkin_audio"), (_lbl_aco, "checkout_audio")):
        if lbl:
            p = config.get(key, "")
            lbl.text  = f'🎵 {os.path.basename(p)}' if p else '🔕 未设置（静音）'
            lbl.color = C_GREEN if p else C_DARK

def _refresh_wp_label():
    if _lbl_wp:
        t = config.get("wallpaper_type", "color")
        if t == "static":
            p = config.get("wallpaper_path", "")
            _lbl_wp.text  = f'🖼 {os.path.basename(p)}' if p else '🖼 静态壁纸（未选图）'
            _lbl_wp.color = C_GREEN if p else C_DARK
        elif t == "animated":
            n = len(config.get("wallpaper_frames", []))
            _lbl_wp.text  = f'✨ 动态壁纸（{n} 帧）' if n else '✨ 动态壁纸（未导入帧）'
            _lbl_wp.color = C_PURPLE if n else C_DARK
        else:
            _lbl_wp.text  = f'🎨 纯色背景 {config.get("bg_color","#1A1A2E")}'
            _lbl_wp.color = C_GRAY

def _refresh_builtin_labels():
    """刷新内置提醒时间显示标签"""
    if _builtin_section:
        ci_h = config.get("checkin_hour",  8)
        ci_m = config.get("checkin_min",  40)
        co_h = config.get("checkout_hour", 18)
        co_m = config.get("checkout_min",   0)
        # 标签存在 _builtin_section.ci_lbl / co_lbl
        if hasattr(_builtin_section, 'ci_lbl'):
            _builtin_section.ci_lbl.text = f'上班时间：{ci_h:02d}:{ci_m:02d}'
        if hasattr(_builtin_section, 'co_lbl'):
            _builtin_section.co_lbl.text = f'下班时间：{co_h:02d}:{co_m:02d}'

# ═══════════════════════════════════════════════════════
#  文件选择器
# ═══════════════════════════════════════════════════════
def open_file_chooser(title, filters, callback, multi=False):
    from kivy.uix.filechooser import FileChooserListView
    layout = BoxLayout(orientation='vertical', spacing=8, padding=10)
    fc = FileChooserListView(
        path=os.path.expanduser("~"),
        filters=filters,
        multiselect=multi,
        size_hint_y=1
    )
    row = BoxLayout(orientation='horizontal', spacing=8, size_hint_y=None, height=44)
    btn_ok = Button(text='确认', background_color=C_GREEN,
                    background_normal='', color=C_WHITE, font_size='15sp')
    btn_no = Button(text='取消', background_color=C_DARK,
                    background_normal='', color=C_WHITE, font_size='15sp')
    row.add_widget(btn_ok)
    row.add_widget(btn_no)
    layout.add_widget(fc)
    layout.add_widget(row)
    popup = Popup(title=title, content=layout,
                  size_hint=(0.95, 0.88),
                  background_color=C_CARD, title_color=C_WHITE)

    def ok(i):
        if fc.selection:
            popup.dismiss()
            callback(fc.selection if multi else fc.selection[0])

    btn_ok.bind(on_press=ok)
    btn_no.bind(on_press=lambda i: popup.dismiss())
    popup.open()

# ═══════════════════════════════════════════════════════
#  修改时间弹窗
# ═══════════════════════════════════════════════════════
def open_time_editor(label_text, hour_key, min_key, on_save):
    """通用时间选择弹窗，确认后回调 on_save(h, m)"""
    h_val = [config.get(hour_key, 0)]
    m_val = [config.get(min_key,  0)]

    content = BoxLayout(orientation='vertical', spacing=14, padding=[20, 16, 20, 16])
    content.add_widget(Label(text=label_text, font_size='16sp', bold=True,
                             color=C_WHITE, size_hint_y=None, height=36))

    # 小时行
    content.add_widget(Label(text='小时（0 – 23）', font_size='13sp',
                             color=C_GRAY, size_hint_y=None, height=24))
    h_row = BoxLayout(orientation='horizontal', spacing=10,
                      size_hint_y=None, height=48)
    lbl_h = Label(text=f'{h_val[0]:02d}', font_size='24sp', bold=True,
                  color=C_WHITE)
    btn_hm = Button(text='−', font_size='22sp',
                    background_color=C_DARK, background_normal='', color=C_WHITE,
                    size_hint_x=0.25)
    btn_hp = Button(text='+', font_size='22sp',
                    background_color=C_DARK, background_normal='', color=C_WHITE,
                    size_hint_x=0.25)

    def h_minus(i):
        h_val[0] = (h_val[0] - 1) % 24
        lbl_h.text = f'{h_val[0]:02d}'

    def h_plus(i):
        h_val[0] = (h_val[0] + 1) % 24
        lbl_h.text = f'{h_val[0]:02d}'

    btn_hm.bind(on_press=h_minus)
    btn_hp.bind(on_press=h_plus)
    h_row.add_widget(btn_hm)
    h_row.add_widget(lbl_h)
    h_row.add_widget(btn_hp)
    content.add_widget(h_row)

    # 分钟行
    content.add_widget(Label(text='分钟（0 – 59，步进5）', font_size='13sp',
                             color=C_GRAY, size_hint_y=None, height=24))
    m_row = BoxLayout(orientation='horizontal', spacing=10,
                      size_hint_y=None, height=48)
    lbl_m = Label(text=f'{m_val[0]:02d}', font_size='24sp', bold=True,
                  color=C_WHITE)
    btn_mm = Button(text='−', font_size='22sp',
                    background_color=C_DARK, background_normal='', color=C_WHITE,
                    size_hint_x=0.25)
    btn_mp = Button(text='+', font_size='22sp',
                    background_color=C_DARK, background_normal='', color=C_WHITE,
                    size_hint_x=0.25)

    def m_minus(i):
        m_val[0] = (m_val[0] - 5) % 60
        lbl_m.text = f'{m_val[0]:02d}'

    def m_plus(i):
        m_val[0] = (m_val[0] + 5) % 60
        lbl_m.text = f'{m_val[0]:02d}'

    btn_mm.bind(on_press=m_minus)
    btn_mp.bind(on_press=m_plus)
    m_row.add_widget(btn_mm)
    m_row.add_widget(lbl_m)
    m_row.add_widget(btn_mp)
    content.add_widget(m_row)

    # 确认/取消
    btn_row = BoxLayout(orientation='horizontal', spacing=10,
                        size_hint_y=None, height=46)
    btn_ok = Button(text='✅ 确认', font_size='15sp', bold=True,
                    background_color=C_GREEN, background_normal='', color=C_WHITE)
    btn_cancel = Button(text='取消', font_size='15sp',
                        background_color=C_DARK, background_normal='', color=C_WHITE)
    btn_row.add_widget(btn_ok)
    btn_row.add_widget(btn_cancel)
    content.add_widget(btn_row)

    popup = Popup(title='修改提醒时间', content=content,
                  size_hint=(0.85, None), height=360,
                  background_color=C_CARD, title_color=C_WHITE)

    def do_save(i):
        config[hour_key] = h_val[0]
        config[min_key]  = m_val[0]
        save_config(config)
        popup.dismiss()
        on_save(h_val[0], m_val[0])

    btn_ok.bind(on_press=do_save)
    btn_cancel.bind(on_press=lambda i: popup.dismiss())
    popup.open()

# ═══════════════════════════════════════════════════════
#  修改提示词弹窗
# ═══════════════════════════════════════════════════════
def open_message_editor(dialog_title, title_key, msg_key, on_save):
    """通用提示词编辑弹窗，带实时预览"""
    content = BoxLayout(orientation='vertical', spacing=10, padding=[16, 14, 16, 14])

    content.add_widget(Label(text='弹窗标题：', font_size='13sp', color=C_GRAY,
                             size_hint_y=None, height=24, halign='left'))
    ti_title = TextInput(text=config.get(title_key, ''),
                         font_size='15sp', multiline=False,
                         background_color=get_color_from_hex("#1E2A45"),
                         foreground_color=C_WHITE,
                         cursor_color=C_WHITE,
                         size_hint_y=None, height=40)
    content.add_widget(ti_title)

    content.add_widget(Label(text='提醒正文：', font_size='13sp', color=C_GRAY,
                             size_hint_y=None, height=24, halign='left'))
    ti_msg = TextInput(text=config.get(msg_key, ''),
                       font_size='14sp', multiline=True,
                       background_color=get_color_from_hex("#1E2A45"),
                       foreground_color=C_WHITE,
                       cursor_color=C_WHITE,
                       size_hint_y=None, height=70)
    content.add_widget(ti_msg)

    # 预览区
    content.add_widget(Label(text='— 实时预览 —', font_size='12sp', color=C_DARK,
                             size_hint_y=None, height=22))
    preview_box = BoxLayout(orientation='vertical', spacing=4, padding=[12, 8, 12, 8],
                            size_hint_y=None, height=80)
    with preview_box.canvas.before:
        Color(*C_ACCENT)
        preview_rect = Rectangle(pos=preview_box.pos, size=preview_box.size)

    def update_preview_rect(inst, val):
        preview_rect.pos  = inst.pos
        preview_rect.size = inst.size

    preview_box.bind(pos=update_preview_rect, size=update_preview_rect)

    lbl_prev_title = Label(text=ti_title.text, font_size='15sp', bold=True,
                           color=C_WHITE, size_hint_y=None, height=30,
                           halign='left')
    lbl_prev_title.bind(size=lbl_prev_title.setter('text_size'))
    lbl_prev_msg   = Label(text=ti_msg.text, font_size='13sp', color=C_GRAY,
                           size_hint_y=None, height=36, halign='left')
    lbl_prev_msg.bind(size=lbl_prev_msg.setter('text_size'))
    preview_box.add_widget(lbl_prev_title)
    preview_box.add_widget(lbl_prev_msg)
    content.add_widget(preview_box)

    def on_title_change(inst, val):
        lbl_prev_title.text = val

    def on_msg_change(inst, val):
        lbl_prev_msg.text = val

    ti_title.bind(text=on_title_change)
    ti_msg.bind(text=on_msg_change)

    btn_row = BoxLayout(orientation='horizontal', spacing=10,
                        size_hint_y=None, height=46)
    btn_ok = Button(text='✅ 确认', font_size='15sp', bold=True,
                    background_color=C_GREEN, background_normal='', color=C_WHITE)
    btn_cancel = Button(text='取消', font_size='15sp',
                        background_color=C_DARK, background_normal='', color=C_WHITE)
    btn_row.add_widget(btn_ok)
    btn_row.add_widget(btn_cancel)
    content.add_widget(btn_row)

    popup = Popup(title=dialog_title, content=content,
                  size_hint=(0.9, None), height=440,
                  background_color=C_CARD, title_color=C_WHITE)

    def do_save(i):
        config[title_key] = ti_title.text
        config[msg_key]   = ti_msg.text
        save_config(config)
        popup.dismiss()
        on_save()

    btn_ok.bind(on_press=do_save)
    btn_cancel.bind(on_press=lambda i: popup.dismiss())
    popup.open()

# ═══════════════════════════════════════════════════════
#  自定义规则：辅助选择器组件
# ═══════════════════════════════════════════════════════
def make_time_spinner(label_text, init_h, init_m, step_m=1):
    """返回 (container, h_val_list, m_val_list)"""
    h_val = [init_h]
    m_val = [init_m]
    box = BoxLayout(orientation='horizontal', spacing=6,
                    size_hint_y=None, height=44)
    box.add_widget(Label(text=label_text, font_size='13sp', color=C_GRAY,
                         size_hint_x=0.28))
    btn_hm = Button(text='−', font_size='18sp',
                    background_color=C_DARK, background_normal='',
                    color=C_WHITE, size_hint_x=0.12)
    lbl_h = Label(text=f'{h_val[0]:02d}', font_size='18sp', bold=True,
                  color=C_WHITE, size_hint_x=0.13)
    btn_hp = Button(text='+', font_size='18sp',
                    background_color=C_DARK, background_normal='',
                    color=C_WHITE, size_hint_x=0.12)
    sep = Label(text=':', font_size='18sp', color=C_GRAY, size_hint_x=0.05)
    btn_mm = Button(text='−', font_size='18sp',
                    background_color=C_DARK, background_normal='',
                    color=C_WHITE, size_hint_x=0.12)
    lbl_m = Label(text=f'{m_val[0]:02d}', font_size='18sp', bold=True,
                  color=C_WHITE, size_hint_x=0.13)
    btn_mp = Button(text='+', font_size='18sp',
                    background_color=C_DARK, background_normal='',
                    color=C_WHITE, size_hint_x=0.12)

    def hm(i): h_val[0] = (h_val[0] - 1) % 24; lbl_h.text = f'{h_val[0]:02d}'
    def hp(i): h_val[0] = (h_val[0] + 1) % 24; lbl_h.text = f'{h_val[0]:02d}'
    def mm(i): m_val[0] = (m_val[0] - step_m) % 60; lbl_m.text = f'{m_val[0]:02d}'
    def mp(i): m_val[0] = (m_val[0] + step_m) % 60; lbl_m.text = f'{m_val[0]:02d}'

    btn_hm.bind(on_press=hm); btn_hp.bind(on_press=hp)
    btn_mm.bind(on_press=mm); btn_mp.bind(on_press=mp)
    for w in (btn_hm, lbl_h, btn_hp, sep, btn_mm, lbl_m, btn_mp):
        box.add_widget(w)
    return box, h_val, m_val


def make_num_spinner(label_text, init_val, min_val, max_val, step=1):
    """返回 (container, val_list)"""
    val = [init_val]
    box = BoxLayout(orientation='horizontal', spacing=8,
                    size_hint_y=None, height=44)
    box.add_widget(Label(text=label_text, font_size='13sp', color=C_GRAY,
                         size_hint_x=0.45))
    btn_m = Button(text='−', font_size='18sp',
                   background_color=C_DARK, background_normal='',
                   color=C_WHITE, size_hint_x=0.15)
    lbl_v = Label(text=str(val[0]), font_size='18sp', bold=True,
                  color=C_WHITE, size_hint_x=0.2)
    btn_p = Button(text='+', font_size='18sp',
                   background_color=C_DARK, background_normal='',
                   color=C_WHITE, size_hint_x=0.15)

    def vm(i):
        val[0] = max(min_val, val[0] - step)
        lbl_v.text = str(val[0])

    def vp(i):
        val[0] = min(max_val, val[0] + step)
        lbl_v.text = str(val[0])

    btn_m.bind(on_press=vm)
    btn_p.bind(on_press=vp)
    box.add_widget(btn_m)
    box.add_widget(lbl_v)
    box.add_widget(btn_p)
    return box, val

# ═══════════════════════════════════════════════════════
#  添加/编辑 自定义提醒弹窗
# ═══════════════════════════════════════════════════════
def open_custom_reminder_editor(existing_rule=None, on_done=None):
    """
    existing_rule: 已有规则 dict（编辑模式），None 为新增
    on_done: 回调，传入新/更新后的 rule dict
    """
    is_edit = existing_rule is not None
    rule = existing_rule or {
        "id": str(uuid.uuid4())[:8],
        "name": "",
        "enabled": True,
        "type": "point",
        "hour": 12, "min": 0,
        "start_hour": 9,  "start_min": 0,
        "end_hour": 18,   "end_min": 0,
        "interval_min": 30,
        "title": "自定义提醒",
        "msg": "提醒时间到了！",
        "audio": "",
    }

    # ---- 主容器 ----
    scroll = ScrollView(size_hint=(1, 1))
    inner = BoxLayout(orientation='vertical', spacing=10, padding=[16, 12, 16, 12],
                      size_hint_y=None)
    inner.bind(minimum_height=inner.setter('height'))

    def sec_label(txt):
        lbl = Label(text=txt, font_size='13sp', color=C_GRAY,
                    size_hint_y=None, height=24, halign='left')
        lbl.bind(size=lbl.setter('text_size'))
        return lbl

    # 名称
    inner.add_widget(sec_label('提醒名称：'))
    ti_name = TextInput(text=rule.get("name", ""),
                        font_size='15sp', multiline=False,
                        background_color=get_color_from_hex("#1E2A45"),
                        foreground_color=C_WHITE, cursor_color=C_WHITE,
                        size_hint_y=None, height=40)
    inner.add_widget(ti_name)

    # 类型切换
    inner.add_widget(sec_label('提醒类型：'))
    type_row = BoxLayout(orientation='horizontal', spacing=8,
                         size_hint_y=None, height=44)
    cur_type = [rule.get("type", "point")]
    btn_point = Button(text='📍 时间点', font_size='14sp',
                       background_color=C_GREEN if cur_type[0] == "point" else C_DARK,
                       background_normal='', color=C_WHITE)
    btn_range = Button(text='🔁 时间段', font_size='14sp',
                       background_color=C_TEAL if cur_type[0] == "range" else C_DARK,
                       background_normal='', color=C_WHITE)
    type_row.add_widget(btn_point)
    type_row.add_widget(btn_range)
    inner.add_widget(type_row)

    # 时间点配置
    point_box = BoxLayout(orientation='vertical', spacing=6, size_hint_y=None, height=0)
    point_time_row, ph_val, pm_val = make_time_spinner(
        '时间：', rule.get("hour", 12), rule.get("min", 0), step_m=5
    )
    point_box.add_widget(point_time_row)

    # 时间段配置
    range_box = BoxLayout(orientation='vertical', spacing=6, size_hint_y=None, height=0)
    range_start_row, rsh_val, rsm_val = make_time_spinner(
        '开始：', rule.get("start_hour", 9), rule.get("start_min", 0), step_m=5
    )
    range_end_row, reh_val, rem_val = make_time_spinner(
        '结束：', rule.get("end_hour", 18), rule.get("end_min", 0), step_m=5
    )
    interval_row, iv_val = make_num_spinner(
        '间隔（分钟）：', rule.get("interval_min", 30), 1, 120, step=5
    )
    range_box.add_widget(range_start_row)
    range_box.add_widget(range_end_row)
    range_box.add_widget(interval_row)

    inner.add_widget(point_box)
    inner.add_widget(range_box)

    def show_point():
        cur_type[0] = "point"
        point_box.height = 44
        point_box.opacity = 1
        range_box.height = 0
        range_box.opacity = 0
        btn_point.background_color = C_GREEN
        btn_range.background_color = C_DARK

    def show_range():
        cur_type[0] = "range"
        point_box.height = 0
        point_box.opacity = 0
        range_box.height = 44 * 3
        range_box.opacity = 1
        btn_point.background_color = C_DARK
        btn_range.background_color = C_TEAL

    btn_point.bind(on_press=lambda i: show_point())
    btn_range.bind(on_press=lambda i: show_range())

    # 初始化显示
    if cur_type[0] == "range":
        show_range()
    else:
        show_point()

    # 提示词
    inner.add_widget(sec_label('弹窗标题：'))
    ti_rtitle = TextInput(text=rule.get("title", "自定义提醒"),
                          font_size='15sp', multiline=False,
                          background_color=get_color_from_hex("#1E2A45"),
                          foreground_color=C_WHITE, cursor_color=C_WHITE,
                          size_hint_y=None, height=40)
    inner.add_widget(ti_rtitle)

    inner.add_widget(sec_label('提醒正文：'))
    ti_rmsg = TextInput(text=rule.get("msg", "提醒时间到了！"),
                        font_size='14sp', multiline=True,
                        background_color=get_color_from_hex("#1E2A45"),
                        foreground_color=C_WHITE, cursor_color=C_WHITE,
                        size_hint_y=None, height=70)
    inner.add_widget(ti_rmsg)

    # 确认/取消
    btn_row = BoxLayout(orientation='horizontal', spacing=10,
                        size_hint_y=None, height=50)
    btn_ok = Button(text='✅ 保存', font_size='15sp', bold=True,
                    background_color=C_GREEN, background_normal='', color=C_WHITE)
    btn_cancel = Button(text='取消', font_size='15sp',
                        background_color=C_DARK, background_normal='', color=C_WHITE)
    btn_row.add_widget(btn_ok)
    btn_row.add_widget(btn_cancel)
    inner.add_widget(btn_row)

    scroll.add_widget(inner)
    popup = Popup(
        title='编辑提醒规则' if is_edit else '添加自定义提醒',
        content=scroll,
        size_hint=(0.92, 0.88),
        background_color=C_CARD, title_color=C_WHITE
    )

    def do_save(i):
        new_rule = dict(rule)
        new_rule["name"]    = ti_name.text.strip() or "未命名"
        new_rule["type"]    = cur_type[0]
        new_rule["title"]   = ti_rtitle.text
        new_rule["msg"]     = ti_rmsg.text
        new_rule["audio"]   = rule.get("audio", "")
        if cur_type[0] == "point":
            new_rule["hour"] = ph_val[0]
            new_rule["min"]  = pm_val[0]
        else:
            new_rule["start_hour"]    = rsh_val[0]
            new_rule["start_min"]     = rsm_val[0]
            new_rule["end_hour"]      = reh_val[0]
            new_rule["end_min"]       = rem_val[0]
            new_rule["interval_min"]  = iv_val[0]
        popup.dismiss()
        if on_done:
            on_done(new_rule)

    btn_ok.bind(on_press=do_save)
    btn_cancel.bind(on_press=lambda i: popup.dismiss())
    popup.open()

# ═══════════════════════════════════════════════════════
#  自定义提醒列表区（动态刷新）
# ═══════════════════════════════════════════════════════
def rebuild_custom_section():
    """清空并重建 _custom_section 内的所有控件"""
    global _custom_section
    if _custom_section is None:
        return
    _custom_section.clear_widgets()

    reminders = config.get("custom_reminders", [])
    if not reminders:
        lbl_empty = Label(text='暂无自定义提醒', font_size='13sp', color=C_DARK,
                          size_hint_y=None, height=30)
        _custom_section.add_widget(lbl_empty)
    else:
        for rule in reminders:
            _add_rule_row(rule)

    # + 添加提醒 按钮
    btn_add = Button(text='＋ 添加提醒', font_size='14sp', bold=True,
                     background_color=C_TEAL, background_normal='',
                     color=C_WHITE, size_hint_y=None, height=44)
    btn_add.bind(on_press=lambda i: _on_add_custom())
    _custom_section.add_widget(btn_add)


def _time_desc(rule):
    rtype = rule.get("type", "point")
    if rtype == "point":
        h = rule.get("hour", 0)
        m = rule.get("min",  0)
        return f'{h:02d}:{m:02d}'
    else:
        sh = rule.get("start_hour", 0); sm = rule.get("start_min", 0)
        eh = rule.get("end_hour", 0);   em = rule.get("end_min",  0)
        iv = rule.get("interval_min", 30)
        return f'{sh:02d}:{sm:02d}~{eh:02d}:{em:02d} 每{iv}分钟'


def _add_rule_row(rule):
    """在 _custom_section 中追加一条规则行"""
    rid = rule.get("id", "")
    row = BoxLayout(orientation='horizontal', spacing=6,
                    size_hint_y=None, height=54)

    # 左侧：名称+时间
    info_box = BoxLayout(orientation='vertical', spacing=2)
    lbl_name = Label(text=rule.get("name", "未命名"),
                     font_size='14sp', bold=True, color=C_WHITE,
                     halign='left', valign='middle')
    lbl_name.bind(size=lbl_name.setter('text_size'))
    lbl_time = Label(text=_time_desc(rule),
                     font_size='12sp', color=C_GRAY,
                     halign='left', valign='middle')
    lbl_time.bind(size=lbl_time.setter('text_size'))
    info_box.add_widget(lbl_name)
    info_box.add_widget(lbl_time)

    # 启用开关
    sw = Switch(active=rule.get("enabled", True),
                size_hint_x=None, width=60)

    def on_sw(inst, val, _rid=rid):
        for r in config.get("custom_reminders", []):
            if r.get("id") == _rid:
                r["enabled"] = val
                break
        save_config(config)

    sw.bind(active=on_sw)

    # 删除按钮
    btn_del = Button(text='✕', font_size='14sp', bold=True,
                     background_color=C_RED, background_normal='',
                     color=C_WHITE, size_hint_x=None, width=40)

    def on_del(i, _rid=rid):
        config["custom_reminders"] = [
            r for r in config.get("custom_reminders", [])
            if r.get("id") != _rid
        ]
        save_config(config)
        rebuild_custom_section()
        show_toast('已删除')

    btn_del.bind(on_press=on_del)

    row.add_widget(info_box)
    row.add_widget(sw)
    row.add_widget(btn_del)
    _custom_section.add_widget(row)


def _on_add_custom():
    def on_done(new_rule):
        reminders = config.get("custom_reminders", [])
        reminders.append(new_rule)
        config["custom_reminders"] = reminders
        save_config(config)
        rebuild_custom_section()
        show_toast(f'已添加：{new_rule["name"]}')

    open_custom_reminder_editor(on_done=on_done)

# ═══════════════════════════════════════════════════════
#  壁纸设置弹窗
# ═══════════════════════════════════════════════════════
COLOR_PRESETS = [
    ("#1A1A2E", "深蓝"),
    ("#0D2137", "午夜"),
    ("#1B2838", "极夜"),
    ("#2D1B33", "紫夜"),
    ("#1A2A1A", "森林"),
    ("#2A1A1A", "暗红"),
    ("#000000", "纯黑"),
    ("#FFFFFF", "纯白"),
]

def open_wallpaper_settings():
    content = BoxLayout(orientation='vertical', spacing=10, padding=14)
    content.add_widget(Label(text='背景类型', font_size='15sp',
                             color=C_GRAY, size_hint_y=None, height=28))
    type_row = BoxLayout(orientation='horizontal', spacing=8,
                         size_hint_y=None, height=44)
    btn_color  = Button(text='🎨 纯色', font_size='14sp',
                        background_color=C_ACCENT, background_normal='', color=C_WHITE)
    btn_static = Button(text='🖼 静态', font_size='14sp',
                        background_color=C_ACCENT, background_normal='', color=C_WHITE)
    btn_anim   = Button(text='✨ 动态', font_size='14sp',
                        background_color=C_PURPLE, background_normal='', color=C_WHITE)
    type_row.add_widget(btn_color)
    type_row.add_widget(btn_static)
    type_row.add_widget(btn_anim)

    lbl_hint = Label(text='选择背景类型后操作', font_size='13sp',
                     color=C_DARK, size_hint_y=None, height=30, halign='left')
    lbl_hint.bind(size=lbl_hint.setter('text_size'))

    color_section = BoxLayout(orientation='vertical', spacing=6,
                               size_hint_y=None, height=0, opacity=0)
    color_grid = BoxLayout(orientation='horizontal', spacing=4,
                           size_hint_y=None, height=0)
    for hex_c, name in COLOR_PRESETS:
        b = Button(text=name, font_size='11sp',
                   background_color=get_color_from_hex(hex_c),
                   background_normal='', color=C_WHITE)

        def make_cb(h):
            def cb(i):
                config["wallpaper_type"] = "color"
                config["bg_color"] = h
                save_config(config)
                Window.clearcolor = get_color_from_hex(h)
                if _root_layout:
                    apply_wallpaper(_root_layout)
                _refresh_wp_label()
                show_toast(f'已设置纯色 {h}')
            return cb

        b.bind(on_press=make_cb(hex_c))
        color_grid.add_widget(b)
    color_section.add_widget(color_grid)

    content.add_widget(type_row)
    content.add_widget(lbl_hint)
    content.add_widget(color_section)

    popup = Popup(title='🖼 背景设置', content=content,
                  size_hint=(0.95, 0.68),
                  background_color=C_CARD, title_color=C_WHITE)

    def show_color(i):
        lbl_hint.text = '选择预设颜色'
        color_section.height = 80
        color_section.opacity = 1
        color_grid.height = 44

    def show_static(i):
        popup.dismiss()

        def cb(path):
            dst = os.path.join(WP_DIR, os.path.basename(path))
            shutil.copy2(path, dst)
            config["wallpaper_type"] = "static"
            config["wallpaper_path"] = dst
            save_config(config)
            if _root_layout:
                apply_wallpaper(_root_layout)
            _refresh_wp_label()
            show_toast('静态壁纸已设置')

        open_file_chooser('选择壁纸图片',
                          ["*.jpg", "*.jpeg", "*.png", "*.bmp", "*.webp"], cb)

    def show_anim(i):
        popup.dismiss()
        _open_animated_wallpaper_settings()

    btn_color.bind(on_press=show_color)
    btn_static.bind(on_press=show_static)
    btn_anim.bind(on_press=show_anim)
    popup.open()


def _open_animated_wallpaper_settings():
    content = BoxLayout(orientation='vertical', spacing=10, padding=14)
    existing = config.get("wallpaper_frames", [])
    existing = [f for f in existing if os.path.exists(f)]

    lbl_info = Label(
        text=f'当前帧数：{len(existing)} 帧\n导入多张图片作为动态帧（按文件名排序）',
        font_size='13sp', color=C_GRAY,
        size_hint_y=None, height=52, halign='left', valign='top'
    )
    lbl_info.bind(size=lbl_info.setter('text_size'))

    fps_row = BoxLayout(orientation='horizontal', spacing=8, size_hint_y=None, height=40)
    fps_row.add_widget(Label(text='帧率（FPS）：', font_size='14sp',
                             color=C_GRAY, size_hint_x=0.45))
    fps_val = [config.get("wallpaper_fps", 8)]
    lbl_fps = Label(text=str(fps_val[0]), font_size='16sp', color=C_WHITE, size_hint_x=0.2)
    btn_fps_m = Button(text='−', font_size='18sp', background_color=C_DARK,
                       background_normal='', color=C_WHITE, size_hint_x=0.15)
    btn_fps_p = Button(text='+', font_size='18sp', background_color=C_DARK,
                       background_normal='', color=C_WHITE, size_hint_x=0.15)

    def fps_minus(i): fps_val[0] = max(1, fps_val[0] - 1); lbl_fps.text = str(fps_val[0])
    def fps_plus(i):  fps_val[0] = min(30, fps_val[0] + 1); lbl_fps.text = str(fps_val[0])

    btn_fps_m.bind(on_press=fps_minus)
    btn_fps_p.bind(on_press=fps_plus)
    fps_row.add_widget(btn_fps_m)
    fps_row.add_widget(lbl_fps)
    fps_row.add_widget(btn_fps_p)

    btn_row = BoxLayout(orientation='horizontal', spacing=8, size_hint_y=None, height=44)
    btn_import = Button(text='📂 导入图片序列', font_size='14sp',
                        background_color=C_PURPLE, background_normal='', color=C_WHITE)
    btn_clear  = Button(text='✕ 清除动态壁纸', font_size='14sp',
                        background_color=C_RED, background_normal='', color=C_WHITE)
    btn_row.add_widget(btn_import)
    btn_row.add_widget(btn_clear)

    btn_apply = Button(text='✅ 应用', font_size='15sp', bold=True,
                       background_color=C_GREEN, background_normal='',
                       color=C_WHITE, size_hint_y=None, height=46)

    content.add_widget(lbl_info)
    content.add_widget(fps_row)
    content.add_widget(btn_row)
    content.add_widget(btn_apply)

    popup = Popup(title='✨ 动态壁纸设置', content=content,
                  size_hint=(0.92, None), height=300,
                  background_color=C_CARD, title_color=C_WHITE)

    frames_buf = [list(existing)]

    def on_import(i):
        popup.dismiss()

        def cb(paths):
            copied = []
            for p in sorted(paths):
                dst = os.path.join(WP_DIR, os.path.basename(p))
                shutil.copy2(p, dst)
                copied.append(dst)
            frames_buf[0] = sorted(copied)
            lbl_info.text = f'已导入 {len(copied)} 帧，点击「应用」生效'
            popup.open()

        open_file_chooser('选择图片序列（多选）',
                          ["*.jpg", "*.jpeg", "*.png", "*.bmp"],
                          cb, multi=True)

    def on_clear(i):
        frames_buf[0] = []
        config["wallpaper_type"]   = "color"
        config["wallpaper_frames"] = []
        save_config(config)
        if _root_layout:
            apply_wallpaper(_root_layout)
        _refresh_wp_label()
        popup.dismiss()
        show_toast('动态壁纸已清除')

    def on_apply(i):
        config["wallpaper_type"]   = "animated"
        config["wallpaper_frames"] = frames_buf[0]
        config["wallpaper_fps"]    = fps_val[0]
        save_config(config)
        if _root_layout:
            apply_wallpaper(_root_layout)
        _refresh_wp_label()
        popup.dismiss()
        show_toast(f'动态壁纸已应用（{len(frames_buf[0])} 帧，{fps_val[0]} FPS）')

    btn_import.bind(on_press=on_import)
    btn_clear.bind(on_press=on_clear)
    btn_apply.bind(on_press=on_apply)
    popup.open()

# ═══════════════════════════════════════════════════════
#  音频辅助
# ═══════════════════════════════════════════════════════
def _set_audio(key, path):
    dst = os.path.join(AUDIO_DIR, os.path.basename(path))
    shutil.copy2(path, dst)
    config[key] = dst
    save_config(config)
    _refresh_audio_labels()
    show_toast(f'已设置：{os.path.basename(dst)}')

def _clear_audio(key):
    config[key] = ""
    save_config(config)
    _refresh_audio_labels()
    show_toast('音频已清除')

# ═══════════════════════════════════════════════════════
#  主 App
# ═══════════════════════════════════════════════════════
class PunchReminderApp(App):
    def build(self):
        global _lbl_ci, _lbl_co, _lbl_aci, _lbl_aco, _lbl_wp
        global _root_layout, _builtin_section, _custom_section

        Window.clearcolor = get_color_from_hex(config.get("bg_color", "#1A1A2E"))

        root_fl = FloatLayout()
        _root_layout = root_fl

        scroll = ScrollView(size_hint=(1, 1), pos_hint={"x": 0, "y": 0})
        inner = BoxLayout(orientation='vertical', padding=20, spacing=12,
                          size_hint_y=None)
        inner.bind(minimum_height=inner.setter('height'))

        def section_lbl(txt):
            lbl = Label(text=txt, font_size='13sp', color=C_GRAY,
                        size_hint_y=None, height=26, halign='left')
            lbl.bind(size=lbl.setter('text_size'))
            return lbl

        def divider():
            return Label(text='─' * 50, color=C_DARK,
                         size_hint_y=None, height=16, font_size='10sp')

        # ── 标题 ──────────────────────────────────────
        inner.add_widget(Label(
            text='🕐 打卡提醒助手 v1.3', font_size='24sp', bold=True,
            color=C_WHITE, size_hint_y=None, height=54
        ))
        inner.add_widget(divider())

        # ── 当日打卡状态 ───────────────────────────────
        inner.add_widget(section_lbl('当日打卡状态'))
        ci_h = config.get("checkin_hour",  8)
        ci_m = config.get("checkin_min",  40)
        co_h = config.get("checkout_hour", 18)
        co_m = config.get("checkout_min",   0)

        _lbl_ci = Label(
            text=f'上班打卡：⏳ 等待提醒 ({ci_h:02d}:{ci_m:02d})',
            font_size='15sp', color=C_GRAY,
            size_hint_y=None, height=34, halign='left'
        )
        _lbl_ci.bind(size=_lbl_ci.setter('text_size'))
        _lbl_co = Label(
            text=f'下班打卡：⏳ 等待提醒 ({co_h:02d}:{co_m:02d})',
            font_size='15sp', color=C_GRAY,
            size_hint_y=None, height=34, halign='left'
        )
        _lbl_co.bind(size=_lbl_co.setter('text_size'))
        inner.add_widget(_lbl_ci)
        inner.add_widget(_lbl_co)
        inner.add_widget(divider())

        # ── 内置提醒设置 ───────────────────────────────
        inner.add_widget(section_lbl('⏰ 内置提醒设置'))

        builtin_box = BoxLayout(orientation='vertical', spacing=8,
                                size_hint_y=None, height=120)
        _builtin_section = builtin_box

        # 上班行
        ci_row = BoxLayout(orientation='horizontal', spacing=8,
                           size_hint_y=None, height=46)
        lbl_ci_time = Label(
            text=f'上班时间：{ci_h:02d}:{ci_m:02d}',
            font_size='15sp', color=C_WHITE,
            size_hint_x=0.38, halign='left'
        )
        lbl_ci_time.bind(size=lbl_ci_time.setter('text_size'))
        builtin_box.ci_lbl = lbl_ci_time

        btn_ci_time = Button(text='修改时间', font_size='13sp',
                             background_color=C_ACCENT, background_normal='',
                             color=C_WHITE, size_hint_x=0.28)
        btn_ci_msg  = Button(text='修改提示词', font_size='13sp',
                             background_color=C_PURPLE, background_normal='',
                             color=C_WHITE, size_hint_x=0.32)
        ci_row.add_widget(lbl_ci_time)
        ci_row.add_widget(btn_ci_time)
        ci_row.add_widget(btn_ci_msg)

        def on_ci_time_save(h, m):
            lbl_ci_time.text = f'上班时间：{h:02d}:{m:02d}'
            update_status_display()
            show_toast(f'上班时间已改为 {h:02d}:{m:02d}')

        btn_ci_time.bind(on_press=lambda i: open_time_editor(
            '修改上班提醒时间', 'checkin_hour', 'checkin_min', on_ci_time_save
        ))
        btn_ci_msg.bind(on_press=lambda i: open_message_editor(
            '修改上班提示词', 'checkin_title', 'checkin_msg',
            lambda: show_toast('上班提示词已更新')
        ))

        # 下班行
        co_row = BoxLayout(orientation='horizontal', spacing=8,
                           size_hint_y=None, height=46)
        lbl_co_time = Label(
            text=f'下班时间：{co_h:02d}:{co_m:02d}',
            font_size='15sp', color=C_WHITE,
            size_hint_x=0.38, halign='left'
        )
        lbl_co_time.bind(size=lbl_co_time.setter('text_size'))
        builtin_box.co_lbl = lbl_co_time

        btn_co_time = Button(text='修改时间', font_size='13sp',
                             background_color=C_ACCENT, background_normal='',
                             color=C_WHITE, size_hint_x=0.28)
        btn_co_msg  = Button(text='修改提示词', font_size='13sp',
                             background_color=C_PURPLE, background_normal='',
                             color=C_WHITE, size_hint_x=0.32)
        co_row.add_widget(lbl_co_time)
        co_row.add_widget(btn_co_time)
        co_row.add_widget(btn_co_msg)

        def on_co_time_save(h, m):
            lbl_co_time.text = f'下班时间：{h:02d}:{m:02d}'
            update_status_display()
            show_toast(f'下班时间已改为 {h:02d}:{m:02d}')

        btn_co_time.bind(on_press=lambda i: open_time_editor(
            '修改下班提醒时间', 'checkout_hour', 'checkout_min', on_co_time_save
        ))
        btn_co_msg.bind(on_press=lambda i: open_message_editor(
            '修改下班提示词', 'checkout_title', 'checkout_msg',
            lambda: show_toast('下班提示词已更新')
        ))

        builtin_box.add_widget(ci_row)
        builtin_box.add_widget(co_row)
        inner.add_widget(builtin_box)
        inner.add_widget(divider())

        # ── 自定义提醒 ────────────────────────────────
        inner.add_widget(section_lbl('📋 自定义提醒'))
        custom_box = BoxLayout(orientation='vertical', spacing=6,
                               size_hint_y=None)
        custom_box.bind(minimum_height=custom_box.setter('height'))
        _custom_section = custom_box
        rebuild_custom_section()
        inner.add_widget(custom_box)
        inner.add_widget(divider())

        # ── 提醒音频：上班 ────────────────────────────
        inner.add_widget(section_lbl('🔔 提醒音频'))
        inner.add_widget(section_lbl('🏢 上班打卡提醒音频'))
        _lbl_aci = Label(text='🔕 未设置（静音）', font_size='13sp',
                         color=C_DARK, size_hint_y=None, height=26, halign='left')
        _lbl_aci.bind(size=_lbl_aci.setter('text_size'))
        inner.add_widget(_lbl_aci)
        row_ci_audio = BoxLayout(orientation='horizontal', spacing=8,
                                 size_hint_y=None, height=44)
        for txt, cb_fn in [
            ('📂 选择', lambda i: open_file_chooser(
                '选择音频', ["*.mp3", "*.wav", "*.ogg", "*.m4a", "*.aac"],
                lambda p: _set_audio("checkin_audio", p)
            )),
            ('▶ 试听', lambda i: (
                show_toast("未设置音频") if not config.get("checkin_audio")
                else play_audio(config["checkin_audio"])
            )),
            ('✕ 清除', lambda i: _clear_audio("checkin_audio")),
        ]:
            b = Button(text=txt, font_size='14sp',
                       background_color=(C_ACCENT if '选' in txt
                                         else (C_DARK if '试' in txt else C_RED)),
                       background_normal='', color=C_WHITE)
            b.bind(on_press=cb_fn)
            row_ci_audio.add_widget(b)
        inner.add_widget(row_ci_audio)

        # ── 提醒音频：下班 ────────────────────────────
        inner.add_widget(section_lbl('🏠 下班打卡提醒音频'))
        _lbl_aco = Label(text='🔕 未设置（静音）', font_size='13sp',
                         color=C_DARK, size_hint_y=None, height=26, halign='left')
        _lbl_aco.bind(size=_lbl_aco.setter('text_size'))
        inner.add_widget(_lbl_aco)
        row_co_audio = BoxLayout(orientation='horizontal', spacing=8,
                                 size_hint_y=None, height=44)
        for txt, cb_fn in [
            ('📂 选择', lambda i: open_file_chooser(
                '选择音频', ["*.mp3", "*.wav", "*.ogg", "*.m4a", "*.aac"],
                lambda p: _set_audio("checkout_audio", p)
            )),
            ('▶ 试听', lambda i: (
                show_toast("未设置音频") if not config.get("checkout_audio")
                else play_audio(config["checkout_audio"])
            )),
            ('✕ 清除', lambda i: _clear_audio("checkout_audio")),
        ]:
            b = Button(text=txt, font_size='14sp',
                       background_color=(C_ACCENT if '选' in txt
                                         else (C_DARK if '试' in txt else C_RED)),
                       background_normal='', color=C_WHITE)
            b.bind(on_press=cb_fn)
            row_co_audio.add_widget(b)
        inner.add_widget(row_co_audio)
        inner.add_widget(divider())

        # ── 背景壁纸 ──────────────────────────────────
        inner.add_widget(section_lbl('🖼 背景壁纸'))
        _lbl_wp = Label(
            text='🎨 纯色背景 #1A1A2E', font_size='13sp',
            color=C_GRAY, size_hint_y=None, height=26, halign='left'
        )
        _lbl_wp.bind(size=_lbl_wp.setter('text_size'))
        inner.add_widget(_lbl_wp)
        btn_wp = Button(text='⚙️  设置壁纸', font_size='15sp', bold=True,
                        background_color=C_PURPLE, background_normal='',
                        color=C_WHITE, size_hint_y=None, height=46)
        btn_wp.bind(on_press=lambda i: open_wallpaper_settings())
        inner.add_widget(btn_wp)
        inner.add_widget(divider())

        # ── 功能测试 ──────────────────────────────────
        inner.add_widget(section_lbl('功能测试'))
        row_test = BoxLayout(orientation='horizontal', spacing=8,
                             size_hint_y=None, height=44)
        b_ti = Button(text='测试上班提醒', font_size='14sp',
                      background_color=C_ACCENT, background_normal='', color=C_WHITE)
        b_to = Button(text='测试下班提醒', font_size='14sp',
                      background_color=C_ACCENT, background_normal='', color=C_WHITE)
        b_ti.bind(on_press=lambda x: trigger_checkin_reminder())
        b_to.bind(on_press=lambda x: trigger_checkout_reminder())
        row_test.add_widget(b_ti)
        row_test.add_widget(b_to)
        inner.add_widget(row_test)

        inner.add_widget(Label(
            text='• 收到 → 当天不再提醒\n• 延迟 → 1小时后再次提醒\n• 5分钟无操作 → 自动再次弹出',
            font_size='12sp', color=C_DARK,
            size_hint_y=None, height=56, halign='left', valign='top'
        ))

        scroll.add_widget(inner)
        root_fl.add_widget(scroll)

        Clock.schedule_once(lambda dt: apply_wallpaper(root_fl), 0.1)
        Clock.schedule_interval(schedule_daily_reminders, 30)
        Clock.schedule_once(update_status_display, 0.5)

        return root_fl


if __name__ == '__main__':
    PunchReminderApp().run()
