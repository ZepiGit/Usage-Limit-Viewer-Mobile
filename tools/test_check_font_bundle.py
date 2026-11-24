import copy
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import warnings
import zipfile


CHECKER = Path(__file__).with_name("check_font_bundle.py")
FONT = "android-res/font/example_regular.ttf"
LICENSE = "android-res/raw/font_license_example.txt"
FONT_BYTES = b"synthetic font bytes\x00\x01"
LICENSE_BYTES = b"Synthetic license fixture\n"


class CheckFontBundleTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.bundle = self.root / "third_party/fonts"
        self.manifest = self.bundle / "manifest.json"
        self.apk = self.root / "app.apk"
        self.resource_table = None
        self.data = {
            "families": [{
                "family": "Example",
                "version": "1.0",
                "source": "https://example.invalid/fonts/1.0",
                "license": {
                    "file": LICENSE,
                    "sha256": hashlib.sha256(LICENSE_BYTES).hexdigest(),
                    "spdx": "OFL-1.1",
                    "source": "https://example.invalid/fonts/1.0/OFL.txt",
                    "upstream_file": "OFL.txt",
                    "copyright": "Copyright 2026 Example Authors",
                },
                "fonts": [{
                    "file": FONT,
                    "upstream_file": "fonts/Example-Regular.ttf",
                    "source": "https://example.invalid/fonts/1.0/Example-Regular.ttf",
                    "sha256": hashlib.sha256(FONT_BYTES).hexdigest(),
                }],
            }],
        }
        for name, content in ((FONT, FONT_BYTES), (LICENSE, LICENSE_BYTES)):
            path = self.bundle / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
        self.write_manifest()
        self.entries = [
            ("res/font/example_regular.ttf", FONT_BYTES),
            ("res/raw/font_license_example.txt", LICENSE_BYTES),
            ("AndroidManifest.xml", b"unrelated APK bytes"),
        ]
        self.write_apk()

    def write_manifest(self):
        self.manifest.write_text(json.dumps(self.data), encoding="utf-8")

    def write_apk(self):
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.apk, "w", zipfile.ZIP_DEFLATED) as apk:
                for name, content in self.entries:
                    apk.writestr(name, content)

    def run_checker(self, *arguments, script=CHECKER):
        metadata = ["--resource-table", str(self.resource_table)] if self.resource_table else []
        return subprocess.run(
            [sys.executable, str(script), *map(str, arguments), *metadata],
            capture_output=True, text=True, cwd=self.root, check=False,
        )

    def assert_rejected(self, message):
        result = self.run_checker(self.apk, "--manifest", self.manifest)
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertEqual(result.stdout, "")
        self.assertIn(message, result.stderr)
        self.assertNotIn("Traceback", result.stderr)

    def test_exact_bundle_succeeds(self):
        result = self.run_checker(self.apk, "--manifest", self.manifest)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, f"{self.apk}: valid font bundle (1 font, 1 license)\n")
        self.assertEqual(result.stderr, "")

    def resource_dump(self, font="res/F1.ttf", license="res/L1.txt"):
        return (
            "Binary APK\nPackage name=com.example id=7f\n"
            "  type font id=07 entryCount=1\n"
            "    resource 0x7f070000 font/example_regular\n"
            f"      () (file) {font}\n"
            "  type raw id=0e entryCount=1\n"
            "    resource 0x7f0e0000 raw/font_license_example\n"
            f"      () (file) {license}\n"
        )

    def use_resource_dump(self, text):
        self.resource_table = self.root / "resources.txt"
        self.resource_table.write_text(text, encoding="utf-8")

    def use_optimized_apk(self):
        self.entries = [("res/F1.ttf", FONT_BYTES), ("res/L1.txt", LICENSE_BYTES)]
        self.write_apk()
        self.use_resource_dump(self.resource_dump())

    def test_optimized_and_debug_resources_succeed_with_resource_table(self):
        for optimized in (False, True):
            with self.subTest(optimized=optimized):
                if optimized:
                    self.use_optimized_apk()
                else:
                    self.use_resource_dump(self.resource_dump(
                        "res/font/example_regular.ttf", "res/raw/font_license_example.txt",
                    ))
                result = self.run_checker(self.apk, "--manifest", self.manifest)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout, f"{self.apk}: valid font bundle (1 font, 1 license)\n")
                self.assertEqual(result.stderr, "")

    def test_optimized_missing_and_changed_zip_assets_are_rejected(self):
        for name in ("res/F1.ttf", "res/L1.txt"):
            for mutation in ("missing", "changed"):
                with self.subTest(name=name, mutation=mutation):
                    self.use_optimized_apk()
                    self.entries = [
                        (key, b"changed" if key == name else value)
                        for key, value in self.entries
                        if mutation != "missing" or key != name
                    ]
                    self.write_apk()
                    message = "missing APK file" if mutation == "missing" else "SHA-256 mismatch: APK"
                    self.assert_rejected(f"{message}: {name}" if mutation == "missing" else f"{message} {name}")

    def test_resource_table_missing_extra_and_duplicate_mappings_are_rejected(self):
        original = self.resource_dump()
        font_header = "    resource 0x7f070000 font/example_regular\n"
        font_value = "      () (file) res/F1.ttf\n"
        cases = (
            (original.replace(font_header + font_value, ""), "missing resource mapping: font/example_regular"),
            (original.replace(font_value, ""), "missing resource file: font/example_regular"),
            (original + font_header + font_value, "duplicate resource mapping: font/example_regular"),
            (original.replace(font_value, font_value * 2), "duplicate resource file: font/example_regular"),
            (original.replace(font_value, font_value + "      () (file) res/other.ttf\n"),
             "duplicate resource file: font/example_regular"),
            (original + "    resource 0x7f070001 font/extra\n      () (file) res/extra.xml\n",
             "extra font resource: font/extra"),
            (original + "    resource 0x7f0e0001 raw/font_license_extra\n      () (file) res/extra.txt\n",
             "extra license resource: raw/font_license_extra"),
            (original.replace("0x7f0e0000", "0x7f070000"), "duplicate resource id: 0x7f070000"),
            (original.replace("res/L1.txt", "res/F1.ttf"), "conflicting resource path: res/F1.ttf"),
            (original.replace("res/F1.ttf", "../outside.ttf"), "invalid resource path"),
            (original.replace("res/F1.ttf", "res/../outside.ttf"), "invalid resource path"),
            (original.replace(font_value, "      (v26) (file) res/F1.ttf\n"), "invalid resource value"),
            (original.replace(font_value, "      () @font/other\n"), "invalid resource value"),
        )
        self.use_optimized_apk()
        for text, message in cases:
            with self.subTest(message=message, text=text):
                self.use_resource_dump(text)
                self.assert_rejected(message)

    def test_optimized_extra_font_outside_resource_table_is_rejected(self):
        self.use_optimized_apk()
        self.entries.append(("assets/hidden.woff2", b"hidden font"))
        self.write_apk()
        self.assert_rejected("extra APK font: assets/hidden.woff2")

    def test_unrelated_resources_are_ignored(self):
        self.use_optimized_apk()
        self.use_resource_dump(self.resource_dump() + (
            "    resource 0x7f0e0001 raw/unrelated\n      () (file) res/unrelated.xml\n"
            "    resource 0x7f100000 string/label\n      () \"Example\"\n"
        ))
        result = self.run_checker(self.apk, "--manifest", self.manifest)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_missing_or_invalid_resource_table_is_rejected(self):
        self.use_optimized_apk()
        self.resource_table.unlink()
        self.assert_rejected("cannot read resource table")
        self.resource_table.write_bytes(b"\xff")
        self.assert_rejected("cannot read resource table")
        self.resource_table.write_text("not a resource dump", encoding="utf-8")
        self.assert_rejected("missing resource mapping")

    def test_cli_defaults_to_the_canonical_manifest_next_to_its_tools(self):
        tools = self.root / "tools"
        tools.mkdir()
        script = tools / CHECKER.name
        shutil.copyfile(CHECKER, script)
        result = self.run_checker(self.apk, script=script)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, f"{self.apk}: valid font bundle (1 font, 1 license)\n")
        self.assertEqual(result.stderr, "")

    def test_missing_apk_assets_are_rejected(self):
        original = list(self.entries)
        for missing in original[:2]:
            with self.subTest(missing=missing[0]):
                self.entries = [entry for entry in original if entry != missing]
                self.write_apk()
                self.assert_rejected(f"missing APK file: {missing[0]}")

    def test_changed_apk_assets_are_rejected(self):
        original = list(self.entries)
        for name, _ in original[:2]:
            with self.subTest(changed=name):
                self.entries = [(key, b"changed" if key == name else value) for key, value in original]
                self.write_apk()
                self.assert_rejected(f"SHA-256 mismatch: APK {name}")

    def test_extra_fonts_anywhere_in_apk_are_rejected(self):
        original = list(self.entries)
        for name in (
            "res/font/unlisted.ttf", "assets/hidden.otf", "arbitrary/font.ttc",
            "nested/font.woff", "nested/font.woff2", "assets/UPPER.TTF",
        ):
            with self.subTest(extra=name):
                self.entries = original + [(name, b"unlisted font")]
                self.write_apk()
                self.assert_rejected(f"extra APK font: {name}")

    def test_extra_font_license_in_apk_is_rejected(self):
        self.entries.append(("res/raw/font_license_unlisted.txt", b"unlisted license"))
        self.write_apk()
        self.assert_rejected("extra APK license: res/raw/font_license_unlisted.txt")

    def test_duplicate_zip_entries_are_rejected_including_unrelated_files(self):
        original = list(self.entries)
        for duplicate in original:
            with self.subTest(duplicate=duplicate[0]):
                self.entries = original + [duplicate]
                self.write_apk()
                self.assert_rejected(f"duplicate APK entry: {duplicate[0]}")

    def test_missing_repository_assets_are_rejected(self):
        for name, content in ((FONT, FONT_BYTES), (LICENSE, LICENSE_BYTES)):
            with self.subTest(missing=name):
                path = self.bundle / name
                path.unlink()
                self.assert_rejected(f"missing repository file: {name}")
                path.write_bytes(content)

    def test_changed_repository_assets_are_rejected_even_when_apk_matches(self):
        for name, content in ((FONT, FONT_BYTES), (LICENSE, LICENSE_BYTES)):
            with self.subTest(changed=name):
                path = self.bundle / name
                path.write_bytes(b"changed")
                self.assert_rejected(f"SHA-256 mismatch: repository {name}")
                path.write_bytes(content)

    def test_manifest_digest_is_authoritative_even_when_repo_and_apk_match(self):
        self.data["families"][0]["fonts"][0]["sha256"] = "0" * 64
        self.write_manifest()
        self.assert_rejected(f"SHA-256 mismatch: repository {FONT}")

    def test_malformed_manifest_shapes_and_duplicate_assets_are_rejected(self):
        original = copy.deepcopy(self.data)
        malformed = [None, [], {}, {"families": []}, {"families": [None]}]
        for key, value in (
            ("family", ""), ("version", None), ("source", 7),
            ("fonts", []), ("fonts", [None]), ("license", None),
        ):
            data = copy.deepcopy(original)
            data["families"][0][key] = value
            malformed.append(data)
        for container, key, value in (
            ("fonts", "file", "../outside.ttf"),
            ("fonts", "file", "/absolute.ttf"),
            ("fonts", "file", "android-res/font/../outside.ttf"),
            ("fonts", "sha256", "not a digest"),
            ("fonts", "upstream_file", ""),
            ("license", "file", "android-res/raw/not_a_font_license.txt"),
            ("license", "sha256", 42),
        ):
            data = copy.deepcopy(original)
            asset = data["families"][0][container]
            if container == "fonts":
                asset = asset[0]
            asset[key] = value
            malformed.append(data)
        data = copy.deepcopy(original)
        data["families"][0]["fonts"].append(copy.deepcopy(data["families"][0]["fonts"][0]))
        malformed.append(data)
        data = copy.deepcopy(original)
        data["families"].append(copy.deepcopy(data["families"][0]))
        malformed.append(data)
        for data in malformed:
            with self.subTest(manifest=data):
                self.data = data
                self.write_manifest()
                self.assert_rejected("invalid manifest")

    def test_missing_or_invalid_asset_metadata_is_rejected(self):
        original = copy.deepcopy(self.data)
        for container in ("license", "fonts"):
            template = original["families"][0][container]
            if container == "fonts":
                template = template[0]
            for key in template:
                for mutation in ("missing", "empty", "wrong type"):
                    with self.subTest(container=container, key=key, mutation=mutation):
                        self.data = copy.deepcopy(original)
                        asset = self.data["families"][0][container]
                        if container == "fonts":
                            asset = asset[0]
                        if mutation == "missing":
                            del asset[key]
                        else:
                            asset[key] = "" if mutation == "empty" else 42
                        self.write_manifest()
                        self.assert_rejected("invalid manifest")

    def test_malformed_json_and_duplicate_json_keys_are_rejected(self):
        for raw in (b"{", b"\xff", b'{"families": [], "families": []}', b'{"families": NaN}'):
            with self.subTest(raw=raw):
                self.manifest.write_bytes(raw)
                self.assert_rejected("invalid manifest")

    def test_only_ofl_1_1_licenses_are_accepted(self):
        for spdx in ("MIT", "Apache-2.0", "OFL-1.0", "ofl-1.1", " OFL-1.1 "):
            with self.subTest(spdx=spdx):
                self.data["families"][0]["license"]["spdx"] = spdx
                self.write_manifest()
                self.assert_rejected("invalid manifest: license spdx must be OFL-1.1")

    def test_missing_manifest_is_rejected(self):
        self.manifest.unlink()
        self.assert_rejected("cannot read manifest")

    def test_missing_or_invalid_apk_is_rejected(self):
        self.apk.unlink()
        self.assert_rejected("cannot read APK")
        self.apk.write_bytes(b"not a ZIP archive")
        self.assert_rejected("cannot read APK")

    def test_multiple_families_and_fonts_are_checked(self):
        other_font = "android-res/font/other_regular.ttf"
        other_license = "android-res/raw/font_license_other.txt"
        family = copy.deepcopy(self.data["families"][0])
        family["family"] = "Other"
        family["license"]["file"] = other_license
        family["fonts"][0]["file"] = other_font
        self.data["families"].append(family)
        for name, content in ((other_font, FONT_BYTES), (other_license, LICENSE_BYTES)):
            (self.bundle / name).write_bytes(content)
            self.entries.append((name.replace("android-res/", "res/"), content))
        self.write_manifest()
        self.write_apk()
        result = self.run_checker(self.apk, "--manifest", self.manifest)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, f"{self.apk}: valid font bundle (2 fonts, 2 licenses)\n")
        self.assertEqual(result.stderr, "")


if __name__ == "__main__":
    unittest.main()
