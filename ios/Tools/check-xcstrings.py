#!/usr/bin/env python3
"""Checks a String Catalog (.xcstrings) against the Swift sources that use it.

There is no Swift toolchain on the machines this project is written on, so nothing here can ask
Xcode whether a catalog is complete. This script is the stand-in: it reads the catalog as JSON and
the Swift files as text, and fails when the two disagree. It proves the catalog and the call sites
line up; it does not prove the app compiles or that a translation reads well.

Checks, each of which makes the exit status 1:

  (a) the catalog parses as JSON and its sourceLanguage is "en";
  (b) every key has a German ("de") localization whose state is "translated"; for a plural
      variation, both "one" and "other" exist in German and are translated;
  (c) the placeholders of each German value (and of each English value, where the catalog has
      one) are the same multiset as the key's: %@, %lld, %d, %%, and positional forms such as
      %1$@ (the position is ignored, so a translation may reorder arguments);
  (d) every literal handed to a call that localizes its argument is a key, and every key is used
      by some literal. Missing keys and unused keys are both reported.

Which literals count (d). The first argument of Text(...), Button(...), Label(...), Toggle(...),
Section(...), Picker(...), NavigationLink(...), Link(...), TextField(...), SecureField(...),
Menu(...), of the modifiers .navigationTitle,
.accessibilityLabel, .accessibilityHint, .accessibilityValue, .alert, .confirmationDialog and
.accessibilityAction(named:), of String(localized: ...) and of LocalizedStringKey(...), when it is
a string literal. `Text(verbatim: ...)` is not looked at: it is the way to say "this is data".
A helper of the project that takes a String and passes it to Text(...) shows it as written, so
its callers write String(localized: ...) and the helper is not listed. More names can be added
with --call, for a helper whose parameter is a LocalizedStringKey. A literal followed by `+` is an error, because concatenating
literals produces a plain String, which SwiftUI shows without looking it up.

The widget extension speaks through WidgetKit and AppIntents, whose text parameters are
LocalizedStringKey or LocalizedStringResource and therefore also look a literal up by key. These
forms count too: the first literal of IntentDescription(...), `@Parameter(title: ...)`,
DisplayRepresentation(title: ...), TypeDisplayRepresentation(name: ...), and of an assignment to a
TypeDisplayRepresentation or a LocalizedStringResource (`static let title: LocalizedStringResource
= "..."`); every literal inside `.configurationDisplayName(...)` and `.description(...)`, so a
ternary of two names is read; and every literal in the dictionary assigned to
`caseDisplayRepresentations`, whose keys are enum cases and whose values are the texts.

A key made only of placeholders ("%@", as in `DisplayRepresentation(title: "\\(name)")`) has no
words to translate: it passes its argument through, so it is neither required nor reported.
A name that is data, such as a provider's, is a key with `"shouldTranslate": false`.

How a literal becomes a key. Text is kept as written. A literal with an interpolation is a format
string, so its "%" is doubled to "%%" and every interpolation \\(...) becomes a placeholder; a
literal without one is looked up as written and its "%" stays single ("Above 80% used").
The script cannot see the type of the expression, and Swift writes %@ for a String but %lld for an
Int, so a placeholder of any kind (%@, %lld, %d, %ld, %f, positional or not) is compared as the
same placeholder: write whichever form is true in the catalog and either matches. Two catalog keys
that differ only in the kind of their placeholders are reported as ambiguous.

Comments are ignored. Multi-line (\"\"\") and raw (#"..."#) literals are not parsed.

Usage:
  check-xcstrings.py --catalog Localizable.xcstrings Sources \\
      --exclude Sources/Widget --exclude Sources/App/MarketingDemo.swift

Exit status: 0 clean, 1 at least one error, 2 bad command line.
"""

import argparse
import json
import re
import sys
from pathlib import Path

# One placeholder: %% (an escaped percent), or % with an optional position (1$), an optional length
# modifier (ll, l, h ...) and a conversion character. "%lldd" is %lld followed by the letter d.
PLACEHOLDER = re.compile(r"%%|%(?:\d+\$)?(?:hh|h|ll|l|z|t|j|q|L)?[@diouxXeEfFgGaAcCsSp]")

