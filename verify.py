import hashlib
import zipfile

apk = "release/BeecountShot-v2.3.apk"
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
