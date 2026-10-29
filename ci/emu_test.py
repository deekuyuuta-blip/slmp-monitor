"""エミュレータ上でアプリを操作し、スクリーンショットと結果を shots/ に保存する。"""
import json
import os
import re
import subprocess
import sys
import time

PKG = "jp.yuta.slmpmonitor"
OUT = "shots"
os.makedirs(OUT, exist_ok=True)
results = {"checks": {}, "notes": []}
shot_no = [0]


def adb(*args, check=False, capture=True):
    r = subprocess.run(["adb", *args], capture_output=capture, text=True)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {args} failed: {r.stderr}")
    return r.stdout if capture else ""


def shot(name):
    shot_no[0] += 1
    path = f"{OUT}/{shot_no[0]:02d}_{name}.png"
    with open(path, "wb") as f:
        f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout)
    print("shot", path, flush=True)


def dump():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return adb("shell", "cat", "/sdcard/ui.xml")


def bounds_of(xml, pattern):
    """text か content-desc が pattern に一致するノードの中心"""
    for m in re.finditer(r'<node [^>]*>', xml):
        node = m.group(0)
        t = re.search(r' text="([^"]*)"', node)
        d = re.search(r' content-desc="([^"]*)"', node)
        txt = (t.group(1) if t else "") + "|" + (d.group(1) if d else "")
        if re.search(pattern, txt):
            b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
            if b:
                x1, y1, x2, y2 = map(int, b.groups())
                return x1, y1, x2, y2
    return None