# Names whose first literal argument SwiftUI looks up in the catalog, written as `Name(`.
CONSTRUCTORS = [
    "Text", "Button", "Label", "Toggle", "Section", "Picker", "NavigationLink", "Link",
    "TextField", "SecureField", "Menu", "LocalizedStringKey", "IntentDescription",
]
# Modifiers, written as `.name(`.
MODIFIERS = [
    "navigationTitle", "accessibilityLabel", "accessibilityHint", "accessibilityValue",
    "alert", "confirmationDialog",
]

ESCAPES = {"n": "\n", "t": "\t", "r": "\r", "0": "\0", "\\": "\\", '"': '"', "'": "'"}


class LiteralError(Exception):
    pass


# ---------------------------------------------------------------------------------------------
# Reading Swift
# ---------------------------------------------------------------------------------------------

def skip_interpolation(src, i):
    """`i` is just after the opening of `\\(`; returns the index just after the matching `)`."""
    depth = 1
    n = len(src)
    while i < n and depth:
        c = src[i]
        if c == '"':
            i = skip_string(src, i)
            continue
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        i += 1
    return i


def skip_string(src, i):
    """`src[i]` is an opening quote; returns the index just after the closing one."""
    i += 1
    n = len(src)
    while i < n:
        c = src[i]
        if c == "\\":
            if src.startswith("\\(", i):
                i = skip_interpolation(src, i + 2)
            else:
                i += 2
            continue
        if c == '"':
            return i + 1
        if c == "\n":
            return i
        i += 1
    return n


def mask_comments(src):
    """The source with every comment replaced by spaces, so offsets and lines are unchanged."""
    out = list(src)
    n = len(src)
    i = 0

    def blank(start, end):
        for k in range(start, end):
            if out[k] != "\n":
                out[k] = " "

    while i < n:
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            i = n if j < 0 else j + 3
        elif src[i] == '"':
            i = skip_string(src, i)
        elif src.startswith("//", i):
            j = src.find("\n", i)
            j = n if j < 0 else j
            blank(i, j)
            i = j
        elif src.startswith("/*", i):
            depth, j = 1, i + 2
            while j < n and depth:
                if src.startswith("/*", j):
                    depth, j = depth + 1, j + 2
                elif src.startswith("*/", j):
                    depth, j = depth - 1, j + 2
                else:
                    j += 1
            blank(i, j)
            i = j
        else:
            i += 1
    return "".join(out)


def parse_literal(src, i):
    """Parses the literal starting at `src[i]`; returns (parts, end) where a part is
    ("t", text) or ("i", expression)."""
    i += 1
    n = len(src)
    parts, buf = [], []

    def flush():
        if buf:
            parts.append(("t", "".join(buf)))
            buf.clear()

    while i < n:
        c = src[i]
        if c == '"':
            flush()
            return parts, i + 1
        if c == "\\" and i + 1 < n:
            nxt = src[i + 1]
            if nxt == "(":
                end = skip_interpolation(src, i + 2)
                flush()
                parts.append(("i", src[i + 2:end - 1]))
                i = end
                continue
            if nxt == "u" and src.startswith("{", i + 2):
                close = src.find("}", i)
                if close < 0:
                    raise LiteralError("bad \\u escape")
                buf.append(chr(int(src[i + 3:close], 16)))
                i = close + 1
                continue
            buf.append(ESCAPES.get(nxt, nxt))
            i += 2
            continue
        if c == "\n":
            raise LiteralError("unterminated literal")
        buf.append(c)
        i += 1
    raise LiteralError("unterminated literal")


def key_of(parts):
    """The lookup key Swift builds for a literal. A literal with an interpolation becomes a format
    string, so its % is doubled and each interpolation is %@; a literal without one is the key as
    written."""
    if not any(kind == "i" for kind, _ in parts):
        return "".join(text for _, text in parts)
    return "".join(text.replace("%", "%%") if kind == "t" else "%@" for kind, text in parts)


