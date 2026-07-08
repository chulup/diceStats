#!/usr/bin/env python3
"""
Build a COCO-format detection dataset for training the YOLO die detector, from the
backed-up app database + roll photos.

Ground truth lives in the Room DB: `die_results.boundingBox` is a normalized
"left,top,right,bottom" string relative to each roll photo *in EXIF-display orientation*
(the app rotates the bitmap per its EXIF tag before running recognition — see
DetectionViewModel), and `die_results.value` is the pip value. We therefore apply the
same EXIF transpose here before mapping boxes, then letterbox each image to a fixed
square (matching YoloDetectionPipeline's inference preprocessing: aspect-preserving
scale + 114 grey pad, no crop) so no dice are cut.

Output (COCO):
    dataset/
      images/{train,val}/*.jpg          letterboxed IMG_SIZE x IMG_SIZE
      annotations/instances_{train,val}.json
      _debug/*.jpg                       a few overlays to spot-check box alignment

Re-runnable: rewrites dataset/ from backup/. Negative (no-dice) images can be added
later — see dataset/README.md.
"""
import json
import os
import random
import sqlite3
import sys

import cv2
import numpy as np
from PIL import Image, ImageOps

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DB = os.path.join(ROOT, "backup/db/dicestats.db")
ROLLS = os.path.join(ROOT, "backup/rolls")
OUT = os.path.join(ROOT, "dataset")

IMG_SIZE = 640          # yolo26n native input; the size images are letterboxed to
PAD = 114               # grey letterbox pad (Ultralytics convention, matches inference)
VAL_FRACTION = 0.15
SEED = 42
N_DEBUG = 12            # how many overlay images to emit for spot-checking

# Class scheme: each box's category is its die *type* ("d6" now; "d8"/"d10"/"d20"/"d100"
# appear automatically once such dice are registered and annotated), all under the COCO
# supercategory "die". A box whose die type is unknown (e.g. a deleted die) falls back to
# the generic "die" class. This keeps every box grouped as a die while distinguishing
# types, so adding new dice later needs no relabeling.
GENERIC = "die"


def type_name(faces):
    """Die-type class name from a face count: d6/d8/d20/d100, or generic 'die' if unknown."""
    if faces is None:
        return GENERIC
    return f"d{faces}"


def letterbox(rgb):
    """Scale `rgb` (H,W,3) to fit IMG_SIZE preserving aspect, pad to square. Returns
    (out_img, scale, pad_x, pad_y)."""
    h, w = rgb.shape[:2]
    scale = min(IMG_SIZE / w, IMG_SIZE / h)
    nw, nh = max(1, round(w * scale)), max(1, round(h * scale))
    resized = cv2.resize(rgb, (nw, nh), interpolation=cv2.INTER_LINEAR)
    pad_x, pad_y = (IMG_SIZE - nw) // 2, (IMG_SIZE - nh) // 2
    out = np.full((IMG_SIZE, IMG_SIZE, 3), PAD, dtype=np.uint8)
    out[pad_y:pad_y + nh, pad_x:pad_x + nw] = resized
    return out, scale, pad_x, pad_y


def to_coco_bbox(norm, w, h, scale, pad_x, pad_y):
    """Normalized (l,t,r,b) in the oriented image -> COCO [x,y,w,h] pixels in the
    letterboxed IMG_SIZE image."""
    l, t, r, b = norm
    x0 = l * w * scale + pad_x
    y0 = t * h * scale + pad_y
    x1 = r * w * scale + pad_x
    y1 = b * h * scale + pad_y
    x0, y0 = max(0.0, x0), max(0.0, y0)
    x1, y1 = min(float(IMG_SIZE), x1), min(float(IMG_SIZE), y1)
    return [round(x0, 2), round(y0, 2), round(x1 - x0, 2), round(y1 - y0, 2)]


