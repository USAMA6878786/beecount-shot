import hashlib
import sys
import zipfile

# 用法：python verify.py release/BeecountShot-v2.5.apk
# 不给参数就默认检查 release/ 里版本号最大的那个 APK。
import glob
import os
import re

if len(sys.argv) > 1:
    apk = sys.argv[1]
else:
    cands = glob.glob("release/BeecountShot-v*.apk")
    def _ver(p):
        m = re.search(r"BeecountShot-v(\d+)\.(\d+)\.apk", p)
        return (int(m.group(1)), int(m.group(2))) if m else (0, 0)
    apk = max(cands, key=_ver) if cands else "release/BeecountShot-v2.5.apk"
z = zipfile.ZipFile(apk)
print("zip integrity:", z.testzip() or "OK")
print("entries:", len(z.namelist()))
for name in ("META-INF/xposed/java_init.list",
             "META-INF/xposed/scope.list",
             "META-INF/xposed/module.prop"):
    print("--- " + name + " ---")
    print(z.read(name).decode("utf-8").rstrip())

data = open(apk, "rb").read()
print("")
print("size   :", len(data), "bytes")
print("sha256 :", hashlib.sha256(data).hexdigest())
print("md5    :", hashlib.md5(data).hexdigest())
