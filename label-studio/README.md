# Label Studio — dice detection annotation

Label Studio is the **source of truth** for annotations (boxes + die type + face value).
Review/fix there, then export **COCO** for training the YOLO detector.

## Files

- `dice-detection.xml` — the labeling config (paste into project **Settings → Labeling interface**).
- Task data (generated, git-ignored): `dataset/label-studio/{train,val}.json` — every existing
  box pre-filled as an annotation, with die type **and** face value. Regenerate with
  `python3 scripts/coco_to_label_studio.py` (reads `dataset/annotations/instances_*.json`).

## One-time setup

1. **Serve the images.** Start Label Studio with local-file serving pointed at `dataset/`:
   ```bash
   export LABEL_STUDIO_LOCAL_FILES_SERVING_ENABLED=true
   export LABEL_STUDIO_LOCAL_FILES_DOCUMENT_ROOT=/home/chulup/dice/dataset
   label-studio start
   ```
   In the project, add **Settings → Cloud Storage → Local files** with the same root and Sync.
   The task `image` URLs (`/data/local-files/?d=images/<split>/<file>`) then resolve.
2. **Create the project**, paste `dice-detection.xml` as the labeling interface.
3. **Import** `dataset/label-studio/train.json` and `val.json` (Data Manager → Import). The 421
   existing boxes load as editable annotations; each task keeps a `split` field so train/val
   stay separable. (Import into two projects, or one project and filter by `split` on export.)

## Export COCO for training

Data Manager → **Export → COCO**. Notes:
- Category **names** round-trip (`d6`, later `d8`/`d10`/…); LS reassigns category **ids**.
- The per-box **face value** is a custom field COCO can't hold — it does **not** round-trip.
  It stays in LS; export **JSON-MIN/JSON** if you ever need it outside LS. The detector doesn't
  need it, so COCO export is correct for training.
- To preserve the train/val split, export the two projects (or two filtered views) separately.

## Why not `label-studio-converter import coco`?

That official tool imports COCO but drops non-standard fields, so the face value would be lost
on the way in. `coco_to_label_studio.py` builds the tasks directly and keeps the value
(pixels→percent conversion included).
