# Recognition benchmark runs against the live on-device database

- **Status:** Accepted
- **Date:** 2026-07-07
- **Related:** `androidTest/.../recognition/DbBenchmarkTest.kt`,
  supersedes the frozen-asset approach of the original DB benchmark

## Context

`DbBenchmarkTest` measured recognition against `androidTest/assets/dbphotos/` — a
frozen set of 103 photos exported once from the app database, oriented, downscaled
and baked into the test APK alongside a `tests.txt` ground truth. Growing or
refreshing that set meant re-running a manual export/orient/downscale/label
pipeline, and the fixtures carried an EXIF-orientation footgun: stored boxes are
normalized in the app's *oriented* display frame, but static-image decoders do not
auto-rotate, so overlays and fixtures had to re-apply the rotation by hand.

The goal shifted to benchmarking against the rolls **actually on the device** —
each already confirmed or corrected by the user on the confirm screen — rather than
a stale snapshot.

## Decision

Rewrite the benchmark to read the live on-device data at runtime:

- Open the app's real Room `DiceDatabase` (`dicestats.db`) the **same way the app
  does** (same migrations), and read every roll and its `die_results` as ground
  truth — the values the user accepted or corrected.
- Decode each roll's stored `photoPath` through the **production** `decodeOriented`
  path (EXIF-oriented, downscaled to 1280 px) before running `DieRecognizer`, so
  fresh detections land in the same oriented, normalized frame the stored boxes
  were saved in. Replaying the real decode path removes the EXIF mismatch by
  construction.
- Keep it **report-only** (recall / value accuracy / false-positive budget
  scorecard, logged under tag `DbBenchmark`), pip-dice only.

Because it constructs `DiceDatabase`, the benchmark is tied to the app's current
schema version and must run against a build whose schema matches the device DB.

## Alternatives considered

- **Keep the frozen `dbphotos/` asset benchmark.** Reproducible across devices and
  time, but stale, small, and requires the manual pipeline to grow. It cannot
  reflect the device's real, expanding corpus, which was the whole request.
- **Read the SQLite file directly, read-only, bypassing Room.** Version-agnostic
  (opens any schema) and safe against the user's data. Prototyped when the device
  DB (v4) refused to open against the then-current source schema (v3), but rejected
  in favour of using `DiceDatabase` as the app does — the mismatch was instead
  resolved by merging the dice-pools work (which brought the source to v4). The
  accepted trade-off: Room cannot open a DB **newer** than the built schema.

## Consequences

- The benchmark reflects whatever rolls the device holds now (137 photos / 336
  dice at first run) — larger and current, but not reproducible across devices or
  over time; the numbers move as the user captures more rolls.
- The test is coupled to the app schema: a device DB newer than the built
  `DiceDatabase` fails to open. This surfaced immediately as a v3/v4 mismatch and
  is the reason the dice-pools branch had to land first.
- Still survivorship-biased — only dice that reached the DB are scored, so
  detector *misses* (dice the user could not add) are absent. This is a direct
  driver of the confirm-screen manual die recovery feature and the deferred
  reported-photo re-processing workflow.
