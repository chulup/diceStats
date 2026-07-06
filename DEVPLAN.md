# DiceStats — Development Plan

Active and upcoming work. Completed steps live in [CHANGELOG.md](CHANGELOG.md);
see [DESIGN.md](DESIGN.md) for the full architecture this works toward.

## Immediate tasks

Focused on evaluating and tuning the Otsu detection pass (see
[RESEARCH.md](RESEARCH.md) / `DiceDetectionPipeline`).

- [x] **Incorporate OpenCV into unit tests on the dev machine.** Done: migrated OpenCV
      from the Android-only AAR to the bytedeco/JavaCPP build (natives for both
      `android-arm64` and desktop `linux-x86_64`) and split a `Bitmap`-free
      `PipCounter.count(Mat, box)` core, so the real pip counter runs in JVM tests
      (`PhotoDetectionTest`). Verified on-device (no recognition regression).
- [ ] **Add a dev overlay on the detection screen.** On top of the photo: an otsu
      on/off control, and the dice found **with** Otsu vs **without** it (each labelled
      with its value, or `?` when undetermined). Log that information together with the
      file name for later analysis.
- [ ] **Test against all d6-only photos in `./photos`** (aside from disabled ones), and
      log the same information (dice found with/without Otsu, value or `?`).
- [x] **Generate the missing d6 PPM fixtures.** Done: converted 101/102/103/109/110 to
      `P6` PPM under `app/src/test/resources/photos/` (native resolution; sources are all
      ≤517px and the pipeline caps the longest edge at 512 without upscaling). The sweep
      now covers them, surfacing the Otsu pass's plain-background wins (e.g. 102 `[]`→`[?, ?]`,
      103 `[?, ?, ?]`→`[?, ?, ?, 1]`). Boxes stay `null` in `tests.txt`, so recall isn't
      asserted on these hard/metallic cases — the sweep is for analysis only.
- [ ] **Trim the OpenCV APK.** The bytedeco `opencv` artifact bundles every module
      (~90 `.so`: dnn, video, stitching, face, …); the app only needs core/imgproc/
      features2d, but the arm64 APK is ~138 MB. Exclude the unused module natives via the
      `packaging { resources { excludes } }` block — carefully, since JavaCPP's `Loader`
      preloads the dependency graph and dropping a needed lib breaks `features2d` loading.

## Die pools (design settled — see DESIGN.md "Die Pools")

Interchangeable identical dice (Risk: red ×3, blue ×2) tracked as one `Die` row
with `count` as an upper bound; storage keeps one `DieResult` per detected die.

Implementation:

- [x] **Schema.** Additive migration (v3→v4): `count INTEGER NOT NULL DEFAULT 1` on
      `dice`, `usesDicePools INTEGER NOT NULL DEFAULT 0` on `games`.
- [x] **Registration + lists.** Count stepper on the register-die dialog; dice
      list and confirm-screen palette chips render "×N" (`ui/DieDisplayName.kt`).
      "Uses dice pools" toggle when starting a game.
- [x] **Confirm screen.** `canSave` additionally requires ≤ `count` boxes per die
      per roll (fewer is fine); palette entries at capacity gray out; active-die
      advance stays on a pool until its capacity in this roll is used
      (`activeDieAfterAssignment`); when the active game has `usesDicePools`,
      pools join the colour-signature auto-assign candidates, with overflow
      capped to capacity by confidence (`capAutoAssignments`). Pure logic covered
      by `DetectionLogicTest`.
- [x] **Pool stats page.** "×N" title, "throws · rolls" sample line
      (`COUNT(DISTINCT rollId)` DAO query), "pooled across N dice" verdict caption.
      Per-roll charts (sum / highest-of-N), if/when added, must bucket by actual N.

Deferred follow-ups (captured, not being thought about now):

- [ ] **"More info" accordion on pool stats** explaining how to collect enough
      statistics to identify a *bad die* within a pool (e.g. calibration rolls of
      one die at a time) — pool pages only flag pool-level unfairness.
- [ ] **Roll editing.** Open a roll from the roll log and modify / re-detect its
      dice (fixes partially-detected pool rolls after the fact).
- [ ] **`GameRules` entity.** Predefined per-game rules + bespoke stats (e.g. Risk
      attacker-vs-defender highest-die comparisons); a `Game` binds to a specific
      `GameRules` instance. Natural home for cross-pool analyses.
