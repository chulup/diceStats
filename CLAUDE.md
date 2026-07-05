# CLAUDE.md

Guidance for Claude Code when working in this Android project.

## Docs / file map

- [DESIGN.md](DESIGN.md) — architecture, data model, screens, scope/roadmap.
- [DEVPLAN.md](DEVPLAN.md) — immediate tasks + future-improvements backlog.
- [CHANGELOG.md](CHANGELOG.md) — completed work.
- [RESEARCH.md](RESEARCH.md) — recognition research (see below).
- `design-records/` — Architecture Design Records (`YYYY-MM-DD-slug.md`, one per settled decision); see its README for the format.
- `app/src/main/java/xyz/chulup/dicestats/` — source, package-by-feature (`feature/*`, `data/*`, `recognition/*`, `ui/*`, `di/*`).
- `photos/` + `photos/tests.txt` — recognition test images and ground truth.
- `app/src/test/resources/photos/*.ppm` — downscaled JVM detection fixtures.

## Recognition research (2026-06)

Evaluated reading numbered + polyhedral dice beyond the MVP's pip-d6 case; full writeup in [RESEARCH.md](RESEARCH.md). Conclusions: **detection** — keep saturation segmentation, add an Otsu/brightness pass for plain backgrounds (NMS union); **reading** — offline TFLite digit CNN over ready-made OCR; **architecture** — detect → crop → route by die type → pip-count or numeral-read. All recognition stays offline on-device.

## Stack

- **Language:** Kotlin (prefer over Java)
- **UI:** Jetpack Compose (prefer over XML)
- **Architecture:** MVVM — `ViewModel` + `StateFlow`/`UiState`, unidirectional data flow
- **DI:** Hilt
- **Async:** Coroutines + Flow
- **Build:** Gradle (Kotlin DSL); version catalog in `gradle/libs.versions.toml`

## Project Layout

- `app/src/main/java/...` — source; `app/src/main/res/` — resources
- `app/src/test/` — JVM unit tests; `app/src/androidTest/` — instrumented/UI tests

## Commands

```bash
./gradlew assembleDebug        # build debug APK
./scripts/build-install.sh     # build + install APK on device
./scripts/run-unit-tests.sh    # JVM unit tests (sets SDK PATH); passes args to Gradle
./scripts/android-test.sh      # build + install + run instrumented tests on device
./gradlew test                 # JVM unit tests
./gradlew lint                 # Android lint
./gradlew ktlintCheck          # style (if configured)
```

- Single unit test: `./scripts/run-unit-tests.sh --tests "xyz.chulup.dicestats.MyClassTest"`
- One instrumented test class: `./scripts/android-test.sh xyz.chulup.dicestats.recognition.PipRecognitionTest`
- `build-install.sh`: fix the build if it fails; if install fails, notify the user and move on.
- `android-test.sh`: use it instead of `./gradlew connectedAndroidTest`, which hangs in this WSL/remote-ADB setup.

**OpenCV note:** OpenCV is the bytedeco/JavaCPP build (`org.bytedeco:opencv`) with natives for both Android and desktop, so pip counting runs on-device *and* in JVM tests. JVM tests need GTK2 once: `sudo apt-get install -y libgtk2.0-0`.

## Conventions

- Keep `Composable`s small and stateless; hoist state to the `ViewModel`.
- No business logic in Activities/Fragments/Composables — delegate to `ViewModel`/use cases.
- Use `viewModelScope` for UI coroutines; never block the main thread.
- Strings in `res/values/strings.xml`, not hardcoded.
- Follow existing package-by-feature structure.
- Add dependencies via the version catalog, not inline literals.

## Before Finishing

- Run `./gradlew lint` and the relevant tests; report results honestly.
- Don't commit, push, or change signing/release config unless asked.
