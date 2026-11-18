"""Runtime smoke pixel, picker geometry, host focus and setting checks: python3 -m unittest discover -s tools"""

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


class SettingTest(unittest.TestCase):
    def setting_tree(self, checked):
        return ET.fromstring(f"""<hierarchy>
          <node package="com.usagelimits" text="Show subscription tier" enabled="true"
                checkable="false" checked="false" clickable="false" bounds="[56,551][540,635]"/>
          <node package="com.usagelimits" enabled="true" checkable="true" checked="{checked}"
                clickable="true" bounds="[573,551][664,635]">
            <node package="com.usagelimits" content-desc="Show subscription tier" enabled="true"
                  checkable="false" checked="false" clickable="false" bounds="[573,565][664,621]"/>
          </node>
        </hierarchy>""")

    def test_setting_returns_the_checkable_parent_without_scrolling_past_it(self):
        for checked in ("true", "false"):
            with self.subTest(checked=checked), TemporaryDirectory() as directory:
                smoke = Smoke(Path(directory), "test-device")
                with patch.object(smoke, "dump", return_value=self.setting_tree(checked)), \
                        patch.object(smoke, "scroll", side_effect=AssertionError("Visible setting scrolled past")):
                    target = smoke.setting("persisted")
                self.assertEqual((573, 551, 664, 635), bounds(target))
                self.assertEqual("true", target.get("checkable"))
                self.assertEqual(checked, target.get("checked"))

    def test_setting_is_disabled_waits_for_the_parent_not_the_unchecked_label_child(self):
        with TemporaryDirectory() as directory:
            smoke = Smoke(Path(directory), "test-device")
            with patch.object(smoke, "dump", side_effect=[self.setting_tree("true"),
                    self.setting_tree("true"), self.setting_tree("false")]) as dump, \
                    patch.object(smoke, "scroll", side_effect=AssertionError("Visible setting scrolled past")), \
                    patch("android_runtime_smoke.time.sleep"):
                smoke.setting_is_disabled("persisted")
            self.assertEqual([("persisted-0",), ("persisted-loaded",), ("persisted-loaded",)],
                             [call.args for call in dump.call_args_list])


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


class FocusedWidgetTest(unittest.TestCase):
    launcher = "com.android.launcher3"

    def widget(self):
        return ET.fromstring(f"""<hierarchy>
          <node package="{self.launcher}" class="com.android.launcher3.widget.LauncherAppWidgetHostView"
                content-desc="Mini Rings" enabled="true" focusable="true" focused="true"
                bounds="[567,163][705,377]">
            <node package="com.usagelimits" class="android.widget.TextView"
                  content-desc="Mini Rings" enabled="true" focusable="true" focused="true"
                  text="No matching accounts" bounds="[585,181][687,359]"/>
          </node>
        </hierarchy>""")

    def test_focused_launcher_host_not_its_description_child_owns_keyboard_input(self):
        root = self.widget()
        host = runtime_smoke.focused_widget(root, self.launcher, "Mini Rings")
        self.assertIs(root[0], host)
        self.assertEqual((567, 163, 705, 377), bounds(host))
        root[0].set("focused", "false")
        self.assertIsNone(runtime_smoke.focused_widget(root, self.launcher, "Mini Rings"))

    def test_unready_foreign_or_non_host_nodes_do_not_qualify(self):
        for attribute, value in (("enabled", "false"), ("focusable", "false"),
                                 ("focused", "false"), ("package", "com.usagelimits"),
                                 ("class", "android.widget.LinearLayout"),
                                 ("content-desc", "Widget Resize Frame for NULL"),
                                 ("bounds", "[15,818][15,818]")):
            with self.subTest(attribute=attribute):
                root = self.widget()
                root[0].set(attribute, value)
                self.assertIsNone(runtime_smoke.focused_widget(root, self.launcher, "Mini Rings"))

    def test_duplicate_mini_rings_hosts_fail_even_when_only_one_is_focused(self):
        root = self.widget()
        duplicate = ET.fromstring(ET.tostring(root[0]))
        duplicate.set("focused", "false")
        duplicate.set("bounds", "[360,818][690,1157]")
        root.append(duplicate)
        with self.assertRaisesRegex(AssertionError, "Ambiguous.*Mini Rings.*host"):
            runtime_smoke.focused_widget(root, self.launcher, "Mini Rings")


