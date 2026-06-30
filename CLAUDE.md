# CLAUDE.md

Guidance for Claude Code when working in this Android project.

## Docs / file map

- [DESIGN.md](DESIGN.md) — architecture, data model, screens, scope/roadmap (MVP→v2→v3).
- [DEVPLAN.md](DEVPLAN.md) — immediate tasks + future-improvements backlog.
- [CHANGELOG.md](CHANGELOG.md) — completed work (MVP steps 1–6).
- [RESEARCH.md](RESEARCH.md) — recognition research (see below).
- `app/src/main/java/xyz/chulup/dicestats/` — source, package-by-feature
  (`feature/*`, `data/*`, `recognition/*`, `ui/*`, `di/*`).
- `photos/` + `photos/tests.txt` — recognition test images and ground truth (boxes/values).
- `app/src/test/resources/photos/*.ppm` — downscaled JVM detection fixtures.

## Recognition research (2026-06)

Evaluated detecting/reading numbered + polyhedral dice (d6 numerals, d8/d10/d20/d100)
beyond the MVP's pip-d6 case — full writeup in [RESEARCH.md](RESEARCH.md). Key conclusions:
**detection** — keep saturation segmentation for cluttered scenes, add an Otsu/brightness
pass for plain backgrounds (NMS union); **reading** — ready-made OCR (ML Kit) is
insufficient, recommend a small offline TFLite digit CNN (synthetic-trained) with ML Kit as
a bridge; **architecture** — detect → crop → route by die type → pip-count or numeral-read.
Ornate engraved dice (photo 109) deferred. All recognition stays **offline on-device**.

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
./scripts/build-install.sh     # build + install on the remote-ADB device (picks the matching ABI split)
./scripts/run-unit-tests.sh    # JVM unit tests (sets the SDK PATH); passes args through to Gradle
./scripts/android-test.sh      # build + install + run instrumented tests on the remote-ADB device
./gradlew test                 # JVM unit tests
./gradlew connectedAndroidTest # instrumented tests (needs device/emulator)
./gradlew lint                 # Android lint
./gradlew ktlintCheck          # style (if configured)
```

Run a single unit test: `./scripts/run-unit-tests.sh --tests "xyz.chulup.dicestats.MyClassTest"`
Run one instrumented test class: `./scripts/android-test.sh xyz.chulup.dicestats.recognition.PipRecognitionTest`

**OpenCV note:** OpenCV is the bytedeco/JavaCPP build (`org.bytedeco:opencv`), one artifact
family with natives for both Android (`android-arm64`) and the desktop (`linux-x86_64`), so
pip counting runs on-device *and* in JVM unit tests. The desktop build links `opencv_highgui`
against GTK2, so JVM tests need it once: `sudo apt-get install -y libgtk2.0-0`.

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

  The install step in `./scripts/build-install.sh` is wrapped in `timeout 60` so an
  unreachable host ADB server can't hang the build. **If the install times out or
  fails (e.g. "cannot connect to daemon", exit 124), do not try to resolve the ADB
  link yourself — notify the user that the device is unreachable and move on.** The
  build/APK is still good; only the install needs a working device link.

- **Instrumented tests against the remote device** — use `./scripts/android-test.sh`
  (optionally a test class as `$1`). `./gradlew connectedAndroidTest` hangs in this
  setup: Gradle's DDMLIB only talks to `127.0.0.1:5037` and can't use the remote ADB
  server ("Cannot reach ADB server"). The script builds the APKs and drives the
  instrumentation through the `adb` CLI instead (it honors `ADB_SERVER_SOCKET`), which
  is equivalent to:
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