def tap_text(pattern, tries=3):
    for _ in range(tries):
        b = bounds_of(dump(), pattern)
        if b:
            adb("shell", "input", "tap", str((b[0] + b[2]) // 2), str((b[1] + b[3]) // 2))
            return True
        time.sleep(1)
    results["notes"].append(f"not found: {pattern}")
    return False


class PanelMap:
    """操作画面の仮想座標(360x640)→画面座標"""

    def __init__(self):
        b = bounds_of(dump(), r"\|panel$")
        if not b:
            raise RuntimeError("panel view not found")
        x1, y1, x2, y2 = b
        w, h = x2 - x1, y2 - y1
        self.s = min(w / 360, h / 640)
        self.ox = x1 + (w - 360 * self.s) / 2
        self.oy = y1 + (h - 640 * self.s) / 2

    def pt(self, vx, vy):
        return str(int(self.ox + vx * self.s)), str(int(self.oy + vy * self.s))

    def tap(self, vx, vy):
        adb("shell", "input", "tap", *self.pt(vx, vy))

    def swipe(self, vx1, vy1, vx2, vy2, ms=800):
        adb("shell", "input", "swipe", *self.pt(vx1, vy1), *self.pt(vx2, vy2), str(ms))


def sim_result():
    try:
        with open("sim.json") as f:
            return json.load(f)
    except Exception:
        return {}


def check(name, ok, detail=""):
    results["checks"][name] = {"ok": bool(ok), "detail": detail}
    print(("OK  " if ok else "NG  ") + name, detail, flush=True)


# ---- 準備 ----
apk = sys.argv[1]
adb("install", "-r", apk, check=True)
prefs = ('<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map>\n'
         '<int name="port" value="5000" />\n<int name="autoStart" value="1" />\n'
         '<int name="openPanel" value="1" />\n<int name="staleSec" value="3" />\n</map>\n')
with open("slmp.xml", "w") as f:
    f.write(prefs)
adb("push", "slmp.xml", "/data/local/tmp/slmp.xml", check=True)
adb("shell", "chmod", "644", "/data/local/tmp/slmp.xml")
adb("shell", "run-as", PKG, "sh", "-c",
    f"'mkdir -p /data/data/{PKG}/shared_prefs && cp /data/local/tmp/slmp.xml /data/data/{PKG}/shared_prefs/slmp.xml'")
adb("forward", "tcp:5000", "tcp:5000", check=True)
adb("logcat", "-c")
adb("shell", "am", "start", "-W", "-n", f"{PKG}/.MainActivity", check=True)
time.sleep(4)

sim = subprocess.Popen([sys.executable, "ci/plc_sim.py", "127.0.0.1", "5000", "75", "sim.json"])
time.sleep(4)
shot("panel_run")
check("PLC接続", sim_result().get("connected"), json.dumps(sim_result())[:200])

pm = PanelMap()

# ロック中に操作 → 書込まれない
pm.tap(95, 195)
time.sleep(1.2)
shot("locked_press")
check("ロック中は書込まない", sim_result().get("m100_on_seen", 0) == 0)

# ロック解除して「運転」（モーメンタリ）
tap_text(r"ロック中")
time.sleep(0.8)
pm.tap(95, 195)
time.sleep(2.0)
shot("after_run_button")
r = sim_result()
check("モーメンタリ: 短いタップでもPLCがONを読めた", r.get("m100_on_seen", 0) >= 1, f"m100_on_seen={r.get('m100_on_seen')}")

# 数値入力 D100 = 55.5（小数1桁 → 555）
pm.tap(180, 400)
time.sleep(1.2)
shot("input_dialog")
adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
for _ in range(8):
    adb("shell", "input", "keyevent", "KEYCODE_DEL")
adb("shell", "input", "text", "55.5")
time.sleep(0.5)
tap_text(r"^書込\|")
time.sleep(1.5)
shot("after_input")
r = sim_result()
check("数値入力: D100=555", 555 in r.get("d100_values", []), f"d100_values={r.get('d100_values')}")

# 範囲外の入力（上限100）
pm.tap(180, 400)
time.sleep(1.0)
adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
for _ in range(8):
    adb("shell", "input", "keyevent", "KEYCODE_DEL")
adb("shell", "input", "text", "150")
tap_text(r"^書込\|")
time.sleep(0.8)
shot("input_out_of_range")
check("範囲外は書込まない", 1500 not in sim_result().get("d100_values", []))
tap_text(r"^キャンセル\|")
time.sleep(0.8)

# 停止ボタン
pm.tap(265, 195)
time.sleep(2.0)
shot("after_stop_button")
check("停止ボタン: M101", sim_result().get("m101_on_seen", 0) >= 1)

# 画面切替
pm.tap(180, 590)
time.sleep(1.2)
shot("screen2")
check("画面切替", bounds_of(dump(), r"\|panel$") is not None)
pm.tap(180, 590)
time.sleep(1.0)

# 通信断 → 灰色表示・操作不可
sim.terminate()
sim.wait()
time.sleep(5)
shot("comm_lost")

# 編集モード
adb("shell", "am", "start", "-W", "-n", f"{PKG}/.PanelActivity", "--ez", "edit", "true")
time.sleep(2.5)
pm = PanelMap()
shot("edit_mode")
pm.tap(95, 195)        # 選択
time.sleep(0.8)
shot("edit_selected")
pm.swipe(95, 195, 95, 495, 900)   # 下へ移動
time.sleep(0.8)
shot("edit_moved")
pm.tap(95, 495)        # 選択中をもう一度 → 設定
time.sleep(1.2)
shot("edit_properties")
adb("shell", "input", "keyevent", "KEYCODE_BACK")
time.sleep(0.8)
# サイズ変更（右下つまみ）
pm.swipe(167, 537, 227, 567, 900)
time.sleep(0.8)
shot("edit_resized")
tap_text(r"^＋部品\|")
time.sleep(1.0)
shot("edit_add_menu")
tap_text(r"^ランプ\|")
time.sleep(1.2)
shot("edit_add_lamp_dialog")
adb("shell", "input", "keyevent", "KEYCODE_BACK")
time.sleep(0.6)
tap_text(r"^⋮\|")
time.sleep(1.0)
shot("menu")
adb("shell", "input", "keyevent", "KEYCODE_BACK")
time.sleep(0.6)
tap_text(r"^完了\|")
time.sleep(1.5)
shot("edit_done")

# 横向き
tap_text(r"^⋮\|")
time.sleep(0.8)
tap_text(r"横向きにする")
time.sleep(0.8)
tap_text(r"^変更\|")
time.sleep(3)
shot("landscape")
tap_text(r"^⋮\|")
time.sleep(0.8)
tap_text(r"縦向きにする")
time.sleep(0.8)
tap_text(r"^変更\|")
time.sleep(3)

# 一覧画面
adb("shell", "input", "keyevent", "KEYCODE_BACK")
time.sleep(1)
adb("shell", "input", "keyevent", "KEYCODE_BACK")
time.sleep(1.5)
shot("main_list")

log = adb("logcat", "-d")
crash = [l for l in log.splitlines() if "FATAL EXCEPTION" in l or ("AndroidRuntime" in l and " E " in l)]
with open(f"{OUT}/logcat.txt", "w") as f:
    f.write(log[-200000:])
check("クラッシュなし", not crash, "\n".join(crash[:20]))

with open(f"{OUT}/results.json", "w") as f:
    json.dump({**results, "sim": sim_result()}, f, ensure_ascii=False, indent=1)
print(json.dumps(results, ensure_ascii=False, indent=1))