class PostDropFrameTest(unittest.TestCase):
    launcher = "com.android.launcher3"
    initial = (567, 163, 705, 377)

    def dropped_widget(self):
        # The real API 37 capture has Compose wrappers between the frame ID and its focus owner.
        return ET.fromstring(f"""<hierarchy>
          <node package="{self.launcher}" class="android.widget.FrameLayout"
                enabled="true" focused="false" bounds="[0,0][720,1560]">
            <node package="{self.launcher}" class="android.view.ViewGroup"
                  enabled="true" focused="false" bounds="[15,163][705,1234]">
              <node package="{self.launcher}" class="com.android.launcher3.widget.LauncherAppWidgetHostView"
                    content-desc="Mini Rings" enabled="true" focusable="true" focused="false"
                    bounds="[429,377][567,591]"/>
            </node>
            <node package="{self.launcher}" class="android.widget.LinearLayout"
                  content-desc="Widget Resize Frame for null" enabled="true" focusable="true" focused="true"
                  bounds="[345,293][651,675]">
              <node package="{self.launcher}" class="androidx.compose.ui.platform.ComposeView"
                    enabled="true" focused="false" bounds="[345,293][651,675]">
                <node package="{self.launcher}" class="android.view.View"
                      enabled="true" focused="false" bounds="[345,293][651,675]">
                  <node package="{self.launcher}" class="android.view.View"
                        enabled="true" focused="false" bounds="[345,293][651,675]">
                    <node package="{self.launcher}" class="android.view.View"
                          resource-id="{self.launcher}:id/widget_resize_frame"
                          enabled="true" focused="false" bounds="[345,293][651,675]"/>
                  </node>
                </node>
              </node>
            </node>
          </node>
        </hierarchy>""")

    def test_captured_null_named_frame_owns_the_unique_moved_host_by_containment(self):
        root = self.dropped_widget()
        owner = runtime_smoke.post_drop_frame(root, self.launcher, "Mini Rings", self.initial)
        self.assertIs(root[0][1], owner)
        self.assertEqual((345, 293, 651, 675), bounds(owner))

    def test_focused_owner_without_the_frame_marker_does_not_qualify(self):
        root = self.dropped_widget()
        wrapper = root[0][1][0][0][0]
        wrapper.remove(wrapper[0])
        self.assertIsNone(runtime_smoke.post_drop_frame(root, self.launcher, "Mini Rings", self.initial))

    def test_unfocused_disabled_or_foreign_frame_owner_does_not_qualify(self):
        for attribute, value in (("focused", "false"), ("enabled", "false"),
                                 ("package", "com.example.other")):
            with self.subTest(attribute=attribute):
                root = self.dropped_widget()
                root[0][1].set(attribute, value)
                self.assertIsNone(runtime_smoke.post_drop_frame(root, self.launcher, "Mini Rings", self.initial))

    def test_disabled_foreign_or_unbounded_frame_marker_does_not_qualify(self):
        for attribute, value in (("enabled", "false"), ("package", "com.example.other"),
                                 ("bounds", "[345,293][345,293]")):
            with self.subTest(attribute=attribute):
                root = self.dropped_widget()
                root[0][1][0][0][0][0].set(attribute, value)
                self.assertIsNone(runtime_smoke.post_drop_frame(root, self.launcher, "Mini Rings", self.initial))

    def test_frame_must_strictly_contain_the_mini_rings_host_on_every_edge(self):
        for box in ("[567,163][705,377]", "[345,293][651,675]", "[345,377][567,591]", "[429,293][567,591]",
                    "[429,377][651,591]", "[429,377][567,675]"):
            with self.subTest(bounds=box):
                root = self.dropped_widget()
                root[0][0][0].set("bounds", box)
                self.assertIsNone(runtime_smoke.post_drop_frame(root, self.launcher, "Mini Rings", self.initial))

    def test_focused_fullscreen_shell_cannot_replace_the_unfocused_frame_owner(self):
        root = self.dropped_widget()
        root[0].set("focused", "true")
        root[0][1].set("focused", "false")
        self.assertIsNone(runtime_smoke.post_drop_frame(root, self.launcher, "Mini Rings", self.initial))

    def test_focused_same_box_node_must_own_the_frame_not_just_overlap_it(self):
        root = self.dropped_widget()
        owner = root[0][1]
        wrapper = owner[0]
        owner.remove(wrapper)
        root[0].append(wrapper)
        self.assertIsNone(runtime_smoke.post_drop_frame(root, self.launcher, "Mini Rings", self.initial))

    def test_two_focused_same_box_owners_fail_closed(self):
        root = self.dropped_widget()
        root[0][1][0].set("focused", "true")
        with self.assertRaisesRegex(AssertionError, "Ambiguous.*resize frame.*owner"):
            runtime_smoke.post_drop_frame(root, self.launcher, "Mini Rings", self.initial)

    def test_owner_is_not_selected_by_its_class_or_null_description(self):
        root = self.dropped_widget()
        root[0][1].set("class", "android.view.ViewGroup")
        root[0][1].set("content-desc", "")
        self.assertIs(root[0][1], runtime_smoke.post_drop_frame(root, self.launcher, "Mini Rings", self.initial))

    def test_two_resize_frames_fail_closed_even_with_only_one_focused_owner(self):
        root = self.dropped_widget()
        duplicate = ET.fromstring(ET.tostring(root[0][1]))
        duplicate.set("focused", "false")
        root[0].append(duplicate)
        with self.assertRaisesRegex(AssertionError, "Ambiguous.*resize frame"):
            runtime_smoke.post_drop_frame(root, self.launcher, "Mini Rings", self.initial)

    def test_unchanged_host_is_rejected_even_when_its_frame_has_focus(self):
        root = self.dropped_widget()
        self.assertIsNone(runtime_smoke.post_drop_frame(root, self.launcher, "Mini Rings", (429, 377, 567, 591)))

    def test_ambiguous_mini_rings_hosts_fail_even_when_only_one_is_enclosed(self):
        root = self.dropped_widget()
        duplicate = ET.fromstring(ET.tostring(root[0][0][0]))
        duplicate.set("bounds", "[567,163][705,377]")
        root[0][0].append(duplicate)
        with self.assertRaisesRegex(AssertionError, "Ambiguous.*Mini Rings.*host"):
            runtime_smoke.post_drop_frame(root, self.launcher, "Mini Rings", self.initial)

    def test_disabled_foreign_or_non_host_mini_rings_nodes_do_not_qualify(self):
        for attribute, value in (("enabled", "false"), ("package", "com.usagelimits"),
                                 ("class", "android.widget.LinearLayout"),
                                 ("bounds", "[429,377][429,377]")):
            with self.subTest(attribute=attribute):
                root = self.dropped_widget()
                root[0][0][0].set(attribute, value)
                self.assertIsNone(runtime_smoke.post_drop_frame(root, self.launcher, "Mini Rings", self.initial))


