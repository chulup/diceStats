# Two-stage recognition (detect → crop → classify) and an interim 2-of-3 consensus

- **Date:** 2026-10-08
- **Status:** Accepted as interim. The permanent pipeline will be chosen from on-device logs
  (see "Deciding later").
- **Builds on:** [2026-07-08 YOLO detector via OpenCV DNN](2026-07-08-yolo-detector-opencv-dnn.md)

## Context

The one-stage YOLO26 models (classes `d6-1`…`d6-6`) find dice well but read values poorly unless
they are large:

| v3 one-stage (training test split, 22 unseen videos) | value mAP50 | any-value mAP50 |
|---|---|---|
| yolo26s | 0.652 | 0.953 |
| yolo26m | 0.769 | 0.968 |

On the Pixel 8 (34 real rolls), m read 91% of values right at ~1.3–1.7 s per photo; s read only
67%. Finding dice is basically solved; **reading the value is the weak half**. The detector sees
the photo shrunk to 640 px, so a die is ~30–60 px wide.

## Research: split the job in two

1. **One-class detector** ("die") on the 640 px letterbox: YOLO26n / s.
2. **Value classifier** (1–6) on a crop cut from the **full-resolution photo**. The crop is a
   square around the box centre, side = 1.3 × the longer box edge, replicate-padded at the edges
   and resized to 224. YOLO26n-cls / s-cls.

**Data** (`../training/two_stage_data.py`, built from the v3 export, same video-level splits):
- `/ai/data/dice/yolo/v3-det` — the v3 images with every box as class 0.
- `/ai/data/dice/cls/v3` — 11,358 train crops (each box plus 2 jittered copies imitating loose
  detector boxes), 791 val, 824 test, plus 102 crops from real app rolls ("app" split, never
  trained on). Median crop side is ~175 px, so most crops gain detail over the detector's view.

**Training:** vast.ai RTX 4090, ~1 h, $0.49. The detector used `train.sh` defaults (degrees 10).
The classifier used imgsz 224, scale 0.3, erasing 0.1, fliplr 0. Both classifiers peaked around
epoch 5–15 and then overfit (train loss 0.02).

### Results

Local CPU (`../training/two_stage_eval.py`). "Found + right" counts a die only if it was found
**and** its value read correctly.

| pipeline | test: found + right | test: value right on found | app photos: found + right |
|---|---|---|---|
| v3 m one-stage | 0.684 | 0.729 | 0.863 |
| det s + cls s | **0.860** | **0.899** | 0.853 |
| det n + cls n | 0.789 | 0.841 | **0.922** |

The one-class detectors alone reach recall 0.958 (s) / 0.938 (n) on test, and 0.99–1.00 on app
photos.

On the Pixel 8 (`TwoStageBenchmarkTest`, 34 saved rolls, 102 dice, app code and assets, first
photo excluded from timings):

| pipeline | found | value right | found + right | extra / photo | ms median | detect / crop / classify ms |
|---|---|---|---|---|---|---|
| v3 m one-stage | 0.980 | 0.910 | 0.892 | 0.00 | 1530–1680 | all detect |
| v3 s one-stage | 0.971 | 0.667 | 0.647 | 0.03 | 516 | all detect |
| det n + cls n | 1.000 | 0.912 | 0.912 | 0.03 | **340–375** | 221 / 60 / 55 |
| det n + cls s | 1.000 | 0.902 | 0.902 | 0.03 | 401–440 | 205 / 62 / 129 |
| det s + cls n | 0.990 | 0.921 | 0.912 | 0.06 | 638–718 | 527 / 60 / 51 |
| det s + cls s | 0.990 | 0.861 | 0.853 | 0.06 | 712–804 | 521 / 58 / 133 |
| **2-of-3 consensus** (m, n+n, s+s) | 1.000 | 0.912 | 0.912 | **0.00** | 2850 | sum of the three |

Value reading alone, at the user-confirmed positions: cls n 91/102, cls s 87/102. Classifying
costs 15–17 ms per die for n and 48–57 ms for s; cropping costs ~25 ms per die. The phone app set
is small (102 dice, few dice types) and its boxes were seeded by m, which flatters m on "found"
and "extra".

