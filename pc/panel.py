# -*- coding: utf-8 -*-
"""操作画面（GOT風）のデータ。スマホ版と同じJSON形式で読み書きする。"""
import json
import math

from slmpcore import Device, format_words, to_s16, words_to_int, int_to_words, words_to_float, float_to_words

PORTRAIT_W, PORTRAIT_H = 360, 640
GRID = 10

COLOR_NAMES = ["緑", "赤", "黄", "青", "水色", "橙", "紫", "灰", "暗い灰", "白", "黒"]
COLORS = [0xFF2E7D32, 0xFFC62828, 0xFFF9A825, 0xFF1565C0, 0xFF0097A7, 0xFFEF6C00,
          0xFF6A1B9A, 0xFF757575, 0xFF37474F, 0xFFFFFFFF, 0xFF000000]

TYPES = ["LAMP", "BUTTON", "NUMBER", "INPUT", "TEXT", "SWITCH"]
TYPE_LABELS = {"LAMP": "ランプ", "BUTTON": "ボタン", "NUMBER": "数値表示", "INPUT": "数値入力",
               "TEXT": "文字", "SWITCH": "画面切替"}
ACTIONS = ["MOMENTARY", "ALTERNATE", "SET", "RESET", "WORD_SET"]
ACTION_LABELS = {"MOMENTARY": "モーメンタリ（押している間ON）", "ALTERNATE": "オルタネート（押すたびON/OFF）",
                 "SET": "セット（ONにする）", "RESET": "リセット（OFFにする）",
                 "WORD_SET": "値書込（ワードに値を入れる）"}
PART_FORMATS = ["S16", "U16", "HEX16", "S32", "FLOAT32"]


def color_index(c):
    try:
        return COLORS.index(c)
    except ValueError:
        return 0


def snap(v):
    return int(round(v / GRID)) * GRID


def _parse_color(s, default):
    try:
        t = str(s).strip()
        if t.startswith("#"):
            t = t[1:]
        if len(t) == 6:
            t = "FF" + t
        if len(t) != 8:
            return default
        return int(t, 16)
    except ValueError:
        return default


