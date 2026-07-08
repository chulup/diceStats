#!/usr/bin/env python3
"""
Convert the COCO dice dataset into Label Studio task JSON with the FULL info pre-filled as
annotations: one box per die, labelled by die type, plus the per-region face value. Label
Studio then holds everything as source of truth — review/fix there, and export back to COCO
for training (the die-type category round-trips through COCO; the face value lives only in LS).

Pairs with the config in label-studio/dice-detection.xml. Reads dataset/annotations/*.json
(produced by build_yolo_dataset.py) and writes dataset/label-studio/{train,val}.json.

Label Studio geometry is PERCENT of image size (COCO is pixels) — converted here.
Image URLs use LS's local-files scheme; set the project's Local Storage /
LOCAL_FILES_DOCUMENT_ROOT to the dataset/ directory so `images/<split>/<file>` resolves.
"""
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
COCO_DIR = os.path.join(ROOT, "dataset/annotations")
OUT_DIR = os.path.join(ROOT, "dataset/label-studio")
IMAGE_ROOT_URL = "/data/local-files/?d="  # + images/<split>/<file>
SPLITS = ("train", "val")


def pct(v, size):
    return round(v / size * 100, 4)


def convert(split):
    coco = json.load(open(os.path.join(COCO_DIR, f"instances_{split}.json")))
    cats = {c["id"]: c["name"] for c in coco["categories"]}
    anns_by_img = {}
    for a in coco["annotations"]:
        anns_by_img.setdefault(a["image_id"], []).append(a)

    tasks = []
    region_total = 0
    for img in coco["images"]:
        w, h = img["width"], img["height"]
        results = []
        for a in anns_by_img.get(img["id"], []):
            x, y, bw, bh = a["bbox"]
            rid = f"r{a['id']}"
            # Per-region results share one id + geometry; the classification adds its own key.
            geom = {"x": pct(x, w), "y": pct(y, h),
                    "width": pct(bw, w), "height": pct(bh, h), "rotation": 0}
            base = {"id": rid, "to_name": "image",
                    "original_width": w, "original_height": h, "image_rotation": 0}
            results.append({**base, "type": "rectanglelabels", "from_name": "label",
                            "value": {**geom, "rectanglelabels": [cats[a["category_id"]]]}})
            if a.get("value") is not None:
                results.append({**base, "type": "number", "from_name": "value",
                                "value": {**geom, "number": a["value"]}})
        region_total += len(anns_by_img.get(img["id"], []))
        tasks.append({
            "data": {
                "image": IMAGE_ROOT_URL + f"images/{split}/{img['file_name']}",
                "split": split,
                "file_name": img["file_name"],
            },
            "annotations": [{"result": results}],
        })

    os.makedirs(OUT_DIR, exist_ok=True)
    out = os.path.join(OUT_DIR, f"{split}.json")
    json.dump(tasks, open(out, "w"))
    return out, len(tasks), region_total


def main():
    for split in SPLITS:
        out, n_tasks, n_regions = convert(split)
        print(f"{split}: {n_tasks} tasks, {n_regions} boxes -> {os.path.relpath(out, ROOT)}")


if __name__ == "__main__":
    main()
