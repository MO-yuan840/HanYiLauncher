#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
echo ">> 编译漢藝啟動器 debug APK（需要 Android SDK 已装、JDK 17、./gradlew 可联网拉依赖）"
if [ ! -d "$ANDROID_HOME" ] && [ ! -d "$ANDROID_SDK_ROOT" ]; then
  echo "!! 请先 export ANDROID_HOME=/你的/sdk 路径"; exit 2
fi
./gradlew :app:assembleDebug --no-daemon --offline || ./gradlew :app:assembleDebug --no-daemon
APK=$(find app/build/outputs/apk/debug -name '*.apk' | head -1)
echo ">> APK: $APK  ($(du -h "$APK" | cut -f1))"
cp "$APK" "/data/workspace/漢藝啟動器_HanYi_v0.2-debug.apk" && echo ">> 已导出安装包"