class Part:
    def __init__(self, type_, x=0, y=0, w=100, h=60):
        self.type = type_
        self.x, self.y, self.w, self.h = x, y, w, h
        self.label = ""
        self.device = ""
        self.lampDevice = ""
        self.action = "MOMENTARY"
        self.setValue = "1"
        self.minOnMs = 500
        self.onText = ""
        self.offText = ""
        self.onColor = COLORS[0]
        self.offColor = COLORS[8]
        self.textColor = 0xFFFFFFFF
        self.format = "S16"
        self.decimals = 0
        self.unit = ""
        self.min = ""
        self.max = ""
        self.confirm = False
        self.target = 0

    def copy(self):
        p = Part(self.type)
        p.__dict__.update(self.__dict__)
        return p

    def contains(self, px, py):
        return self.x <= px < self.x + self.w and self.y <= py < self.y + self.h

    def operable(self):
        return self.type in ("BUTTON", "INPUT")

    # ---- 値の読み書き
    def is_on(self, mem):
        try:
            if self.type == "BUTTON" and self.action == "WORD_SET" and not self.lampDevice:
                cur = self.read_number(mem, self.device)
                want = self.parse_number(self.setValue)
                return cur is not None and want is not None and abs(cur - want) < 1e-9
            dev = self.lampDevice if (self.type == "BUTTON" and self.lampDevice) else self.device
            if not dev:
                return False
            d = Device.parse(dev)
            if d.bit:
                return mem.read_bits(d.code, d.number, 1)[0]
            return mem.read_words(d.code, d.number, 1)[0] != 0
        except (ValueError, IndexError):
            return False

    def write_bit(self, mem, on, momentary=False):
        d = Device.parse(self.device)
        mem.local_write_bits(d.code, d.number, [on], momentary)

    def read_bit(self, mem):
        d = Device.parse(self.device)
        return mem.read_bits(d.code, d.number, 1)[0]

    def read_number(self, mem, dev):
        try:
            d = Device.parse(dev)
            if d.bit:
                return 1.0 if mem.read_bits(d.code, d.number, 1)[0] else 0.0
            w = mem.read_words(d.code, d.number, format_words(self.format))
            f = self.format
            if f in ("U16", "HEX16"):
                raw = w[0] & 0xFFFF
            elif f == "S32":
                raw = words_to_int(w)
            elif f == "FLOAT32":
                return float(words_to_float(w))
            else:
                raw = to_s16(w[0])
            return float(raw) if f == "HEX16" else raw / (10 ** self.decimals)
        except (ValueError, IndexError):
            return None

    def display_value(self, mem):
        v = self.read_number(mem, self.device)
        if v is None:
            return "----"
        if self.format == "HEX16":
            return "%04X" % (int(v) & 0xFFFF)
        return ("%." + str(self.decimals) + "f") % v

    def parse_number(self, s):
        try:
            t = str(s).strip()
            if self.format == "HEX16":
                t = t.upper()
                if t.startswith("H"):
                    t = t[1:]
                if t.startswith("0X"):
                    t = t[2:]
                return float(int(t, 16))
            return float(t)
        except ValueError:
            return None

    def _scaled(self, raw):
        if self.decimals == 0 or self.format == "HEX16":
            return str(raw)
        return ("%." + str(self.decimals) + "f") % (raw / 10 ** self.decimals)

    def check_input(self, s):
        v = self.parse_number(s)
        if v is None or math.isnan(v) or math.isinf(v):
            return "数値が正しくありません"
        lo = self.parse_number(self.min) if self.min else None
        hi = self.parse_number(self.max) if self.max else None
        if lo is not None and v < lo - 1e-9:
            return "下限 %s より小さい値です" % self.min
        if hi is not None and v > hi + 1e-9:
            return "上限 %s より大きい値です" % self.max
        if self.format == "FLOAT32":
            return None
        raw = v if self.format == "HEX16" else round(v * 10 ** self.decimals)
        if self.format in ("U16", "HEX16"):
            if raw < 0 or raw > 65535:
                return "0〜%s の範囲で入力してください" % self._scaled(65535)
        elif self.format == "S32":
            if raw < -2 ** 31 or raw > 2 ** 31 - 1:
                return "32bitの範囲外です"
        elif raw < -32768 or raw > 32767:
            return "%s〜%s の範囲で入力してください" % (self._scaled(-32768), self._scaled(32767))
        return None

    def write_number(self, mem, s):
        d = Device.parse(self.device)
        v = self.parse_number(s)
        f = self.format
        if f == "FLOAT32":
            w = float_to_words(v)
        elif f == "S32":
            w = int_to_words(int(round(v * 10 ** self.decimals)))
        elif f == "HEX16":
            w = [to_s16(int(v))]
        else:
            w = [to_s16(int(round(v * 10 ** self.decimals)))]
        mem.local_write_words(d.code, d.number, w)

    def validate(self, screen_count):
        try:
            t = self.type
            if t == "LAMP":
                if not self.device:
                    return "監視デバイスを入力してください"
                Device.parse(self.device)
            elif t == "BUTTON":
                if not self.device:
                    return "書込デバイスを入力してください"
                d = Device.parse(self.device)
                bit_action = self.action != "WORD_SET"
                if bit_action and not d.bit:
                    return "この動作にはビットデバイス（M、Lなど）を指定してください"
                if not bit_action:
                    if d.bit:
                        return "値書込にはワードデバイス（Dなど）を指定してください"
                    e = self.check_input(self.setValue)
                    if e:
                        return "書込値: " + e
                if d.write_blocked_reason():
                    return d.write_blocked_reason()
                if self.lampDevice:
                    Device.parse(self.lampDevice)
            elif t in ("NUMBER", "INPUT"):
                if not self.device:
                    return "デバイスを入力してください"
                nd = Device.parse(self.device)
                if nd.bit:
                    return "ワードデバイス（D、Wなど）を指定してください"
                if t == "INPUT" and nd.write_blocked_reason():
                    return nd.write_blocked_reason()
                if self.min and self.parse_number(self.min) is None:
                    return "下限が数値ではありません"
                if self.max and self.parse_number(self.max) is None:
                    return "上限が数値ではありません"
            elif t == "SWITCH":
                if self.target < 0 or self.target >= screen_count:
                    return "切替先の画面を選んでください"
        except ValueError as e:
            return str(e)
        return None

    def device_summary(self):
        if self.type == "BUTTON":
            return self.device + (" / ランプ " + self.lampDevice if self.lampDevice else "")
        if self.type == "SWITCH":
            return "→ 画面%d" % (self.target + 1)
        if self.type == "TEXT":
            return ""
        return self.device

    def devices_to_read(self):
        """読出しが必要なデバイス（名前, ワード数）"""
        out = []
        for d in (self.device, self.lampDevice):
            if d and self.type != "SWITCH" and self.type != "TEXT":
                out.append((d, format_words(self.format)))
        return out

    # ---- JSON
    def to_dict(self):
        return {"type": self.type, "x": self.x, "y": self.y, "w": self.w, "h": self.h,
                "label": self.label, "device": self.device, "lampDevice": self.lampDevice,
                "action": self.action, "setValue": self.setValue, "minOnMs": self.minOnMs,
                "onText": self.onText, "offText": self.offText,
                "onColor": "#%08X" % self.onColor, "offColor": "#%08X" % self.offColor,
                "textColor": "#%08X" % self.textColor, "format": self.format,
                "decimals": self.decimals, "unit": self.unit, "min": self.min, "max": self.max,
                "confirm": self.confirm, "target": self.target}

    @staticmethod
    def from_dict(o):
        t = o["type"]
        if t not in TYPES:
            raise ValueError("unknown type")
        p = Part(t, int(o.get("x", 0)), int(o.get("y", 0)), int(o.get("w", 100)), int(o.get("h", 60)))
        p.label = str(o.get("label", ""))
        p.device = str(o.get("device", "")).upper()
        p.lampDevice = str(o.get("lampDevice", "")).upper()
        a = o.get("action", "MOMENTARY")
        p.action = a if a in ACTIONS else "MOMENTARY"
        p.setValue = str(o.get("setValue", "1"))
        p.minOnMs = max(0, min(5000, int(o.get("minOnMs", 500))))
        p.onText = str(o.get("onText", ""))
        p.offText = str(o.get("offText", ""))
        p.onColor = _parse_color(o.get("onColor", ""), COLORS[0])
        p.offColor = _parse_color(o.get("offColor", ""), COLORS[8])
        p.textColor = _parse_color(o.get("textColor", ""), 0xFFFFFFFF)
        f = o.get("format", "S16")
        p.format = f if f in PART_FORMATS else "S16"
        p.decimals = max(0, min(6, int(o.get("decimals", 0))))
        p.unit = str(o.get("unit", ""))
        p.min = str(o.get("min", ""))
        p.max = str(o.get("max", ""))
        p.confirm = bool(o.get("confirm", False))
        p.target = max(0, int(o.get("target", 0)))
        return p


