#!/usr/bin/env bash
# Run the JVM unit tests. Sets the Android SDK on PATH (the SDK tools live on the
# Windows host in this WSL setup). Extra args are passed straight to Gradle, e.g.:
#   scripts/run-unit-tests.sh --tests "xyz.chulup.dicestats.recognition.PhotoDetectionTest"
#
# Note: the recognition sweep pip-counts via bytedeco/JavaCPP OpenCV, whose Linux build
# needs GTK2 at load time — install it once with: sudo apt-get install -y libgtk2.0-0
set -euo pipefail

cd "$(dirname "$0")/.."

export PATH="$HOME/Android/Sdk/build-tools/36.1.0/:$HOME/Android/Sdk/platform-tools:$PATH"

./gradlew :app:testDebugUnitTest "$@"
