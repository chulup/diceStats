# DiceStats

An offline Android app for logging physical dice rolls from photos and tracking
per-die statistics. Use your dice as usual in any tabletop game — after each roll,
snap a photo and DiceStats recognizes the dice, records the outcome, and builds up
distribution stats over time.

All image processing runs **on-device** — no network, no cloud.

## How it works

1. Take a photo of your rolled dice.
2. The app detects each d6 in the frame and counts the pips.
3. You confirm/correct the values and assign each die on a confirm screen.
4. Results are saved; per-die statistics and distributions update.

Photos are stored in a separate on-device directory; outcomes are kept in a local
database.

## Features

**MVP**
- Multi-die detection per photo (d6, pip counting)
- Manual die assignment on a confirm screen
- Per-die distribution, count, mean, and a chi-square fairness indicator

**Planned**
- *v2* — Games (timed sessions) and Die Groups (joint/sum distributions)
- *v3* — Players and per-player stats within a game

## Tech stack

- Kotlin + Jetpack Compose
- MVVM, Hilt, Coroutines + Flow
- CameraX, Room
- On-device CV/ML: LiteRT (TFLite) detection + OpenCV pip counting

## Build

```bash
./gradlew assembleDebug   # build debug APK
./gradlew installDebug    # build + install on a connected device/emulator
./gradlew test            # unit tests
```

## Permissions

- `CAMERA` — required to photograph rolls. No network, storage, or location
  permissions are used.

## Documentation

See [DESIGN.md](DESIGN.md) for the full architecture, data model, recognition
pipeline, and roadmap.
