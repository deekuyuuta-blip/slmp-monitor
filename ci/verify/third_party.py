"""第三者ライブラリ（PLC側と同じSLMPクライアント役）でアプリのサーバを試す"""
import json, sys, traceback, inspect
port = int(sys.argv[1])
R = {"checks": {}, "info": {}}

def check(name, ok, detail=""):
    R["checks"][name] = {"ok": bool(ok), "detail": str(detail)[:300]}
    print(("OK  " if ok else "NG  ") + name, str(detail)[:200], flush=True)

def guard(name, fn):
    try:
        fn()
    except Exception as e:
        check(name, False, "".join(traceback.format_exception_only(type(e), e)).strip())

# ---------------- pymcprotocol (TCP, Q / iQ-R) ----------------
try:
    import pymcprotocol
    R["info"]["pymcprotocol"] = getattr(pymcprotocol, "__version__", "?")
    for plctype in ("Q", "iQ-R"):
        def run(plctype=plctype):
            c = pymcprotocol.Type3E(plctype=plctype)
            c.setaccessopt(commtype="binary")
            c.connect("127.0.0.1", port)
            v = c.batchread_wordunits(headdevice="D100", readsize=2)
            check(f"pymcprotocol {plctype}: D100,D101 読出し", v == [555, -2] or v == [555, 65534], v)
            bits = c.batchread_bitunits(headdevice="M2000", readsize=3)
            check(f"pymcprotocol {plctype}: M2000〜2002 ビット読出し", list(bits) == [1, 0, 1], bits)
            c.batchwrite_wordunits(headdevice="D10", values=[11, -22, 33])
            check(f"pymcprotocol {plctype}: D10 書込み→読出し", c.batchread_wordunits(headdevice="D10", readsize=3) in ([11, -22, 33], [11, 65514, 33]), "")
            c.batchwrite_bitunits(headdevice="M10", values=[1, 0, 1])
            check(f"pymcprotocol {plctype}: M10 ビット書込み→読出し", list(c.batchread_bitunits(headdevice="M10", readsize=3)) == [1, 0, 1], "")
            big = c.batchread_wordunits(headdevice="D0", readsize=960)
            check(f"pymcprotocol {plctype}: 960ワード読出し", len(big) == 960, len(big))
            xb = c.batchread_bitunits(headdevice="X1F", readsize=1)
            check(f"pymcprotocol {plctype}: X1F（16進番号）読出し", len(xb) == 1, xb)
            try:
                c.randomread(word_devices=["D0"], dword_devices=[])
                check(f"pymcprotocol {plctype}: 未対応コマンドは異常応答", False, "例外が出ない")
            except Exception as e:
                check(f"pymcprotocol {plctype}: 未対応コマンドは異常応答", "not supported" in str(e) or "C059" in str(e).upper(), e)
            # 異常応答のあとも同じ接続で続けられる
            check(f"pymcprotocol {plctype}: 異常応答後も継続", c.batchread_wordunits(headdevice="D100", readsize=1)[0] == 555, "")
            c.close()
        guard(f"pymcprotocol {plctype}", run)
except ImportError as e:
    check("pymcprotocol 導入", False, e)

# ---------------- pyslmpclient (UDP / TCP) ----------------
try:
    import pyslmpclient
    R["info"]["pyslmpclient"] = getattr(pyslmpclient, "__version__", "?")
    R["info"]["pyslmpclient_dir"] = [n for n in dir(pyslmpclient) if not n.startswith("_")]
    cls = getattr(pyslmpclient, "SLMPClient", None)
    if cls:
        R["info"]["SLMPClient_init"] = str(inspect.signature(cls.__init__))
        R["info"]["SLMPClient_methods"] = {n: str(inspect.signature(getattr(cls, n))) for n in dir(cls)
                                           if not n.startswith("_") and callable(getattr(cls, n))}
except ImportError as e:
    R["info"]["pyslmpclient"] = f"import error: {e}"

json.dump(R, open("third_party.json", "w"), ensure_ascii=False, indent=1)
