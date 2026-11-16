#!/usr/bin/env python3
"""Arrange already captured real screenshot pixels; never synthesize status bar content."""
import argparse
from pathlib import Path
from PIL import Image, ImageDraw

parser = argparse.ArgumentParser()
parser.add_argument("root", type=Path)
parser.add_argument("sha")
args = parser.parse_args()
root = args.root
rows = []
for api in (30, 34, 35, 36):
    directory = root / f"prototype-statusbar-api{api}-{args.sha}"
    for variant in ("A", "C"):
        for scenario in ("a", "b", "c", "d", "e"):
            image = directory / f"api{api}-{variant}-{scenario}-statusbar.png"
            if image.exists():
                rows.append((f"API {api} / {variant} / {scenario}", image))
    for image in sorted(directory.glob(f"api{api}-candidate-*-statusbar.png")):
        rows.append((image.stem.replace("-statusbar", ""), image))
    for image in sorted(directory.glob(f"api{api}-E-*-statusbar.png")):
        rows.append((image.stem.replace("-statusbar", ""), image))
canvas = Image.new("RGB", (1060, 36 + 114 * len(rows)), "#e5eaf0")
draw = ImageDraw.Draw(canvas)
draw.text((12, 12), "REAL EMULATOR PIXELS — unscaled 720px screenshot crops; labels outside captured pixels", fill="#182433")
for i, (label, path) in enumerate(rows):
    y = 36 + i * 114
    draw.text((12, y + 40), label, fill="#182433")
    with Image.open(path) as image:
        canvas.paste(image.convert("RGB"), (330, y))
canvas.save(root / f"comparison-real-statusbars-{args.sha[:10]}.png")
manifest = [str(p.resolve()) for p in sorted(root.rglob("*.png"))]
(root / "all-png-absolute-paths.txt").write_text("\n".join(manifest) + "\n")
print(f"Comparison rows: {len(rows)}; PNG manifest paths: {len(manifest)}")
