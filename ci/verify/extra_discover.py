"""導入できたライブラリのAPIを表示（UDP対応のものを探す）"""
import importlib, inspect, pkgutil
for name in ("pymelsec", "pyslmp", "slmpclient", "mcprotocol", "melsec_slmp", "slmp"):
    try:
        m = importlib.import_module(name)
    except Exception as e:
        print(f"== {name}: import失敗 {e}")
        continue
    print(f"== {name} {getattr(m, '__version__', '')} file={getattr(m, '__file__', '')}")
    subs = [x.name for x in pkgutil.iter_modules(getattr(m, '__path__', []))]
    print("  submodules:", subs)
    for n in dir(m):
        if n.startswith("_"): continue
        o = getattr(m, n)
        if inspect.isclass(o):
            try: sig = str(inspect.signature(o.__init__))
            except Exception: sig = "?"
            meths = [k for k in dir(o) if not k.startswith("_")]
            print(f"  class {n}{sig}\\n    {meths[:60]}")
    try:
        src = inspect.getsource(m)
        for kw in ("SOCK_DGRAM", "udp", "UDP"):
            if kw in src: print(f"  contains {kw}")
    except Exception:
        pass
