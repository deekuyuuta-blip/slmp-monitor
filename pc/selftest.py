# -*- coding: utf-8 -*-
"""
PC版の自動試験（GitHub ActionsのWindowsで実行）。
模擬PLC（UDP・4E・リモートパスワードあり・1割のパケットを捨てる）を相手に、
実際の画面を動かして、書込みがPLCに届くか・画面が描けるかを確かめ、スクリーンショットを残す。
"""
import os
import random
import socket
import struct
import sys
import tempfile
import threading
import time
import traceback

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tkinter as tk  # noqa: E402

import app as A  # noqa: E402
import panel as pn  # noqa: E402

OUT = sys.argv[1] if len(sys.argv) > 1 else "selftest_out"
os.makedirs(OUT, exist_ok=True)
PORT = 15010
PW = b"secret1"
results = []
plc_log = []


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print(("OK  " if ok else "NG  ") + name + ("  " + detail if detail else ""), flush=True)


# ---------------------------------------------------------------- 模擬PLC

class FakePlc(threading.Thread):
    def __init__(self):
        super().__init__(daemon=True)
        self.words = {(0xA8, 0): 1234, (0xA8, 1): 1, (0xA8, 10): 0x5678, (0xA8, 11): 0x1234}
        self.bits = {(0x90, 0)}
        self.unlocked = set()
        self.s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.s.bind(("127.0.0.1", PORT))
        self.drop = 0.1

    def run(self):
        s = self.s
        while True:
            d, a = s.recvfrom(4096)
            # D0は1秒ごとに増える
            self.words[(0xA8, 0)] = (1234 + int(time.time()) % 1000) & 0xFFFF
            if random.random() < self.drop:
                continue
            if d[0] != 0x54:
                continue
            pre = b"\xd4\x00" + d[2:6]
            r = d[6:11]
            body = d[13:]
            cmd, sub = struct.unpack("<HH", body[2:6])

            def reply(data=b"", end=0):
                s.sendto(pre + r + struct.pack("<HH", 2 + len(data), end) + data, a)

            if cmd == 0x1630:
                n = struct.unpack("<H", body[6:8])[0]
                if body[8:8 + n] == PW:
                    self.unlocked.add(a)
                    plc_log.append("unlock")
                    reply()
                else:
                    reply(r + body[2:6], 0xC200)
                continue
            if cmd == 0x1631:
                self.unlocked.discard(a)
                plc_log.append("lock")
                reply()
                continue
            if a not in self.unlocked:
                reply(r + body[2:6], 0xC201)
                continue
            no = int.from_bytes(body[6:9], "little")
            code = body[9]
            pts = struct.unpack("<H", body[10:12])[0]
            if cmd == 0x0401:
                data = b""
                for i in range(pts):
                    if code in (0x90, 0x9C, 0x9D):
                        w = sum(1 << j for j in range(16) if (code, no + i * 16 + j) in self.bits)
                    else:
                        w = self.words.get((code, no + i), 0)
                    data += struct.pack("<H", w)
                reply(data)
            elif cmd == 0x1401 and sub == 1:
                for i in range(pts):
                    b = body[12 + i // 2]
                    v = (b >> 4) if i % 2 == 0 else (b & 15)
                    (self.bits.add if v else self.bits.discard)((code, no + i))
                    plc_log.append("bit %02X %d %d" % (code, no + i, v))
                reply()
            elif cmd == 0x1401:
                for i in range(pts):
                    self.words[(code, no + i)] = struct.unpack("<H", body[12 + i * 2:14 + i * 2])[0]
                    plc_log.append("word %02X %d %d" % (code, no + i, self.words[(code, no + i)]))
                reply()
            else:
                reply(r + body[2:6], 0xC059)


# ---------------------------------------------------------------- 試験の手順

def grab(win, name):
    try:
        from PIL import ImageGrab
        win.update()
        x, y = win.winfo_rootx(), win.winfo_rooty()
        w, h = win.winfo_width(), win.winfo_height()
        ImageGrab.grab(bbox=(x, y, x + w, y + h)).save(os.path.join(OUT, name))
    except Exception as e:
        print("screenshot failed", name, e)


def run_steps(app, root):
    steps = []

    def step(fn, delay=400):
        steps.append((fn, delay))

    def find_part(label):
        for p in app.panel.screens[app.panel_win.screen_index].parts:
            if p.label == label:
                return p
        raise KeyError(label)

    def click_part(label, hold=0.0):
        c = app.panel_win.canvas
        p = find_part(label)
        x1, y1, x2, y2 = c.rect(p)
        x, y = int((x1 + x2) / 2), int((y1 + y2) / 2)
        hp = c.hit(c.vx(x), c.vy(y))
        c.event_generate("<ButtonPress-1>", x=x, y=y)
        plc_log.append("state: hit=%s pressed=%s momentary=%s" % (
            hp.label if hp else None, c.pressed.label if c.pressed else None,
            c.momentary_on.label if c.momentary_on else None))
        if hold:
            t0 = time.time()
            while time.time() - t0 < hold:
                root.update()
                time.sleep(0.02)
        # 本物のマウスと同じく「ボタン1が押されている」状態つきで離す（ないとTkが押したままと扱う）
        c.event_generate("<ButtonRelease-1>", x=x, y=y, state=0x100)

    # 1. 通信開始（パスワードは保存済みの設定）
    step(lambda: app.start_comm(root), 2500)
    step(lambda: check("通信できる", app.link.fresh(), app.lbl_status.cget("text")))
    step(lambda: check("4Eで解除", "unlock" in plc_log))
    step(lambda: check("D0を読めている", app.items[0].read(app.link.mem) not in ("0", "----"),
                       app.items[0].read(app.link.mem)))
    step(lambda: grab(root, "01_main.png"))

    # 2. 操作画面を開く
    step(lambda: app.open_panel(), 1500)
    step(lambda: grab(app.panel_win, "02_panel_run.png"))

    # 3. ロック中は操作できない
    def locked_press():
        before = len(plc_log)
        app.panel_win.message = lambda m: plc_log.append("msg:" + m)
        click_part("運転")
        root.after(800, lambda: check("ロック中は書込まない",
                                      not any(l.startswith("bit 90 2000") for l in plc_log[before:])))
    step(locked_press, 1200)

    # 4. ロック解除してモーメンタリ
    step(lambda: app.set_unlocked(True), 300)

    def press():
        plc_log.append("state: unlocked=%s fresh=%s why=%s" % (app.unlocked, app.link.fresh(),
                                                               app.panel_win.can_operate()))
        click_part("運転", hold=0.2)
    step(press, 1500)
    step(lambda: check("モーメンタリ ON→OFF がPLCに届く",
                       "bit 90 2000 1" in plc_log and plc_log[-1] != "bit 90 2000 1" and
                       any(l == "bit 90 2000 0" for l in plc_log),
                       ", ".join(l for l in plc_log if l.startswith(("bit 90 2000", "msg:", "state:")))))

    # 5. 数値入力（ダイアログの代わりに直接）
    def number():
        p = None
        for s in app.panel.screens:
            for q in s.parts:
                if q.type == "INPUT":
                    p = q
                    break
            if p:
                break
        check("入力部品がある", p is not None)
        if p:
            check("範囲外は拒否", p.check_input("999999") is not None)
            p.write_number(app.link.mem, "12.3" if p.decimals == 1 else "123")
            app.panel_win.operated()
    step(number, 1200)
    step(lambda: check("数値入力がPLCに届く", any(l.startswith("word A8 ") for l in plc_log),
                       ", ".join(l for l in plc_log if l.startswith("word A8"))[-200:]))

    # 6. Yへの書込みは拒否
    def ywrite():
        n = len(plc_log)
        app.link.mem.local_write_bits(0x9D, 0, [True])
        root.after(600, lambda: check("Y0への書込みは送らない", not any("bit 9D" in l for l in plc_log[n:])))
    step(ywrite, 1000)

    # 7. 編集モードと各ダイアログ
    def edit():
        w = app.panel_win
        w.set_edit(True)
        part = w.cur().parts[0]
        w.canvas.select(part)
        grab(w, "03_panel_edit.png")
        for t in pn.TYPES:
            p = pn.Part(t, 0, 0, 150, 70)
            p.label = "試験"
            p.device = {"LAMP": "M0", "BUTTON": "M2000", "NUMBER": "D0", "INPUT": "D2000"}.get(t, "")
            d = A.PartDialog(w, p, app.panel, True)
            d.update()
            if t == "BUTTON":
                grab(d, "04_part_dialog.png")
            r = d.apply()
            check("部品ダイアログ " + t, r is not None)
            d.destroy()
        sd = A.ScreensDialog(w)
        sd.update()
        sd.destroy()
        w.set_edit(False)
        s = A.SettingsDialog(root, app.store)
        s.update()
        grab(s, "05_settings.png")
        check("設定ダイアログ", s.apply() is not None)
        s.destroy()
        it = A.ItemDialog(root)
        it.v_dev.set("Y10")
        it.v_w.set(True)
        it.messagebox_shown = False
        A.messagebox.showerror = lambda *a, **k: None
        check("一覧でYを書込可にできない", it.apply() is None)
        it.destroy()
        lw = A.LogWindow(app)
        lw.update()
        grab(lw, "06_log.png")
        lw.destroy()
    step(edit, 800)

    # 8. 横向き・JSON往復
    def landscape():
        js = app.panel.to_json()
        check("JSON往復", pn.Panel.from_json(js).to_json() == js)
        app.panel_win.toggle_orientation()
        app.panel_win.update()
        grab(app.panel_win, "07_panel_landscape.png")
        app.panel_win.toggle_orientation()
    step(landscape, 800)

    # 8b. 画面が多いときのタブの折り返し
    def many_tabs():
        w = app.panel_win
        keep = app.panel
        p = pn.Panel.from_json(keep.to_json())
        for i in range(15):
            p.screens.append(pn.Screen("B%d ストッパ・起立" % (i + 1)))
        app.panel = p
        w.rebuild_tabs()
        w.update()
        grab(w, "09_many_tabs.png")
        last = w._tab_buttons[-1]
        check("タブが全部見える", last.winfo_y() + last.winfo_height() <= w.tabs.winfo_height() + 1
              and last.winfo_x() + last.winfo_width() <= w.tabs.winfo_width() + 1,
              "tabs h=%d last=(%d,%d)" % (w.tabs.winfo_height(), last.winfo_x(), last.winfo_y()))
        app.panel = keep
        w.screen_index = 0
        w.rebuild_tabs()
    step(many_tabs, 800)

    # 8c. PC用の大きいキャンバス（1280×720）
    def custom():
        w = app.panel_win
        keep = app.panel
        p = pn.Panel.from_json(keep.to_json())
        p.custom_w, p.custom_h = 1280, 720
        js = p.to_json()
        q = pn.Panel.from_json(js)
        check("大きいキャンバスのJSON往復", q.is_custom() and q.width() == 1280 and q.to_json() == js)
        app.panel = q
        w.fit_orientation()
        w.rebuild_tabs()
        w.canvas.redraw()
        w.update()
        grab(w, "10_custom_canvas.png")
        check("大きいキャンバスで描ける", w.canvas.scale > 0)
        app.panel = keep
        w.fit_orientation()
        w.rebuild_tabs()
    step(custom, 800)

    # 9. 押したまま通信停止 → OFFが届いてからロック
    def stop_while_pressed():
        c = app.panel_win.canvas
        p = find_part("運転")
        x1, y1, x2, y2 = c.rect(p)
        c.event_generate("<ButtonPress-1>", x=int((x1 + x2) / 2), y=int((y1 + y2) / 2))
        root.update()
        time.sleep(0.3)
        n = len(plc_log)
        app.toggle_comm()   # 停止
        check("停止時に押しっぱなしのOFFが届く", "bit 90 2000 0" in plc_log[n:] or "bit 90 2000 0" in plc_log[-3:],
              ", ".join(plc_log[n - 2:]))
        check("停止時にロックする", "lock" in plc_log[n:])
    step(stop_while_pressed, 1500)

    def finish():
        grab(root, "08_main_stopped.png")
        ng = [r for r in results if not r[1]]
        with open(os.path.join(OUT, "result.txt"), "w", encoding="utf-8") as f:
            for name, ok, detail in results:
                f.write(("OK  " if ok else "NG  ") + name + ("  " + detail if detail else "") + "\n")
            f.write("\n--- 通信ログ ---\n")
            f.write("\n".join(getattr(app.link, "last_log", [])))
        print("RESULT: %d OK / %d NG" % (len(results) - len(ng), len(ng)))
        app.quit()
        os._exit(1 if ng else 0)
    step(finish, 100)

    def runner(i=0):
        if i >= len(steps):
            return
        fn, delay = steps[i]
        try:
            fn()
        except Exception as e:
            check("例外なし（手順%d）" % (i + 1), False, "%s\n%s" % (e, traceback.format_exc()))
        root.after(delay, lambda: runner(i + 1))

    root.after(1500, runner)


def main():
    FakePlc().start()
    tmp = tempfile.mkdtemp()
    store = A.Store(os.path.join(tmp, "config.json"))
    store["plcIp"] = "127.0.0.1"
    store["plcPort"] = PORT
    store["usePw"] = True
    store["pw"] = PW.decode()
    store["heartbeat"] = "D2019"
    here = os.path.dirname(os.path.abspath(__file__))
    sample = os.path.join(here, "..", "samples", "操作画面_通信試験_直接読み書き.json")
    with open(sample, encoding="utf-8") as f:
        store["panel"] = f.read()
    store["items"] = [{"name": "PLCカウンタ", "device": "D0", "format": "S16", "writable": False},
                      {"name": "32bit", "device": "D10", "format": "S32", "writable": False},
                      {"name": "運転中", "device": "M0", "format": "BIT", "writable": False},
                      {"name": "設定値", "device": "D2000", "format": "S16", "writable": True}]
    store.save()
    root = tk.Tk()
    if len(sys.argv) > 2:   # 表示倍率の試験（例: 2.0 = 150%）
        root.tk.call("tk", "scaling", float(sys.argv[2]))
    app = A.App(root, store)
    root.after(90000, lambda: (print("TIMEOUT"), os._exit(2)))
    run_steps(app, root)
    root.mainloop()


if __name__ == "__main__":
    main()
