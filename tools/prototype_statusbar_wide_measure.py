#!/usr/bin/env python3
"""Measure visible mask pixels in already-captured black-background probes."""
import argparse
import json
from pathlib import Path
from PIL import Image

parser = argparse.ArgumentParser()
parser.add_argument("directory", type=Path)
parser.add_argument("--api", type=int, required=True)
args = parser.parse_args()
rows = []
for path in sorted(args.directory.glob(f"api{args.api}-wide-*-probe.png")):
    name = path.stem.removeprefix(f"api{args.api}-wide-").removesuffix("-probe")
    variant, provider = name.rsplit("-", 1)
    with Image.open(path).convert("RGB") as image:
        # These bounds are grounded in the delivered full probe screenshots:
        # API30 clock + system icon precede the account at x~136; API36 clock
        # precedes the account at x~148. Status bars have different y offsets.
        region = (132, 0, 170, 50) if args.api == 30 else (145, 55, 181, 93)
        points = [(x, y) for y in range(region[1], region[3]) for x in range(region[0], region[2]) if min(image.getpixel((x, y))) > 30]
        bbox = [min(x for x, y in points), min(y for x, y in points), max(x for x, y in points)+1, max(y for x, y in points)+1] if points else None
        crop = image.crop((region[0]-4, region[1], region[2]+4, region[3]))
        target = args.directory / f"api{args.api}-wide-{variant}-{provider}-measured-icon.png"
        crop.save(target)
        crop.resize((crop.width*4, crop.height*4), Image.Resampling.NEAREST).save(target.with_stem(target.stem + "-x4"))
        rows.append({"api":args.api,"variant":variant,"provider":provider,"region":region,"threshold":30,"alpha_bbox":bbox,"width":bbox[2]-bbox[0] if bbox else None,"height":bbox[3]-bbox[1] if bbox else None})
(args.directory / "wide-icon-measured-visible-bounds.json").write_text(json.dumps(rows,indent=2))
for row in rows: print(row['variant'],row['provider'],row['width'],row['height'],row['alpha_bbox'])