- [ ] **Die edit screen.** Rename a die; change a pool's `count` — increase is
      always allowed, decrease only when no existing roll uses more dice of the
      pool than the new count.

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
  square-shape detector and pip counting. Researched in [RESEARCH.md](RESEARCH.md).

  **Done so far:**
  - **Detection** — an **Otsu/brightness pass** runs on plain backgrounds (gated by edge
    density, unioned with the saturation boxes via NMS) in `DiceDetectionPipeline`; on the
    reference photos it adds the previously-undetectable metallic d6, d100, d8 and numbered
    d6 while leaving the cluttered originals (1–12) unchanged.
  - **Stats layer** — `data/DieType.kt` models d6/d8/d10/d20/d100 (face values, expected
    mean, histogram step, `isValidValue`/`faceIndex`). `DieStatistics` and
    `RollTotalStatistics` are die-type-aware (default d6); `DieStatsViewModel` and
    `buildGameStatsUiState` resolve each die's type from `DieEntity.faces`.
  - **Sanitization** — external data is filtered against the die type at its boundary:
    `DiceRepository` (`sanitizeRolls`, `valuesForDie`) drops stored values that aren't a
    face of their die (results from a deleted die are kept), and `DetectionViewModel`
    validates user input against the assigned die's type. Impossible values are ignored
    everywhere, never clamped.

  **Remaining tasks:**
  - [ ] **Die-type registration + persistence.** Let the user choose a die's type when
    registering it (and editing it), and persist it via `DieEntity.faces` (already a
    column, currently always 6). Until this lands every die resolves to d6, so the
    type-aware stats/sanitization above only exercise the d6 path in practice.
  - [ ] **Route recognition by die type.** After detection, route each crop to pip
    counting (d6) or a **numeral reader** for numbered/polyhedral faces. Value
    interpretation needs the die type (d10/d100 0-indexed faces).
  - [ ] **Numeral reader.** A small offline TFLite digit CNN trained on synthetic die
    fonts (preferred), with bundled ML Kit OCR as a ship-now bridge.
  - [ ] **Type-aware value entry on the confirm screen.** The value steppers assume a d6
    (1..6, step 1) and default an unread die to 1; generalise to the assigned die's range
    and step (d100 is 0..90 step 10, so 1 isn't even a valid face) and default to a real
    face. Needed before non-d6 dice can actually be confirmed/saved.
  - [ ] **Heterogeneous roll totals.** The roll-totals screen (and a game's totals) assume
    every die in a roll is the same type (d6). A roll can mix types, so the total range,
    spacing, and expected mean need to combine per-die types rather than one `DieType`.
  - [ ] Ornate engraved dice (test photo 109) stay out of scope — likely needs the per-die
    multi-face registration approach below.
- **Per-die multi-face registration → template / subimage matching (future).** When a
  user registers an *unusual* die, optionally have them photograph **every face**.
  Recognition then becomes per-die **pattern matching / subimage search** — match a
  detected face against that specific die's stored face images — instead of generic
  OCR / pip counting. Robust for ornate, custom, or hard-to-OCR dice that defeat the
  generic readers (e.g. 109). Extends the existing per-die appearance matching
  (`DieColorSignature` / `DieIdentifier`) from a colour fingerprint to per-face image
  templates; pairs naturally with a `referencePhotoPath`-style asset set per die.

### Annotation & training data
- **Data-annotation build.** A separate build variant (debug/internal flavour, not
  shipped) for labelling the recognition corpus and turning real misses into training
  data. It presents a **carousel over all test photos**; each photo shows the **detected
  dice with their bounding boxes and read values** overlaid. Per photo the annotator can:
  **drop** a detection that's a false positive, **edit the value** of a correctly-detected
  die, and hit a **"mark for further analysis"** button to flag the photo as a hard case.
  **Every user edit is recorded** (drop / value correction / flag, with the photo id and
  the model's original output) so the deltas become a labelled dataset for algorithm
  improvement — feeding ground-truth boxes/values back into `photos/tests.txt`-style
  fixtures and, later, training the numeral reader. Builds on the existing detection
  pipeline + `PipCounter`; the recorded corrections are the same signal the confirm
  screen's `wasCorrected` flag captures, but gathered deliberately and in bulk over the
  whole photo set rather than incidentally per roll.
- **Data-annotation build v2 — label the device's reported photos.** Same carousel and
  recording mechanism, but sourced from the **photos the device reported as unrecognized**
  (the `unrecognized/` captures pulled off the device) rather than the curated test set.
  Because these are the hard cases the detector *missed*, the annotator works from scratch:
  **draw a bounding box** around each die the model didn't find and **enter its value**,
  building full ground truth (boxes + values) for exactly the photos the algorithm fails
  on. Combined with v1's drop/correct edits, this yields a labelled corpus of both
  false positives and false negatives — the misses are the most valuable training signal,
  and the resulting boxes/values can graduate straight into `photos/tests.txt` fixtures.

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
  once (aggregate + per-die breakdown within the game). Beyond that: **every**
  stats view (die, pool, roll totals) should be scopable to a game.

### Refactor
The per-game stats screen (`GameStatsScreen`) used to copy-paste UI from the
existing stats screens. The shared chart palette lives in
`feature/stats/StatsColors.kt`; the remaining duplication has now been consolidated:
- [x] **Shared bar renderer.** Extracted `StatsBarChart`/`StatsBar` to
  `feature/stats/StatsChart.kt` (label list + `count > 0` guard / zero-count display
  parameterised); `GameStatsScreen`, `DieStatsScreen`, and `RollTotalsScreen` all use
  it. (The per-game charts now use the standard 180.dp height, up from 160.dp.)
- [x] **Shared roll-totals card.** Extracted `RollTotalsCard`
  (`feature/stats/RollTotalsCard.kt`), used by both `RollTotalsScreen` and
  `GameStatsScreen`'s totals section.
- [x] **Shared fairness verdict mapping.** Extracted `fairnessVerdictStyle`
  (`feature/stats/FairnessVerdictStyle.kt`) returning the `(Color, String)` for a
  `FairnessVerdict`; used by `GameStatsScreen.DiePerformanceCard` and
  `DieStatsScreen.FairnessCard`.
