"""Runtime smoke pixel and observed picker geometry checks: python3 -m unittest discover -s tools"""

import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import android_runtime_smoke as runtime_smoke
from android_runtime_smoke import Smoke, bounds, changed_pixels, judge_press, png_bytes, png_frame, rounded_rect_distance, scaled_crop

# The CI emulator's 280 dpi: 1.75 px per dp, so the 6dp pill radius is 10.5 px.
RADIUS = 10.5
PILL = (100, 214, 400, 270)
IDLE_PILL = (30, 214, 100, 270)
REGION = (0, 158, 480, 326)
WIDTH, HEIGHT = 480, 330
GROUND, LIGHT = bytes([15, 15, 14, 255]), bytes([40, 40, 40, 255])


def frame(lit):
    """An RGBA frame whose pixels are light where lit(x, y) holds."""
    return WIDTH, 4, [b"".join(LIGHT if lit(x, y) else GROUND for x in range(WIDTH)) for y in range(HEIGHT)]


def in_box(box):
    return lambda x, y: box[0] <= x < box[2] and box[1] <= y < box[3]


REST = frame(lambda x, y: False)


def judge(lit):
    return judge_press(changed_pixels(REST, frame(lit), REGION), PILL, RADIUS, IDLE_PILL)


def rounded(spread):
    return lambda x, y: rounded_rect_distance(PILL, RADIUS, x, y) <= spread


class RoundedRectTest(unittest.TestCase):
    def test_distance_is_negative_inside_and_positive_beyond_the_rounding(self):
        self.assertLess(rounded_rect_distance(PILL, RADIUS, 250, 242), -20)
        self.assertAlmostEqual(rounded_rect_distance(PILL, RADIUS, 250, 214), -0.5)
        self.assertAlmostEqual(rounded_rect_distance(PILL, RADIUS, 250, 213), 0.5)
        # The rectangle's own corner pixel lies about 2.2 px beyond the 10.5 px arc.
        self.assertAlmostEqual(rounded_rect_distance(PILL, RADIUS, 100, 214), 10 * 2 ** 0.5 - RADIUS, places=6)


class JudgePressTest(unittest.TestCase):
    def test_a_highlight_filling_the_rounded_pill_stays_inside(self):
        result = judge(rounded(0))
        self.assertGreater(result["changedInPill"], 0)
        self.assertEqual(0, result["changedOutsidePill"])
        self.assertEqual(0, result["changedInExcludedCorners"])
        self.assertEqual(0, result["changedInIdlePill"])

    def test_one_pixel_of_anti_aliased_edge_is_allowed_and_two_are_not(self):
        fringe = judge(rounded(1))
        self.assertGreater(fringe["changedInFringe"], 0)
        self.assertEqual(0, fringe["changedOutsidePill"])
        self.assertGreater(judge(rounded(2))["changedOutsidePill"], 0)

    def test_a_square_highlight_fails_on_every_corner_pixel_beyond_the_rounding(self):
        result = judge(in_box(PILL))
        self.assertGreater(result["cornerMaskPixels"], 4 * 4)
        self.assertEqual(result["cornerMaskPixels"], result["changedInExcludedCorners"])
        self.assertEqual(result["cornerMaskPixels"], result["changedOutsidePill"])

    def test_the_touch_target_highlight_of_issue_26_spills_above_and_below(self):
        result = judge(in_box((100, 200, 400, 284)))
        # The 13 rows of touch padding above and below, beyond the one-pixel fringe, plus the excluded corners.
        self.assertGreaterEqual(result["changedOutsidePill"], 300 * 26 + result["cornerMaskPixels"])
        self.assertEqual((100, 200), result["firstChangedOutsidePill"][0])

    def test_a_change_beside_the_pill_at_mid_height_is_spill(self):
        result = judge(lambda x, y: rounded(0)(x, y) or (x, y) == (PILL[2] + 3, 242))
        self.assertEqual(1, result["changedOutsidePill"])
        self.assertEqual([(PILL[2] + 3, 242)], result["firstChangedOutsidePill"])

    def test_a_change_inside_the_selected_segment_marks_a_released_frame(self):
        result = judge(lambda x, y: rounded(0)(x, y) or in_box(IDLE_PILL)(x, y))
        self.assertEqual(70 * 56, result["changedInIdlePill"])

    def test_changes_outside_the_compared_region_are_not_seen(self):
        self.assertEqual([], changed_pixels(REST, frame(lambda x, y: y < REGION[1]), REGION))


