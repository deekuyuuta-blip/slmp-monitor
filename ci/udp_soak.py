"""シンプルCPU通信相当のUDP連続通信（設定4つ、各100ms周期）。結果をJSONで追記"""
import json, socket, struct, sys, threading, time
host, port, secs, phase, out = sys.argv[1], int(sys.argv[2]), float(sys.argv[3]), sys.argv[4], sys.argv[5]
def frame(cmd, sub, data, net=0):
    body = struct.pack("<HHH", 0x0010, cmd, sub) + data
    return bytes([0x50, 0, net, 0xFF, 0xFF, 0x03, 0]) + struct.pack("<H", len(body)) + body
q = lambda no, code, n: struct.pack("<I", no)[:3] + bytes([code]) + struct.pack("<H", n)
lat, tout, bad, n = [], [0], [0], [0]
lock = threading.Lock()
def worker(i):
    u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); u.settimeout(1.0)
    k = 0; end = time.time() + secs
    while time.time() < end:
        k += 1
        v = (i * 1000 + k) % 30000
        for req, exp in ((frame(0x1401, 0, q(3000 + i * 10, 0xA8, 10) + struct.pack("<10h", *([v] * 10)), i), None),
                         (frame(0x0401, 0, q(3000 + i * 10, 0xA8, 10), i), v),
                         (frame(0x0401, 0, q(100, 0xA8, 1), i), None)):
            t0 = time.perf_counter()
            try:
                u.sendto(req, (host, port))
                while True:   # 古い（タイムアウト済み）応答は読み捨てる
                    r, _ = u.recvfrom(4096)
                    if r[2] == i: break
                with lock:
                    lat.append((time.perf_counter() - t0) * 1000); n[0] += 1
                    if exp is not None and struct.unpack("<10h", r[11:31]) != tuple([exp] * 10): bad[0] += 1
            except socket.timeout:
                with lock: tout[0] += 1
        time.sleep(0.1)
ths = [threading.Thread(target=worker, args=(i,)) for i in range(4)]
for t in ths: t.start()
for t in ths: t.join()
lat.sort()
res = {"phase": phase, "ok_responses": n[0], "timeouts": tout[0], "value_mismatch": bad[0],
       "avg_ms": round(sum(lat) / len(lat), 1) if lat else None,
       "p99_ms": round(lat[int(len(lat) * 0.99)], 1) if lat else None,
       "max_ms": round(lat[-1], 1) if lat else None}
print(res, flush=True)
try:
    allr = json.load(open(out))
except Exception:
    allr = []
allr.append(res)
json.dump(allr, open(out, "w"), ensure_ascii=False, indent=1)