### Findings

- **The two-stage n + n matches m's accuracy at about 4.5× the speed.** On the larger
  training test split, s + s is clearly the most accurate. The app set is too small to rank them.
- **On the phone, cls n beats cls s.** Most of s's misses are one yellow die's 4 read as 5.
- **Some "errors" are which-face-is-up ambiguity.** At steep camera angles two faces are visible,
  and the classifier sometimes reads the near face. A few saved values may also be wrong. Phone
  sensors (gravity, tilt, shake) are now logged with every roll to test this.
- **Full-res cropping is cheap on the phone**, ~60 ms per photo through `BitmapRegionDecoder`,
  though ~150 ms in Python on 12 MP JPEGs.
- **JavaCPP memory cap.** By default JavaCPP refuses allocations once the process uses more than
  1 GB of memory; six loaded nets crossed that (OutOfMemoryError in `Net.forward`). Fixed with
  `org.bytedeco.javacpp.maxPhysicalBytes=0` in `DiceStatsApp`. Comparison runs are also wrapped,
  so a failing one can't crash the app.
- **Classifier ONNX:** Ultralytics classify export (opset 12) outputs softmax `[1,6]`; class
  `i` → value `i+1`. The Kotlin pipeline matches Python/OpenCV to 0.002 on a test crop
  (`ValueClassifierPipelineTest`).

## Decision (interim)

Taken photos are recognized by a **2-of-3 vote** (`DieRecognizer.recognizeVoted`, `Consensus`).
The three voters are v3 m one-stage, det n + cls n, and det s + cls s.
- A die is kept when at least 2 pipelines box it (IoU ≥ 0.5). Its box is the mean of their boxes.
- Its value is the one at least 2 pipelines read. Otherwise it shows as an unread "?" for the
  user to set.
- Re-detection around a long-press still uses m alone.

The one-stage s model is no longer bundled.

## Deciding later: what is logged

Each saved roll appends one line to `files/model-eval/log.jsonl`, next to the photo in `rolls/`.
Pull and summarize with `../training/app_eval_report.py --pull`.
- **Every voter's run:** each detected die's box, value, detector score, classifier probability
  and blur probability. Timings: preprocess / inference / decode / crop / classify, model load
  time on first use, a run index (0 = cold), and thermal status.
- **The consensus run:** the same fields, plus per die the number of pipelines that found it and
  the number that agreed on its value.
- **What the user saved:** box, value, value shown, origin (detected / long-press /
  placeholder), and whether they edited the value or dragged the box.
- **Each classifier at the saved positions:** its value and probability per die, crop time,
  classify time.
- **Confirm session:** time from opening the photo to dice shown, time from then to Save, and
  counts of dice removed, added and edited and of region re-detects.
- **Phone sensors** at capture: gravity, camera tilt, rotation, gyro/accelerometer shake during
  the exposure, light. The model asset list and sizes. The device.

The `TwoStageBenchmarkTest` instrumented test replays every saved roll through all pipelines on
the device. It reads a copy of the DB and never the live file.

**Choose by:** found + right per pipeline vs. latency, the edits users had to make, and whether
errors cluster by tilt, blur or die type. Leading candidates: n + n alone (fast, as accurate as m
on real rolls), or the consensus if its "?" dice and zero extras save more edits than its 2.8 s
costs.

## Alternatives considered

- **Keep v3 m one-stage only:** accurate but 1.5 s per photo, and weak on value on the larger
  test split.
- **Feed the gravity vector to the classifier:** rejected for now. The die's rotation in the image
  is random anyway, the tilt is visible in the crop, and no training frames have sensor data.
  Sensors are logged instead.
- **Run the consensus voters in parallel:** not tried. OpenCV DNN already uses several cores.

## Consequences

- Recognition takes ~2.8 s per photo on a Pixel 8 until a single pipeline is chosen.
- The APK bundles five models (m one-stage, n/s detectors, n/s classifiers), ~160 MB.
- Models: `/ai/runs/dice-v3/` (one-stage) and `/ai/runs/dice-two-stage/` (two-stage). Datasets
  are listed above; their bundles are in `/ai/data/dice/bundles/dice-v3-{det,cls}.tar.gz`.
