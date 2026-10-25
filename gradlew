#!/usr/bin/env sh
# Gradle 启动脚本占位。
# 正式构建请二选一：
#   1) 装 Gradle 8.7+，然后直接： gradle :app:assembleDebug
#   2) 用 Android Studio 打开本目录，点 ▶ Build → Build APK(s)
# 这里不直接内嵌 gradle-wrapper.jar（~2MB），避免仓库臃肿。
echo "请先安装 Gradle >= 8.7，然后运行："
echo "  gradle :app:assembleDebug"
echo "产物：app/build/outputs/apk/debug/app-debug.apk"
exec gradle "$@"