class MotionCaptureTest(unittest.TestCase):
    """#81: the slow-motion frame capture is evidence, and must always hand animations back off."""

    def device(self, scale_after_restore="0.0"):
        """A stand-in for adb that remembers the animator scale it was given."""
        state = {"scale": "0.0", "puts": []}
        still = png_bytes(2, 2, 4, [GROUND * 2, GROUND * 2])

        def adb(*args, **kwargs):
            if args[:2] == ("exec-out", "screencap"):
                return still
            if args[:3] == ("shell", "settings", "put"):
                state["puts"].append(args[-1])
                state["scale"] = args[-1] if args[-1] != "0.0" else scale_after_restore
                return ""
            if args[:3] == ("shell", "settings", "get"):
                return state["scale"]
            if "resolve-activity" in args:
                return "priority=0\ncom.usagelimits/.UsageLimitsActivity"
            return ""
        return state, adb

    def test_a_sequence_counts_its_distinct_frames(self):
        moving = runtime_smoke.motion_sequence("tab", "crossfade", [{"sha256": "a"}, {"sha256": "b"}, {"sha256": "b"}])
        self.assertEqual((2, True), (moving["distinctFrames"], moving["motionObserved"]))
        still = runtime_smoke.motion_sequence("tab", "crossfade", [{"sha256": "a"}, {"sha256": "a"}])
        self.assertEqual((1, False), (still["distinctFrames"], still["motionObserved"]))

    def test_a_failed_capture_is_recorded_and_animations_are_turned_off_again(self):
        state, adb = self.device()
        with TemporaryDirectory() as directory:
            smoke = Smoke(Path(directory), "test-device")
            with patch.object(smoke, "adb", side_effect=adb), \
                    patch.object(smoke, "wait_for", side_effect=AssertionError("UI did not reach motion-launched")):
                self.assertEqual("capture failed", smoke.capture_motion("motion"))
            report = json.loads((Path(directory) / "motion.json").read_text())
        self.assertEqual([str(runtime_smoke.MOTION_SCALE), "0.0"], state["puts"])
        self.assertEqual("0.0", report["animatorScaleRestored"])
        self.assertIn("UI did not reach motion-launched", report["error"])
        # The launch frames taken before the failure are kept.
        self.assertEqual(["launch"], [sequence["name"] for sequence in report["sequences"]])
        self.assertFalse(report["sequences"][0]["motionObserved"])

    def test_animations_left_on_fail_the_smoke(self):
        _, adb = self.device(scale_after_restore=str(runtime_smoke.MOTION_SCALE))
        with TemporaryDirectory() as directory:
            smoke = Smoke(Path(directory), "test-device")
            with patch.object(smoke, "adb", side_effect=adb), \
                    patch.object(smoke, "wait_for", side_effect=AssertionError("UI did not reach motion-launched")):
                with self.assertRaisesRegex(AssertionError, "not turned off again"):
                    smoke.capture_motion("motion")


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
