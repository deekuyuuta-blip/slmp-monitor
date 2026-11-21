# -*- coding: utf-8 -*-
"""
SLMPモニタ PC版：通信とデータの部分（画面なし）。
スマホ版（Android）の Device / MonitorItem / DeviceMemory / PlcClient と同じ動きにしてある。
"""
import socket
import struct
import threading
import time
import collections
from datetime import datetime

# ---------------------------------------------------------------- デバイス

# prefix, デバイスコード, ビットデバイスか, 16進番号か（長い名前を先に判定）
_TABLE = [
    ("SM", 0x91, True, False), ("SD", 0xA9, False, False),
    ("SB", 0xA1, True, True), ("SW", 0xB5, False, True),
    ("ZR", 0xB0, False, False),
    ("TS", 0xC1, True, False), ("TC", 0xC0, True, False), ("TN", 0xC2, False, False),
    ("CS", 0xC4, True, False), ("CC", 0xC3, True, False), ("CN", 0xC5, False, False),
    ("X", 0x9C, True, True), ("Y", 0x9D, True, True),
    ("M", 0x90, True, False), ("L", 0x92, True, False), ("F", 0x93, True, False),
    ("B", 0xA0, True, True),
    ("D", 0xA8, False, False), ("W", 0xB4, False, True), ("R", 0xAF, False, False),
]


def is_bit_code(code):
    for p, c, bit, _ in _TABLE:
        if c == code:
            return bit
    return None


def device_name(code, number):
    for p, c, _, hexn in _TABLE:
        if c == code:
            return p + (format(number, "X") if hexn else str(number))
    return "code%02X:%d" % (code, number)


class Device:
    __slots__ = ("prefix", "code", "number", "bit", "hex")

    def __init__(self, prefix, code, number, bit, hexn):
        self.prefix, self.code, self.number, self.bit, self.hex = prefix, code, number, bit, hexn

    @staticmethod
    def parse(text):
        t = (text or "").strip().upper()
        for p, c, bit, hexn in _TABLE:
            if not t.startswith(p) or len(t) == len(p):
                continue
            try:
                n = int(t[len(p):], 16 if hexn else 10)
            except ValueError:
                continue
            if n < 0 or n > 0xFFFFFF:
                break
            return Device(p, c, n, bit, hexn)
        raise ValueError("デバイス名が正しくありません: " + str(text))

    def write_blocked_reason(self):
        if self.prefix == "X":
            return "X（入力）には書込めません"
        if self.prefix == "Y":
            return "Y（出力）への直接書込みは安全のため禁止しています。M等を書き、ラダーでYを出してください"
        if self.prefix in ("SM", "SD"):
            return "特殊リレー・特殊レジスタ（SM・SD）への書込みは禁止しています"
        return None

    def __str__(self):
        return self.prefix + (format(self.number, "X") if self.hex else str(self.number))


# ---------------------------------------------------------------- データ形式

FORMATS = ["BIT", "S16", "U16", "HEX16", "S32", "FLOAT32"]
FORMAT_LABELS = {"BIT": "ビット", "S16": "符号付き16bit", "U16": "符号なし16bit", "HEX16": "16進",
                 "S32": "符号付き32bit", "FLOAT32": "実数(単精度)"}


def format_words(fmt):
    return 2 if fmt in ("S32", "FLOAT32") else 1


def to_s16(v):
    v &= 0xFFFF
    return v - 0x10000 if v & 0x8000 else v


def words_to_int(w):
    v = (w[0] & 0xFFFF) | ((w[1] & 0xFFFF) << 16)
    return v - (1 << 32) if v & 0x80000000 else v


def int_to_words(v):
    v &= 0xFFFFFFFF
    return [to_s16(v & 0xFFFF), to_s16(v >> 16)]


def words_to_float(w):
    return struct.unpack("<f", struct.pack("<HH", w[0] & 0xFFFF, w[1] & 0xFFFF))[0]


def float_to_words(f):
    a, b = struct.unpack("<HH", struct.pack("<f", f))
    return [to_s16(a), to_s16(b)]


