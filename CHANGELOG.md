# DiceStats — Changelog

Completed work, moved here from [DEVPLAN.md](DEVPLAN.md) as it lands. See
[DESIGN.md](DESIGN.md) for the architecture this builds toward.

## MVP (steps 1–6)

The incremental MVP build. Each step was independently runnable and built on the last.

> **Module note:** DESIGN.md targets a multi-module layout. These steps used a single
> `:app` module to avoid premature structure; the split into `:core:*` / `:feature:*` /
> `:recognition` modules is deferred until there's enough code to justify it.

### Step 1 — Photo capture & storage

Launch the app, take a photo, store it in a dedicated on-device directory, and list
captured photos.

- [x] Project scaffold: Gradle (Kotlin DSL + version catalog), `:app` module, manifest.
- [x] Compose + Material3 theme, single-activity, Navigation.
- [x] `CAMERA` runtime permission handling.
- [x] Capture screen: CameraX preview + shutter → save JPEG.
- [x] `PhotoStorage`: writes to `getExternalFilesDir(null)/rolls`.
- [x] Roll log screen: list stored photos (newest first), FAB → capture.

**Done:** a photo taken in-app appears in the roll log and persists in the `rolls/`
directory across app restarts.

### Step 2 — Die detection

Find each d6 in a captured photo.

- [x] `:recognition` interface: `DieDetector.detect(image) → List<BoundingBox>`.
- [x] Detect dice — spike chose classical CV (HSV saturation/value segmentation, in
      pure Kotlin so it stays JVM-unit-testable).
- [x] Overlay detected bounding boxes on the photo for visual confirmation.

**Done:** the app draws a box around each die in a captured photo.

### Step 3 — Pip counting

Read the value of each detected die.

- [x] OpenCV pip/blob counting on each die crop → value 1–6 (`PipCounter`,
      SimpleBlobDetector, both polarities).
- [x] Produce `DetectedDie(value, boundingBox)` per die (`DieRecognizer`).
      Confidence is currently binary — value is `null` when pips are unreadable;
      a numeric `valueConfidence` 0..1 is deferred.
- [x] Show the recognized value next to each box; unreadable faces flagged amber "?".

**Done:** each detected die shows a 1–6 value. (numeric confidence deferred)

### Step 4 — Die database & store rolls

Persist recognized rolls with user confirmation.

- [x] Room DB: `Die`, `Roll`, `DieResult` (nullable forward FKs per DESIGN.md).
- [x] Wire Hilt for DI.
- [x] Confirm screen: per detected die, edit value + assign to a `Die`
      (pick from list / register new).
- [x] Persist `Roll` + `DieResult`s; roll log reads from DB.

**Done:** a confirmed capture is saved to the DB and survives restart, with each die
assigned and its value recorded.

### Step 5 — Per-die statistics

Surface the recorded data as per-die fairness stats.

- [x] Dice list screen (registered dice + roll counts), reachable from the roll log.
- [x] `DieStatistics` (pure Kotlin): 1–6 distribution, total, mean, chi-square +
      p-value (regularized incomplete gamma), fairness verdict.
- [x] Die stats screen: distribution bar chart, summary (rolls/mean/expected),
      fairness card ("looks fair / possibly biased / not enough rolls").

**Done:** tapping a registered die shows its face distribution and a fairness indicator.

### Step 6 — Automatic dice identification

Guess which registered die each detected face is, so the confirm screen pre-fills the
assignment instead of requiring it every time.

- [x] Colour fingerprint (`DieColorSignature`, `ColorFingerprintPipeline`):
      saturation-weighted hue + lightness per face, pips/highlights excluded.
- [x] Auto-learn: each saved roll folds the crop's colour into the die's running
      fingerprint (`dice.colorSignature`/`colorSamples`, DB v2 + migration).
- [x] Match on capture (`DieIdentifier`): confident guesses pre-fill the die;
      unsure ones fall back to manual tap-to-identify.

**Done:** a die confirmed once is auto-assigned on later captures. Thresholds
(`MATCH_MAX_DISTANCE`, `IDENTITY_CONFIRM_THRESHOLD`) are starting hypotheses still to
calibrate against real labelled rolls.

---

After step 5 the MVP was feature-complete. v2/v3 features (Games, Die Groups, Players)
follow per DESIGN.md.
</content>
</invoke>
