import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("screenshots", Path(__file__).with_name("export-screenshots.py"))
screenshots = importlib.util.module_from_spec(spec)
spec.loader.exec_module(screenshots)


class ScreenshotExportTests(unittest.TestCase):
    def test_similar_capture_names_export_their_own_attachments(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            records = []
            names = [
                "iphone-custom-layout", "iphone-custom-layout-restored",
                "iphone-overview", "iphone-overview-dials", "iphone-overview-order",
                "iphone-overview-detail", "iphone-overview-empty",
                "iphone-settings", "iphone-settings-layout", "iphone-settings-credits",
                "iphone-credits-compact", "iphone-credits-expanded",
                "ipad-overview", "ipad-overview-dials",
            ]
            for index, name in enumerate(names):
                filename = f"attachment-{index}.png"
                (root / filename).write_bytes(name.encode())
                records.append({"suggestedHumanReadableName": f"{name}_0_UUID.png", "exportedFileName": filename})
            (root / "manifest.json").write_text(json.dumps(records))
            screenshots.export(root, root / "output", names)
            for name in names:
                self.assertEqual((root / "output" / f"{name}.png").read_bytes(), name.encode())

    def test_a_missing_overview_does_not_use_detail(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "detail.png").write_bytes(b"detail")
            (root / "manifest.json").write_text(json.dumps([{
                "suggestedHumanReadableName": "iphone-overview-detail_0_UUID.png",
                "exportedFileName": "detail.png",
            }]))
            with self.assertRaisesRegex(ValueError, "found 0"):
                screenshots.export(root, root / "output", ["iphone-overview"])

    def test_missing_new_captures_do_not_use_similar_names(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            records = []
            for index, name in enumerate([
                "iphone-overview", "iphone-settings", "ipad-overview", "iphone-credits-expanded",
            ]):
                filename = f"attachment-{index}.png"
                (root / filename).write_bytes(name.encode())
                records.append({"suggestedHumanReadableName": f"{name}_0_UUID.png", "exportedFileName": filename})
            (root / "manifest.json").write_text(json.dumps(records))
            for name in [
                "iphone-overview-dials", "iphone-settings-layout", "ipad-overview-dials",
                "iphone-credits-compact", "iphone-settings-credits",
            ]:
                with self.subTest(name=name), self.assertRaisesRegex(ValueError, "found 0"):
                    screenshots.export(root, root / "output", [name])

    def test_a_missing_expanded_capture_does_not_use_compact(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "compact.png").write_bytes(b"compact")
            (root / "manifest.json").write_text(json.dumps([{
                "suggestedHumanReadableName": "iphone-credits-compact_0_UUID.png",
                "exportedFileName": "compact.png",
            }]))
            with self.assertRaisesRegex(ValueError, "found 0"):
                screenshots.export(root, root / "output", ["iphone-credits-expanded"])

    def test_a_missing_capture_does_not_use_a_longer_name(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "restored.png").write_bytes(b"restored")
            (root / "manifest.json").write_text(json.dumps([{
                "suggestedHumanReadableName": "iphone-custom-layout-restored_0_UUID.png",
                "exportedFileName": "restored.png",
            }]))
            with self.assertRaisesRegex(ValueError, "found 0"):
                screenshots.export(root, root / "output", ["iphone-custom-layout"])


if __name__ == "__main__":
    unittest.main()