class MonitorItem:
    """一覧の1行"""

    def __init__(self, name, device_text, fmt="S16", writable=False):
        d = Device.parse(device_text)
        self.name = name
        self.device_text = str(d)
        self.format = "BIT" if d.bit else ("S16" if fmt == "BIT" else fmt)
        self.writable = writable

    def device(self):
        return Device.parse(self.device_text)

    def to_dict(self):
        return {"name": self.name, "device": self.device_text, "format": self.format, "writable": self.writable}

    @staticmethod
    def from_dict(o):
        return MonitorItem(o.get("name", ""), o.get("device", "D0"), o.get("format", "S16"), bool(o.get("writable")))

    def read(self, mem):
        d = self.device()
        if self.format == "BIT":
            return "ON" if mem.read_bits(d.code, d.number, 1)[0] else "OFF"
        w = mem.read_words(d.code, d.number, format_words(self.format))
        f = self.format
        if f == "U16":
            return str(w[0] & 0xFFFF)
        if f == "HEX16":
            return "H%04X" % (w[0] & 0xFFFF)
        if f == "S32":
            return str(words_to_int(w))
        if f == "FLOAT32":
            return "%.3f" % words_to_float(w)
        return str(to_s16(w[0]))

    def parse_words(self, s):
        t = s.strip()
        f = self.format
        if f == "U16":
            v = int(t)
            if not 0 <= v <= 65535:
                raise ValueError("0〜65535で入力してください")
            return [to_s16(v)]
        if f == "HEX16":
            h = t.upper()
            if h.startswith("H"):
                h = h[1:]
            elif h.startswith("0X"):
                h = h[2:]
            v = int(h, 16)
            if not 0 <= v <= 0xFFFF:
                raise ValueError("0〜FFFFで入力してください")
            return [to_s16(v)]
        if f == "S32":
            v = int(t)
            if not -2 ** 31 <= v < 2 ** 31:
                raise ValueError("32bitの範囲外です")
            return int_to_words(v)
        if f == "FLOAT32":
            return float_to_words(float(t))
        v = int(t)
        if not -32768 <= v <= 32767:
            raise ValueError("-32768〜32767で入力してください")
        return [v]


# ---------------------------------------------------------------- PC内のデバイスメモリ（読出し値の置き場）

class DeviceMemory:
    """PLCから読んだ値を置く。画面から書いたときは write_hook でPLCへ送る。"""

    def __init__(self):
        self._lock = threading.RLock()
        self._words = {}
        self._bits = set()
        self.write_hook = None  # (kind, code, no, values, momentary) -> None

    @staticmethod
    def _require(code):
        b = is_bit_code(code)
        if b is None:
            raise ValueError("未対応のデバイスコード")
        return b

    def read_words(self, code, no, points):
        bit = self._require(code)
        out = []
        with self._lock:
            for i in range(points):
                if bit:
                    w = 0
                    for j in range(16):
                        if (code, no + i * 16 + j) in self._bits:
                            w |= 1 << j
                    out.append(to_s16(w))
                else:
                    out.append(self._words.get((code, no + i), 0))
        return out

    def write_words(self, code, no, values):
        bit = self._require(code)
        with self._lock:
            for i, v in enumerate(values):
                if bit:
                    for j in range(16):
                        k = (code, no + i * 16 + j)
                        if v & (1 << j):
                            self._bits.add(k)
                        else:
                            self._bits.discard(k)
                else:
                    self._words[(code, no + i)] = to_s16(v)

    def read_bits(self, code, no, points):
        if not self._require(code):
            raise ValueError("ワードデバイスにビット単位アクセス")
        with self._lock:
            return [(code, no + i) in self._bits for i in range(points)]

    def write_bits(self, code, no, values):
        if not self._require(code):
            raise ValueError("ワードデバイスにビット単位アクセス")
        with self._lock:
            for i, v in enumerate(values):
                k = (code, no + i)
                if v:
                    self._bits.add(k)
                else:
                    self._bits.discard(k)

    # 画面からの書込み（表示もすぐ変え、PLCへ送る）
    def local_write_words(self, code, no, values):
        self.write_words(code, no, values)
        h = self.write_hook
        if h:
            h("words", code, no, list(values), False)

    def local_write_bits(self, code, no, values, momentary=False):
        self.write_bits(code, no, values)
        h = self.write_hook
        if h:
            h("bits", code, no, list(values), momentary)


# ---------------------------------------------------------------- 通信

class PlcError(IOError):
    def __init__(self, msg, end):
        super().__init__(msg)
        self.end = end


