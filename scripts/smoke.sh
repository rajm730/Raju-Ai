#!/usr/bin/env bash
# Launches the debug APK on the CI emulator and prints what is on screen plus any crash/ANR logs.
set -x
PKG=com.raju.aiassistant.debug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant $PKG android.permission.RECORD_AUDIO || true
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb logcat -c

echo "===== RUN 1: animations off ====="
adb shell am start -W -n $PKG/com.raju.aiassistant.MainActivity
sleep 20
adb shell dumpsys activity activities | grep -iE "ResumedActivity" || true
adb shell uiautomator dump /sdcard/ui1.xml || true
adb shell cat /sdcard/ui1.xml | tr '>' '\n' | grep -oE '(text|content-desc)="[^"]+"' | head -80 || true
adb exec-out screencap -p > smoke-1.png
adb logcat -d | grep -iE "AndroidRuntime|FATAL|ANR in|Application Not Responding|raju|System.err|Choreographer" | head -150 || true

echo "===== RUN 2: animations on ====="
adb shell settings put global animator_duration_scale 1
adb shell settings put global transition_animation_scale 1
adb shell settings put global window_animation_scale 1
adb shell am force-stop $PKG
adb logcat -c
adb shell am start -W -n $PKG/com.raju.aiassistant.MainActivity
sleep 8
adb shell dumpsys gfxinfo $PKG reset > /dev/null
sleep 20
adb shell dumpsys activity activities | grep -iE "ResumedActivity" || true
adb exec-out screencap -p > smoke-2.png
adb shell dumpsys gfxinfo $PKG | grep -iE "Total frames|Janky|Percentile|90th|99th" || true
adb logcat -d | grep -iE "AndroidRuntime|FATAL|ANR in|Application Not Responding|raju|System.err|Skipped .* frames" | head -150 || true
adb logcat -d > logcat.txt
exit 0
