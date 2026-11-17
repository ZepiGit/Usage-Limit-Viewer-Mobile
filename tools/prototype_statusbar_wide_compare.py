#!/usr/bin/env python3
"""Arrange measured real glyph-left crops at exact nearest x4."""
import argparse
from pathlib import Path
from PIL import Image, ImageDraw
parser = argparse.ArgumentParser()
parser.add_argument("directory", type=Path)
args = parser.parse_args()
paths = [args.directory / f"api36-wide-{v}-codex-measured-icon-x4.png" for v in ("B", "Gsquare", "Gwide", "GthreeTwo", "Gcondensed")]
image = Image.new("RGB", (1100, 230), "#e5eaf0")
draw = ImageDraw.Draw(image)
for index, path in enumerate(paths):
    variant = path.name.split("-")[2]
    draw.text((index*220+12, 12), variant, fill="#182433")
    with Image.open(path) as crop: image.paste(crop, (index*220+12, 45))
image.save(args.directory / "api36-G-versus-B-measured-x4.png")
print(args.directory / "api36-G-versus-B-measured-x4.png")