def _password_error(end):
    return 0xC200 <= end <= 0xC2FF


def _hint(end):
    if _password_error(end):
        return "：リモートパスワード（設定・入力した文字・ロック状態）を確認"
    return {0xC059: "：PLCがこのコマンドに対応していない", 0xC056: "：デバイス番号が範囲外",
            0xC051: "：デバイス番号が範囲外", 0xC05C: "：要求内容が不正",
            0xC061: "：データ長が不正"}.get(end, "：PLC側の設定（RUN中書込許可など）を確認" if 0x4000 <= end < 0x5000 else "")


class _Write:
    __slots__ = ("code", "no", "words", "bits", "at", "quiet", "must_deliver")

    def __init__(self, code, no, words=None, bits=None):
        self.code, self.no, self.words, self.bits = code, no, words, bits
        self.at = time.time()
        self.quiet = False
        self.must_deliver = False


class PlcClient:
    """
    PCからPLCへSLMP（バイナリ）で読み書きしに行く。
    - 一覧・操作画面で使っているデバイスを周期的に一括読出しして、メモリへ入れる
    - 画面から書いた値は、すぐPLCへ書込む（読出しより優先）
    - 応答が来なければ0.3秒で送り直す
    - 4Eフレーム（通し番号つき）で始め、PLCが答えなければ3Eフレームに切り替える
    """
    TIMEOUT_UDP = 0.3
    TIMEOUT_TCP = 1.0
    RETRIES = 3
    MAX_WORDS = 960
    WRITE_MAX_AGE = 3.0

    def __init__(self, mem, ip, port, udp=True, poll_ms=200, password="", devices_provider=None,
                 on_auth_failed=None):
        self.mem = mem
        self.ip = (ip or "").strip()
        self.port = int(port)
        self.udp = udp
        self.poll = max(50, int(poll_ms)) / 1000.0
        self.password = password or ""
        self.devices_provider = devices_provider or (lambda: [])
        self.on_auth_failed = on_auth_failed

        self.request_count = 0
        self.last_ok = 0.0
        self.connected_now = False
        self.auth_failed = False
        self.running = False
        self.flushing = False

        self._log = collections.deque(maxlen=300)
        self._last_log_body = None
        self._last_log_rep = 0
        self._log_lock = threading.Lock()

        self._writes = collections.deque()
        self._wlock = threading.Lock()
        self._signal = threading.Condition()
        self._thread = None
        self._sock = None
        self._use4e = True
        self._ever_ok = False
        self._serial = 0
        self._unlocked = False
        self._ranges = []
        self._ranges_at = 0.0

    # ---------------- ログ（同じ内容が続くときはまとめる）
    def log(self, body):
        t = datetime.now().strftime("%H:%M:%S")
        with self._log_lock:
            if body == self._last_log_body and self._log:
                self._last_log_rep += 1
                self._log.pop()
                self._log.append("%s %s  ×%d" % (t, body, self._last_log_rep))
                return
            self._last_log_body = body
            self._last_log_rep = 1
            self._log.append(t + " " + body)

    def log_lines(self):
        with self._log_lock:
            return list(self._log)

    def waiting_text(self):
        return "リモートパスワードが違います（通信停止→開始で再入力）" if self.auth_failed else "PLCへ接続中"

    def peer_text(self):
        if not self.connected_now:
            return ""
        return "%s:%d(%s・%s)" % (self.ip, self.port, "UDP" if self.udp else "TCP", "4E" if self._use4e else "3E")

    def is_fresh(self, stale_sec):
        return self.last_ok > 0 and time.time() - self.last_ok <= stale_sec

    # ---------------- 開始・停止
    def start(self):
        parts = self.ip.split(".")
        if len(parts) != 4 or not all(p.isdigit() and 0 <= int(p) <= 255 for p in parts):
            raise IOError("設定でPLCのIPアドレスを入れてください")
        self.running = True
        self.log("PLC %s:%d（%s）へ読みに行きます　周期 %dms" % (self.ip, self.port, "UDP" if self.udp else "TCP",
                                                         int(self.poll * 1000)))
        self._thread = threading.Thread(target=self._loop, name="slmp-client", daemon=True)
        self._thread.start()

    def stop(self, wait=3.0):
        self.running = False
        with self._signal:
            self._signal.notify_all()
        if self._thread:
            self._thread.join(wait)
        self._close()
        self.log("停止")

    # ---------------- 書込み受付
    def _blocked(self, code, no):
        try:
            why = Device.parse(device_name(code, no)).write_blocked_reason()
        except ValueError:
            why = "不明なデバイス"
        if why:
            self.log("書込を拒否 %s：%s" % (device_name(code, no), why))
            return True
        return False

    def _enqueue(self, w):
        with self._wlock:
            self._writes.append(w)
        with self._signal:
            self._signal.notify_all()

    def queue_words(self, code, no, values):
        if not self.running or self._blocked(code, no):
            return
        self._enqueue(_Write(code, no, words=list(values)))

    def queue_bits(self, code, no, values, momentary=False):
        release = momentary and not any(values)
        alive = self._thread is not None and self._thread.is_alive()
        if (not self.running and not (release and alive)) or self._blocked(code, no):
            return
        w = _Write(code, no, bits=list(values))
        w.must_deliver = release
        self._enqueue(w)

    def queue_quiet(self, code, no, words=None, bits=None):
        """ハートビート用：ログに出さない。送れていない古いものは置き換える"""
        if not self.running:
            return
        with self._wlock:
            self._writes = collections.deque(x for x in self._writes
                                             if not (x.quiet and x.code == code and x.no == no))
        w = _Write(code, no, words=words, bits=bits)
        w.quiet = True
        self._enqueue(w)

    def _poll_write(self):
        with self._wlock:
            return self._writes.popleft() if self._writes else None

    def _writes_empty(self):
        with self._wlock:
            return not self._writes

    # ---------------- 周期処理
    def _sleep(self, sec):
        with self._signal:
            self._signal.wait(sec)

    def _loop(self):
        fail = 0
        while self.running:
            t0 = time.time()
            try:
                if self.auth_failed:
                    self._sleep(1.0)
                    continue
                self._ensure_unlocked()
                while self.running:
                    w = self._poll_write()
                    if w is None:
                        break
                    if not w.must_deliver and time.time() - w.at > self.WRITE_MAX_AGE:
                        if not w.quiet:
                            self.log("書込を取り消し %s（通信できず時間が経ったため）" % device_name(w.code, w.no))
                        continue
                    try:
                        self._do_write(w)
                    except PlcError:
                        raise
                    except (IOError, OSError):
                        with self._wlock:
                            self._writes.appendleft(w)
                        raise
                if t0 - self._ranges_at > 2.0:
                    self._ranges = self._build_ranges()
                    self._ranges_at = t0
                for code, start, words in self._ranges:
                    if not self.running or not self._writes_empty():
                        break
                    v = self._request(self._cmd(0x0401, 0x0000, self._dev(start, code), struct.pack("<H", words)))
                    if len(v) < words:
                        raise IOError("読出し応答が短い " + device_name(code, start))
                    self.mem.write_words(code, start, v[:words])
                self._mark_ok()
                fail = 0
            except PlcError as e:
                if self.auth_failed:
                    continue
                if _password_error(e.end):
                    self._unlocked = False
                    self.log(str(e) + ("　※設定で「リモートパスワードを使う」にしてください" if not self.password else ""))
                    time.sleep(3.0 if not self.password else 0.3)
                    continue
                self._mark_ok()
                self.log(str(e))
                time.sleep(1.0)
            except (IOError, OSError) as e:
                fail += 1
                self.connected_now = False
                self.log("通信エラー: %s" % (e if str(e) else e.__class__.__name__))
                self._close()
                if not self._ever_ok:
                    self._use4e = not self._use4e
                    self.log(("4E" if self._use4e else "3E") + "フレームで試します")
                time.sleep(min(2.0, 0.3 * fail))
                continue
            except Exception as e:  # 想定外でも止めない
                self.log("処理エラー: %s %s" % (e.__class__.__name__, e))
                time.sleep(1.0)
            wait = self.poll - (time.time() - t0)
            if wait > 0:
                with self._signal:
                    if self._writes_empty() and self.running:
                        self._signal.wait(wait)
        # 停止時：押しっぱなしのモーメンタリをOFFにしてから終わる
        self.flushing = True
        until = time.time() + 2.5
        if self._writes_empty() is False and not self.auth_failed:
            try:
                self._ensure_unlocked()   # 通信エラーでロックに戻っていたら解除し直してから送る
            except (IOError, OSError):
                pass
        while time.time() < until:
            w = self._poll_write()
            if w is None:
                break
            if not w.must_deliver:
                continue
            try:
                self._do_write(w)
            except (IOError, OSError) as e:
                self.log("停止時に %s をOFFにできませんでした：%s" % (device_name(w.code, w.no), e))
        self._lock_quiet()
        self.flushing = False

    def _mark_ok(self):
        self.last_ok = time.time()
        self.request_count += 1
        if not self.connected_now:
            self.log("PLCと通信できました（%sフレーム）" % ("4E" if self._use4e else "3E"))
        self.connected_now = True
        self._ever_ok = True

    def _do_write(self, w):
        dev = device_name(w.code, w.no)
        if w.words is not None:
            data = b"".join(struct.pack("<H", x & 0xFFFF) for x in w.words)
            self._request(self._cmd(0x1401, 0x0000, self._dev(w.no, w.code), struct.pack("<H", len(w.words)), data))
            if not w.quiet:
                self.log("書込 " + dev + (" = %d" % w.words[0] if len(w.words) == 1 else " %dワード" % len(w.words)))
            self.mem.write_words(w.code, w.no, w.words)
        else:
            data = bytearray((len(w.bits) + 1) // 2)
            for i, b in enumerate(w.bits):
                if b:
                    data[i // 2] |= 0x10 if i % 2 == 0 else 0x01
            self._request(self._cmd(0x1401, 0x0001, self._dev(w.no, w.code), struct.pack("<H", len(w.bits)),
                                    bytes(data)))
            if not w.quiet:
                self.log("書込 " + dev + ((" ON" if w.bits[0] else " OFF") if len(w.bits) == 1 else " %d点" % len(w.bits)))
            self.mem.write_bits(w.code, w.no, w.bits)

    def _build_ranges(self):
        """使っているデバイスを読出し単位（ワード単位、ビットは16点=1ワード）にまとめる"""
        want = {}
        for dev, words in self.devices_provider():
            try:
                d = Device.parse(dev)
            except ValueError:
                continue
            s = want.setdefault(d.code, set())
            if d.bit:
                s.add(d.number // 16)
            else:
                for i in range(words):
                    s.add(d.number + i)
        out = []
        for code in sorted(want):
            bit = bool(is_bit_code(code))
            start = last = None
            for n in sorted(want[code]):
                if start is not None and n - last <= 16 and n - start < self.MAX_WORDS:
                    last = n
                    continue
                if start is not None:
                    out.append((code, start * 16 if bit else start, last - start + 1))
                start = last = n
            if start is not None:
                out.append((code, start * 16 if bit else start, last - start + 1))
        if not out:
            out.append((0xA8, 0, 1))  # 登録なしでも通信の確認のためD0を読む
        return out

    # ---------------- リモートパスワード
    def _ensure_unlocked(self):
        if not self.password or self._unlocked:
            return
        pw = self.password.encode("ascii")
        try:
            self._request(self._cmd(0x1630, 0x0000, struct.pack("<H", len(pw)), pw))
        except PlcError as e:
            self.auth_failed = True
            if self.on_auth_failed:
                self.on_auth_failed()
            self.log("リモートパスワードの解除に失敗しました（%04X）。PLCのロックアウトを避けるため、通信を止めます" % e.end)
            raise
        self._unlocked = True
        self.log("リモートパスワードを解除しました")

    def _lock_quiet(self):
        if not self.password or not self._unlocked:
            return
        pw = self.password.encode("ascii")
        try:
            self._request(self._cmd(0x1631, 0x0000, struct.pack("<H", len(pw)), pw))
            self.log("リモートパスワードでロックしました")
        except (IOError, OSError) as e:
            self.log("終了時のロックに失敗: %s" % e)
        self._unlocked = False

    # ---------------- フレーム
    @staticmethod
    def _dev(no, code):
        return bytes([no & 0xFF, (no >> 8) & 0xFF, (no >> 16) & 0xFF, code])

    @staticmethod
    def _cmd(cmd, sub, *parts):
        # 監視タイマ 4×250ms=1秒 + コマンド + サブコマンド + 続き
        return struct.pack("<HHH", 4, cmd, sub) + b"".join(parts)

    def _frame(self, cmd, ser):
        if self._use4e:
            head = struct.pack("<BBHH", 0x54, 0, ser, 0)
        else:
            head = b"\x50\x00"
        return head + bytes([0x00, 0xFF, 0xFF, 0x03, 0x00]) + struct.pack("<H", len(cmd)) + cmd

    def _matches(self, r, ser):
        if self._use4e:
            return len(r) >= 15 and r[0] == 0xD4 and r[1] == 0 and struct.unpack_from("<H", r, 2)[0] == ser
        return len(r) >= 11 and r[0] == 0xD0 and r[1] == 0

    def _request(self, cmd):
        last = None
        attempt = 0
        while attempt <= self.RETRIES and (self.running or self.flushing):
            self._serial = (self._serial + 1) & 0xFFFF
            ser = self._serial
            frame = self._frame(cmd, ser)
            try:
                resp = self._exchange_udp(frame, ser) if self.udp else self._exchange_tcp(frame, ser)
                hl = 13 if self._use4e else 9
                end = struct.unpack_from("<H", resp, hl)[0]
                if end != 0:
                    c = struct.unpack_from("<H", cmd, 2)[0]
                    no = cmd[6] | (cmd[7] << 8) | (cmd[8] << 16) if len(cmd) >= 10 else 0
                    what = ("リモートパスワード解除" if c == 0x1630 else "リモートパスワードのロック" if c == 0x1631
                            else ("書込 " if c == 0x1401 else "読出 ") + device_name(cmd[9], no))
                    raise PlcError("PLCが異常応答 %04X（%s）%s" % (end, what, _hint(end)), end)
                data = resp[hl + 2:]
                n = len(data) // 2
                v = [to_s16(x) for x in struct.unpack_from("<%dH" % n, data)] if n else []
                if attempt > 0:
                    self.log("再送で回復（%d回目）" % attempt)
                return v
            except PlcError:
                raise
            except (IOError, OSError) as e:
                last = e
                if not self.udp:
                    self._close()
            attempt += 1
        raise last if last else IOError("停止")

    def _exchange_udp(self, frame, ser):
        if self._sock is None:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect((self.ip, self.port))
            self._sock = s
        s = self._sock
        # 前の要求への遅れた応答が残っていたら捨てる
        s.setblocking(False)
        try:
            for _ in range(20):
                s.recv(4096)
        except (BlockingIOError, OSError):
            pass
        s.setblocking(True)
        s.send(frame)
        deadline = time.time() + self.TIMEOUT_UDP
        hl = 13 if self._use4e else 9
        while True:
            left = deadline - time.time()
            if left <= 0:
                raise IOError("応答なし（%dms）" % int(self.TIMEOUT_UDP * 1000))
            s.settimeout(left)
            try:
                r = s.recv(4096)
            except socket.timeout:
                raise IOError("応答なし（%dms）" % int(self.TIMEOUT_UDP * 1000))
            except ConnectionResetError:
                # Windowsでは ICMP Port Unreachable がこれになる
                raise IOError("ICMP Port Unreachable（PLCがこのポートで受け付けていない）")
            if self._matches(r, ser):
                ln = struct.unpack_from("<H", r, hl - 2)[0]
                if hl + ln <= len(r):
                    return r[:hl + ln]

    def _exchange_tcp(self, frame, ser):
        if self._sock is None:
            s = socket.create_connection((self.ip, self.port), timeout=2.0)
            s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            s.settimeout(self.TIMEOUT_TCP)
            self._sock = s
        s = self._sock
        s.sendall(frame)
        hl = 13 if self._use4e else 9
        while True:
            h = self._read_fully(s, hl)
            ln = struct.unpack_from("<H", h, hl - 2)[0]
            r = h + self._read_fully(s, ln)
            if self._matches(r, ser):
                return r
            if not self._use4e:
                raise IOError("想定外の応答")

    @staticmethod
    def _read_fully(s, n):
        b = b""
        while len(b) < n:
            try:
                c = s.recv(n - len(b))
            except socket.timeout:
                raise IOError("応答なし")
            if not c:
                raise IOError("PLCが切断しました")
            b += c
        return b

    def _close(self):
        self._unlocked = False  # UDPは送信元ポートが変わるので解除し直す
        if self._sock is not None:
            try:
                self._sock.close()
            except OSError:
                pass
        self._sock = None
