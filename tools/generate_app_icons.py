"""Resize the opaque app-icon master. Development-only dependency: Pillow."""

import json
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    iconset = ROOT / "ios/UsageLimits/Sources/Assets.xcassets/AppIcon.appiconset"
    with Image.open(iconset / "icon-1024.png") as source:
        assert source.size == (1024, 1024)
        assert source.convert("RGBA").getchannel("A").getextrema() == (255, 255)
        master = source.convert("RGB")
    for entry in json.loads((iconset / "Contents.json").read_text())["images"]:
        pixels = int(float(entry["size"].split("x")[0]) * float(entry["scale"][:-1]))
        output = master.resize((pixels, pixels), Image.Resampling.LANCZOS)
        assert output.mode == "RGB"
        output.save(iconset / entry["filename"], optimize=True)


if __name__ == "__main__":
    main()
