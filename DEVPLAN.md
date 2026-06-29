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

## Step 6 — Automatic dice identification

**Goal:** Guess which registered die each detected face is, so the confirm screen
pre-fills the assignment instead of requiring it every time.

- [x] Colour fingerprint (`DieColorSignature`, `ColorFingerprintPipeline`):
      saturation-weighted hue + lightness per face, pips/highlights excluded.
- [x] Auto-learn: each saved roll folds the crop's colour into the die's running
      fingerprint (`dice.colorSignature`/`colorSamples`, DB v2 + migration).
- [x] Match on capture (`DieIdentifier`): confident guesses pre-fill the die;
      unsure ones fall back to manual tap-to-identify.

**Done when:** a die confirmed once is auto-assigned on later captures. ✓
Thresholds (`MATCH_MAX_DISTANCE`, `IDENTITY_CONFIRM_THRESHOLD`) are starting
hypotheses still to calibrate against real labelled rolls.

---

After step 5 the MVP is feature-complete. v2/v3 features (Games, Die Groups,
Players) follow per DESIGN.md.

## Future improvements (backlog)

Unprioritized; capture now, schedule later.

### Recognition
- **Robust detection on bright / multicolored surfaces.** The HSV saturation+value
  segmentation (`DiceDetectionPipeline`) assumes dice are the saturated, bright
  objects against a duller background; bright tabletops, patterned cloths, or
  multicolored surfaces break that assumption. Explore adaptive thresholds,
  background modeling, or an edge/shape cue alongside colour.
- **Read numbered & polyhedral dice (d6 with numerals, d8/d10/d20/d100).** The MVP
  reads pip d6 only; numbered faces and non-square polyhedra defeat both the
  square-shape detector and pip counting. Researched in [RESEARCH.md](RESEARCH.md):
  **Done — detection:** an **Otsu/brightness pass** now runs on plain backgrounds
  (gated by edge density, unioned with the saturation boxes via NMS) in
  `DiceDetectionPipeline`; on the reference photos it adds the previously-undetectable
  metallic d6, d100, d8 and numbered d6 while leaving the cluttered originals (1–12)
  unchanged. **Still to do:** **route by die type** to either
  pip counting (d6) or a **numeral reader** — a small offline TFLite digit CNN trained
  on synthetic die fonts (preferred), with bundled ML Kit OCR as a ship-now bridge.
  Value interpretation needs the die type (d10/d100 0-indexed faces). Ornate engraved
  dice (test photo 109) are out of scope for now.
- **Per-die multi-face registration → template / subimage matching (future).** When a
  user registers an *unusual* die, optionally have them photograph **every face**.
  Recognition then becomes per-die **pattern matching / subimage search** — match a
  detected face against that specific die's stored face images — instead of generic
  OCR / pip counting. Robust for ornate, custom, or hard-to-OCR dice that defeat the
  generic readers (e.g. 109). Extends the existing per-die appearance matching
  (`DieColorSignature` / `DieIdentifier`) from a colour fingerprint to per-face image
  templates; pairs naturally with a `referencePhotoPath`-style asset set per die.

### Confirmation UX
- **On-photo value steppers.** Show the +/− value buttons directly below the
  selected die on the photo (in addition to / instead of the list rows), so
  correcting a value doesn't mean hunting for its row. Couples with selecting a
  die by tapping it.
- **Capture multiple rolls in a row.** After saving a roll, offer "capture next"
  so the user can log several rolls without bouncing back to the gallery each
  time. Affects the capture → detection → save navigation loop.

### Statistics
- **Stats on the home page.** Surface a summary (recent fairness / per-die
  snapshot) on the roll log / home screen instead of only behind the dice list.
- **All dice at once on the stats page.** The stats screen should show every die
  together (overview/comparison), drilling into a single die's detail when one is
  chosen — rather than requiring a die be picked first.
- **Per-game stats.** Once Games (v2) exist, show stats for all dice in a game at
  once (aggregate + per-die breakdown within the game).
