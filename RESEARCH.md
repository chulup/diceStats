# DiceStats — Recognition Research

Findings from evaluating how the app could **detect** and **read** dice beyond the
MVP's pip-d6 case — specifically numbered d6 and polyhedral dice (d8/d10/d20/d100).
All approaches are constrained to run **fully offline on-device**.

Date: 2026-06-29. See [DESIGN.md](DESIGN.md) for the architecture and
[DEVPLAN.md](DEVPLAN.md) (Future improvements → Recognition) for the resulting backlog.

## Test set

`photos/` + `photos/tests.txt` (ground truth: bounding boxes and/or values).

- **1–12** — original pip d6 (red/yellow), with bounding boxes. Regression baseline.
- **101–110** — newly added numbered / polyhedral dice, values only (no boxes):

  | Photo | Die | Value(s) | Condition |
  |---|---|---|---|
  | 101 | d6 | 5 | white numeral on blue, rotated ~90°, fills frame |
  | 102 | d6 ×2 | 3, 4 | black numerals on **metallic/silver** |
  | 103 | d6 ×3 | 2, 3, 6 | numbered, small in frame |
  | 104 | d10 | 10 | black on yellow, multi-digit |
  | 105 | d100 | 70 | white on blue, multi-digit |
  | 106 | d8 | 2 | numbered |
  | 107 | d20 | **8** | gold-on-green, **low contrast**; apex shows "20" (see ground-truth note) |
  | 108 | d20 | 10 | numbered |
  | 109 | d6 ×4 | 5, 3, 5, 1 | **ornate engraved** cream-on-cream on wood — hardest |
  | 110 | d6 ×3 | 2, 5, 6 | numbered, small in frame |

## Method

Four parallel investigations against the real photos:
1. **Baseline** — ran the real `DiceDetectionPipeline` (current detector) on all photos.
2. **Detection alternatives** — prototyped classical-CV localizers in standalone Java
   (ImageIO), scored vs ground truth.
3. **Numeral reading** — segmentation evidence + offline-reader options analysis.
4. **On-device OCR benchmark** — ML Kit Text Recognition v2 (bundled) on the real phone.

(JVM tooling only — Java + ImageIO + ffmpeg — plus one Android device for the ML Kit run;
the detection pipeline is pure Kotlin so it runs off-device.)

## Detection (localizing dice)

**Baseline:** the current pipeline is **100% recall on pip d6 (1–12)** but localizes only
~3 of 18 dice in 101–110 and reads **0**. It assumes a *small-in-frame, saturated, solid,
square* object; the new dice fail on area>max (close-ups), aspect/fill (polyhedra), and an
empty saturation mask (metallic/white).

**Approaches compared** (all O(pixels) at 512px working res, cheap on-device):

| Approach | Idea |
|---|---|
| D1 saturation (current) | adaptive HSV saturation+value + near-square filter |
| D3 relaxed-shape | same, looser aspect/fill |
| D2 edge | Sobel magnitude → adaptive threshold → close → fill → components |
| D4 **Otsu/brightness** | Otsu on gray, minority class = foreground → morphology → components |
| D5 union | D3 ∪ D2 with NMS |

Pip d6 (1–12), recall @ IoU≥0.5 / false positives:

| Approach | Recall | FP |
|---|---|---|
| D1 saturation | 1.00 | 1 |
| D3 relaxed | 1.00 | 4 |
| D2 edge | 0.44 | 48 |
| D4 Otsu | 0.31 | 32 |
| D5 union | 1.00 | 50 |

**Per-category result on the new dice (from box visualizations):**

- **Metallic d6 (102):** only D2/D4 see it (saturation is blind to silver); **D4 gives two
  tight, correct boxes.**
- **Colored polyhedra (105 d100, 106 d8, 107 d20, 108 d20):** **D4 localizes best** —
  107/108 a single perfect box, 105 a good box, 106 slightly fragmented. D3 finds them but
  the low-saturation printed numbers fragment the saturation mask into unusable pieces.
- **Numbered d6 (103, 110):** **D4 = exact count** with good boxes; D3 over-fragments.
- **Ornate white-on-wood (109):** **no approach works** — D4 latches onto shadows, D2/D3
  drown in wood grain. **Deferred** (see below).
- D4 misses extreme close-ups (101, 104) where the die fills >50% of the frame — fixable
  with a "die fills the frame" fallback (accept a majority / border-touching blob).

**Conclusion — augment, don't replace.** Keep the saturation detector for cluttered scenes
(edge/Otsu explode there: 32–50 FP on 1–12). Add a **D4 Otsu pass that triggers only on
plain backgrounds** (low edge density / near-unimodal histogram), unioned via NMS. An
always-on union (D5) is rejected: it inherits Otsu's false positives on busy scenes.

## Reading the value (numerals)

**Ready-made on-device OCR is insufficient.** ML Kit Text Recognition v2 (bundled Latin
model, fully offline, ~13 MB/ABI) on the numbered dice:

- **Full image: 1/10** correct (only 105 "70"). It reads brand watermarks (CHESSEX, eM4,
  UltraGear) more reliably than die faces — a false-positive hazard.
