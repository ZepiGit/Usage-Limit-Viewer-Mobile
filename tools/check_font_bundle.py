#!/usr/bin/env python3
"""Verify canonical font/license bytes in the repository and an Android APK.

Usage: python3 tools/check_font_bundle.py APK [--manifest MANIFEST]
Default manifest: third_party/fonts/manifest.json, relative to the repository root.
Manifest file paths are relative to the manifest directory. Android resources map
from android-res/ to res/ in the APK. SHA-256 pins the same bytes for future iOS
bundling; this checker does not build or inspect iOS bundles.
Exit status: 0 valid, 1 invalid, 2 invalid arguments.
"""

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import sys
import zipfile


ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "third_party/fonts/manifest.json"
FONT_EXTENSIONS = {".ttf", ".otf", ".ttc", ".woff", ".woff2"}
FONT_PATH = re.compile(r"android-res/font/[a-z][a-z0-9_]*\.ttf")
LICENSE_PATH = re.compile(r"android-res/raw/font_license_[a-z0-9_]+\.txt")
SHA256 = re.compile(r"[0-9a-f]{64}")


class Invalid(ValueError):
    pass


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise Invalid(f"invalid manifest: duplicate key {key!r}")
        result[key] = value
    return result


def _constant(name):
    raise Invalid(f"invalid manifest: {name} is not a JSON number")


def _object(value, keys, label):
    if not isinstance(value, dict) or set(value) != set(keys):
        raise Invalid(f"invalid manifest: {label} must contain exactly {', '.join(keys)}")


def _text(value, label):
    if not isinstance(value, str) or not value.strip():
        raise Invalid(f"invalid manifest: {label} must be a non-empty string")


def read_manifest(path: Path) -> dict[str, str]:
    try:
        raw = path.read_text(encoding="utf-8")
    except OSError as error:
        raise Invalid(f"cannot read manifest {path}: {error}") from None
    except UnicodeError as error:
        raise Invalid(f"invalid manifest: {error}") from None
    try:
        data = json.loads(raw, object_pairs_hook=_pairs, parse_constant=_constant)
        json.dumps(data, ensure_ascii=False).encode("utf-8")
    except (ValueError, UnicodeError, RecursionError) as error:
        raise Invalid(f"invalid manifest: {error}") from None
    _object(data, ("families",), "root")
    families = data["families"]
    if not isinstance(families, list) or not families:
        raise Invalid("invalid manifest: families must be a non-empty list")
    assets = {}
    names = set()
    for family in families:
        _object(family, ("family", "version", "source", "license", "fonts"), "family")
        for key in ("family", "version", "source"):
            _text(family[key], key)
        if family["family"] in names:
            raise Invalid(f"invalid manifest: duplicate family {family['family']!r}")
        names.add(family["family"])
        fonts = family["fonts"]
        if not isinstance(fonts, list) or not fonts:
            raise Invalid("invalid manifest: fonts must be a non-empty list")
        for asset, pattern, keys in [
            (family["license"], LICENSE_PATH,
             ("file", "sha256", "spdx", "source", "upstream_file", "copyright")),
            *((font, FONT_PATH, ("file", "upstream_file", "source", "sha256")) for font in fonts),
        ]:
            _object(asset, keys, "asset")
            for key in keys:
                _text(asset[key], key)
            name = asset["file"]
            if not pattern.fullmatch(name):
                raise Invalid(f"invalid manifest: invalid asset path {name!r}")
            if not SHA256.fullmatch(asset["sha256"]):
                raise Invalid(f"invalid manifest: invalid SHA-256 for {name}")
            if name in assets:
                raise Invalid(f"invalid manifest: duplicate asset {name}")
            assets[name] = asset["sha256"]
    return assets


def check(apk_path: Path, manifest_path: Path) -> tuple[int, int]:
    assets = read_manifest(manifest_path)
    expected = {}
    for name, digest in assets.items():
        path = manifest_path.parent / name
        try:
            with path.open("rb") as stream:
                actual = hashlib.file_digest(stream, "sha256").hexdigest()
        except FileNotFoundError:
            raise Invalid(f"missing repository file: {name}") from None
        except OSError as error:
            raise Invalid(f"cannot read repository file {name}: {error}") from None
        if actual != digest:
            raise Invalid(f"SHA-256 mismatch: repository {name}")
        expected[name.replace("android-res/", "res/", 1)] = digest

    try:
        with zipfile.ZipFile(apk_path) as apk:
            entries = set()
            for entry in apk.infolist():
                name = entry.filename
                if name in entries:
                    raise Invalid(f"duplicate APK entry: {name}")
                entries.add(name)
            for name in expected:
                if name not in entries:
                    raise Invalid(f"missing APK file: {name}")
            for name in sorted(entries - expected.keys()):
                if PurePosixPath(name).suffix.lower() in FONT_EXTENSIONS:
                    raise Invalid(f"extra APK font: {name}")
                if LICENSE_PATH.fullmatch(name.replace("res/", "android-res/", 1)):
                    raise Invalid(f"extra APK license: {name}")
            for name, digest in expected.items():
                with apk.open(name) as stream:
                    actual = hashlib.file_digest(stream, "sha256").hexdigest()
                if actual != digest:
                    raise Invalid(f"SHA-256 mismatch: APK {name}")
    except (OSError, zipfile.BadZipFile, RuntimeError, NotImplementedError) as error:
        raise Invalid(f"cannot read APK {apk_path}: {error}") from None
    fonts = sum(name.startswith("res/font/") for name in expected)
    return fonts, len(expected) - fonts


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--manifest", type=Path, default=MANIFEST)
    arguments = parser.parse_args(argv[1:])
    try:
        fonts, licenses = check(arguments.apk, arguments.manifest)
    except Invalid as error:
        print(f"{arguments.apk}: {error}", file=sys.stderr)
        return 1
    font_label = "font" if fonts == 1 else "fonts"
    license_label = "license" if licenses == 1 else "licenses"
    print(f"{arguments.apk}: valid font bundle ({fonts} {font_label}, {licenses} {license_label})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
