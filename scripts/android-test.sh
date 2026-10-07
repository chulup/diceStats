#!/usr/bin/env bash
# Build, install, and run the instrumented (on-device) tests on the device reachable via
# the remote ADB server (WSL -> Windows host). connectedAndroidTest can't use the remote
# ADB server, so we drive instrumentation through the adb CLI instead.
#
# Usage:
#   scripts/android-test.sh                       # run all instrumented tests
#   scripts/android-test.sh <fully.qualified.Class>   # run one test class, e.g.
#   scripts/android-test.sh xyz.chulup.dicestats.recognition.PipRecognitionTest
set -euo pipefail

cd "$(dirname "$0")/.."

export PATH="$HOME/Android/Sdk/build-tools/36.1.0/:$HOME/Android/Sdk/platform-tools:$PATH"
# Under WSL, adb talks to the Windows host's ADB server; elsewhere the local one is used.
if grep -qi microsoft /proc/version; then
    export ADB_SERVER_SOCKET="tcp:$(ip route show default | awk '{print $3}'):5037"
fi

./gradlew :app:assembleDebug :app:assembleDebugAndroidTest

abi="$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
app_apk="app/build/outputs/apk/debug/app-${abi}-debug.apk"
test_apk="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

if [[ ! -f "$app_apk" ]]; then
    echo "No app APK for device ABI '$abi' at $app_apk" >&2
    ls app/build/outputs/apk/debug/*.apk >&2
    exit 1
fi

adb install -r "$app_apk"
adb install -r "$test_apk"

runner="xyz.chulup.dicestats.test/androidx.test.runner.AndroidJUnitRunner"
if [[ $# -gt 0 ]]; then
    adb shell am instrument -w -e class "$1" "$runner"
else
    adb shell am instrument -w "$runner"
fi