def main():
    if not os.path.exists(DB):
        sys.exit(f"DB not found at {DB} (run the backup first)")
    db = sqlite3.connect(DB)
    rolls = db.execute(
        "SELECT r.id, r.photoPath FROM rolls r "
        "WHERE EXISTS (SELECT 1 FROM die_results dr WHERE dr.rollId = r.id) "
        "ORDER BY r.id"
    ).fetchall()

    # Categories = die types present in the data ("die" generic first, then by face count),
    # all grouped under the COCO supercategory "die".
    faces_present = [f for (f,) in db.execute(
        "SELECT DISTINCT d.faces FROM die_results dr LEFT JOIN dice d ON d.id = dr.dieId"
    ).fetchall()]
    names = sorted({type_name(f) for f in faces_present},
                   key=lambda n: (n != GENERIC, int(n[1:]) if n != GENERIC else 0))
    cat_id = {n: i + 1 for i, n in enumerate(names)}
    categories = [{"id": cat_id[n], "name": n, "supercategory": "die"} for n in names]

    random.seed(SEED)
    shuffled = rolls[:]
    random.shuffle(shuffled)
    n_val = round(len(shuffled) * VAL_FRACTION)
    val_ids = {r[0] for r in shuffled[:n_val]}

    for d in ("images/train", "images/val", "annotations", "_debug"):
        os.makedirs(os.path.join(OUT, d), exist_ok=True)

    coco = {
        split: {"images": [], "annotations": [], "categories": categories}
        for split in ("train", "val")
    }
    ann_id = 1
    debug_left = N_DEBUG
    kept_imgs = kept_anns = skipped = 0

    for roll_id, photo_path in rolls:
        src = os.path.join(ROLLS, os.path.basename(photo_path))
        if not os.path.exists(src):
            skipped += 1
            continue
        # Orient exactly as the app did (EXIF transpose) before mapping normalized boxes.
        pil = ImageOps.exif_transpose(Image.open(src)).convert("RGB")
        w, h = pil.size
        rgb = np.asarray(pil)
        out_img, scale, pad_x, pad_y = letterbox(rgb)

        split = "val" if roll_id in val_ids else "train"
        file_name = os.path.basename(photo_path)
        Image.fromarray(out_img).save(os.path.join(OUT, "images", split, file_name), quality=95)
        coco[split]["images"].append(
            {"id": roll_id, "file_name": file_name, "width": IMG_SIZE, "height": IMG_SIZE}
        )
        kept_imgs += 1

        results = db.execute(
            "SELECT dr.value, dr.boundingBox, dr.dieId, dr.confidence, d.faces "
            "FROM die_results dr LEFT JOIN dice d ON d.id = dr.dieId WHERE dr.rollId=?",
            (roll_id,),
        ).fetchall()
        overlay = out_img.copy() if debug_left > 0 else None
        for value, bb, die_id, conf, faces in results:
            norm = tuple(float(x) for x in bb.split(","))
            bbox = to_coco_bbox(norm, w, h, scale, pad_x, pad_y)
            if bbox[2] <= 1 or bbox[3] <= 1:  # degenerate after clipping
                continue
            dtype = type_name(faces)
            coco[split]["annotations"].append({
                "id": ann_id,
                "image_id": roll_id,
                "category_id": cat_id[dtype],
                "bbox": bbox,
                "area": round(bbox[2] * bbox[3], 2),
                "iscrowd": 0,
                # Extra metadata (ignored by trainers; lets the set be repurposed):
                "die_type": dtype,
                "value": value,
                "die_id": die_id,
                "confidence": conf,
            })
            ann_id += 1
            kept_anns += 1
            if overlay is not None:
                x, y, bw, bh = bbox
                cv2.rectangle(overlay, (int(x), int(y)), (int(x + bw), int(y + bh)), (0, 255, 0), 2)
                cv2.putText(overlay, f"{dtype}:{value}", (int(x), int(y) - 4),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 255, 0), 2)
        if overlay is not None:
            Image.fromarray(overlay).save(os.path.join(OUT, "_debug", file_name))
            debug_left -= 1

    for split in ("train", "val"):
        with open(os.path.join(OUT, "annotations", f"instances_{split}.json"), "w") as f:
            json.dump(coco[split], f)

    print(f"images: train={len(coco['train']['images'])} val={len(coco['val']['images'])} "
          f"(kept {kept_imgs}, skipped-missing {skipped})")
    print(f"annotations: train={len(coco['train']['annotations'])} "
          f"val={len(coco['val']['annotations'])} (total {kept_anns})")
    print(f"classes: {[c['name'] for c in categories]}  img_size: {IMG_SIZE}  debug overlays: {N_DEBUG - debug_left}")


if __name__ == "__main__":
    main()
