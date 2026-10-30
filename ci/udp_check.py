"""UDPでスマホ(エミュレータ)のD100とM100を読む。結果をJSONで出力"""
import json, socket, struct, sys, time
host, port, out = sys.argv[1], int(sys.argv[2]), sys.argv[3]
def frame(cmd, sub, data):
    body = struct.pack("<HHH", 0x0010, cmd, sub) + data
    return bytes([0x50, 0, 0, 0xFF, 0xFF, 0x03, 0]) + struct.pack("<H", len(body)) + body
q = lambda no, code, n: struct.pack("<I", no)[:3] + bytes([code]) + struct.pack("<H", n)
res = {"ok": False}
u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
u.settimeout(2)
for i in range(10):
    try:
        u.sendto(frame(0x0401, 0, q(100, 0xA8, 1)), (host, port))
        r, _ = u.recvfrom(8192)
        res["d100"] = struct.unpack("<h", r[11:13])[0]
        u.sendto(frame(0x1401, 0, q(0, 0xA8, 1) + struct.pack("<h", 777)), (host, port))
        r, _ = u.recvfrom(8192)
        res["write_end"] = struct.unpack("<H", r[9:11])[0]
        res["ok"] = True
        break
    except Exception as e:
        res["error"] = str(e)
        time.sleep(1)
json.dump(res, open(out, "w"))
print(res)
