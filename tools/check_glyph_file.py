#!/usr/bin/env python3
"""Fail unless the status icon glyph file is strict JSON of the documented shape.

The file ships inside both apps, and their loaders trust it beyond their own schema check, so CI
runs this before it can ship. The shape and the path grammar are in docs/providers/assets.md
("Glyph path grammar"). The expected provider ids come from shared/test-vectors/
glyph-path-grammar.json ("providers"), which the Android and iOS suites check against their own
provider enums.

Usage: check_glyph_file.py [glyph file]   (default: shared/glyphs/status-icon-glyphs.json)
Exit status: 0 valid, 1 invalid.
"""

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GLYPH_FILE = ROOT / "shared/glyphs/status-icon-glyphs.json"
VECTORS = ROOT / "shared/test-vectors/glyph-path-grammar.json"

ARITY = {"M": 2, "L": 2, "H": 1, "V": 1, "C": 6, "Z": 0}
# ASCII digits only: without re.ASCII, \d also matches digits of other scripts, which the apps reject.
NUMBER = re.compile(r"-?(?:\d+(?:\.\d*)?|\.\d+)", re.ASCII)


class Invalid(ValueError):
    pass


def expected_providers() -> list[str]:
    providers = json.loads(VECTORS.read_text(encoding="utf-8"))["providers"]
    if not providers or len(set(providers)) != len(providers):
        raise Invalid(f"{VECTORS.name}: 'providers' must be a non-empty list of distinct ids")
    return providers


def _pairs(pairs):
    keys = [key for key, _ in pairs]
    duplicates = sorted({key for key in keys if keys.count(key) > 1})
    if duplicates:
        raise Invalid(f"duplicate key {duplicates[0]!r}")
    return dict(pairs)


def _constant(name):
    raise Invalid(f"{name} is not a JSON number")


def check_path(data: str, name: str) -> None:
    """The documented subset: absolute M L H V C Z with their operand counts."""
    index, command, commands = 0, None, 0

    def skip():
        nonlocal index
        while index < len(data) and data[index] in " ,":
            index += 1

    while True:
        skip()
        if index == len(data):
            break
        c = data[index]
        if c.isalpha():
            if c not in ARITY:
                raise Invalid(f"{name}: unsupported path command {c!r}")
            if commands == 0 and c != "M":
                raise Invalid(f"{name}: path data must start with M")
            command, commands, index = c, commands + 1, index + 1
            if c == "Z":
                continue
        else:
            if command is None or command == "Z":
                raise Invalid(f"{name}: operands without a command at {index}")
            if command == "M":
                command = "L"
        for _ in range(ARITY[command]):
            skip()
            match = NUMBER.match(data, index)
            if not match:
                raise Invalid(f"{name}: command {command} expects {ARITY[command]} numbers at {index}")
            index = match.end()
            if index < len(data) and not (data[index] in " ,-" or data[index].isalpha()):
                raise Invalid(f"{name}: malformed number at {match.start()}")
    if commands == 0:
        raise Invalid(f"{name}: empty path data")


def check(raw: bytes, providers: list[str]) -> None:
    if raw.startswith(b"\xef\xbb\xbf"):
        raise Invalid("byte-order mark")
    try:
        text = raw.decode("utf-8", errors="strict")
    except UnicodeDecodeError as error:
        raise Invalid(f"not UTF-8: {error}") from None
    try:
        root = json.loads(text, object_pairs_hook=_pairs, parse_constant=_constant)
    except RecursionError:
        raise Invalid("nested too deeply") from None
    except json.JSONDecodeError as error:
        raise Invalid(f"not strict JSON: {error}") from None
    # Python decodes a lone escaped surrogate into the key or path; it is not text either app reads.
    try:
        json.dumps(root, ensure_ascii=False).encode("utf-8")
    except UnicodeEncodeError:
        raise Invalid("a lone surrogate escape") from None

    if not isinstance(root, dict) or set(root) != {"grid", "glyphs", "fallback"}:
        raise Invalid("expected an object with exactly grid, glyphs and fallback")
    grid = root["grid"]
    if isinstance(grid, bool) or not isinstance(grid, (int, float)) or grid != 24:
        raise Invalid("grid must be the JSON number 24")
    glyphs = root["glyphs"]
    if not isinstance(glyphs, dict):
        raise Invalid("glyphs must be an object of provider id to path")
    if set(glyphs) != set(providers):
        raise Invalid(f"glyphs {sorted(glyphs)} do not match the providers {sorted(providers)}")
    for name, path in [*glyphs.items(), ("fallback", root["fallback"])]:
        if not isinstance(path, str):
            raise Invalid(f"{name}: a path must be a JSON string")
        check_path(path, name)


def main(argv: list[str]) -> int:
    path = Path(argv[1]) if len(argv) > 1 else GLYPH_FILE
    try:
        check(path.read_bytes(), expected_providers())
    except Invalid as error:
        print(f"{path}: {error}", file=sys.stderr)
        return 1
    print(f"{path}: valid")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