class Screen:
    def __init__(self, name):
        self.name = name
        self.parts = []


class Panel:
    def __init__(self):
        self.landscape = False
        self.screens = []

    def width(self):
        return PORTRAIT_H if self.landscape else PORTRAIT_W

    def height(self):
        return PORTRAIT_W if self.landscape else PORTRAIT_H

    def clamp(self, p):
        p.w = min(max(p.w, GRID * 2), self.width())
        p.h = min(max(p.h, GRID * 2), self.height())
        p.x = min(max(p.x, 0), self.width() - p.w)
        p.y = min(max(p.y, 0), self.height() - p.h)

    def set_landscape(self, l):
        if l == self.landscape:
            return
        sx = (PORTRAIT_H if l else PORTRAIT_W) / self.width()
        sy = (PORTRAIT_W if l else PORTRAIT_H) / self.height()
        self.landscape = l
        for s in self.screens:
            for p in s.parts:
                p.x = snap(p.x * sx)
                p.y = snap(p.y * sy)
                p.w = max(GRID * 2, snap(p.w * sx))
                p.h = max(GRID * 2, snap(p.h * sy))
                self.clamp(p)

    def devices_to_read(self):
        out = []
        for s in self.screens:
            for p in s.parts:
                out.extend(p.devices_to_read())
        return out

    def to_json(self):
        return json.dumps({"version": 1, "landscape": self.landscape,
                           "screens": [{"name": s.name, "parts": [p.to_dict() for p in s.parts]}
                                       for s in self.screens]}, ensure_ascii=False, indent=1)

    @staticmethod
    def from_json(text):
        o = json.loads(text)
        panel = Panel()
        panel.landscape = bool(o.get("landscape", False))
        for i, so in enumerate(o["screens"]):
            s = Screen(str(so.get("name", "画面%d" % (i + 1))))
            for po in so.get("parts") or []:
                try:
                    p = Part.from_dict(po)
                    panel.clamp(p)
                    s.parts.append(p)
                except (KeyError, ValueError, TypeError):
                    pass
            panel.screens.append(s)
        if not panel.screens:
            panel.screens.append(Screen("メイン"))
        for s in panel.screens:
            for p in s.parts:
                if p.target >= len(panel.screens):
                    p.target = 0
        return panel

    @staticmethod
    def create_default():
        p = Panel()
        s = Screen("メイン")

        def add(scr, t, x, y, w, h, **kw):
            part = Part(t, x, y, w, h)
            for k, v in kw.items():
                setattr(part, k, v)
            scr.parts.append(part)

        add(s, "TEXT", 20, 10, 320, 50, label="操作パネル")
        add(s, "LAMP", 20, 70, 150, 60, label="運転中", device="M0", onColor=COLORS[0])
        add(s, "LAMP", 190, 70, 150, 60, label="異常", device="M1", onColor=COLORS[1])
        add(s, "BUTTON", 20, 150, 150, 90, label="運転", device="M100", lampDevice="M0", onColor=COLORS[0])
        add(s, "BUTTON", 190, 150, 150, 90, label="停止", device="M101", onColor=COLORS[1], offColor=COLORS[8])
        add(s, "NUMBER", 20, 260, 320, 80, label="現在値", device="D0", decimals=1, unit="m/min")
        add(s, "INPUT", 20, 360, 320, 80, label="設定値", device="D100", decimals=1, unit="m/min", min="0", max="100")
        add(s, "SWITCH", 20, 560, 320, 60, label="画面2へ", target=1, onColor=COLORS[3], offColor=COLORS[3])
        p.screens.append(s)
        s2 = Screen("画面2")
        add(s2, "TEXT", 20, 10, 320, 50, label="画面2")
        add(s2, "SWITCH", 20, 560, 320, 60, label="メインへ戻る", target=0, onColor=COLORS[3], offColor=COLORS[3])
        p.screens.append(s2)
        return p
