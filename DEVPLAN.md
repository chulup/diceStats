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
