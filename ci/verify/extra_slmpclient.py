import inspect, os, slmpclient
from slmpclient import slmp_controller as sc
d = os.path.dirname(slmpclient.__file__)
print("files:", os.listdir(d))
print(inspect.getsource(sc)[:9000])
for root, _, files in os.walk(d):
    for f in files:
        if f.endswith((".c", ".h")):
            p = os.path.join(root, f)
            txt = open(p, errors="replace").read()
            print(f"==== {p} ({len(txt)})")
            print(txt[:2500])
