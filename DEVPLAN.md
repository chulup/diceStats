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

- [ ] `:recognition` interface: `DieDetector.detect(image) → List<BoundingBox>`.
- [ ] Detect dice (model or classical CV segmentation — decide via spike).
- [ ] Overlay detected bounding boxes on the photo for visual confirmation.

**Done when:** the app draws a box around each die in a captured photo.

## Step 3 — Pip counting

**Goal:** Read the value of each detected die.

- [ ] OpenCV pip/blob counting on each die crop → value 1–6.
- [ ] Produce `DetectedDie(value, valueConfidence, boundingBox)` per die.
- [ ] Show the recognized value next to each box; flag low confidence.

**Done when:** each detected die shows a 1–6 value with a confidence score.

## Step 4 — Die database & store rolls

**Goal:** Persist recognized rolls with user confirmation.

- [ ] Room DB: `Die`, `Roll`, `DieResult` (nullable forward FKs per DESIGN.md).
- [ ] Wire Hilt for DI.
- [ ] Confirm screen: per detected die, edit value + assign to a `Die`
      (pick from list / register new).
- [ ] Persist `Roll` + `DieResult`s; roll log reads from DB.

**Done when:** a confirmed capture is saved to the DB and survives restart, with
each die assigned and its value recorded.

---

After step 4 the MVP is feature-complete (per-die stats build on this data). v2/v3
features (Games, Die Groups, Players) follow per DESIGN.md.
