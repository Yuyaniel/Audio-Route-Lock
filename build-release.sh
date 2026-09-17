#!/usr/bin/env bash
# 本地构建并签名 release APK
set -e
cd "$(dirname "$0")"
GRADLE=/c/Users/Administrator/.gradle/wrapper/dists/gradle-9.5.0-bin/bvnork1r7n8i6kp5cnkibsc9q/gradle-9.5.0/bin/gradle
BT=/c/Users/Administrator/AppData/Local/Android/Sdk/build-tools/36.0.0

# 1. 确保有 SDK 路径声明（缺失时自动生成）
[ -f local.properties ] || printf 'sdk.dir=C:/Users/Administrator/AppData/Local/Android/Sdk\n' > local.properties

# 2. keystore 缺失时自动生成（保持参数一致以保证签名可互相覆盖）
if [ ! -f release.keystore ]; then
  "/c/Program Files/Eclipse Adoptium/jdk-17.0.20.8-hotspot/bin/keytool" -genkeypair \
    -keystore release.keystore -alias arl -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass audioroutelock -keypass audioroutelock -dname "CN=Audio Route Lock"
fi

# 3. 构建 + 签名
"$GRADLE" assembleRelease
"$BT/apksigner.bat" sign --ks release.keystore --ks-pass pass:audioroutelock --ks-key-alias arl \
  --out app/build/outputs/apk/release/app-release.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk
"$BT/apksigner.bat" verify app/build/outputs/apk/release/app-release.apk
echo "完成: app/build/outputs/apk/release/app-release.apk"