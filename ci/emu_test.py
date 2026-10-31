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
    """画面の部品一覧。失敗したら古いファイルを読まないよう消してからやり直す"""
    for _ in range(5):
        adb("shell", "rm", "-f", "/sdcard/ui.xml")
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        x = adb("shell", "cat", "/sdcard/ui.xml")
        if "<hierarchy" in x:
            return x
        time.sleep(1)
    return ""


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


def in_picker(x):
    return "documentsui" in x or x == ""


def focused_bounds(x):
    for m in re.finditer(r'<node [^>]*focused="true"[^>]*>', x):
        bb = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', m.group(0))
        if bb:
            return tuple(map(int, bb.groups()))
    return None


def pick_file(name):
    """ファイル選択画面で Downloads を開き、キーボードで name にフォーカスして開く
    （この環境のファイル選択画面は input tap に反応しないため）"""
    x = dump()
    if not bounds_of(x, "^" + re.escape(name) + r"\|"):
        tap_text(r"(?i)show roots|ルートを表示", tries=1)
        time.sleep(1.2)
        tap_text(r"(?i)^downloads\||^ダウンロード\|", tries=1)
        time.sleep(2)
    tap_text(r"(?i)^list view\||リスト表示", tries=1)   # 縦1列にして下キーだけで移動できるように
    time.sleep(1.5)
    adb("shell", "input", "keyevent", "KEYCODE_TAB")
    for step in range(16):
        x = dump()
        if not in_picker(x):
            return True
        t = bounds_of(x, "^" + re.escape(name) + r"\|")
        f = focused_bounds(x)
        results["notes"].append(f"pick {name}: step {step} target={t} focus={f}")
        if t and f and f[0] <= t[0] and t[2] <= f[2] and f[1] <= t[1] and t[3] <= f[3] and (f[2] - f[0]) < 1080 * 0.95 or \
                (t and f and f[1] <= t[1] and t[3] <= f[3] and f[3] - f[1] < 400):
            adb("shell", "input", "keyevent", "KEYCODE_ENTER")
            time.sleep(2.5)
            return not in_picker(dump())
        adb("shell", "input", "keyevent", "KEYCODE_DPAD_DOWN")
        time.sleep(0.4)
    return False


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

