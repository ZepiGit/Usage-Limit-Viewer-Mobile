#!/usr/bin/env python3
"""Check the built iOS .app against the canonical Android/iOS font manifest.

The app uses a folder reference to third_party/fonts/android-res. No files are
regenerated, renamed or copied in the repository. Widget extensions must contain
neither these resources nor a UIAppFonts registration.
"""

import argparse
import hashlib
import importlib.util
from pathlib import Path
import plistlib
import sys

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("canonical_font_bundle", ROOT / "tools/check_font_bundle.py")
canonical = importlib.util.module_from_spec(spec)
spec.loader.exec_module(canonical)
Invalid = canonical.Invalid


def digest(path):
    try:
        with path.open("rb") as stream:
            return hashlib.file_digest(stream, "sha256").hexdigest()
    except OSError as error:
        raise Invalid(f"cannot read {path}: {error}") from None


def plist(path):
    try:
        with path.open("rb") as stream:
            return plistlib.load(stream)
    except (OSError, ValueError, plistlib.InvalidFileException) as error:
        raise Invalid(f"cannot read {path}: {error}") from None


def check(app, manifest=canonical.MANIFEST):
    assets = canonical.read_manifest(manifest)
    fonts = {name for name in assets if name.startswith("android-res/font/")}
    for name, expected in assets.items():
        if digest(manifest.parent / name) != expected:
            raise Invalid(f"SHA-256 mismatch: repository {name}")
        if digest(app / name) != expected:
            raise Invalid(f"SHA-256 mismatch: app {name}")
    registrations = plist(app / "Info.plist").get("UIAppFonts", [])
    if not isinstance(registrations, list) or len(registrations) != len(set(registrations)) or set(registrations) != fonts:
        raise Invalid("UIAppFonts must register each canonical font exactly once")
    actual = set()
    for path in app.rglob("*"):
        if not path.is_file():
            continue
        name = path.relative_to(app).as_posix()
        extension = next((parent for parent in path.parents if parent.suffix == ".appex"), None)
        is_font = path.suffix.lower() in canonical.FONT_EXTENSIONS
        is_notice = path.name.startswith("font_license_") and path.suffix == ".txt"
        if extension is not None and (is_font or is_notice):
            raise Invalid(f"app font resource leaked into widget extension: {name}")
        if is_font or is_notice:
            if name not in assets:
                raise Invalid(f"extra app font or notice: {name}")
            actual.add(name)
    if actual != set(assets):
        raise Invalid("canonical app font resources are incomplete")
    for extension in (app / "PlugIns").glob("*.appex"):
        if "UIAppFonts" in plist(extension / "Info.plist"):
            raise Invalid(f"UIAppFonts leaked into {extension.name}")
    return len(fonts), len(assets) - len(fonts)


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("app", type=Path)
    parser.add_argument("--manifest", type=Path, default=canonical.MANIFEST)
    arguments = parser.parse_args(argv[1:])
    try:
        fonts, notices = check(arguments.app, arguments.manifest)
    except Invalid as error:
        print(f"{arguments.app}: {error}", file=sys.stderr)
        return 1
    print(f"{arguments.app}: valid canonical font bundle ({fonts} fonts, {notices} notices); extensions isolated")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
