import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("glyphs", Path(__file__).with_name("check_glyph_file.py"))
glyphs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(glyphs)

FIXTURE = glyphs.ROOT / "shared/test-vectors/glyph-file-raw.json"


def edited(shipped: bytes, case: dict) -> bytes:
    """The shipped file with the case's one edit, as bytes (some cases are not valid text)."""
    edit = case["edit"]
    if edit == "none":
        return shipped
    if edit == "prepend":
        return case["text"].encode() + shipped
    if edit == "append":
        return shipped + case["text"].encode()
    find = case["find"].encode()
    if find not in shipped:
        raise AssertionError(f"{case['name']}: find is not in the shipped file")
    if edit == "bytes":
        return shipped.replace(find, bytes.fromhex(case["hex"]), 1)
    if edit == "replace":
        if "nest" in case:
            middle = case["open"] * case["nest"] + case["core"] + case["close"] * case["nest"]
            replacement = case["with"] + middle + case["after"]
        else:
            replacement = case["with"]
        return shipped.replace(find, replacement.encode(), 1)
    raise AssertionError(f"{case['name']}: unknown edit {edit}")


class CheckGlyphFileTests(unittest.TestCase):
    def setUp(self):
        self.shipped = glyphs.GLYPH_FILE.read_bytes()
        self.providers = glyphs.expected_providers()

    def verdict(self, raw: bytes) -> str:
        try:
            glyphs.check(raw, self.providers)
        except glyphs.Invalid:
            return "reject"
        return "accept"

    def test_the_shipped_file_is_valid(self):
        self.assertEqual(self.verdict(self.shipped), "accept")
        self.assertEqual(glyphs.main(["check_glyph_file.py"]), 0)

    def test_every_raw_fixture_case_gets_its_verdict(self):
        cases = json.loads(FIXTURE.read_text(encoding="utf-8"))["cases"]
        self.assertTrue(any(c["expect"] == "accept" for c in cases))
        self.assertTrue(any(c["expect"] == "reject" for c in cases))
        for case in cases:
            with self.subTest(case["name"]):
                self.assertEqual(self.verdict(edited(self.shipped, case)), case["expect"])

    def test_the_parsed_whole_file_cases_both_apps_read_get_their_verdicts(self):
        vectors = json.loads(glyphs.VECTORS.read_text(encoding="utf-8"))
        for verdict, key in (("accept", "acceptFiles"), ("reject", "rejectFiles")):
            for case in vectors[key]:
                with self.subTest(case["name"]):
                    raw = json.dumps(case["file"]).encode()
                    self.assertEqual(self.verdict(raw), verdict)

    def test_the_shared_path_grammar_cases_get_their_verdicts(self):
        vectors = json.loads(glyphs.VECTORS.read_text(encoding="utf-8"))
        for case in vectors["accept"]:
            with self.subTest(case["name"]):
                glyphs.check_path(case["path"], case["name"])
        for case in vectors["reject"]:
            with self.subTest(case["name"]):
                with self.assertRaises(glyphs.Invalid):
                    glyphs.check_path(case["path"], case["name"])


if __name__ == "__main__":
    unittest.main()
