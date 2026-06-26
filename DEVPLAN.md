# DiceStats — Development Plan

Incremental MVP build. Each step is independently runnable and builds on the last.
See [DESIGN.md](DESIGN.md) for the full architecture this works toward.

> **Module note:** DESIGN.md targets a multi-module layout. Early steps use a single
> `:app` module to avoid premature structure; we split into `:core:*` / `:feature:*` /
> `:recognition` modules once there's enough code to justify it (around step 3–4).

## Step 1 — Photo capture & storage (most basic MVP)

**Goal:** Launch the app, take a photo, store it in a dedicated on-device directory,
and list captured photos.

- [x] Project scaffold: Gradle (Kotlin DSL + version catalog), `:app` module, manifest.
- [x] Compose + Material3 theme, single-activity, Navigation.
- [x] `CAMERA` runtime permission handling.
- [x] Capture screen: CameraX preview + shutter → save JPEG.
- [x] `PhotoStorage`: writes to `getExternalFilesDir(null)/rolls`.
- [x] Roll log screen: list stored photos (newest first), FAB → capture.

**Done when:** a photo taken in-app appears in the roll log and persists in the
`rolls/` directory across app restarts.

## Step 2 — Die detection

**Goal:** Find each d6 in a captured photo.

- [x] `:recognition` interface: `DieDetector.detect(image) → List<BoundingBox>`.
- [x] Detect dice — spike chose classical CV (HSV saturation/value segmentation, in
      pure Kotlin so it stays JVM-unit-testable).
- [x] Overlay detected bounding boxes on the photo for visual confirmation.

**Done when:** the app draws a box around each die in a captured photo. ✓

## Step 3 — Pip counting

**Goal:** Read the value of each detected die.

- [x] OpenCV pip/blob counting on each die crop → value 1–6 (`PipCounter`,
      SimpleBlobDetector, both polarities).
- [x] Produce `DetectedDie(value, boundingBox)` per die (`DieRecognizer`).
      Confidence is currently binary — value is `null` when pips are unreadable;
      a numeric `valueConfidence` 0..1 is deferred.
- [x] Show the recognized value next to each box; unreadable faces flagged amber "?".

**Done when:** each detected die shows a 1–6 value. ✓ (numeric confidence deferred)

## Step 4 — Die database & store rolls

**Goal:** Persist recognized rolls with user confirmation.

- [x] Room DB: `Die`, `Roll`, `DieResult` (nullable forward FKs per DESIGN.md).
- [x] Wire Hilt for DI.
- [x] Confirm screen: per detected die, edit value + assign to a `Die`
      (pick from list / register new).
- [x] Persist `Roll` + `DieResult`s; roll log reads from DB.

**Done when:** a confirmed capture is saved to the DB and survives restart, with
each die assigned and its value recorded. ✓

## Step 5 — Per-die statistics

**Goal:** Surface the recorded data as per-die fairness stats.

- [x] Dice list screen (registered dice + roll counts), reachable from the roll log.
- [x] `DieStatistics` (pure Kotlin): 1–6 distribution, total, mean, chi-square +
      p-value (regularized incomplete gamma), fairness verdict.
- [x] Die stats screen: distribution bar chart, summary (rolls/mean/expected),
      fairness card ("looks fair / possibly biased / not enough rolls").

**Done when:** tapping a registered die shows its face distribution and a
fairness indicator. ✓

---

After step 5 the MVP is feature-complete. v2/v3 features (Games, Die Groups,
Players) follow per DESIGN.md.
