"""slmpclient（第三者・UDP対応）でアプリのサーバを試す。まずAPIのソースを表示"""
import inspect, sys, struct, traceback
import slmpclient
from slmpclient import client as cl
port = int(sys.argv[1])
src = inspect.getsource(cl)
print("---- client.py (先頭) ----")
print(src[:6000])
try:
    from slmpclient import util
    print("---- util.py (先頭) ----")
    print(inspect.getsource(util)[:5000])
except Exception as e:
    print("util:", e)