class CaptureTest(unittest.TestCase):
    def test_malformed_screenshot_fails_and_retains_raw_bytes(self):
        malformed = b"Assertion failed! rcEnc featureInfo hasReadColorBufferDma\nAborted"
        with TemporaryDirectory() as directory:
            smoke = Smoke(Path(directory), "test-device")
            with patch.object(smoke, "dump", return_value=ET.fromstring("<hierarchy/>")), \
                    patch.object(smoke, "adb", return_value=malformed):
                with self.assertRaisesRegex(AssertionError, "Not a PNG"):
                    smoke.capture("raw")
            self.assertEqual(malformed, (Path(directory) / "raw.png").read_bytes())


class PickerGeometryTest(unittest.TestCase):
    launcher = "com.android.launcher3"
    namespace = launcher + ".widgetpicker:id/"
    home = (0, 50, 720, 1510)

    def picker(self, target=""):
        # The Browse capture's list, tab row and padded floating wrapper, not a legacy launcher list.
        return ET.fromstring(f"""<hierarchy>
          <node package="{self.launcher}" resource-id="{self.namespace}widgets_catalog"
                enabled="true" bounds="[0,0][720,1560]">
            <node package="{self.launcher}" enabled="true" bounds="[18,144][702,1476]">
              <node package="{self.launcher}" resource-id="{self.namespace}personal_widgets_list"
                    enabled="true" scrollable="true" bounds="[18,403][702,1476]">{target}</node>
              <node package="{self.launcher}" enabled="true" bounds="[169,1350][551,1462]">
                <node package="{self.launcher}" enabled="true" bounds="[169,1364][551,1448]">
                  <node package="{self.launcher}" resource-id="{self.namespace}featured_widgets_tab"
                        enabled="true" checkable="true" checked="false" bounds="[183,1364][341,1448]"/>
                  <node package="{self.launcher}" resource-id="{self.namespace}personal_widgets_tab"
                        enabled="true" checkable="true" checked="true" bounds="[341,1364][537,1448]"/>
                </node>
              </node>
            </node>
          </node>
        </hierarchy>""")

    def test_browse_viewport_excludes_the_padded_floating_tabs_and_home_edges(self):
        root = self.picker()
        picker, visible = runtime_smoke.picker_geometry(root, self.launcher, self.home)
        self.assertEqual(self.namespace + "personal_widgets_list", picker.get("resource-id"))
        self.assertEqual((18, 403, 702, 1350), visible)
        self.assertEqual((30, 450, 690, 1300), runtime_smoke.picker_geometry(
            root, self.launcher, (30, 450, 690, 1300))[1])

    def test_nested_non_scrollable_list_id_preserves_viewport_and_control_scrolling(self):
        root = self.picker(f"""
          <node package="{self.launcher}" text="Usage Limits" enabled="true"
                clickable="true" bounds="[18,1242][702,1300]"/>
          <node package="{self.launcher}" resource-id="{self.namespace}personal_widgets_list"
                enabled="true" scrollable="false" bounds="[18,1345][702,1476]"/>
        """)
        picker, visible = runtime_smoke.picker_geometry(root, self.launcher, self.home)
        self.assertIs(root[0][0][0], picker)
        self.assertEqual((18, 403, 702, 1350), visible)
        with TemporaryDirectory() as directory:
            smoke = Smoke(Path(directory), "test-device")
            smoke.launcher = self.launcher
            smoke.launcher_content_viewport = self.home
            with patch.object(smoke, "widget_capture", side_effect=[self.picker(), self.picker(), root]), \
                    patch.object(smoke, "adb") as adb, patch("android_runtime_smoke.time.sleep"):
                found = smoke.widget_control("browse", self.launcher, label="Usage Limits", scroll=True)
            self.assertEqual([
                ("shell", "input", "swipe", "360", "1113", "360", "639", "400"),
                ("shell", "input", "swipe", "360", "1113", "360", "639", "400"),
            ], [call.args for call in adb.call_args_list])
            self.assertEqual((18, 1242, 702, 1300), bounds(found))
            self.assertEqual([18, 403, 702, 1350], json.loads(
                (Path(directory) / "browse-2-tap.json").read_text())["visiblePicker"])

    def test_unready_browse_waits_and_duplicate_lists_fail_closed(self):
        for change in ("missing", "disabled", "foreign", "unbounded", "non-scrollable", "featured"):
            with self.subTest(change=change):
                root = self.picker()
                catalog = root[0]
                picker = catalog.find(f".//node[@resource-id='{self.namespace}personal_widgets_list']")
                if change == "missing":
                    catalog[0].remove(picker)
                elif change == "disabled":
                    picker.set("enabled", "false")
                elif change == "foreign":
                    picker.set("package", "com.example.other")
                elif change == "unbounded":
                    picker.set("bounds", "[18,403][18,403]")
                elif change == "non-scrollable":
                    picker.set("scrollable", "false")
                else:
                    catalog.find(f".//node[@resource-id='{self.namespace}personal_widgets_tab']").set("checked", "false")
                self.assertIsNone(runtime_smoke.picker_geometry(root, self.launcher, self.home))
        root = self.picker()
        root[0][0].append(ET.fromstring(ET.tostring(root[0][0][0])))
        with self.assertRaisesRegex(AssertionError, "Ambiguous.*personal_widgets_list"):
            runtime_smoke.picker_geometry(root, self.launcher, self.home)

    def test_widget_control_scrolls_and_clips_only_the_unobscured_browse_content(self):
        def target(box):
            return (f'<node package="{self.launcher}" text="Usage Limits" enabled="true" '
                    f'clickable="true" bounds="{box}"/>')

        for first, start, end in (("", "1113", "639"),
                                  (target("[18,1356][702,1440]"), "1113", "639"),
                                  (target("[18,300][702,400]"), "639", "1113")):
            with self.subTest(first=first), TemporaryDirectory() as directory:
                smoke = Smoke(Path(directory), "test-device")
                smoke.launcher = self.launcher
                smoke.launcher_content_viewport = self.home
                final = self.picker(target("[18,1320][702,1440]"))
                with patch.object(smoke, "widget_capture", side_effect=[self.picker(first), final]), \
                        patch.object(smoke, "adb") as adb, patch("android_runtime_smoke.time.sleep"):
                    found = smoke.widget_control("browse", self.launcher, label="Usage Limits", scroll=True)
                adb.assert_called_once_with("shell", "input", "swipe", "360", start, "360", end, "400")
                self.assertEqual((18, 1320, 702, 1350), bounds(found))
                self.assertEqual((18, 1320, 702, 1440), bounds(Smoke.control(final, self.launcher, label="Usage Limits")))
                self.assertEqual([18, 403, 702, 1350], json.loads(
                    (Path(directory) / "browse-1-tap.json").read_text())["visiblePicker"])


class PngTest(unittest.TestCase):
    def test_written_frames_decode_unchanged_and_crops_scale_by_nearest_neighbour(self):
        width, channels, rows = frame(rounded(0))
        decoded = png_frame(png_bytes(width, HEIGHT, channels, rows), HEIGHT - 1)
        self.assertEqual((width, channels, rows), decoded)
        crop = png_frame(scaled_crop(decoded, (99, 241, 101, 242), 4), 3)
        self.assertEqual(GROUND * 4 + LIGHT * 4, crop[2][0])
        self.assertEqual(crop[2][0], crop[2][3])


if __name__ == "__main__":
    unittest.main()
