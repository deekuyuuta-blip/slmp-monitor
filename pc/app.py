# -*- coding: utf-8 -*-
"""
SLMPモニタ PC版（Windows）
PCから三菱PLCへSLMPで読み書きし、一覧と操作画面（GOT風）で監視・操作する。
スマホ版と同じ操作画面ファイル（JSON）を読み書きできる。
"""
import json
import os
import sys
import time
import tkinter as tk
import tkinter.font as tkfont
from datetime import datetime
from tkinter import ttk, messagebox, filedialog, simpledialog

from slmpcore import (Device, MonitorItem, DeviceMemory, PlcClient, FORMATS, FORMAT_LABELS, format_words)
import panel as pn

APP_NAME = "SLMPモニタ"
VERSION = "1.4"
AUTO_LOCK_SEC = 120
FONT = "Meiryo UI" if sys.platform == "win32" else "TkDefaultFont"

C_ON = "#2E7D32"
C_ERR = "#C62828"
C_GRAY = "#607D8B"


# 画面の表示倍率（Windowsの「拡大縮小」125%・150%など）。Tkを作ったあとに決める
UI_SCALE = 1.0


def S(px):
    """100%のときのピクセル数を、今の表示倍率に合わせる"""
    return int(round(px * UI_SCALE))


def init_scale(root):
    global UI_SCALE
    try:
        UI_SCALE = max(1.0, root.winfo_fpixels("1i") / 96.0)
    except tk.TclError:
        UI_SCALE = 1.0


def place_window(win, store, key, w, h, minw, minh):
    """前回の位置と大きさを戻す（画面からはみ出す・小さすぎるときは既定の大きさ）"""
    win.minsize(S(minw), S(minh))
    g = store["win_" + key]
    sw, sh = win.winfo_screenwidth(), win.winfo_screenheight()
    if g:
        try:
            ww, hh, x, y = g
            if S(minw) <= ww <= sw and S(minh) <= hh <= sh and -50 <= x < sw - 100 and 0 <= y < sh - 100:
                win.geometry("%dx%d+%d+%d" % (ww, hh, x, y))
                return
        except (TypeError, ValueError):
            pass
    win.geometry("%dx%d" % (min(S(w), sw - 40), min(S(h), sh - 80)))


def remember_window(win, store, key):
    try:
        if win.state() == "normal":
            store["win_" + key] = [win.winfo_width(), win.winfo_height(), win.winfo_x(), win.winfo_y()]
    except tk.TclError:
        pass


def argb(c):
    return "#%06X" % (c & 0xFFFFFF)


