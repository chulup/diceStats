# CLAUDE.md

Guidance for Claude Code when working in this Android project.

## Stack

- **Language:** Kotlin (prefer over Java for new code)
- **UI:** Jetpack Compose (prefer over XML layouts)
- **Architecture:** MVVM — `ViewModel` + `StateFlow`/`UiState`, unidirectional data flow
- **DI:** Hilt
- **Async:** Coroutines + Flow
- **Build:** Gradle (Kotlin DSL, `build.gradle.kts`); version catalog in `gradle/libs.versions.toml`

## Project Layout

- `app/src/main/java/...` — source
- `app/src/main/res/` — resources
- `app/src/test/` — unit tests (JVM)
- `app/src/androidTest/` — instrumented/UI tests

## Commands

```bash
./gradlew assembleDebug        # build debug APK
./gradlew installDebug         # build + install on connected device/emulator
./gradlew test                 # JVM unit tests
./gradlew connectedAndroidTest # instrumented tests (needs device/emulator)
./gradlew lint                 # Android lint
./gradlew ktlintCheck          # style (if configured)
```

Run a single test: `./gradlew test --tests "com.example.MyClassTest"`

## Environment & Device Access

This runs in WSL2; the Android SDK tools and ADB server live on the Windows host.

- **Android SDK binaries** (`adb`, `aapt`, etc.) — add the SDK dirs to `PATH`:
  ```bash
  PATH="$HOME/Android/Sdk/build-tools/36.1.0/:$HOME/Android/Sdk/platform-tools:$PATH"
  ```
- **Physical device via remote ADB server** — point ADB at the host's server (the WSL default gateway):
  ```bash
  export ADB_SERVER_SOCKET=tcp:$(ip route show default | awk '{print $3}'):5037
  ```
  Then `adb devices` should list the connected device.

- **Instrumented tests against the remote device** — `./gradlew connectedAndroidTest`
  hangs in this setup: Gradle's DDMLIB only talks to `127.0.0.1:5037` and can't use
  the remote ADB server ("Cannot reach ADB server"). Build the APKs and drive the
  instrumentation through the `adb` CLI instead (it honors `ADB_SERVER_SOCKET`):
  ```bash
  ./gradlew assembleDebug assembleDebugAndroidTest
  adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
  adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
  adb shell am instrument -w \
    xyz.chulup.dicestats.test/androidx.test.runner.AndroidJUnitRunner
  ```
  (APK is split per ABI; pick the one matching `adb shell getprop ro.product.cpu.abi`.)

## Conventions

- Keep `Composable`s small and stateless; hoist state to the `ViewModel`.
- No business logic in Activities/Fragments/Composables — delegate to `ViewModel`/use cases.
- Use `viewModelScope` for coroutines tied to UI; never block the main thread.
- Strings in `res/values/strings.xml`, not hardcoded.
- Follow existing package-by-feature structure.
- Add dependencies via the version catalog, not inline literals.

## Before Finishing

- Run `./gradlew lint` and the relevant tests; report results honestly.
- Don't commit, push, or change signing/release config unless asked.
