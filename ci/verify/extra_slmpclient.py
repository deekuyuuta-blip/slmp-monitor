"""slmpclient（三菱のSLMPサンプルCライブラリでパケットを作る第三者ライブラリ）で、UDPとTCPからアプリのサーバを試す"""
import ctypes, struct, sys, traceback
from slmpclient import SLMPClient, SLMPPacket, FrameType, SLMPCommand, SLMPSubCommand
from slmpclient import slmp_controller as sc
port = int(sys.argv[1])
fails = 0

def check(name, ok, detail=""):
    global fails
    print(("OK  " if ok else "NG  ") + name, detail, flush=True)
    fails += 0 if ok else 1

# 応答の解析もCライブラリで（あれば）
clib = ctypes.CDLL(sc.so_file)
get_info = getattr(clib, "SLMP_GetSLMPInfo", None)
print("SLMP_GetSLMPInfo:", bool(get_info))

def parse(resp):
    """Cライブラリで応答を解析 → (endcode, data)。無ければ手で"""
    if get_info:
        info = sc.SLMPInfoC()
        buf = (ctypes.c_ubyte * len(resp)).from_buffer_copy(bytes(resp))
        data = ctypes.create_string_buffer(4096)
        info.pucData = ctypes.cast(data, ctypes.c_char_p)
        get_info.argtypes = [ctypes.POINTER(sc.SLMPInfoC), ctypes.POINTER(ctypes.c_ubyte * len(resp))]
        rc = get_info(ctypes.byref(info), buf)
        n = max(0, info.usDataLength - 2)
        return info.usEndCode, data.raw[:n], rc, info.ulFrameType
    return struct.unpack("<H", resp[9:11])[0], bytes(resp[11:]), None, None

def req(cmd, sub, data):
    p = SLMPPacket(ulFrameType=FrameType.SLMP_FTYPE_BIN_REQ_ST.value, usNetNumber=0, usNodeNumber=0xFF,
                   usProcNumber=0x03FF, usTimer=0x0010, usCommand=cmd, usSubCommand=sub, pucData=data)
    return bytes(p.create_stream())

q = lambda no, code, n: struct.pack("<I", no)[:3] + bytes([code]) + struct.pack("<H", n)
READ = SLMPCommand.SLMP_COMMAND_DEVICE_READ.value
WRITE = SLMPCommand.SLMP_COMMAND_DEVICE_WRITE.value
RRAND = SLMPCommand.SLMP_COMMAND_DEVICE_READ_RANDOM.value
W0, B1 = SLMPSubCommand.SUB_word0.value, SLMPSubCommand.SUB_bit1.value
print("cmd READ=%04X WRITE=%04X word=%04X bit=%04X" % (READ, WRITE, W0, B1))

for proto, tcp in (("UDP", 0), ("TCP", 1)):
    try:
        c = SLMPClient("127.0.0.1", port, tcp=tcp)
        c.open()
        c._socket.settimeout(3)
        r1 = req(READ, W0, q(100, 0xA8, 2))
        print(proto, "request bytes:", r1.hex())
        c.send(r1); resp = c.receive()
        print(proto, "response bytes:", bytes(resp).hex())
        end, data, rc, ft = parse(resp)
        print(proto, "parsed rc/frametype:", rc, ft)
        check(f"{proto}: D100,D101 読出し（Cライブラリで解析）", end == 0 and struct.unpack("<2h", data[:4]) == (555, -2), (end, data[:4].hex()))
        c.send(req(READ, B1, q(2000, 0x90, 3))); end, data, _, _ = parse(c.receive())
        check(f"{proto}: M2000〜2002 ビット読出し", end == 0 and data[:2] == b"\x10\x10", (end, data[:2].hex()))
        c.send(req(WRITE, W0, q(20, 0xA8, 2) + struct.pack("<2h", 7, -7))); end, _, _, _ = parse(c.receive())
        check(f"{proto}: D20 書込み", end == 0, end)
        c.send(req(READ, W0, q(20, 0xA8, 2))); end, data, _, _ = parse(c.receive())
        check(f"{proto}: D20 書込み値の読出し", end == 0 and struct.unpack("<2h", data[:4]) == (7, -7), data[:4].hex())
        c.send(req(RRAND, W0, bytes([1, 0]) + struct.pack("<I", 0)[:3] + b"\xA8")); end, data, _, _ = parse(c.receive())
        check(f"{proto}: 未対応コマンド→C059", end == 0xC059, "%04X" % end)
        c.close()
    except Exception:
        check(f"{proto}: 例外", False, traceback.format_exc()[-500:])
print("SLMPCLIENT PASSED" if fails == 0 else f"SLMPCLIENT FAILED {fails}")