def contrast(c):
    r, g, b = (c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF
    return "#000000" if 0.299 * r + 0.587 * g + 0.114 * b > 160 else "#FFFFFF"


def darker(c):
    r, g, b = int(((c >> 16) & 0xFF) * 0.7), int(((c >> 8) & 0xFF) * 0.7), int((c & 0xFF) * 0.7)
    return 0xFF000000 | (r << 16) | (g << 8) | b


# ================================================================ 設定の保存

def config_dir():
    base = os.environ.get("APPDATA") or os.path.expanduser("~")
    d = os.path.join(base, "SLMPMonitor")
    os.makedirs(d, exist_ok=True)
    return d


class Store:
    DEFAULTS = {"plcIp": "192.168.1.1", "plcPort": 5010, "plcUdp": True, "pollMs": 200,
                "heartbeat": "D2019", "staleSec": 3, "autoStart": False, "openPanelOnStart": False,
                "usePw": False, "pw": "", "showDev": True, "items": None, "panel": None, "lastDir": ""}

    def __init__(self, path=None):
        self.path = path or os.path.join(config_dir(), "config.json")
        self.data = dict(self.DEFAULTS)
        try:
            with open(self.path, encoding="utf-8") as f:
                self.data.update(json.load(f))
        except (OSError, ValueError):
            pass

    def __getitem__(self, k):
        return self.data.get(k, self.DEFAULTS.get(k))

    def __setitem__(self, k, v):
        self.data[k] = v

    def save(self):
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(self.data, f, ensure_ascii=False, indent=1)
        os.replace(tmp, self.path)

    def items(self):
        raw = self["items"]
        if raw is None:
            return [MonitorItem("PLCから受信 例", "D0", "S16", False),
                    MonitorItem("PLCへ送る値 例", "D2000", "S16", True),
                    MonitorItem("PLCへ送るビット 例", "M2000", "BIT", True)]
        out = []
        for o in raw:
            try:
                out.append(MonitorItem.from_dict(o))
            except (ValueError, KeyError):
                pass
        return out

    def save_items(self, items):
        self["items"] = [i.to_dict() for i in items]
        self.save()

    def panel(self):
        raw = self["panel"]
        if raw:
            try:
                return pn.Panel.from_json(raw)
            except (ValueError, KeyError, TypeError):
                pass
        return pn.Panel.create_default()

    def save_panel(self, panel):
        self["panel"] = panel.to_json()
        self.save()


# ================================================================ 通信の管理（スマホ版の PlcLink）

class Link:
    def __init__(self, app):
        self.app = app
        self.mem = DeviceMemory()
        self.client = None
        self.session_pw = None
        self.hb_count = 0
        self.mem.write_hook = self._hook

    def _hook(self, kind, code, no, values, momentary):
        c = self.client
        if not c:
            return
        if kind == "words":
            c.queue_words(code, no, values)
        else:
            c.queue_bits(code, no, values, momentary)

    def running(self):
        return self.client is not None

    def fresh(self):
        return self.client is not None and self.client.is_fresh(max(1, int(self.app.store["staleSec"])))

    def password(self):
        s = self.app.store
        if not s["usePw"]:
            return ""
        if s["pw"]:
            return s["pw"]
        return self.session_pw

    def forget_password(self):
        self.session_pw = None
        if self.app.store["pw"]:
            self.app.store["pw"] = ""
            self.app.store.save()

    def devices(self):
        out = []
        for it in self.app.items:
            out.append((it.device_text, format_words(it.format)))
        out.extend(self.app.panel.devices_to_read())
        return out

    def start(self, parent):
        if self.client:
            return True
        pw = self.password()
        if pw is None:
            pw = simpledialog.askstring("リモートパスワード",
                                        "PLCに設定したリモートパスワードを入力してください。\n（このPCには保存しません）",
                                        show="*", parent=parent)
            if pw is None:
                return False
            if not (4 <= len(pw) <= 32) or not all(0x21 <= ord(ch) <= 0x7E for ch in pw):
                messagebox.showerror(APP_NAME, "半角英数字・記号で入力してください", parent=parent)
                return False
            self.session_pw = pw
        s = self.app.store
        c = PlcClient(self.mem, s["plcIp"], int(s["plcPort"]), bool(s["plcUdp"]), int(s["pollMs"]), pw,
                      devices_provider=self.devices,
                      on_auth_failed=lambda: self.app.root.after(0, self.forget_password))
        try:
            c.start()
        except (IOError, OSError) as e:
            messagebox.showerror(APP_NAME, "通信を開始できません: %s" % e, parent=parent)
            return False
        self.client = c
        return True

    def stop(self):
        c = self.client
        self.client = None
        if c:
            c.stop()
            self.last_log = c.log_lines()

    def heartbeat(self):
        dev = (self.app.store["heartbeat"] or "").strip()
        c = self.client
        if c and dev:
            try:
                d = Device.parse(dev)
                self.hb_count = (self.hb_count + 1) & 0xFFFF
                if d.bit:
                    c.queue_quiet(d.code, d.number, bits=[bool(self.hb_count & 1)])
                else:
                    v = self.hb_count - 0x10000 if self.hb_count & 0x8000 else self.hb_count
                    c.queue_quiet(d.code, d.number, words=[v])
            except ValueError:
                pass


# ================================================================ 共通の小物

def center(win, parent):
    win.update_idletasks()
    try:
        x = parent.winfo_rootx() + (parent.winfo_width() - win.winfo_width()) // 2
        y = parent.winfo_rooty() + (parent.winfo_height() - win.winfo_height()) // 3
        win.geometry("+%d+%d" % (max(0, x), max(0, y)))
    except tk.TclError:
        pass


class Dialog(tk.Toplevel):
    """OK/キャンセル付きのモーダルダイアログ"""

    def __init__(self, parent, title, ok_text="OK"):
        super().__init__(parent)
        self.title(title)
        self.transient(parent)
        self.resizable(False, False)
        self.result = None
        self.body = ttk.Frame(self, padding=14)
        self.body.pack(fill="both", expand=True)
        bar = ttk.Frame(self, padding=(14, 0, 14, 12))
        bar.pack(fill="x")
        ttk.Button(bar, text="キャンセル", command=self.destroy).pack(side="right")
        ttk.Button(bar, text=ok_text, command=self._ok).pack(side="right", padx=6)
        self.bind("<Escape>", lambda e: self.destroy())
        self.parent = parent

    def show(self):
        center(self, self.parent)
        self.grab_set()
        self.wait_window()
        return self.result

    def _ok(self):
        r = self.apply()
        if r is not None:
            self.result = r
            self.destroy()

    def apply(self):
        return True

    def row(self, r, label, widget, note=None):
        ttk.Label(self.body, text=label).grid(row=r, column=0, sticky="w", pady=3, padx=(0, 10))
        widget.grid(row=r, column=1, sticky="we", pady=3)
        if note:
            ttk.Label(self.body, text=note, foreground="#607D8B", wraplength=420).grid(
                row=r + 1, column=1, sticky="w")
        return widget


# ================================================================ 一覧の項目ダイアログ

class ItemDialog(Dialog):
    def __init__(self, parent, item=None):
        super().__init__(parent, "項目を編集" if item else "項目を追加")
        self.v_name = tk.StringVar(value=item.name if item else "")
        self.v_dev = tk.StringVar(value=item.device_text if item else "")
        self.v_fmt = tk.StringVar(value=FORMAT_LABELS[item.format] if item else FORMAT_LABELS["S16"])
        self.v_w = tk.BooleanVar(value=item.writable if item else False)
        self.row(0, "名前", ttk.Entry(self.body, textvariable=self.v_name, width=30))
        self.row(1, "デバイス", ttk.Entry(self.body, textvariable=self.v_dev, width=30),
                 "PLCのデバイス番号をそのまま入れます（例: D0、M2000）")
        self.row(3, "データ形式", ttk.Combobox(self.body, textvariable=self.v_fmt, state="readonly",
                                            values=[FORMAT_LABELS[f] for f in FORMATS[1:]], width=28),
                 "ビットデバイス（M、Xなど）は自動でビットになります")
        ttk.Checkbutton(self.body, text="PCから値を変える（スマホ → PLC の欄）", variable=self.v_w).grid(
            row=5, column=1, sticky="w", pady=4)

    def apply(self):
        fmt = [k for k, v in FORMAT_LABELS.items() if v == self.v_fmt.get()][0]
        try:
            it = MonitorItem(self.v_name.get().strip() or self.v_dev.get().strip().upper(), self.v_dev.get(),
                             fmt, self.v_w.get())
        except ValueError as e:
            messagebox.showerror(APP_NAME, str(e), parent=self)
            return None
        if it.writable and it.device().write_blocked_reason():
            messagebox.showerror(APP_NAME, it.device().write_blocked_reason(), parent=self)
            return None
        return it


# ================================================================ 設定ダイアログ

class SettingsDialog(Dialog):
    def __init__(self, parent, store):
        super().__init__(parent, "設定", "保存")
        self.s = store
        self.v_ip = tk.StringVar(value=store["plcIp"])
        self.v_port = tk.StringVar(value=str(store["plcPort"]))
        self.v_udp = tk.BooleanVar(value=bool(store["plcUdp"]))
        self.v_poll = tk.StringVar(value=str(store["pollMs"]))
        self.v_usepw = tk.BooleanVar(value=bool(store["usePw"]))
        self.v_savepw = tk.BooleanVar(value=bool(store["pw"]))
        self.v_pw = tk.StringVar()
        self.v_hb = tk.StringVar(value=store["heartbeat"])
        self.v_stale = tk.StringVar(value=str(store["staleSec"]))
        self.v_auto = tk.BooleanVar(value=bool(store["autoStart"]))
        self.v_panel = tk.BooleanVar(value=bool(store["openPanelOnStart"]))
        b = self.body
        r = 0
        ttk.Label(b, text="PLCとの通信（SLMP）", font=(FONT, 10, "bold")).grid(row=r, column=0, columnspan=2, sticky="w")
        r += 1
        self.row(r, "PLCのIPアドレス", ttk.Entry(b, textvariable=self.v_ip, width=24))
        r += 1
        self.row(r, "SLMPポート番号", ttk.Entry(b, textvariable=self.v_port, width=24),
                 "GX Works3「Ethernetポート → 相手機器接続構成設定」のSLMP接続機器のポート（バイナリ）")
        r += 2
        pf = ttk.Frame(b)
        ttk.Radiobutton(pf, text="UDP（おすすめ）", variable=self.v_udp, value=True).pack(side="left")
        ttk.Radiobutton(pf, text="TCP", variable=self.v_udp, value=False).pack(side="left", padx=10)
        self.row(r, "プロトコル", pf)
        r += 1
        self.row(r, "読出し周期（ms）", ttk.Entry(b, textvariable=self.v_poll, width=24),
                 "50〜5000。応答がなければ0.3秒で送り直します")
        r += 2
        ttk.Separator(b).grid(row=r, column=0, columnspan=2, sticky="we", pady=8)
        r += 1
        ttk.Label(b, text="リモートパスワード", font=(FONT, 10, "bold")).grid(row=r, column=0, columnspan=2, sticky="w")
        r += 1
        ttk.Checkbutton(b, text="リモートパスワードを使う（通信開始のたびに入力）", variable=self.v_usepw,
                        command=self._vis).grid(row=r, column=0, columnspan=2, sticky="w")
        r += 1
        self.cb_save = ttk.Checkbutton(b, text="このPCに保存する（PCを使える人は誰でも操作できます）",
                                       variable=self.v_savepw, command=self._vis)
        self.cb_save.grid(row=r, column=0, columnspan=2, sticky="w")
        r += 1
        self.e_pw = ttk.Entry(b, textvariable=self.v_pw, show="*", width=24)
        self.lbl_pw = ttk.Label(b, text="保存するパスワード" + ("（保存済み・変えるときだけ）" if store["pw"] else ""))
        self.lbl_pw.grid(row=r, column=0, sticky="w")
        self.e_pw.grid(row=r, column=1, sticky="we")
        r += 1
        ttk.Separator(b).grid(row=r, column=0, columnspan=2, sticky="we", pady=8)
        r += 1
        ttk.Label(b, text="安全・監視", font=(FONT, 10, "bold")).grid(row=r, column=0, columnspan=2, sticky="w")
        r += 1
        self.row(r, "ハートビート デバイス", ttk.Entry(b, textvariable=self.v_hb, width=24),
                 "1秒ごとにPLCのこのデバイスへ+1を書込みます（例: D2019）。空欄で使わない")
        r += 2
        self.row(r, "通信なし判定（秒）", ttk.Entry(b, textvariable=self.v_stale, width=24))
        r += 1
        ttk.Separator(b).grid(row=r, column=0, columnspan=2, sticky="we", pady=8)
        r += 1
        ttk.Checkbutton(b, text="起動時に自動で通信を開始", variable=self.v_auto).grid(row=r, column=0, columnspan=2, sticky="w")
        r += 1
        ttk.Checkbutton(b, text="起動時に操作画面を開く", variable=self.v_panel).grid(row=r, column=0, columnspan=2, sticky="w")
        self._vis()

    def _vis(self):
        self.cb_save.state(["!disabled"] if self.v_usepw.get() else ["disabled"])
        show = self.v_usepw.get() and self.v_savepw.get()
        for w in (self.e_pw, self.lbl_pw):
            if show:
                w.grid()
            else:
                w.grid_remove()

    def apply(self):
        ip = self.v_ip.get().strip()
        parts = ip.split(".")
        if len(parts) != 4 or not all(p.isdigit() and 0 <= int(p) <= 255 for p in parts):
            messagebox.showerror(APP_NAME, "PLCのIPアドレスを入れてください（例: 192.168.1.1）", parent=self)
            return None
        try:
            port = int(self.v_port.get())
            assert 1 <= port <= 65535
        except (ValueError, AssertionError):
            messagebox.showerror(APP_NAME, "ポート番号は1〜65535で入力してください", parent=self)
            return None
        try:
            poll = int(self.v_poll.get())
            assert 50 <= poll <= 5000
        except (ValueError, AssertionError):
            messagebox.showerror(APP_NAME, "読出し周期は50〜5000で入力してください", parent=self)
            return None
        try:
            stale = int(self.v_stale.get())
            assert 1 <= stale <= 60
        except (ValueError, AssertionError):
            messagebox.showerror(APP_NAME, "通信なし判定は1〜60秒で入力してください", parent=self)
            return None
        hb = self.v_hb.get().strip().upper()
        if hb:
            try:
                d = Device.parse(hb)
                if d.write_blocked_reason():
                    raise ValueError(d.write_blocked_reason())
                hb = str(d)
            except ValueError as e:
                messagebox.showerror(APP_NAME, "ハートビート: %s" % e, parent=self)
                return None
        pw = self.v_pw.get()
        s = self.s
        if self.v_usepw.get() and self.v_savepw.get():
            if not pw and not s["pw"]:
                messagebox.showerror(APP_NAME, "保存するパスワードを入力してください", parent=self)
                return None
            if pw and (not 4 <= len(pw) <= 32 or not all(0x21 <= ord(ch) <= 0x7E for ch in pw)):
                messagebox.showerror(APP_NAME, "パスワードは半角英数字・記号で入力してください", parent=self)
                return None
            if pw:
                s["pw"] = pw
        else:
            s["pw"] = ""
        s["usePw"] = self.v_usepw.get()
        s["plcIp"], s["plcPort"], s["plcUdp"], s["pollMs"] = ip, port, self.v_udp.get(), poll
        s["heartbeat"], s["staleSec"] = hb, stale
        s["autoStart"], s["openPanelOnStart"] = self.v_auto.get(), self.v_panel.get()
        s.save()
        return True


# ================================================================ ログ

class LogWindow(tk.Toplevel):
    def __init__(self, app):
        super().__init__(app.root)
        self.app = app
        self.title("通信ログ（新しい順）")
        place_window(self, app.store, "log", 640, 480, 360, 240)
        self.text = tk.Text(self, font=("Consolas", 10), wrap="none", state="disabled")
        sb = ttk.Scrollbar(self, command=self.text.yview)
        self.text.configure(yscrollcommand=sb.set)
        bar = ttk.Frame(self, padding=6)
        bar.pack(side="bottom", fill="x")
        self.v_follow = tk.BooleanVar(value=True)
        ttk.Checkbutton(bar, text="自動更新", variable=self.v_follow).pack(side="left")
        ttk.Button(bar, text="コピー", command=self.copy).pack(side="right")
        sb.pack(side="right", fill="y")
        self.text.pack(fill="both", expand=True)
        self.last = None
        self.protocol("WM_DELETE_WINDOW", self.close)
        self.refresh()

    def close(self):
        remember_window(self, self.app.store, "log")
        self.app.store.save()
        self.destroy()

    def lines(self):
        c = self.app.link.client
        lines = c.log_lines() if c else getattr(self.app.link, "last_log", [])
        return list(reversed(lines)) or ["ログはありません（通信中のみ記録）"]

    def refresh(self):
        if not self.winfo_exists():
            return
        if self.v_follow.get():
            lines = self.lines()
            if lines != self.last:
                self.last = lines
                self.text.configure(state="normal")
                self.text.delete("1.0", "end")
                self.text.insert("1.0", "\n".join(lines))
                self.text.configure(state="disabled")
        self.after(1000, self.refresh)

    def copy(self):
        self.clipboard_clear()
        self.clipboard_append("\n".join(self.lines()))


# ================================================================ 操作画面（キャンバス）

class PanelCanvas(tk.Canvas):
    BG_OUT = "#101316"
    BG_PANEL = "#1E2329"
    SEL = "#00E5FF"
    HANDLE = 14  # 100%のとき（使うときは S() で倍率をかける）

    def __init__(self, master, win):
        super().__init__(master, bg=self.BG_OUT, highlightthickness=0)
        self.win = win
        self.scale = 1.0
        self.ox = self.oy = 0
        self.edit = False
        self.selected = None
        self.pressed = None
        self.pressed_inside = False
        self.momentary_on = None
        self.momentary_at = 0
        self.pending_release = None
        self._drag = None
        self._fonts = {}
        self.bind("<Configure>", lambda e: self.redraw())
        self.bind("<ButtonPress-1>", self.on_press)
        self.bind("<B1-Motion>", self.on_motion)
        self.bind("<ButtonRelease-1>", self.on_release)
        self.bind("<Double-Button-1>", self.on_double)

    @property
    def panel(self):
        return self.win.app.panel

    def screen(self):
        ss = self.panel.screens
        return ss[min(self.win.screen_index, len(ss) - 1)] if ss else None

    def font(self, size, bold=True, mono=False):
        size = max(6, int(size))
        k = (size, bold, mono)
        f = self._fonts.get(k)
        if f is None:
            f = tkfont.Font(family="Consolas" if mono else FONT, size=-size, weight="bold" if bold else "normal")
            self._fonts[k] = f
        return f

    # ---- 座標
    def recalc(self):
        w, h = max(1, self.winfo_width()), max(1, self.winfo_height())
        self.scale = min(w / self.panel.width(), h / self.panel.height())
        self.ox = (w - self.panel.width() * self.scale) / 2
        self.oy = (h - self.panel.height() * self.scale) / 2

    def vx(self, x):
        return (x - self.ox) / self.scale

    def vy(self, y):
        return (y - self.oy) / self.scale

    def rect(self, p):
        s = self.scale
        return (self.ox + p.x * s, self.oy + p.y * s, self.ox + (p.x + p.w) * s, self.oy + (p.y + p.h) * s)

    # ---- 描画
    def round_rect(self, x1, y1, x2, y2, r, **kw):
        r = max(0, min(r, (x2 - x1) / 2, (y2 - y1) / 2))
        # 直線部分の点を2つずつ置くと、smooth でも辺がまっすぐになる（はみ出しが出ない）
        pts = [x1 + r, y1, x1 + r, y1, x2 - r, y1, x2 - r, y1, x2, y1, x2, y1 + r, x2, y1 + r,
               x2, y2 - r, x2, y2 - r, x2, y2, x2 - r, y2, x2 - r, y2, x1 + r, y2, x1 + r, y2,
               x1, y2, x1, y2 - r, x1, y2 - r, x1, y1 + r, x1, y1 + r, x1, y1]
        return self.create_polygon(pts, smooth=True, **kw)

    def fit_text(self, text, box, color, bold=True, max_frac=0.5, anchor="center"):
        if not text:
            return
        x1, y1, x2, y2 = box
        lines = text.split("\n")
        pad = min(x2 - x1, y2 - y1) * 0.08
        aw, ah = (x2 - x1) - pad * 2, (y2 - y1) - pad * 2
        size = max(6, min(ah * max_frac, ah / len(lines) * 0.85))
        f = self.font(size, bold)
        widest = max(f.measure(l) for l in lines)
        if widest > aw > 0:
            f = self.font(max(6, size * aw / widest), bold)
        if anchor == "w":
            x, a, j = x1 + pad, "w", "left"
        elif anchor == "e":
            x, a, j = x2 - pad, "e", "right"
        else:
            x, a, j = (x1 + x2) / 2, "center", "center"
        self.create_text(x, (y1 + y2) / 2, text=text, fill=color, font=f, anchor=a, justify=j)

    def redraw(self):
        self.delete("all")
        scr = self.screen()
        if scr is None:
            return
        self.recalc()
        s = self.scale
        self.create_rectangle(self.ox, self.oy, self.ox + self.panel.width() * s, self.oy + self.panel.height() * s,
                              fill=self.BG_PANEL, width=0)
        if self.edit:
            step = pn.GRID * 2
            for gx in range(0, self.panel.width() + 1, step):
                for gy in range(0, self.panel.height() + 1, step):
                    X, Y = self.ox + gx * s, self.oy + gy * s
                    self.create_rectangle(X, Y, X + 1, Y + 1, fill="#3A4048", width=0)
        mem = self.win.app.link.mem
        fresh = self.win.app.link.fresh()
        for p in scr.parts:
            self.draw_part(p, mem, fresh)
        if self.edit:
            for p in scr.parts:
                x1, y1, x2, y2 = self.rect(p)
                self.create_rectangle(x1, y1, x2, y2, outline="#5A6068")
                info = p.device_summary()
                if info:
                    f = self.font(max(8, min(S(11), 11 * self.scale)), False, True)
                    tw = f.measure(info)
                    th = f.metrics("linespace")
                    self.create_rectangle(x1, y2 - th - 2, x1 + tw + 6, y2, fill="#000000", width=0)
                    self.create_text(x1 + 3, y2 - th / 2 - 1, text=info, fill="#FFEB3B", font=f, anchor="w")
            if self.selected in scr.parts:
                x1, y1, x2, y2 = self.rect(self.selected)
                self.create_rectangle(x1, y1, x2, y2, outline=self.SEL, width=2)
                hs = S(self.HANDLE)
                self.create_rectangle(x2 - hs, y2 - hs, x2, y2, fill=self.SEL, width=0)

    def dev_tag(self, p, x1, y1, x2, y2, col):
        """運転中、ランプ・ボタンの左上にデバイス番号を小さく出す。文字に使える高さを返す"""
        if self.edit or not p.device or p.type not in ("LAMP", "BUTTON") or not self.win.app.store["showDev"]:
            return 0
        size = max(S(9), min(11 * self.scale, (y2 - y1) * 0.22))
        f = self.font(size, False, True)
        tc = "#263238" if contrast(col) == "#000000" else "#CFD8DC"
        pad = max(2, 3 * self.scale)
        self.create_text(x1 + pad, y1 + pad * 0.6, text=p.device, fill=tc, font=f, anchor="nw")
        return f.metrics("linespace") * 0.8

    def draw_part(self, p, mem, fresh):
        x1, y1, x2, y2 = self.rect(p)
        s = self.scale
        rad = min(x2 - x1, y2 - y1) * 0.12
        stale = not fresh and not self.edit
        t = p.type
        if t == "LAMP":
            on = p.is_on(mem)
            col = p.onColor if on else p.offColor
            self.round_rect(x1, y1, x2, y2, rad, fill=argb(col), outline="")
            if on:
                self.round_rect(x1 + 3 * s, y1 + 3 * s, x2 - 3 * s, y2 - 3 * s, rad, fill="",
                                outline="#9FBF9F" if col == pn.COLORS[0] else "#DDDDDD", width=max(2, 2 * s))
            txt = (p.onText or p.label) if on else (p.offText or p.label)
            self.fit_text(txt, (x1, y1 + self.dev_tag(p, x1, y1, x2, y2, col), x2, y2), contrast(col), True, 0.42)
            if stale:
                self.round_rect(x1, y1, x2, y2, rad, fill="#202428", outline="", stipple="gray50")
        elif t in ("BUTTON", "SWITCH"):
            on = p.is_on(mem) if t == "BUTTON" else False
            down = (p is self.pressed and self.pressed_inside) or p is self.momentary_on
            base = p.onColor if on else p.offColor
            if not down:
                self.round_rect(x1, y1 + 4 * s, x2, y2 + 4 * s, rad, fill="#0B0D10", outline="")
            off = 3 * s if down else 0
            col = darker(base) if down else base
            self.round_rect(x1, y1 + off, x2, y2 + off, rad, fill=argb(col), outline="#8A9096", width=max(1, 1.5 * s))
            txt = p.label
            if t == "BUTTON":
                if on and p.onText:
                    txt = p.onText
                if not on and p.offText:
                    txt = p.offText
            else:
                txt = "▶ " + txt
            top = self.dev_tag(p, x1, y1 + off, x2, y2 + off, col)
            self.fit_text(txt, (x1, y1 + off + top, x2, y2 + off), contrast(col), True, 0.38)
        elif t in ("NUMBER", "INPUT"):
            inp = t == "INPUT"
            down = inp and p is self.pressed and self.pressed_inside
            self.round_rect(x1, y1, x2, y2, rad * 0.6, fill="#2B3440" if down else "#0C0F12",
                            outline="#64B5F6" if inp else "#455A64", width=max(1.5, (2.5 if inp else 1.5) * s))
            pad = min(y2 - y1, x2 - x1) * 0.1
            show_label = bool(p.label) and p.h >= 50
            va = (x1 + pad, y1, x2 - pad, y2)
            if show_label:
                la = (x1 + pad, y1 + pad * 0.5, x2 - pad, y1 + (y2 - y1) * 0.36)
                self.fit_text(p.label + ("  ✎" if inp else ""), la, "#B0BEC5", False, 0.9, "w")
                va = (x1 + pad, la[3], x2 - pad, y2)
            v = p.display_value(mem) + ((" " + p.unit) if p.unit else "")
            vc = "#78909C" if stale else ("#FFFFFF" if inp else "#69F0AE")
            self.fit_text(v, va, vc, True, 0.8 if show_label else 0.6, "e")
        else:
            self.fit_text(p.label, (x1, y1, x2, y2), argb(p.textColor), True, 0.7)

    # ---- マウス
    def hit(self, vx, vy):
        scr = self.screen()
        if scr is None:
            return None
        for p in reversed(scr.parts):
            if p.contains(vx, vy):
                return p
        return None

    def on_press(self, e):
        vx, vy = self.vx(e.x), self.vy(e.y)
        if self.edit:
            self._drag = None
            if self.selected is not None:
                x1, y1, x2, y2 = self.rect(self.selected)
                hs = S(self.HANDLE) * 1.4
                if x2 - hs <= e.x <= x2 + 4 and y2 - hs <= e.y <= y2 + 4:
                    self._drag = ("resize", e.x, e.y, self.selected.x, self.selected.y, self.selected.w, self.selected.h)
                    return
            p = self.hit(vx, vy)
            self.select(p)
            if p is not None:
                self._drag = ("move", e.x, e.y, p.x, p.y, p.w, p.h)
            return
        p = self.hit(vx, vy)
        if p is None or p.type in ("LAMP", "NUMBER", "TEXT"):
            self.pressed = None
            return
        if p.operable():
            why = self.win.can_operate()
            if why:
                self.win.message(why)
                self.pressed = None
                return
        self.pressed = p
        self.pressed_inside = True
        if p.type == "BUTTON" and p.action == "MOMENTARY":
            self.press_momentary(p)
        self.redraw()

    def on_motion(self, e):
        if self.edit:
            if not self._drag or self.selected is None:
                return
            kind, sx, sy, ox, oy, ow, oh = self._drag
            dx, dy = (e.x - sx) / self.scale, (e.y - sy) / self.scale
            p = self.selected
            if kind == "resize":
                p.w = min(max(pn.GRID * 2, pn.snap(ow + dx)), self.panel.width() - p.x)
                p.h = min(max(pn.GRID * 2, pn.snap(oh + dy)), self.panel.height() - p.y)
            else:
                p.x, p.y = pn.snap(ox + dx), pn.snap(oy + dy)
                self.panel.clamp(p)
            self.win.changed = True
            self.redraw()
            return
        if self.pressed is not None:
            inside = self.pressed.contains(self.vx(e.x), self.vy(e.y))
            if inside != self.pressed_inside:
                self.pressed_inside = inside
                self.redraw()

    def on_release(self, e):
        if self.edit:
            self._drag = None
            return
        p = self.pressed
        inside = p is not None and p.contains(self.vx(e.x), self.vy(e.y))
        self.pressed = None
        if p is not None:
            if p.type == "BUTTON" and p.action == "MOMENTARY":
                self.release_momentary_later()
            elif inside:
                self.win.tapped(p)
        self.redraw()

    def on_double(self, e):
        # Tkはすばやい2回目の押下を「ダブルクリック」として渡す。運転中は普通の押下として扱う
        # （でないと、すばやく2回押したボタンの2回目が効かない）
        if not self.edit:
            self.on_press(e)
            return
        if self.selected is not None and self.selected.contains(self.vx(e.x), self.vy(e.y)):
            self._drag = None
            self.win.edit_part(self.selected)

    def select(self, p):
        self.selected = p
        self.win.selection_changed()
        self.redraw()

    # ---- モーメンタリ
    def press_momentary(self, p):
        if self.pending_release is not None:
            self.after_cancel(self.pending_release[0])
            self.pending_release[1]()
        try:
            p.write_bit(self.win.app.link.mem, True, True)
            self.momentary_on = p
            self.momentary_at = time.time()
            self.win.operated()
        except ValueError as ex:
            self.win.message("設定エラー: %s" % ex)

    def release_momentary_later(self):
        p = self.momentary_on
        if p is None:
            return
        remain = p.minOnMs / 1000.0 - (time.time() - self.momentary_at)

        def rel():
            self.pending_release = None
            try:
                p.write_bit(self.win.app.link.mem, False, True)
            except ValueError:
                pass
            if self.momentary_on is p:
                self.momentary_on = None
            self.redraw()

        if remain > 0:
            self.pending_release = (self.after(int(remain * 1000), rel), rel)
        else:
            rel()

    def release_momentary(self):
        """画面を離れるときなど、押しっぱなしのモーメンタリを即OFF"""
        if self.pending_release is not None:
            self.after_cancel(self.pending_release[0])
            self.pending_release[1]()
        if self.momentary_on is not None:
            try:
                self.momentary_on.write_bit(self.win.app.link.mem, False, True)
            except ValueError:
                pass
            self.momentary_on = None
        self.pressed = None


# ================================================================ 部品の設定ダイアログ

class PartDialog(Dialog):
    def __init__(self, parent, part, panel, is_new):
        super().__init__(parent, ("追加：" if is_new else "設定：") + pn.TYPE_LABELS[part.type], "追加" if is_new else "OK")
        self.p, self.panel = part, panel
        t = part.type
        b = self.body
        self.rows = {}
        self.v = {}

        def add(key, label, widget, var=None):
            lab = ttk.Label(b, text=label)
            r = len(self.rows)
            lab.grid(row=r, column=0, sticky="nw", pady=3, padx=(0, 10))
            widget.grid(row=r, column=1, sticky="we", pady=3)
            self.rows[key] = (lab, widget)

        def entry(key, label, value, width=30):
            v = tk.StringVar(value=value)
            self.v[key] = v
            add(key, label, ttk.Entry(b, textvariable=v, width=width))

        def combo(key, label, values, idx, cmd=None):
            v = tk.StringVar(value=values[max(0, min(idx, len(values) - 1))])
            self.v[key] = v
            c = ttk.Combobox(b, textvariable=v, values=values, state="readonly", width=34)
            if cmd:
                c.bind("<<ComboboxSelected>>", lambda e: cmd())
            add(key, label, c)

        self.t_label = tk.Text(b, width=34, height=2, font=(FONT, 10))
        self.t_label.insert("1.0", part.label)
        add("label", "表示する文字（改行可）" if t == "TEXT" else "表示名（改行可）", self.t_label)
        combo("action", "動作", [pn.ACTION_LABELS[a] for a in pn.ACTIONS], pn.ACTIONS.index(part.action), self._vis)
        entry("device", {"LAMP": "監視デバイス（例: M0, X10, D0）", "BUTTON": "書込デバイス"}.get(t, "デバイス（ワード 例: D0）"),
              part.device)
        entry("setValue", "書込む値", part.setValue)
        entry("lamp", "ランプデバイス（空欄なら書込デバイスで点灯）", part.lampDevice)
        entry("minOn", "最小ON時間（ミリ秒）", str(part.minOnMs))
        combo("format", "データ形式", [FORMAT_LABELS[f] for f in pn.PART_FORMATS],
              pn.PART_FORMATS.index(part.format) if part.format in pn.PART_FORMATS else 0)
        entry("decimals", "小数点以下の桁数（例: 1 なら 123 → 12.3）", str(part.decimals))
        entry("unit", "単位", part.unit)
        entry("min", "入力の下限（空欄で制限なし）", part.min)
        entry("max", "入力の上限（空欄で制限なし）", part.max)
        entry("onText", "ON時の文字（空欄なら表示名）", part.onText)
        entry("offText", "OFF時の文字（空欄なら表示名）", part.offText)
        combo("onColor", "色" if t == "SWITCH" else "ON時の色", pn.COLOR_NAMES, pn.color_index(part.onColor))
        combo("offColor", "OFF時の色", pn.COLOR_NAMES, pn.color_index(part.offColor))
        combo("textColor", "文字の色", pn.COLOR_NAMES, pn.color_index(part.textColor))
        names = ["%d. %s" % (i + 1, s.name) for i, s in enumerate(panel.screens)]
        combo("target", "切替先の画面", names, part.target)
        self.v_confirm = tk.BooleanVar(value=part.confirm)
        cb = ttk.Checkbutton(b, text="操作前に確認ダイアログを出す", variable=self.v_confirm)
        add("confirm", "", cb)
        self._vis()

    def action(self):
        return pn.ACTIONS[[pn.ACTION_LABELS[a] for a in pn.ACTIONS].index(self.v["action"].get())]

    def _vis(self):
        t = self.p.type
        a = self.action()
        btn = t == "BUTTON"
        num = t in ("NUMBER", "INPUT")
        word_btn = btn and a == "WORD_SET"
        show = {"label": True, "action": btn, "device": t not in ("TEXT", "SWITCH"), "setValue": word_btn,
                "lamp": btn, "minOn": btn and a == "MOMENTARY", "format": num or word_btn,
                "decimals": num or word_btn, "unit": num, "min": t == "INPUT", "max": t == "INPUT",
                "onText": t == "LAMP" or btn, "offText": t == "LAMP" or btn,
                "onColor": t in ("LAMP", "BUTTON", "SWITCH"), "offColor": t == "LAMP" or btn,
                "textColor": t == "TEXT", "target": t == "SWITCH",
                "confirm": (btn and a != "MOMENTARY") or t == "INPUT"}
        for k, (lab, w) in self.rows.items():
            if show.get(k):
                lab.grid()
                w.grid()
            else:
                lab.grid_remove()
                w.grid_remove()

    def apply(self):
        n = self.p.copy()
        g = lambda k: self.v[k].get()

        def norm(s):
            s = s.strip()
            if not s:
                return ""
            try:
                return str(Device.parse(s))
            except ValueError:
                return s.upper()

        def to_int(s, d, lo, hi):
            try:
                return max(lo, min(hi, int(str(s).strip())))
            except ValueError:
                return d

        n.label = self.t_label.get("1.0", "end-1c")
        n.action = self.action()
        n.device = norm(g("device"))
        n.setValue = g("setValue").strip()
        n.lampDevice = norm(g("lamp"))
        n.minOnMs = to_int(g("minOn"), 500, 0, 5000)
        n.format = pn.PART_FORMATS[[FORMAT_LABELS[f] for f in pn.PART_FORMATS].index(g("format"))]
        n.decimals = to_int(g("decimals"), 0, 0, 6)
        n.unit = g("unit").strip()
        n.min, n.max = g("min").strip(), g("max").strip()
        n.onText, n.offText = g("onText"), g("offText")
        n.onColor = pn.COLORS[pn.COLOR_NAMES.index(g("onColor"))]
        n.offColor = n.onColor if n.type == "SWITCH" else pn.COLORS[pn.COLOR_NAMES.index(g("offColor"))]
        n.textColor = pn.COLORS[pn.COLOR_NAMES.index(g("textColor"))]
        n.target = [("%d. %s" % (i + 1, s.name)) for i, s in enumerate(self.panel.screens)].index(g("target")) \
            if self.panel.screens else 0
        n.confirm = self.v_confirm.get() and not (n.type == "BUTTON" and n.action == "MOMENTARY")
        if n.type == "BUTTON" and n.action == "WORD_SET" and n.format == "HEX16":
            n.decimals = 0
        err = n.validate(len(self.panel.screens))
        if err:
            messagebox.showerror(APP_NAME, err, parent=self)
            return None
        return n


# ================================================================ 操作画面ウィンドウ

class PanelWindow(tk.Toplevel):
    def __init__(self, app):
        super().__init__(app.root)
        self.app = app
        self.title("操作画面 - " + APP_NAME)
        self.screen_index = 0
        self.changed = False
        p = app.panel
        self.fit_orientation()
        self.configure(bg="#14181C")

        top = tk.Frame(self, bg="#14181C")
        top.pack(fill="x")
        self.status = tk.Label(top, text="", bg="#14181C", fg="#B0BEC5", font=(FONT, 11, "bold"), anchor="w",
                               cursor="hand2")
        self.status.pack(side="left", padx=8, pady=6)
        self.status.bind("<Button-1>", lambda e: self.status_clicked())
        self.btn_menu = tk.Button(top, text="メニュー ▾", command=self.show_menu, bg="#37474F", fg="white",
                                  relief="flat", font=(FONT, 10), padx=10)
        self.btn_menu.pack(side="right", padx=6, pady=4)
        self.btn_lock = tk.Button(top, text="", command=self.toggle_lock, relief="flat", font=(FONT, 10, "bold"),
                                  padx=10)
        self.btn_lock.pack(side="right", padx=2, pady=4)

        self.tabs = tk.Frame(self, bg="#14181C")
        self.tabs.pack(fill="x")
        self.editbar = tk.Frame(self, bg="#263238")
        rows = [tk.Frame(self.editbar, bg="#263238"), tk.Frame(self.editbar, bg="#263238")]
        for r in rows:
            r.pack(fill="x")
        for i, (text, cmd) in enumerate((("＋部品", self.add_part), ("設定", self.edit_selected),
                                         ("複製", self.duplicate), ("前面へ", self.bring_front),
                                         ("削除", self.delete_selected), ("画面追加", self.add_screen),
                                         ("画面の管理", self.manage_screens), ("縦/横", self.toggle_orientation))):
            tk.Button(rows[0 if i < 4 else 1], text=text, command=cmd, bg="#37474F", fg="white", relief="flat",
                      font=(FONT, 9), padx=6).pack(side="left", padx=2, pady=2)
        tk.Button(rows[1], text="✓ 編集終了", command=lambda: self.set_edit(False), bg="#00838F", fg="white",
                  relief="flat", font=(FONT, 9, "bold"), padx=8).pack(side="right", padx=4, pady=2)
        tk.Label(rows[0], text="ダブルクリックで設定\n右下のつまみで大きさ", bg="#263238", fg="#90A4AE",
                 font=(FONT, 8), justify="left").pack(side="left", padx=6)
        self.canvas = PanelCanvas(self, self)
        self.canvas.pack(fill="both", expand=True)
        self.protocol("WM_DELETE_WINDOW", self.close)
        self.bind("<FocusOut>", lambda e: self.canvas.release_momentary() if e.widget is self else None)
        self.bind("<Delete>", lambda e: self.delete_selected() if self.canvas.edit else None)
        self.rebuild_tabs()
        self.tick()

    # ---- 状態表示
    def tick(self):
        if not self.winfo_exists():
            return
        link = self.app.link
        c = link.client
        if c is None:
            self.status.configure(text="● 通信停止中（クリックで開始）", fg="#EF5350")
        elif link.fresh():
            self.status.configure(text="● 通信中", fg="#69F0AE")
        elif c.last_ok == 0:
            self.status.configure(text="● " + c.waiting_text(), fg="#B0BEC5")
        else:
            self.status.configure(text="● 応答なし %d秒" % (time.time() - c.last_ok), fg="#EF5350")
        self.app.check_auto_lock()
        u = self.app.unlocked
        self.btn_lock.configure(text="🔓 操作可" if u else "🔒 ロック中", bg="#5D4037" if u else "#37474F",
                                fg="#FFD54F" if u else "white")
        if not self.canvas.edit:
            self.canvas.redraw()
        self.after(200, self.tick)

    def status_clicked(self):
        if not self.app.link.running():
            self.app.start_comm(self)

    def message(self, msg):
        messagebox.showinfo(APP_NAME, msg, parent=self)

    def can_operate(self):
        if self.canvas.edit:
            return "編集中です"
        if not self.app.link.running():
            return "通信が停止しています"
        if not self.app.link.fresh():
            return "PLCと通信できていません（古い値で操作しないよう止めています）"
        if not self.app.unlocked:
            return "操作ロック中です。右上の「🔒 ロック中」を押して解除してください"
        return None

    def operated(self):
        self.app.last_operated = time.time()

    def toggle_lock(self):
        self.app.set_unlocked(not self.app.unlocked)

    # ---- 操作
    def tapped(self, p):
        if p.type == "SWITCH":
            self.switch_screen(p.target)
            return
        if p.type == "INPUT":
            self.request_input(p)
            return
        if p.type != "BUTTON":
            return
        mem = self.app.link.mem

        def act():
            why = self.can_operate()
            if why:
                self.message(why)
                return
            try:
                if p.action == "ALTERNATE":
                    p.write_bit(mem, not p.read_bit(mem))
                elif p.action == "SET":
                    p.write_bit(mem, True)
                elif p.action == "RESET":
                    p.write_bit(mem, False)
                elif p.action == "WORD_SET":
                    p.write_number(mem, p.setValue)
                self.operated()
            except ValueError as ex:
                self.message("設定エラー: %s" % ex)
            self.canvas.redraw()

        if p.confirm:
            what = (p.device + " に " + p.setValue + " を書込みます") if p.action == "WORD_SET" else \
                (p.device + " を切り替えます") if p.action == "ALTERNATE" else \
                (p.device + " を " + ("ON" if p.action == "SET" else "OFF") + " にします")
            if messagebox.askokcancel("操作確認", "「%s」\n%s" % (p.label, what), parent=self):
                act()
        else:
            act()

    def request_input(self, p):
        cur = p.display_value(self.app.link.mem)
        rng = ""
        if p.min or p.max:
            rng = "\n範囲: %s 〜 %s" % (p.min or "", p.max or "")
        s = simpledialog.askstring(p.label or "数値入力", "%s に書込む値%s%s\n（現在 %s）" % (
            p.device, (" [" + p.unit + "]") if p.unit else "", rng, cur), parent=self)
        if s is None:
            return
        err = p.check_input(s)
        if err:
            self.message(err)
            return
        if p.confirm and not messagebox.askokcancel("操作確認", "%s に %s を書込みます" % (p.device, s.strip()), parent=self):
            return
        why = self.can_operate()
        if why:
            self.message(why)
            return
        try:
            p.write_number(self.app.link.mem, s)
            self.operated()
        except ValueError as ex:
            self.message("設定エラー: %s" % ex)

    def switch_screen(self, i):
        self.canvas.release_momentary()
        self.screen_index = max(0, min(i, len(self.app.panel.screens) - 1))
        self.canvas.selected = None
        self.rebuild_tabs()
        self.canvas.redraw()

    def rebuild_tabs(self):
        for w in self.tabs.winfo_children():
            w.destroy()
        self._tab_buttons = []
        for i, s in enumerate(self.app.panel.screens):
            sel = i == self.screen_index
            b = tk.Button(self.tabs, text=s.name, command=lambda i=i: self.switch_screen(i), relief="flat",
                          bg="#37474F" if sel else "#14181C", fg="white" if sel else "#90A4AE",
                          font=(FONT, 10, "bold" if sel else "normal"), padx=8, pady=1)
            self._tab_buttons.append(b)
        self.layout_tabs()
        if not getattr(self, "_tabs_bound", False):
            self.tabs.bind("<Configure>", lambda e: self.layout_tabs())
            self._tabs_bound = True

    def layout_tabs(self):
        """タブを窓の幅で折り返して並べる（画面が多くても全部見えるように）"""
        btns = getattr(self, "_tab_buttons", [])
        width = max(self.tabs.winfo_width(), self.winfo_width(), 200)
        x = y = 0
        row_h = 0
        for b in btns:
            bw, bh = b.winfo_reqwidth(), b.winfo_reqheight()
            if x > 0 and x + bw > width:
                x = 0
                y += row_h + 1
            b.place(x=x, y=y)
            x += bw + 1
            row_h = max(row_h, bh)
        h = y + row_h + 2 if btns else 1
        if int(self.tabs.cget("height") or 0) != h:
            self.tabs.configure(height=h)

    # ---- メニュー
    def show_menu(self):
        m = tk.Menu(self, tearoff=0)
        m.add_command(label="画面を編集する" if not self.canvas.edit else "編集を終える",
                      command=lambda: self.set_edit(not self.canvas.edit))
        m.add_separator()
        m.add_command(label="書き出し（ファイルに保存）…", command=self.export_panel)
        m.add_command(label="読み込み（ファイルから）…", command=self.import_panel)
        m.add_command(label="JSONをクリップボードにコピー", command=self.copy_json)
        m.add_command(label="クリップボードのJSONを読み込む", command=self.paste_json)
        m.add_separator()
        m.add_command(label=("✓ " if self.app.store["showDev"] else "　") + "デバイス番号を表示", command=self.toggle_dev)
        m.add_command(label="通信ログ", command=self.app.show_log)
        m.tk_popup(self.btn_menu.winfo_rootx(), self.btn_menu.winfo_rooty() + self.btn_menu.winfo_height())

    def toggle_dev(self):
        self.app.store["showDev"] = not self.app.store["showDev"]
        self.app.store.save()
        self.canvas.redraw()

    def set_edit(self, e):
        self.canvas.release_momentary()
        self.canvas.edit = e
        self.canvas.selected = None
        if e:
            self.editbar.pack(fill="x", before=self.canvas)
        else:
            self.editbar.pack_forget()
            if self.changed:
                self.save()
        self.canvas.redraw()

    def save(self):
        self.app.store.save_panel(self.app.panel)
        self.changed = False

    def cur(self):
        return self.app.panel.screens[self.screen_index]

    def selection_changed(self):
        pass

    # ---- 編集
    def add_part(self):
        m = tk.Menu(self, tearoff=0)
        for t in pn.TYPES:
            m.add_command(label=pn.TYPE_LABELS[t], command=lambda t=t: self._add(t))
        m.tk_popup(self.winfo_pointerx(), self.winfo_pointery())

    def _add(self, t):
        panel = self.app.panel
        w, h = 150, 70
        if t == "BUTTON":
            h = 90
        if t in ("NUMBER", "INPUT", "TEXT"):
            w = 320
        p = pn.Part(t, 0, 0, min(w, panel.width()), h)
        self.place_free(p)
        p.label = {"LAMP": "ランプ", "BUTTON": "ボタン", "NUMBER": "表示", "INPUT": "入力", "TEXT": "文字",
                   "SWITCH": "画面切替"}[t]
        if t == "SWITCH":
            p.onColor = p.offColor = pn.COLORS[3]
        n = PartDialog(self, p, panel, True).show()
        if n:
            self.cur().parts.append(n)
            self.changed = True
            self.canvas.select(n)

    def place_free(self, p):
        panel = self.app.panel
        for y in range(0, panel.height() - p.h + 1, pn.GRID):
            for x in range(0, panel.width() - p.w + 1, pn.GRID):
                if not any(x < o.x + o.w and x + p.w > o.x and y < o.y + o.h and y + p.h > o.y for o in self.cur().parts):
                    p.x, p.y = x, y
                    return
        p.x = p.y = 0

    def edit_part(self, p):
        n = PartDialog(self, p, self.app.panel, False).show()
        if n:
            parts = self.cur().parts
            if p in parts:
                parts[parts.index(p)] = n
            self.changed = True
            self.canvas.select(n)

    def edit_selected(self):
        if self.canvas.selected:
            self.edit_part(self.canvas.selected)

    def duplicate(self):
        s = self.canvas.selected
        if not s:
            return
        c = s.copy()
        c.x += pn.GRID * 2
        c.y += pn.GRID * 2
        self.app.panel.clamp(c)
        self.cur().parts.append(c)
        self.changed = True
        self.canvas.select(c)

    def bring_front(self):
        s = self.canvas.selected
        if s and s in self.cur().parts:
            self.cur().parts.remove(s)
            self.cur().parts.append(s)
            self.changed = True
            self.canvas.redraw()

    def delete_selected(self):
        s = self.canvas.selected
        if not s:
            return
        if messagebox.askokcancel("削除", "「%s」を削除しますか？" % (s.label or pn.TYPE_LABELS[s.type]), parent=self):
            self.cur().parts.remove(s)
            self.changed = True
            self.canvas.select(None)

    def add_screen(self):
        name = simpledialog.askstring("画面を追加", "画面の名前", initialvalue="画面%d" % (len(self.app.panel.screens) + 1),
                                      parent=self)
        if name:
            self.app.panel.screens.append(pn.Screen(name.strip() or "画面"))
            self.changed = True
            self.switch_screen(len(self.app.panel.screens) - 1)

    def manage_screens(self):
        ScreensDialog(self).show()
        self.screen_index = min(self.screen_index, len(self.app.panel.screens) - 1)
        self.rebuild_tabs()
        self.canvas.redraw()

    def toggle_orientation(self):
        if self.app.panel.is_custom():
            self.message("PC用の大きい画面（%d×%d）は縦横を切り替えられません" % (self.app.panel.width(), self.app.panel.height()))
            return
        remember_window(self, self.app.store, self.orient_key())
        self.app.panel.set_landscape(not self.app.panel.landscape)
        self.changed = True
        self.fit_orientation()
        self.canvas.redraw()

    # ---- 書き出し・読み込み
    def export_name(self):
        names = "・".join(s.name for s in self.app.panel.screens)
        bad = '\\/:*?"<>|'
        names = "".join("_" if ch in bad else ch for ch in names)[:60]
        return "操作画面_%s_%s.json" % (names, datetime.now().strftime("%Y-%m-%d_%H%M"))

    def export_panel(self):
        path = filedialog.asksaveasfilename(parent=self, title="操作画面を書き出す", defaultextension=".json",
                                            initialfile=self.export_name(), initialdir=self.app.store["lastDir"] or None,
                                            filetypes=[("操作画面", "*.json")])
        if not path:
            return
        with open(path, "w", encoding="utf-8") as f:
            f.write(self.app.panel.to_json())
        self.app.store["lastDir"] = os.path.dirname(path)
        self.app.store.save()
        self.message("保存しました\n" + path)

    def import_panel(self):
        path = filedialog.askopenfilename(parent=self, title="操作画面を読み込む", initialdir=self.app.store["lastDir"] or None,
                                          filetypes=[("操作画面", "*.json"), ("すべて", "*.*")])
        if not path:
            return
        try:
            with open(path, encoding="utf-8-sig") as f:
                p = pn.Panel.from_json(f.read())
        except (OSError, ValueError, KeyError, TypeError) as e:
            self.message("読み込めませんでした: %s" % e)
            return
        self.app.store["lastDir"] = os.path.dirname(path)
        self.confirm_replace(p, os.path.basename(path))

    def copy_json(self):
        self.clipboard_clear()
        self.clipboard_append(self.app.panel.to_json())
        self.message("クリップボードにコピーしました")

    def paste_json(self):
        try:
            p = pn.Panel.from_json(self.clipboard_get())
        except (tk.TclError, ValueError, KeyError, TypeError) as e:
            self.message("読み込めませんでした: %s" % e)
            return
        self.confirm_replace(p, "クリップボード")

    def confirm_replace(self, p, src):
        n = sum(len(s.parts) for s in p.screens)
        if not messagebox.askokcancel("操作画面を置き換えますか？", "%s\n画面 %d・部品 %d\n\n今の操作画面は上書きされます。" % (
                src, len(p.screens), n), parent=self):
            return
        self.canvas.release_momentary()
        self.app.panel = p
        self.save()
        self.screen_index = 0
        self.fit_orientation()
        self.rebuild_tabs()
        self.canvas.selected = None
        self.canvas.redraw()

    def orient_key(self):
        p = self.app.panel
        return "panel_c" if p.is_custom() else ("panel_l" if p.landscape else "panel_p")

    def fit_orientation(self):
        """縦横に合った大きさにする（前回その向きで使った大きさがあればそれ）"""
        p = self.app.panel
        if p.is_custom():
            w = 1280
            h = int(w * p.height() / p.width()) + 90
            place_window(self, self.app.store, "panel_c", w, h, 640, 400)
        elif self.app.panel.landscape:
            place_window(self, self.app.store, "panel_l", 760, 520, 480, 320)
        else:
            place_window(self, self.app.store, "panel_p", 440, 760, 300, 420)

    def close(self):
        remember_window(self, self.app.store, self.orient_key())
        self.app.store.save()
        self.canvas.release_momentary()
        if self.changed:
            self.save()
        self.app.panel_win = None
        self.destroy()


class ScreensDialog(Dialog):
    def __init__(self, win):
        super().__init__(win, "画面の管理", "閉じる")
        self.win = win
        self.lb = tk.Listbox(self.body, height=10, width=30, font=(FONT, 10))
        self.lb.grid(row=0, column=0, rowspan=6, sticky="nsew")
        for i, (t, c) in enumerate((("名前を変更", self.rename), ("上へ", lambda: self.move(-1)),
                                     ("下へ", lambda: self.move(1)), ("削除", self.remove))):
            ttk.Button(self.body, text=t, command=c).grid(row=i, column=1, sticky="we", padx=6, pady=2)
        self.fill()

    def fill(self, sel=None):
        self.lb.delete(0, "end")
        for i, s in enumerate(self.win.app.panel.screens):
            self.lb.insert("end", "%d. %s" % (i + 1, s.name))
        if sel is not None:
            self.lb.selection_set(sel)

    def idx(self):
        s = self.lb.curselection()
        return s[0] if s else None

    def rename(self):
        i = self.idx()
        if i is None:
            return
        s = self.win.app.panel.screens[i]
        n = simpledialog.askstring("名前を変更", "画面の名前", initialvalue=s.name, parent=self)
        if n:
            s.name = n.strip() or s.name
            self.win.changed = True
            self.fill(i)

    def move(self, d):
        i = self.idx()
        ss = self.win.app.panel.screens
        if i is None or not 0 <= i + d < len(ss):
            return
        j = i + d
        ss[i], ss[j] = ss[j], ss[i]
        for s in ss:
            for p in s.parts:
                if p.target == i:
                    p.target = j
                elif p.target == j:
                    p.target = i
        self.win.changed = True
        self.fill(j)

    def remove(self):
        i = self.idx()
        ss = self.win.app.panel.screens
        if i is None or len(ss) <= 1:
            return
        if not messagebox.askokcancel("画面を削除", "「%s」と、その上の部品を削除しますか？" % ss[i].name, parent=self):
            return
        del ss[i]
        for s in ss:
            for p in s.parts:
                if p.target == i:
                    p.target = 0
                elif p.target > i:
                    p.target -= 1
        self.win.changed = True
        self.fill()


# ================================================================ メイン画面（一覧）

class App:
    def __init__(self, root, store=None):
        self.root = root
        self.store = store or Store()
        self.items = self.store.items()
        self.panel = self.store.panel()
        self.link = Link(self)
        self.unlocked = False
        self.last_operated = 0
        self.panel_win = None
        self.log_win = None

        root.title("%s %s（PC版）" % (APP_NAME, VERSION))
        init_scale(root)
        place_window(root, self.store, "main", 800, 600, 620, 420)
        style = ttk.Style(root)
        try:
            style.theme_use("vista" if sys.platform == "win32" else "clam")
        except tk.TclError:
            pass
        for name in ("TkDefaultFont", "TkTextFont", "TkMenuFont", "TkHeadingFont"):
            try:
                tkfont.nametofont(name).configure(family=FONT, size=10)
            except tk.TclError:
                pass
        # 行の高さは文字の高さから決める（表示倍率が大きいと文字が重なるため）
        style.configure("Treeview", rowheight=int(tkfont.Font(family=FONT, size=11).metrics("linespace") * 1.45),
                        font=(FONT, 11))
        style.configure("Treeview.Heading", font=(FONT, 10, "bold"))

        top = ttk.Frame(root, padding=(10, 8, 10, 0))
        top.pack(fill="x")
        self.btn_start = ttk.Button(top, text="通信開始", command=self.toggle_comm, width=12)
        self.btn_start.pack(side="left")
        ttk.Button(top, text="▶ 操作画面を開く", command=self.open_panel).pack(side="left", padx=6)
        ttk.Button(top, text="⚙ 設定", command=self.open_settings).pack(side="left")
        ttk.Button(top, text="ログ", command=self.show_log).pack(side="left", padx=6)
        ttk.Button(top, text="使い方", command=self.show_help).pack(side="left")

        info = ttk.Frame(root, padding=(10, 6, 10, 0))
        info.pack(fill="x")
        self.lbl_plc = ttk.Label(info, text="", foreground="#455A64")
        self.lbl_plc.pack(anchor="w")
        self.lbl_status = tk.Label(info, text="", font=(FONT, 12, "bold"), anchor="w")
        self.lbl_status.pack(fill="x", pady=(2, 0))

        lockf = ttk.Frame(root, padding=(10, 4, 10, 4))
        lockf.pack(fill="x")
        self.v_unlock = tk.BooleanVar(value=False)
        ttk.Checkbutton(lockf, text="操作ロック解除（2分操作がないと自動でロック）", variable=self.v_unlock,
                        command=lambda: self.set_unlocked(self.v_unlock.get())).pack(side="left")

        mid = ttk.Frame(root, padding=(10, 0, 10, 0))
        mid.pack(fill="both", expand=True)
        cols = ("name", "dev", "fmt", "val", "dir")
        self.tree = ttk.Treeview(mid, columns=cols, show="headings", selectmode="browse")
        for c, t, w, a in (("name", "名前", 220, "w"), ("dev", "デバイス", 90, "w"), ("fmt", "形式", 120, "w"),
                           ("val", "値", 140, "e"), ("dir", "区分", 120, "w")):
            self.tree.heading(c, text=t)
            self.tree.column(c, width=S(w), minwidth=S(50), anchor=a)
        sb = ttk.Scrollbar(mid, command=self.tree.yview)
        self.tree.configure(yscrollcommand=sb.set)
        sb.pack(side="right", fill="y")
        self.tree.pack(fill="both", expand=True)
        self.tree.tag_configure("stale", foreground="#90A4AE")
        self.tree.tag_configure("on", foreground="#2E7D32")
        self.tree.bind("<Double-1>", lambda e: self.edit_item())
        self.tree.bind("<<TreeviewSelect>>", lambda e: self.update_buttons())

        bot = ttk.Frame(root, padding=10)
        bot.pack(fill="x", side="bottom", before=mid)   # 窓が小さくてもボタンが隠れないように
        for t, c in (("＋追加", self.add_item), ("編集", self.edit_item), ("削除", self.delete_item),
                     ("↑", lambda: self.move_item(-1)), ("↓", lambda: self.move_item(1))):
            ttk.Button(bot, text=t, command=c, width=6 if len(t) <= 2 else 8).pack(side="left", padx=2)
        self.btn_off = ttk.Button(bot, text="OFF", command=lambda: self.write_bit(False), width=6)
        self.btn_off.pack(side="right", padx=2)
        self.btn_on = ttk.Button(bot, text="ON", command=lambda: self.write_bit(True), width=6)
        self.btn_on.pack(side="right", padx=2)
        self.btn_val = ttk.Button(bot, text="値を変更…", command=self.write_value)
        self.btn_val.pack(side="right", padx=2)

        root.protocol("WM_DELETE_WINDOW", self.quit)
        self.fill_tree()
        self.update_info()
        self.root.after(1000, self.heartbeat_tick)
        self.root.after(300, self.tick)
        if self.store["autoStart"]:
            self.root.after(300, lambda: self.start_comm(self.root))
        if self.store["openPanelOnStart"]:
            self.root.after(500, self.open_panel)

    # ---- 一覧
    def fill_tree(self, select=None):
        self.tree.delete(*self.tree.get_children())
        for i, it in enumerate(self.items):
            self.tree.insert("", "end", iid=str(i), values=(it.name, it.device_text, FORMAT_LABELS[it.format], "",
                                                           "PC → PLC（変更可）" if it.writable else "PLC → PC（表示）"))
        if select is not None and 0 <= select < len(self.items):
            self.tree.selection_set(str(select))
        self.update_values()

    def update_values(self):
        fresh = self.link.fresh()
        for i, it in enumerate(self.items):
            try:
                v = it.read(self.link.mem) if (self.link.running() or it.writable) else "----"
            except ValueError:
                v = "----"
            tags = () if fresh else ("stale",)
            if fresh and v == "ON":
                tags = ("on",)
            if self.tree.exists(str(i)):
                self.tree.set(str(i), "val", v)
                self.tree.item(str(i), tags=tags)

    def sel_index(self):
        s = self.tree.selection()
        return int(s[0]) if s else None

    def add_item(self):
        it = ItemDialog(self.root).show()
        if it:
            self.items.append(it)
            self.store.save_items(self.items)
            self.fill_tree(len(self.items) - 1)

    def edit_item(self):
        i = self.sel_index()
        if i is None:
            return
        it = ItemDialog(self.root, self.items[i]).show()
        if it:
            self.items[i] = it
            self.store.save_items(self.items)
            self.fill_tree(i)

    def delete_item(self):
        i = self.sel_index()
        if i is None:
            return
        if messagebox.askokcancel(APP_NAME, "「%s」を一覧から消しますか？" % self.items[i].name, parent=self.root):
            del self.items[i]
            self.store.save_items(self.items)
            self.fill_tree()

    def move_item(self, d):
        i = self.sel_index()
        if i is None or not 0 <= i + d < len(self.items):
            return
        self.items[i], self.items[i + d] = self.items[i + d], self.items[i]
        self.store.save_items(self.items)
        self.fill_tree(i + d)

    # ---- 操作（一覧から）
    def can_operate(self):
        if not self.link.fresh():
            return "PLCと通信できていません"
        if not self.unlocked:
            return "操作ロック中です"
        return None

    def update_buttons(self):
        i = self.sel_index()
        it = self.items[i] if i is not None else None
        ok = it is not None and it.writable and self.can_operate() is None
        bit = it is not None and it.format == "BIT"
        self.btn_on.state(["!disabled"] if ok and bit else ["disabled"])
        self.btn_off.state(["!disabled"] if ok and bit else ["disabled"])
        self.btn_val.state(["!disabled"] if ok and not bit else ["disabled"])

    def write_bit(self, on):
        i = self.sel_index()
        if i is None:
            return
        it = self.items[i]
        why = self.can_operate()
        if why:
            messagebox.showinfo(APP_NAME, why, parent=self.root)
            return
        if not messagebox.askokcancel("操作確認", "%s（%s）を %s にします。\nPLCへすぐ書込みます。実機の安全を確認してください。" % (
                it.name, it.device_text, "ON" if on else "OFF"), parent=self.root):
            return
        d = it.device()
        self.link.mem.local_write_bits(d.code, d.number, [on])
        self.last_operated = time.time()

    def write_value(self):
        i = self.sel_index()
        if i is None:
            return
        it = self.items[i]
        s = simpledialog.askstring(it.name, "%s（%s）に書込む値\n（現在 %s）\nPLCへすぐ書込みます。" % (
            it.device_text, FORMAT_LABELS[it.format], it.read(self.link.mem)), parent=self.root)
        if s is None:
            return
        try:
            w = it.parse_words(s)
        except ValueError as e:
            messagebox.showerror(APP_NAME, "値が正しくありません: %s" % e, parent=self.root)
            return
        why = self.can_operate()
        if why:
            messagebox.showinfo(APP_NAME, why, parent=self.root)
            return
        d = it.device()
        self.link.mem.local_write_words(d.code, d.number, w)
        self.last_operated = time.time()

    # ---- ロック
    def set_unlocked(self, u):
        self.unlocked = u
        self.v_unlock.set(u)
        if u:
            self.last_operated = time.time()
        self.update_buttons()

    def check_auto_lock(self):
        if self.unlocked and time.time() - self.last_operated > AUTO_LOCK_SEC:
            self.set_unlocked(False)

    # ---- 通信
    def toggle_comm(self):
        if self.link.running():
            if self.panel_win:
                self.panel_win.canvas.release_momentary()
            self.link.stop()
        else:
            self.start_comm(self.root)
        self.update_info()

    def start_comm(self, parent):
        self.link.start(parent)
        self.update_info()

    def heartbeat_tick(self):
        self.link.heartbeat()
        self.root.after(1000, self.heartbeat_tick)

    def update_info(self):
        s = self.store
        self.lbl_plc.configure(text="PLC: %s：%s（%s）%s" % (s["plcIp"], s["plcPort"], "UDP" if s["plcUdp"] else "TCP",
                                                            "　リモートパスワード使用" if s["usePw"] else ""))
        self.btn_start.configure(text="通信停止" if self.link.running() else "通信開始")

    def tick(self):
        c = self.link.client
        if c is None:
            self.lbl_status.configure(text="停止中", fg=C_GRAY)
        elif self.link.fresh():
            self.lbl_status.configure(text="通信中  %s ／ 読出 %d回" % (c.peer_text(), c.request_count), fg=C_ON)
        elif c.last_ok == 0:
            self.lbl_status.configure(text=c.waiting_text(), fg=C_ERR if c.auth_failed else C_GRAY)
        else:
            self.lbl_status.configure(text="PLCから応答なし %d秒" % (time.time() - c.last_ok), fg=C_ERR)
        self.check_auto_lock()
        self.update_values()
        self.update_buttons()
        self.update_info()
        self.root.after(300, self.tick)

    # ---- 画面
    def open_panel(self):
        if self.panel_win and self.panel_win.winfo_exists():
            self.panel_win.lift()
            return
        self.panel_win = PanelWindow(self)

    def open_settings(self):
        before = json.dumps(self.store.data, sort_keys=True, default=str)
        if SettingsDialog(self.root, self.store).show():
            after = json.dumps(self.store.data, sort_keys=True, default=str)
            if self.link.running() and before != after:
                self.link.session_pw = None if self.store["usePw"] else self.link.session_pw
                self.link.stop()
                self.start_comm(self.root)
            self.update_info()

    def show_log(self):
        if self.log_win and self.log_win.winfo_exists():
            self.log_win.lift()
            return
        self.log_win = LogWindow(self)

    def show_help(self):
        messagebox.showinfo("使い方", (
            "1. GX Works3「Ethernetポート → 相手機器接続構成設定」にSLMP接続機器"
            "（UDP、ポート例 5010、交信データコード バイナリ）を追加してPLCへ書込み・リセット\n"
            "2. 「⚙ 設定」でPLCのIP・ポートを入れ、「通信開始」\n"
            "3. 見たい・変えたいPLCのデバイスを一覧に追加、または「操作画面」で画面を作る\n\n"
            "・操作画面のファイルはスマホ版と同じ形式です（書き出し・読み込みで共有できます）\n"
            "・X、Y、SM、SDへの書込みは安全のため禁止しています\n"
            "・通信できていないとき、ロック中は操作できません\n"
            "・非常停止など安全に関わる操作には使わないでください"), parent=self.root)

    def quit(self):
        remember_window(self.root, self.store, "main")
        if self.log_win and self.log_win.winfo_exists():
            remember_window(self.log_win, self.store, "log")
        self.store.save()
        if self.panel_win:
            self.panel_win.close()
        self.link.stop()
        self.root.destroy()


def main():
    try:  # 高DPI画面でぼやけないように（Tkを作る前に）
        from ctypes import windll
        windll.shcore.SetProcessDpiAwareness(1)
    except Exception:
        pass
    root = tk.Tk()
    App(root)
    root.mainloop()


if __name__ == "__main__":
    main()