def call_patterns(extra):
    """The patterns whose match ends at the opening quote of the one literal that is a key."""
    names = CONSTRUCTORS + list(extra)
    patterns = [re.compile(r"(?<![\w.])(?:%s)\(\s*(?=\")" % "|".join(map(re.escape, names)))]
    patterns.append(re.compile(r"(?<![\w.])String\(\s*localized:\s*(?=\")"))
    patterns.append(re.compile(r"\.(?:%s)\(\s*(?=\")" % "|".join(MODIFIERS)))
    patterns.append(re.compile(r"\.accessibilityAction\(\s*named:\s*(?=\")"))
    # WidgetKit and AppIntents: see the module docstring.
    patterns.append(re.compile(r"@Parameter\(\s*title:\s*(?=\")"))
    patterns.append(re.compile(
        r"(?<![\w.])(?:TypeDisplayRepresentation\(\s*name|DisplayRepresentation\(\s*title):\s*(?=\")"))
    patterns.append(re.compile(r"\b(?:TypeDisplayRepresentation|LocalizedStringResource)\s*=\s*(?=\")"))
    return patterns


# Patterns whose match ends at an opening ( or [, inside which every literal is a key.
REGION_PATTERNS = [
    re.compile(r"\.(?:configurationDisplayName|description)(?=\()"),
    re.compile(r"\bcaseDisplayRepresentations\s*:[^=\n]*=\s*(?=\[)"),
]


def region_literals(src, open_index):
    """The index of each string literal between the bracket at `open_index` and its partner."""
    depth = 0
    i = open_index
    n = len(src)
    while i < n:
        c = src[i]
        if c == '"':
            if depth:
                yield i
            i = skip_string(src, i)
            continue
        if c in "([":
            depth += 1
        elif c in ")]":
            depth -= 1
            if depth == 0:
                return
        i += 1


def has_words(key):
    """False for a key that is only placeholders: it passes its argument through."""
    rest = PLACEHOLDER.sub(lambda m: m.group(0) if m.group(0) == "%%" else "", key)
    return bool(rest.strip())


def scan_swift(path, patterns, errors):
    """Yields (line, key) for each localized literal in one file."""
    src = path.read_text(encoding="utf-8")
    masked = mask_comments(src)

    starts = []
    for pattern in patterns:
        starts.extend(match.end() for match in pattern.finditer(masked))
    for pattern in REGION_PATTERNS:
        for match in pattern.finditer(masked):
            starts.extend(region_literals(masked, match.end()))

    seen = set()
    for start in sorted(starts):
        if start in seen:
            continue
        seen.add(start)
        line = masked.count("\n", 0, start) + 1
        if masked.startswith('"""', start):
            errors.append(f"{path}:{line}: multi-line literal, not supported by this checker")
            continue
        try:
            parts, end = parse_literal(masked, start)
        except LiteralError as problem:
            errors.append(f"{path}:{line}: {problem}")
            continue
        if masked[end:].lstrip().startswith("+"):
            errors.append(
                f"{path}:{line}: literal is concatenated with +, which makes a plain String "
                "that is never looked up; write one literal")
            continue
        key = key_of(parts)
        if key and has_words(key):
            yield line, key


def swift_files(roots, excludes):
    excluded = [Path(e).resolve() for e in excludes]

    def is_excluded(path):
        resolved = path.resolve()
        return any(resolved == e or e in resolved.parents for e in excluded)

    for root in roots:
        root = Path(root)
        candidates = [root] if root.is_file() else sorted(root.rglob("*.swift"))
        for path in candidates:
            if not is_excluded(path):
                yield path


# ---------------------------------------------------------------------------------------------
# Reading the catalog
# ---------------------------------------------------------------------------------------------

def placeholders(text):
    """The placeholders of a string as a sorted list, positions dropped."""
    return sorted(re.sub(r"^%\d+\$", "%", p) for p in PLACEHOLDER.findall(text))


def fold(key):
    """A key with every placeholder that is not %% replaced by %@."""
    return PLACEHOLDER.sub(lambda m: m.group(0) if m.group(0) == "%%" else "%@", key)


def units_of(localization):
    """The (label, stringUnit) pairs of a localization and whether it uses substitutions."""
    units = []
    if "stringUnit" in localization:
        units.append(("", localization["stringUnit"]))
    plural = localization.get("variations", {}).get("plural")
    if plural is not None:
        for form, variant in plural.items():
            units.append((form, variant.get("stringUnit", {})))
    substitutions = localization.get("substitutions")
    if substitutions:
        for name, substitution in substitutions.items():
            for form, variant in substitution.get("variations", {}).get("plural", {}).items():
                units.append((f"{name}.{form}", variant.get("stringUnit", {})))
    return units, bool(substitutions)


