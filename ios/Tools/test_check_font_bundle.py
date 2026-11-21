import importlib.util
from pathlib import Path
import plistlib
import shutil
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("ios_fonts", Path(__file__).with_name("check_font_bundle.py"))
fonts = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fonts)


class IOSFontBundleTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.app = Path(self.directory.name) / "UsageLimits.app"
        self.assets = fonts.canonical.read_manifest(fonts.canonical.MANIFEST)
        for name in self.assets:
            target = self.app / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(fonts.canonical.MANIFEST.parent / name, target)
        self.info = {"UIAppFonts": [name for name in self.assets if name.startswith("android-res/font/")]}
        self.write_plist(self.app, self.info)
        self.extension = self.app / "PlugIns/UsageLimitsWidget.appex"
        self.extension.mkdir(parents=True)
        self.write_plist(self.extension, {"NSExtension": {"NSExtensionPointIdentifier": "com.apple.widgetkit-extension"}})

    def write_plist(self, directory, value):
        (directory / "Info.plist").write_bytes(plistlib.dumps(value))

    def test_canonical_bytes_and_registration(self):
        self.assertEqual(fonts.check(self.app), (9, 6))

    def test_missing_or_changed_font_and_notice_are_rejected(self):
        for name in ["android-res/font/figtree.ttf", "android-res/raw/font_license_figtree.txt"]:
            with self.subTest(name=name):
                target = self.app / name
                original = target.read_bytes()
                target.write_bytes(original + b"changed")
                with self.assertRaisesRegex(fonts.Invalid, "SHA-256 mismatch: app"):
                    fonts.check(self.app)
                target.unlink()
                with self.assertRaisesRegex(fonts.Invalid, "cannot read"):
                    fonts.check(self.app)
                target.write_bytes(original)

    def test_extra_fonts_and_notices_are_rejected(self):
        for name in ["extra.ttf", "font_license_extra.txt"]:
            target = self.app / name
            target.write_bytes(b"extra")
            with self.assertRaisesRegex(fonts.Invalid, "extra app font or notice"):
                fonts.check(self.app)
            target.unlink()

    def test_wrong_duplicate_and_absent_registrations_are_rejected(self):
        for registrations in [[], self.info["UIAppFonts"] + self.info["UIAppFonts"][:1], ["missing.ttf"]]:
            self.write_plist(self.app, {"UIAppFonts": registrations})
            with self.assertRaisesRegex(fonts.Invalid, "UIAppFonts"):
                fonts.check(self.app)

    def test_extension_fonts_notices_and_even_empty_registration_are_rejected(self):
        for name in ["font.ttf", "font_license_figtree.txt"]:
            target = self.extension / name
            target.write_bytes(b"leak")
            with self.assertRaisesRegex(fonts.Invalid, "leaked into widget extension"):
                fonts.check(self.app)
            target.unlink()
        self.write_plist(self.extension, {"UIAppFonts": []})
        with self.assertRaisesRegex(fonts.Invalid, "UIAppFonts leaked"):
            fonts.check(self.app)


if __name__ == "__main__":
    unittest.main()
