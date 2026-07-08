# Replace the classical die detector with a YOLO model run via OpenCV DNN

- **Date:** 2026-07-08
- **Status:** Accepted (scaffolding in place; model not yet trained)

## Context

Detection is currently classical CV (`ClassicalDieDetector` → `DiceDetectionPipeline`): HSV
saturation segmentation + an Otsu pass on plain backgrounds. It is recall-oriented and works for
saturated pip-d6 dice, but misses low-contrast, busy-background, close-up, and polyhedral/numbered
dice (see `RESEARCH.md` and the recognition roadmap). We want to move detection to a trained
**YOLO26n** object detector. The model does not exist yet — it will be trained on our photo corpus —
so this change is the *plumbing* to drop a trained model in, not the model itself.

The key decision was the on-device inference runtime, since it dictates the model export format and
whether detection stays JVM-unit-testable.

## Decision

Run the YOLO model through **OpenCV's `dnn` module**, which we already ship: the bytedeco/JavaCPP
`org.bytedeco:opencv` build bundles `libjniopencv_dnn.so` for both Android ABIs *and* the desktop
`linux-x86_64` JVM natives. Consequences:

- **No new dependency.** `opencv_dnn.Net` / `readNetFromONNX` are already on the classpath.
- **Detection stays JVM-testable** and A/B-benchmarkable in `DetectionStatsReport`, exactly like
  `DiceDetectionPipeline` and `PipCounter` — a major asset given our fixture/benchmark investment.
- Model format is **ONNX** (`yolo export model=... format=onnx opset=12 imgsz=640`).

Structure mirrors the existing detector split:

- `recognition/internal/YoloDetectionPipeline.kt` — framework-free OpenCV core. Letterbox → blob →
  `net.forward()` → decode → IoU-NMS → normalized `BoundingBox`es. The decoder handles both the
  **RAW** `[1, 4+nc, anchors]` layout and the **END_TO_END** `[1, N, 6]` (NMS-free) layout, and the
  geometry is unit-tested with synthetic tensors in `YoloDecodeTest` (no model needed).
- `recognition/YoloDieDetector.kt` — Android `DieDetector`; lazily loads the model from assets via
  `readNetFromONNX(buffer)` (no temp-file copy), returns `[]` when the asset is absent.
- `di/RecognitionModule.kt` — provides `DieRecognizer`; picks `YoloDieDetector` when the model asset
  is present, else `ClassicalDieDetector`. **Asset presence is the switch:** drop
  `app/src/main/assets/yolo26n-dice.onnx` in and YOLO takes over on the next build, no code change.

`DetectionViewModel` now receives its `DieRecognizer` by Hilt injection instead of constructing it.

## Alternatives considered

- **TFLite / LiteRT** — Ultralytics' first-class Android path, best mobile perf + GPU/NNAPI
  delegates. Rejected for now: adds a runtime dependency and, more importantly, detection would run
  only on-device — outside our JVM benchmark.
- **ONNX Runtime for Android** — good op coverage for new architectures, but again a new dependency
  and on-device-only.
- Chosen OpenCV DNN trades peak inference speed and possible gaps in very-new ONNX op support for
  zero new dependencies and JVM testability. If OpenCV 4.13's ONNX parser chokes on YOLO26-specific
  ops once we export the real model, revisit TFLite/ONNX Runtime (this record would be superseded).

## Consequences

- Until the model is trained, behavior is unchanged (classical fallback).
- When the model lands: confirm the real export's **output shape** and **class map** against
  `YoloDetectionPipeline.Params` (`inputSize`, `keepClasses`, `layout`); the decode math itself is
  already locked by tests. Add a benchmark config in `DetectionStatsReport` and re-run the corpus.
- APK grows by the model size (~a few MB for yolo26n); within the size budget.
- Reading (pip counting / numeral OCR) is unchanged — this record is detection only.