# ---- ファイルに保存 ----
adb("shell", "rm", "-f", "/sdcard/Download/export_test.json")
tap_text(r"^⋮\|")
time.sleep(0.8)
tap_text(r"書き出し")
time.sleep(0.8)
tap_text(r"ファイルに保存")
time.sleep(2.5)
shot("save_picker")
# ファイル名を固定して Download に保存
b = bounds_of(dump(), r"\.json")
if b:
    adb("shell", "input", "tap", str((b[0] + b[2]) // 2), str((b[1] + b[3]) // 2))
    time.sleep(0.5)
    adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
    for _ in range(40):
        adb("shell", "input", "keyevent", "KEYCODE_DEL")
    adb("shell", "input", "text", "export_test.json")
if not bounds_of(dump(), r"(?i)^download"):
    tap_text(r"(?i)show roots|ルートを表示", tries=1)
    time.sleep(1)
    tap_text(r"(?i)^downloads?\||^ダウンロード", tries=1)
    time.sleep(1.5)
tap_text(r"(?i)^save\||^保存\|")
time.sleep(2.5)
shot("after_save")
saved = adb("shell", "cat", "/sdcard/Download/export_test.json")
check("ファイルに保存", '"screens"' in saved, saved[:120].replace("\n", " "))

# ---- ファイルを選んで読み込む ----
test_json = json.dumps({"version": 1, "landscape": False, "screens": [
    {"name": "読込テスト", "parts": [
        {"type": "TEXT", "x": 20, "y": 20, "w": 320, "h": 60, "label": "ファイルから読込OK", "textColor": "#FFFFEB3B"},
        {"type": "LAMP", "x": 20, "y": 100, "w": 150, "h": 60, "label": "運転中", "device": "M0"}]},
    {"name": "2枚目", "parts": []}]}, ensure_ascii=False)
with open("import_test.json", "w", encoding="utf-8") as f:
    f.write(test_json)
adb("push", "import_test.json", "/sdcard/Download/import_test.json", check=True)
tap_text(r"^⋮\|")
time.sleep(0.8)
tap_text(r"^読み込み\|")
time.sleep(0.8)
tap_text(r"ファイルを選ぶ")
time.sleep(2.5)
shot("open_picker")
pick_file("import_test.json")
time.sleep(2)
shot("import_confirm")
check("読込前に内容を確認", bounds_of(dump(), r"画面 2") is not None or bounds_of(dump(), r"置き換え") is not None)
tap_text(r"^置き換える\|")
time.sleep(2)
shot("after_import")
x = dump()
check("ファイルから読み込み", bounds_of(x, r"^読込テスト\|") is not None, "")

# 壊れたファイル
with open("broken.json", "w") as f:
    f.write("this is not json")
adb("push", "broken.json", "/sdcard/Download/broken.json", check=True)
tabs_before = re.findall(r'text="([^"]+)"[^>]*class="android.widget.TextView"', dump())[:3]
tap_text(r"^⋮\|")
time.sleep(0.8)
tap_text(r"^読み込み\|")
time.sleep(0.8)
tap_text(r"ファイルを選ぶ")
time.sleep(2.5)
pick_file("broken.json")
time.sleep(0.6)
shot("broken_file")
time.sleep(1.5)
tabs_after = re.findall(r'text="([^"]+)"[^>]*class="android.widget.TextView"', dump())[:3]
check("壊れたファイルは読まず今の画面のまま", tabs_before == tabs_after and PKG in dump(), f"{tabs_before} -> {tabs_after}")

# ---- 再起動しても画面の値が残る ＋ UDP ----
adb("shell", "am", "force-stop", PKG)
time.sleep(1)
adb("shell", "am", "start", "-W", "-n", f"{PKG}/.MainActivity")
time.sleep(4)
adb("emu", "redir", "add", "udp:5000:5000")
time.sleep(1)
subprocess.run([sys.executable, "ci/udp_check.py", "127.0.0.1", "5000", "udp.json"])
try:
    u = json.load(open("udp.json"))
except Exception:
    u = {}
check("UDPで読出し・書込み", u.get("ok") and u.get("write_end") == 0, json.dumps(u))
check("再起動後も設定値D100=555が残る（PLCが0で上書きされない）", u.get("d100") == 555, json.dumps(u))
time.sleep(1.5)
shot("after_restart_udp")

# ---- 連続通信（UDP）：通常／バックグラウンド／画面オフ／Doze ----
def soak(secs, phase):
    subprocess.run([sys.executable, "ci/udp_soak.py", "127.0.0.1", "5000", str(secs), phase, "soak.json"])

soak(90, "通常（操作画面を表示中）")
adb("shell", "input", "keyevent", "KEYCODE_HOME")
time.sleep(2)
soak(45, "バックグラウンド（ホーム画面）")
adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
time.sleep(2)
adb("shell", "input", "keyevent", "KEYCODE_SLEEP")
time.sleep(2)
soak(45, "画面オフ")
adb("shell", "dumpsys", "deviceidle", "force-idle")
time.sleep(2)
soak(45, "Doze（強制）")
adb("shell", "dumpsys", "deviceidle", "unforce")
adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
adb("shell", "wm", "dismiss-keyguard")
time.sleep(2)
try:
    for r in json.load(open("soak.json")):
        check(f"連続UDP {r['phase']}", r["timeouts"] == 0 and r["value_mismatch"] == 0 and r["ok_responses"] > 0, json.dumps(r, ensure_ascii=False))
except Exception as e:
    check("連続UDP", False, str(e))
import shutil
shutil.copy("soak.json", f"{OUT}/soak.json") if os.path.exists("soak.json") else None
shot("after_soak")

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
