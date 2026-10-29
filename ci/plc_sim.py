"""模擬PLC（シンプルCPU通信の代わり）。
スマホ（SLMP対応機器として待受）に3Eフレームで周期的に書込み・読出しする。
簡単なラダー相当：M100(運転)がONならM0(運転中)をON、M101(停止)でOFF。D0は運転中だけ増える。
結果は JSON に保存する。
"""
import json
import socket
import struct
import sys
import time

host, port, seconds, out = sys.argv[1], int(sys.argv[2]), float(sys.argv[3]), sys.argv[4]


def frame(cmd, sub, data):
    body = struct.pack("<HHH", 0x0010, cmd, sub) + data
    return bytes([0x50, 0, 0, 0xFF, 0xFF, 0x03, 0]) + struct.pack("<H", len(body)) + body


def head(no, code, n):
    return struct.pack("<I", no)[:3] + bytes([code]) + struct.pack("<H", n)


def call(s, req):
    s.sendall(req)
    h = b""
    while len(h) < 9:
        c = s.recv(9 - len(h))
        if not c:
            raise ConnectionError("closed")
        h += c
    ln = struct.unpack("<H", h[7:9])[0]
    b = b""
    while len(b) < ln:
        c = s.recv(ln - len(b))
        if not c:
            raise ConnectionError("closed")
        b += c
    end = struct.unpack("<H", b[:2])[0]
    if end != 0:
        raise RuntimeError("end code %04X" % end)
    return b[2:]


res = {"cycles": 0, "m100_on_seen": 0, "m101_on_seen": 0, "d100_values": [], "errors": [], "connected": False}
run = False
d0 = 123
t_end = time.time() + seconds
s = None
while time.time() < t_end:
    try:
        if s is None:
            s = socket.create_connection((host, port), timeout=3)
            res["connected"] = True
        # PLC → スマホ（書込み）: M0〜M15, D0〜D1
        mword = (1 if run else 0) | (0 << 1)
        call(s, frame(0x1401, 0, head(0, 0x90, 1) + struct.pack("<H", mword)))
        call(s, frame(0x1401, 0, head(0, 0xA8, 2) + struct.pack("<hh", d0, 0)))
        # スマホ → PLC（読出し）: M100〜M115, D100
        m = struct.unpack("<H", call(s, frame(0x0401, 0, head(100, 0x90, 1))))[0]
        d100 = struct.unpack("<h", call(s, frame(0x0401, 0, head(100, 0xA8, 1))))[0]
        if m & 1:
            res["m100_on_seen"] += 1
            run = True
        if m & 2:
            res["m101_on_seen"] += 1
            run = False
        if run:
            d0 = min(d0 + 1, 32000)
        if not res["d100_values"] or res["d100_values"][-1] != d100:
            res["d100_values"].append(d100)
        res["cycles"] += 1
    except Exception as e:  # noqa
        res["errors"].append(str(e))
        try:
            s.close()
        except Exception:
            pass
        s = None
        time.sleep(0.5)
    with open(out, "w") as f:
        json.dump(res, f)
    time.sleep(0.2)
if s:
    s.close()
with open(out, "w") as f:
    json.dump(res, f)