def check_catalog(catalog, errors, warnings):
    if catalog.get("sourceLanguage") != "en":
        errors.append(f"[a] sourceLanguage is {catalog.get('sourceLanguage')!r}, expected 'en'")
    strings = catalog.get("strings")
    if not isinstance(strings, dict):
        errors.append("[a] the catalog has no 'strings' object")
        return {}

    folded = {}
    for key, entry in strings.items():
        folded.setdefault(fold(key), []).append(key)
    for same in folded.values():
        if len(same) > 1:
            errors.append(f"[d] ambiguous keys (differ only in placeholder kind): {same}")

    for key, entry in strings.items():
        if entry.get("shouldTranslate") is False:
            continue
        localizations = entry.get("localizations", {})
        de = localizations.get("de")
        if de is None:
            errors.append(f"[b] {key!r}: no 'de' localization")
            continue
        units, has_substitutions = units_of(de)
        if not units:
            errors.append(f"[b] {key!r}: 'de' has no stringUnit or plural variations")
            continue
        plural = de.get("variations", {}).get("plural")
        if plural is not None:
            for form in ("one", "other"):
                if form not in plural:
                    errors.append(f"[b] {key!r}: 'de' plural has no {form!r}")
        for label, unit in units:
            where = f"{key!r} de{'.' + label if label else ''}"
            if unit.get("state") != "translated":
                errors.append(f"[b] {where}: state is {unit.get('state')!r}, expected 'translated'")
            if not unit.get("value"):
                errors.append(f"[b] {where}: empty value")

        if has_substitutions:
            warnings.append(f"[c] {key!r}: uses substitutions, placeholders not compared")
            continue
        expected = placeholders(key)
        for language in ("de", "en"):
            localization = localizations.get(language)
            if localization is None:
                continue
            for label, unit in units_of(localization)[0]:
                actual = placeholders(unit.get("value", ""))
                if actual != expected:
                    where = f"{key!r} {language}{'.' + label if label else ''}"
                    errors.append(f"[c] {where}: placeholders {actual} differ from the key's {expected}")
    return folded


# ---------------------------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------------------------

def run(args):
    errors, warnings = [], []

    try:
        catalog = json.loads(Path(args.catalog).read_text(encoding="utf-8"))
    except (OSError, ValueError) as problem:
        print(f"ERROR [a] {args.catalog}: {problem}")
        return 1
    folded = check_catalog(catalog, errors, warnings)

    patterns = call_patterns(args.call)
    used = {}
    files = list(swift_files(args.sources, args.exclude))
    literals = 0
    for path in files:
        for line, key in scan_swift(path, patterns, errors):
            literals += 1
            used.setdefault(fold(key), []).append(f"{path}:{line}")

    for key, places in sorted(used.items()):
        if key not in folded:
            errors.append(f"[d] missing key {key!r} (first use {places[0]}, {len(places)} use(s))")
    unused = sorted(key for key in folded if key not in used)
    for key in unused:
        message = f"[d] unused key {folded[key][0]!r}"
        (warnings if args.warn_unused else errors).append(message)

    for message in warnings:
        print(f"WARN  {message}")
    for message in errors:
        print(f"ERROR {message}")
    print(
        f"{len(catalog.get('strings', {}))} keys, {literals} literals in {len(files)} files, "
        f"{len(errors)} error(s), {len(warnings)} warning(s)")
    return 1 if errors else 0


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Check a String Catalog against the Swift sources that use it. "
                    "See the module docstring for the rules.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="Exit status: 0 clean, 1 at least one error, 2 bad command line.")
    parser.add_argument("--catalog", required=True, help="the .xcstrings file")
    parser.add_argument("sources", nargs="+", help="Swift files or directories to scan")
    parser.add_argument("--exclude", action="append", default=[], metavar="PATH",
                        help="a file or directory to skip (repeatable)")
    parser.add_argument("--call", action="append", default=[], metavar="NAME",
                        help="another Name(...) whose first literal is a key (repeatable)")
    parser.add_argument("--warn-unused", action="store_true",
                        help="report unused keys as warnings, for a catalog shared with sources "
                             "that are not scanned in this run")
    return run(parser.parse_args(argv))


if __name__ == "__main__":
    sys.exit(main())
