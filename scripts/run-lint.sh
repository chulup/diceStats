#!/usr/bin/env bash
# Run Android lint. Sets the Android SDK on PATH (the SDK tools live on the
# Windows host in this WSL setup). Extra args are passed straight to Gradle.
# The HTML report is written to app/build/reports/lint-results-debug.html.
set -euo pipefail

cd "$(dirname "$0")/.."

export PATH="$HOME/Android/Sdk/build-tools/36.1.0/:$HOME/Android/Sdk/platform-tools:$PATH"

./gradlew lint "$@"
