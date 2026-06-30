#!/usr/bin/env bash
# Build the debug APK and install it on the device reachable via the remote ADB
# server (WSL -> Windows host). Picks the split APK matching the device's ABI.
set -euo pipefail

cd "$(dirname "$0")/.."

export PATH="$HOME/Android/Sdk/build-tools/36.1.0/:$HOME/Android/Sdk/platform-tools:$PATH"
export ADB_SERVER_SOCKET="tcp:$(ip route show default | awk '{print $3}'):5037"

./gradlew assembleDebug

abi="$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
apk="app/build/outputs/apk/debug/app-${abi}-debug.apk"

if [[ ! -f "$apk" ]]; then
    echo "No APK for device ABI '$abi' at $apk" >&2
    echo "Available:" >&2
    ls app/build/outputs/apk/debug/*.apk >&2
    exit 1
fi

echo "Installing $apk on $abi device..."
# Cap the install so a broken/unreachable remote ADB server can't hang the build.
# If this times out (exit 124) or otherwise fails, do NOT try to fix the ADB link
# yourself — notify the user that the device is unreachable and move on.
timeout 60 adb install -r "$apk"