- **Best-case per-face crop + upscale: 3/10** (105, 106, 108) + 109 partial (2/4).
  Single upright faces ~3/6; multi-die photos 2 of 12 dice. Reads **no pips**.
- Fails on: rotation (101, polyhedral side faces), low-contrast/metallic (102, 107),
  engraving (109), small/low-res multi-die (103, 110), and picking the right face when
  several numerals are visible (108).

(Original benchmark scored 107 as correct for reading "20"; corrected to a miss here since
ground truth is 8 — see note. Tesseract evaluated and **rejected**: heavier, worse on
stylized die fonts.)

**But the input can be made clean** (segmentation evidence):
- High-contrast painted numerals (101, 104, 105) segment cleanly with trivial Otsu →
  multi-digit faces split into separate components (good for per-digit classification).
- Low-contrast gold-on-green (107) fails on **luminance** but is crisp on the **R−B color
  channel** — a feature-choice problem, not a wall. Use color/saturation features, not raw
  luminance, when luma contrast is low.
- Ornate engraved (109): the digit fuses with the decorative frame → defeats thresholding
  **and** pip counting. Needs a learned classifier (or the multi-face approach below).

**Recommended reader (offline), ranked:**
1. **Tiny TFLite digit CNN (<1 MB)** trained on **synthetic** die fonts × rotation ×
   lighting × decorative-frame overlays, behind an OpenCV segment + orientation-normalize
   front end. Only option robust to rotation + stylized/low-contrast fonts + engraving at
   once. Cost: a synthetic-data/training pipeline (planned separately).
2. **Ship-now bridge: ML Kit v2 (bundled)** with the same color-aware binarize +
   orientation-normalize preprocessing and type-based routing; good on clean numerals.
   Use it to collect real labeled crops, then promote the CNN.
3. Classical template matching of 0–9 — optional cheap fallback/tie-breaker only.

APK size is **not** a binding constraint (≥50 MB headroom acceptable), so a generous
backbone / bundled model is fine. Latency is lax (single-photo → confirm flow).

## Recommended architecture

**Detect → crop → route by die type → read.** Cropping doubled ML Kit accuracy and
disambiguates which numeral is the value, so detection is load-bearing.

- **Routing by die type** (the app already stores type): d8/d10/d20/d100 → numeral reader;
  d6 → pip-constellation gate (N round, high-circularity blobs in a canonical layout) →
  pip count, else numeral reader.
- **Orientation:** per-glyph `minAreaRect`/PCA to upright; resolve the 180° flip (and
  6-vs-9) by classifier confidence.
- **Value interpretation needs the type:** a bare "10" can't distinguish d10 from d100;
  d10/d100 faces are 0-indexed (0→10, "00"→100).

## Future direction (see DEVPLAN backlog)

**Per-die multi-face registration → template / subimage matching.** For *unusual* dice,
let the user photograph every face at registration; recognize by matching a detected face
against that die's stored face images instead of generic OCR/pip counting. Best for ornate
/ custom dice (109). Extends `DieColorSignature` / `DieIdentifier` from a colour fingerprint
to per-face image templates.

## Deferred / out of scope

- **Photo 109 (ornate engraved white d6 on wood):** too hard for early detection algorithms
  — white body lost to shadows/grain, digit fused with the engraved frame. Revisit via the
  multi-face registration approach.

## Visual evidence (`research/`)

Saved artifacts from the investigations (prototype outputs, not production code):

- **`research/detection/`** — `viz_<photo>_<approach>.png`: each approach's bounding
  boxes drawn over the source photo, for photos 101/102/104–109 × approaches
  `D2-edge`, `D3-relax`, `D4-otsu`, `D5-union`. Key ones:
  - `viz_102_D4-otsu.png` — metallic d6, two tight correct boxes (best result).
  - `viz_107_D4-otsu.png`, `viz_108_D4-otsu.png` — colored d20, single perfect box.
  - `viz_105_D4-otsu.png` (clean) vs `viz_105_D2-edge.png` (over-segmented interior).
  - `viz_107_D3-relax.png` — saturation fragmentation on a numbered polyhedron.
  - `viz_109_D4-otsu.png` / `_D2-edge.png` / `_D3-relax.png` — the ornate-on-wood
    failure case (all latch onto shadows / wood grain).
- **`research/segmentation/`** — glyph isolation per face: `<face>_a_crop.png` (cropped
  face), `_b_gray.png` (grayscale), `_c_otsu.png` (Otsu binarization). Highlights:
  - `101_5`, `104_10`, `105_70` — clean numerals via luminance Otsu.
  - `107_8_c_otsu.png` (luminance, fails) vs `107_8_d_redblue.png` (R−B channel, crisp
    "8") — shows the color-feature fix for low contrast.
  - `109_1` / `109_3` / `109_5a` — engraved glyph fuses with the decorative frame.
- **`research/prototypes/`** — standalone Java used to produce the above
  (`Dice.java` = detection approaches; `Seg.java` / `Seg2.java` = glyph segmentation).
  ImageIO-based, JVM-runnable; not part of the app build.

## Open ground-truth issue

For polyhedral dice, **which face is "the value"?** Photo 107: `tests.txt` says **8** (the
large front face) while the apex reads "20". Detection crops, the reader, and labels must
all target the same face consistently before training a model.
