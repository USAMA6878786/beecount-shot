import os
import shutil
import sys
import zipfile

# 把 classes.dex 与 META-INF/xposed/* 追加进 aapt2 产出的 APK。
# 不重打整个包，直接在原 zip 上追加条目——aapt2 产出的 resources.arsc / AndroidManifest.xml
# 原样保留。对齐与签名放在后面的 zipalign / apksigner 处理。

base_apk = sys.argv[1]
dex = sys.argv[2]
xposed_dir = sys.argv[3]
out_apk = sys.argv[4]

if os.path.exists(out_apk):
    os.remove(out_apk)
shutil.copyfile(base_apk, out_apk)

with zipfile.ZipFile(out_apk, "a", zipfile.ZIP_DEFLATED) as z:
    existing = set(z.namelist())
    if "classes.dex" not in existing:
        z.write(dex, "classes.dex")
    for name in ("java_init.list", "module.prop", "scope.list"):
        src = os.path.join(xposed_dir, name)
        if not os.path.exists(src):
            raise SystemExit("missing xposed entry: " + src)
        z.write(src, "META-INF/xposed/" + name)

with zipfile.ZipFile(out_apk, "r") as z:
    bad = z.testzip()
    if bad:
        raise SystemExit("corrupt entry: " + bad)
    names = z.namelist()

for required in ("AndroidManifest.xml", "resources.arsc", "classes.dex"):
    if required not in names:
        raise SystemExit("APK is missing " + required)

print("APK entries (%d):" % len(names))
for n in sorted(names):
    print("  " + n)
