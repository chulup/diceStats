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

## Post-MVP

### Die pools — interchangeable dice without individual identity (2026-07)

Design: DESIGN.md "Die Pools" + `design-records/2026-07-05-dice-pools.md`. A pool is
one `Die` row with `count > 1` (an upper bound); storage keeps one `DieResult` per
detected die, sharing the pool's `dieId`.

- [x] Schema v4: `dice.count` (default 1), `games.usesDicePools` (default 0).
- [x] Register-die dialog count stepper; "×N" on dice list + palette chips
      (`ui/DieDisplayName.kt`); "uses dice pools" switch on the start-game dialog.
- [x] Confirm screen: per-roll capacity in `canSave` (≤ `count` boxes per die,
      fewer is fine), at-capacity palette chips grayed out and unselectable,
      active-die highlight stays on a pool until its capacity is used
      (`activeDieAfterAssignment`), pools join colour auto-assign only when the
      active game uses pools, overflow guesses capped by confidence
      (`capAutoAssignments`). Pure logic under `DetectionLogicTest`.
- [x] Pool stats: "×N" title, "N throws across M rolls" line
      (`COUNT(DISTINCT rollId)`), "pooled across N dice" fairness caption.

**Done:** a Risk session registers "Red ×3" / "Blue ×2" once; each roll photo is
confirmed with three taps or auto-assignment, and the pool pages show pooled
distributions with an honest pool-level fairness verdict.
</content>
</invoke>

## Two-stage recognition & model comparison (2026-10-08)

See [design record](design-records/2026-10-08-two-stage-recognition-consensus.md).

- [x] Taken photos are read by a 2-of-3 vote (`DieRecognizer.recognizeVoted`, `Consensus`):
      v3 m one-stage + one-class detector → full-res crop (`DieCropper`) → value classifier
      (`ValueClassifier`), in n and s pairs (`TwoStageRecognizer`). Split votes show "?".
- [x] Model comparison log `files/model-eval/log.jsonl` (`ModelEvalLog`): every pipeline's dice
      and timings, the confirmed dice, classifier readings at confirmed positions, confirm-session
      effort, model files. Summary: `../training/app_eval_report.py --pull`.
- [x] Phone sensors per capture (`SensorRecorder`, `.sensors.json` sidecar): gravity, tilt,
      shake during exposure, light.
- [x] `TwoStageBenchmarkTest`: replays every saved roll through all pipelines on the device.
- [x] JavaCPP's 1 GB memory cap lifted (`maxPhysicalBytes=0`); comparison runs can't crash recognition.
