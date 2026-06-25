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
