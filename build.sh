#!/usr/bin/env bash
# BeeCount「记账截图」LSPosed 模块 —— 无 Gradle 构建脚本
# 链路：aapt2 编译/链接资源 -> javac -> d8 -> 打包 dex 与 xposed 注册文件 -> zipalign -> apksigner
set -e

ROOT="D:/buddy/2026-10-04-18-03-46"
PROJ="$ROOT/beecount-shot"
TC="$ROOT/toolchain"
JAVA_HOME="$TC/jdk-17.0.20.1+1"
JAVA="$JAVA_HOME/bin/java.exe"
JAVAC="$JAVA_HOME/bin/javac.exe"
KEYTOOL="$JAVA_HOME/bin/keytool.exe"
SDK="$TC/sdk"
BT="$SDK/build-tools/35.0.0"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"
LIBXPOSED="$TC/libxposed/classes.jar"

OUT="$PROJ/build"
PY="C:/Users/Administrator/.workbuddy/binaries/python/versions/3.13.12/python.exe"

echo "=== 0. 清理 ==="
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/res" "$OUT/gen"

echo "=== 1. aapt2 compile 资源 ==="
"$BT/aapt2.exe" compile --dir "$PROJ/res" -o "$OUT/res.zip"

echo "=== 2. aapt2 link 生成基础 APK + R.java ==="
"$BT/aapt2.exe" link \
  -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$PROJ/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 35 \
  --version-code 14 \
  --version-name 2.3 \
  "$OUT/res.zip"

echo "=== 3. javac 编译 Java 源码（含生成的 R.java） ==="
find "$PROJ/src" "$OUT/gen" -name "*.java" > "$OUT/sources.txt"
cat "$OUT/sources.txt"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -nowarn \
  -bootclasspath "$ANDROID_JAR" \
  -classpath "$LIBXPOSED" \
  -d "$OUT/classes" \
  "@$OUT/sources.txt"

echo "=== 4. 打成 jar 并转 dex ==="
"$JAVA_HOME/bin/jar.exe" cf "$OUT/program.jar" -C "$OUT/classes" .
"$JAVA" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --release --lib "$ANDROID_JAR" --min-api 26 \
  --output "$OUT/dex" \
  "$OUT/program.jar"

echo "=== 5. 打包 dex + xposed 注册文件 ==="
"$PY" "$PROJ/pack.py" "$OUT/base.apk" "$OUT/dex/classes.dex" "$PROJ/xposed" "$OUT/unsigned.apk"

echo "=== 6. 生成签名密钥 ==="
KS="$PROJ/beecount-shot.jks"
if [ ! -f "$KS" ]; then
  "$KEYTOOL" -genkeypair -noprompt \
    -keystore "$KS" -storepass android -keypass android \
    -alias beecountshot -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=BeecountShot, OU=xtgxiso, O=xtgxiso, L=Beijing, S=Beijing, C=CN"
fi

echo "=== 7. zipalign 对齐 ==="
"$BT/zipalign.exe" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "=== 8. apksigner 签名 ==="
"$JAVA" -jar "$BT/lib/apksigner.jar" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$PROJ/BeecountShot-v2.3.apk" \
  "$OUT/aligned.apk"

echo "=== 9. 校验签名 ==="
"$JAVA" -jar "$BT/lib/apksigner.jar" verify --print-certs "$PROJ/BeecountShot-v2.3.apk"

echo ""
echo "=== 完成 ==="
ls -la "$PROJ/BeecountShot-v2.3.apk"
