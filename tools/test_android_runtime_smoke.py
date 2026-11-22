"""Runtime smoke pixel, picker geometry, host focus and setting checks: python3 -m unittest discover -s tools"""

import json
import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
from types import SimpleNamespace
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

    def test_setting_reaches_the_checkable_parent_below_the_fold(self):
        empty = ET.fromstring('''<hierarchy>
          <node package="com.usagelimits" scrollable="true" bounds="[0,100][720,1500]"/>
        </hierarchy>''')
        with TemporaryDirectory() as directory:
            smoke = Smoke(Path(directory), "test-device")
            with patch.object(smoke, "dump", side_effect=[empty, empty, self.setting_tree("true")]) as dump, \
                    patch.object(smoke, "adb") as adb:
                target = smoke.setting("persisted")
            self.assertEqual("true", target.get("checkable"))
            self.assertEqual("true", target.get("checked"))
            self.assertEqual([("persisted-0",), ("persisted-1",), ("persisted-2",)],
                             [call.args for call in dump.call_args_list])
            self.assertEqual([("shell", "input", "swipe", "360", "1150", "360", "450", "400")] * 2,
                             [call.args for call in adb.call_args_list])

    def test_setting_search_fails_after_the_bounded_number_of_views(self):
        empty = ET.fromstring('''<hierarchy>
          <node package="com.usagelimits" scrollable="true" bounds="[0,100][720,1500]"/>
        </hierarchy>''')
        with TemporaryDirectory() as directory:
            smoke = Smoke(Path(directory), "test-device")
            with patch.object(smoke, "dump", return_value=empty) as dump, \
                    patch.object(smoke, "adb") as adb:
                with self.assertRaisesRegex(AssertionError, "Could not reach Show subscription tier"):
                    smoke.setting("persisted")
            self.assertEqual(9, dump.call_count)
            self.assertEqual(9, adb.call_count)

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


class FakeDevice:
    """adb at the subprocess seam, on a clock the test owns; it also stands in for the script's `time`.

    Every call is recorded with the timeout it was given and when it started. It costs `seconds(command)`
    of the fake clock; one that costs more than its timeout raises TimeoutExpired once the timeout has
    passed, with its effect on the device already applied, which is what a late adb answer looks like.
    The screen is the seeded debug build as far as the smoke walks it, shaped like the API 37 dump:
    one bar node holding the three tab items, Overview with its filter chips and two account rows
    (title, then the identity line "<label>"), the Resets tab, and the tapped row's
    detail, which repeats that row's account label under its title.
    """

    STILL = png_bytes(2, 2, 4, [GROUND * 2, GROUND * 2])
    PACKAGE = runtime_smoke.DEBUG_PACKAGE
    TABS = ("Overview", "Resets", "Settings")
    ROWS = ("Grok (synthetic)", "Kimi (synthetic)")
    ROW = ROWS[0]
    BOXES = {"Overview": (0, 1400, 180, 1560), "Resets": (180, 1400, 360, 1560), "Settings": (360, 1400, 540, 1560),
             "All": (40, 200, 200, 260), "Needs attention": (200, 200, 400, 260),
             ROWS[0]: (40, 400, 680, 520), ROWS[1]: (40, 540, 680, 660), "Limits": (40, 300, 680, 360)}
    SCALE = ("shell", "settings", "put", "global", "animator_duration_scale")

    def __init__(self):
        self.now = self.started = 1000.0
        self.scale = "0.0"
        self.calls = []
        self.frames = 0
        self.screen = None
        self.filter = "All"
        self.opened = None
        self.seconds = lambda command: 0.25
        # A test's hook: raise to fail a command, or return bytes to answer it without its effect.
        self.before = lambda command: None

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds

    def run(self, args, check, capture_output, timeout):
        command = tuple(args[3:])
        self.calls.append((command, timeout, self.now))
        seconds = self.seconds(command)
        answer = self.before(command)
        if answer is None:
            answer = self.answer(command)
        if seconds > timeout:
            self.now += timeout
            raise subprocess.TimeoutExpired(args, timeout)
        self.now += seconds
        return SimpleNamespace(stdout=answer)

    def answer(self, command):
        if command[:2] == ("exec-out", "screencap"):
            self.frames += 1
            return self.STILL
        if command[:2] == ("exec-out", "cat"):
            return self.hierarchy()
        if command[:-1] == self.SCALE:
            self.scale = command[-1]
        elif command[:3] == ("shell", "settings", "get"):
            return self.scale.encode()
        elif "resolve-activity" in command:
            return f"priority=0\n{self.PACKAGE}/com.usagelimits.UsageLimitsActivity".encode()
        elif command[:3] == ("shell", "am", "force-stop"):
            self.screen = None
        elif command[:3] == ("shell", "am", "start"):
            self.screen, self.filter = "Overview", "All"
        elif command[:3] == ("shell", "input", "keyevent"):
            self.screen = "Overview"
        elif command[:3] == ("shell", "input", "tap"):
            x, y = int(command[3]), int(command[4])
            label = next((label for label in self.visible() if self.BOXES[label][0] <= x < self.BOXES[label][2]
                          and self.BOXES[label][1] <= y < self.BOXES[label][3]), None)
            if label in ("All", "Needs attention"):
                self.filter = label
            elif label in self.ROWS:
                self.screen, self.opened = "Detail", label
            elif label:
                self.screen = label
        return b""

    def visible(self):
        return {"Overview": self.TABS + ("All", "Needs attention") + self.ROWS,
                "Resets": self.TABS, "Detail": ("Limits",)}.get(self.screen, ())

    def hierarchy(self):
        pkg = self.PACKAGE

        def box(label):
            return "[{},{}][{},{}]".format(*self.BOXES[label])

        def node(label):
            if label == "Limits":
                # The detail: its account's title and label, then its sections.
                title = self.opened.split(" (", 1)[0]
                return (f'<node package="{pkg}" text="{title}" bounds="[109,172][199,221]"/>'
                        f'<node package="{pkg}" text="{self.opened}" bounds="[109,221][278,250]"/>'
                        f'<node package="{pkg}" text="Limits" bounds="{box(label)}"/>')
            if label in self.ROWS:
                title = label.split(" (", 1)[0]
                return (f'<node package="{pkg}" enabled="true" clickable="true" bounds="{box(label)}">'
                        f'<node package="{pkg}" text="{title}" bounds="{box(label)}"/>'
                        f'<node package="{pkg}" text="{label}" bounds="{box(label)}"/></node>')
            chosen = str(label in (self.screen, self.filter)).lower()
            state = (f'checkable="true" checked="{chosen}" clickable="{str(chosen == "false").lower()}"'
                     if label in ("All", "Needs attention")
                     else f'selected="{chosen}" clickable="{str(chosen == "false").lower()}"')
            return (f'<node package="{pkg}" enabled="true" {state} bounds="{box(label)}">'
                    f'<node package="{pkg}" text="{label}" bounds="{box(label)}"/></node>')

        shown = self.visible()
        tabs = "".join(node(label) for label in shown if label in self.TABS)
        bar = f'<node package="{pkg}" enabled="true" bounds="[0,1400][540,1560]">{tabs}</node>' if tabs else ""
        rest = "".join(node(label) for label in shown if label not in self.TABS)
        return f"<hierarchy>{rest}{bar}</hierarchy>".encode()

    def restores(self):
        """The index of every call that asked for the scale to go back to 0."""
        return [index for index, call in enumerate(self.calls) if call[0] == self.SCALE + ("0.0",)]


class ThreeTabsTest(unittest.TestCase):
    """#88 01b: the bar has Overview, Resets and Settings, and an Accounts item is a failure, not an extra."""

    @staticmethod
    def bar(*labels):
        items = "".join(
            f'<node package="com.usagelimits" enabled="true" clickable="true" selected="false" '
            f'bounds="[{index * 100},1400][{index * 100 + 100},1560]"><node package="com.usagelimits" text="{label}" '
            f'bounds="[{index * 100},1400][{index * 100 + 100},1560]"/></node>' for index, label in enumerate(labels))
        # As in the API 37 dump: one bar node whose children are the items.
        return ET.fromstring(f'<hierarchy><node package="com.usagelimits" enabled="true" '
                             f'bounds="[0,1400][720,1560]">{items}</node></hierarchy>')

    def test_the_three_tabs_pass(self):
        self.assertTrue(runtime_smoke.three_tabs(self.bar("Overview", "Resets", "Settings")))

    def test_a_missing_tab_or_a_leftover_accounts_tab_fails(self):
        self.assertFalse(runtime_smoke.three_tabs(self.bar("Overview", "Resets")))
        self.assertFalse(runtime_smoke.three_tabs(self.bar("Overview", "Accounts", "Resets", "Settings")))

    def test_another_package_with_the_same_labels_is_not_this_app(self):
        self.assertFalse(runtime_smoke.three_tabs(self.bar("Overview", "Resets", "Settings"), runtime_smoke.DEBUG_PACKAGE))

    def test_an_extra_item_under_another_name_fails(self):
        self.assertFalse(runtime_smoke.three_tabs(self.bar("Overview", "Resets", "Settings", "People")))
        self.assertFalse(runtime_smoke.three_tabs(self.bar("Overview", "Dials", "Resets", "Settings")))

    def test_the_real_api_37_bar_passes(self):
        """The bar exactly as run 44675626408 dumped it (current-three-tabs.xml), trimmed to the bar."""
        root = ET.fromstring(
            '<hierarchy><node package="com.usagelimits" enabled="true" bounds="[0,1336][720,1476]">'
            '<node package="com.usagelimits" enabled="true" clickable="false" selected="true" focusable="true" bounds="[0,1336][230,1476]">'
            '<node package="com.usagelimits" bounds="[72,1392][157,1476]"><node package="com.usagelimits" text="Overview" bounds="[72,1422][157,1446]"/></node></node>'
            '<node package="com.usagelimits" enabled="true" clickable="true" selected="false" focusable="true" bounds="[244,1336][475,1476]">'
            '<node package="com.usagelimits" bounds="[317,1392][401,1476]"><node package="com.usagelimits" text="Resets" bounds="[327,1422][391,1446]"/></node></node>'
            '<node package="com.usagelimits" enabled="true" clickable="true" selected="false" focusable="true" bounds="[489,1336][720,1476]">'
            '<node package="com.usagelimits" bounds="[562,1392][646,1476]"><node package="com.usagelimits" text="Settings" bounds="[565,1422][643,1446]"/></node></node>'
            '</node></hierarchy>')
        self.assertEqual(["Overview", "Resets", "Settings"], runtime_smoke.bar_items(root))
        self.assertTrue(runtime_smoke.three_tabs(root))


class BootReadinessTest(unittest.TestCase):
    # Dialog text/IDs transcribed from runs 44012206579 attempt 2 and 44055226886 (#66).
    DIALOG = '''<hierarchy><node package="android" resource-id="android:id/alertTitle"
        text="System UI isn't responding"/><node package="android" resource-id="android:id/aerr_close"
        text="Close app"/><node package="android" resource-id="android:id/aerr_wait" text="Wait"/></hierarchy>'''
    ANR = ("ACTIVITY MANAGER LAST ANR (dumpsys activity lastanr)\nANR in com.android.systemui\n"
           "PID: 1061\nReason: Process com.android.systemui failed to complete startup\n")
    CLEAN = "ACTIVITY MANAGER LAST ANR (dumpsys activity lastanr)\n  <no ANR has occurred since boot>"

    class Device(FakeDevice):
        def __init__(self, dialog, anr, focused=True):
            super().__init__()
            self.dialog, self.anr, self.focused = dialog, anr, focused

        def hierarchy(self):
            return (self.dialog or '<hierarchy><node package="com.android.launcher3" enabled="true" '
                    'resource-id="com.android.launcher3:id/workspace" bounds="[0,0][720,1560]"/></hierarchy>').encode()

        def answer(self, command):
            if command == ("shell", "dumpsys", "activity", "lastanr"):
                return self.anr.encode()
            if command == ("shell", "dumpsys", "window"):
                owner = "com.android.launcher3/.Launcher" if self.focused else "com.other/.Activity"
                return f"mCurrentFocus=Window{{abc u0 {owner}}}".encode()
            if "resolve-activity" in command:
                return b"com.android.launcher3/.Launcher"
            if command[:2] == ("shell", "getprop"):
                return {"ro.kernel.qemu": b"1", "ro.build.version.sdk": b"37"}[command[-1]]
            if command[:1] == ("logcat",):
                return self.anr.encode()
            return super().answer(command)

    def preflight(self, device):
        with TemporaryDirectory() as directory, patch.object(runtime_smoke.subprocess, "run", device.run), \
                patch.object(runtime_smoke, "time", device):
            summary = Path(directory, "summary.md")
            with patch.dict(os.environ, GITHUB_STEP_SUMMARY=str(summary)):
                result = runtime_smoke.boot_readiness(Path(directory), "emulator-test")
            files = {path.name: path.read_bytes() for path in Path(directory).iterdir()}
            return result, files

    def test_only_proven_systemui_startup_anr_requests_a_clean_boot(self):
        device = self.Device(self.DIALOG, self.ANR)
        code, files = self.preflight(device)
        self.assertEqual(75, code)
        for name in ("failure.xml", "failure.png", "system-log.txt", "last-anr.txt", "system-app-anr.txt"):
            self.assertIn(name, files)
        self.assertIn(b"Rejected pre-install boot", files["summary.md"])
        self.assertIn(b"com.android.systemui", files["boot-readiness.json"])
        self.assertFalse(any(command[0] == "install" or command[:2] == ("shell", "input")
                             for command, _, _ in device.calls))

    def test_missing_or_unrelated_evidence_and_nonstartup_anrs_are_fatal(self):
        cases = [
            (self.DIALOG, self.CLEAN),
            (self.DIALOG.replace("android:id/aerr_wait", "android:id/aerr_report"), self.ANR),
            (self.DIALOG, self.ANR.replace("failed to complete startup", "Input dispatching timed out")),
            (self.DIALOG.replace("System UI", "Settings"), self.ANR),
            (self.DIALOG.replace("android:id/aerr_wait", "android:id/other"), self.ANR),
            (self.DIALOG, self.ANR.replace("com.android.systemui", "com.android.settings")),
            (None, self.ANR),
            (self.DIALOG, self.ANR + "\nANR in com.other\nReason: failed to complete startup\n"),
        ]
        for dialog, anr in cases:
            with self.subTest(dialog=dialog, anr=anr):
                device = self.Device(dialog, anr)
                code, files = self.preflight(device)
                self.assertEqual(1, code)
                self.assertNotIn("summary.md", files)
                self.assertFalse(any(command[0] == "install" or command[:2] == ("shell", "input")
                                     for command, _, _ in device.calls))

    def test_an_anr_after_home_observation_still_rejects_the_boot(self):
        class LateANR(self.Device):
            def __init__(self):
                super().__init__(None, BootReadinessTest.CLEAN)
                self.reads = 0

            def answer(self, command):
                if command == ("shell", "dumpsys", "activity", "lastanr"):
                    self.reads += 1
                    if self.reads > 1:
                        return BootReadinessTest.ANR.encode()
                return super().answer(command)
        code, files = self.preflight(LateANR())
        self.assertEqual(1, code)
        self.assertIn(b"failed to complete startup", files["initial-home-0-ready-last-anr.txt"])
        self.assertEqual("failed", json.loads(files["initial-home.json"])["result"])

    def test_clean_boot_still_requires_focused_home_and_clean_anr(self):
        for focused, expected in ((True, 0), (False, 1)):
            with self.subTest(focused=focused):
                device = self.Device(None, self.CLEAN, focused)
                code, files = self.preflight(device)
                self.assertEqual(expected, code)
                if expected == 0:
                    self.assertEqual("passed", json.loads(files["initial-home.json"])["result"])
                self.assertFalse(any(command[0] == "install" or command[:2] == ("shell", "input")
                                     for command, _, _ in device.calls))


class BootRunnerTest(unittest.TestCase):
    """Execute the runner's boot/teardown functions with synthetic adb and readiness subprocesses."""

    def boot(self, statuses, smoke_status=0):
        runner = (Path(__file__).parent / "run_android_runtime.sh").read_text()
        functions = runner[runner.index("preserve_boot() {"):runner.index("cleanup() {")]
        bootstrap = runner[runner.index("boot_emulator() {"):]
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            emulator = root / "sdk/emulator/emulator"
            emulator.parent.mkdir(parents=True)
            emulator.write_text('#!/bin/bash\necho "vulkan_mode_selected:swiftshader gles_mode_selected:swiftshader"\nexec /bin/sleep 60\n')
            emulator.chmod(0o755)
            evidence = root / "evidence"
            evidence.mkdir()
            harness = r'''
set -euo pipefail
emulator_pid=
ANDROID_SERIAL=emulator-test
script_directory=/fixture/tools
emulator_args=(-wipe-data -gpu swiftshader)
smoke_args=(--apk fixture.apk)
boot_evidence=$evidence
sleep() { wait "$emulator_pid" 2>/dev/null || true; }
timeout() {
  shift
  [[ "$1" == adb ]] || return 90
  shift 3
  case "$*" in
    wait-for-device)
      for ((probe=0; probe<100; probe++)); do
        [[ -s "$boot_evidence/emulator-boot.txt" ]] && return 0
        /bin/sleep 0.01
      done
      return 91 ;;
    'shell getprop sys.boot_completed') echo 1 ;;
    'shell wm size') echo 'Physical size: 720x1560' ;;
    'shell wm density') echo 'Physical density: 280' ;;
    *) echo "diagnostic: $*" ;;
  esac
}
readiness=0
python3() {
  if [[ "${2:-}" == --boot-readiness ]]; then
    ((readiness+=1))
    printf 'ready-%s\n' "$readiness" >> "$evidence/calls.txt"
    local results=(${TEST_STATUSES//,/ })
    return "${results[readiness-1]}"
  fi
  if [[ "$1" == /fixture/tools/android_runtime_smoke.py ]]; then
    echo smoke >> "$evidence/calls.txt"
    return "$TEST_SMOKE_STATUS"
  fi
  command python3 "$@"
}
'''
            script = harness + functions + '\ntrap stop_emulator EXIT\n' + bootstrap
            env = {**os.environ, "ANDROID_HOME": str(root / "sdk"), "evidence": str(evidence),
                   "GITHUB_STEP_SUMMARY": str(root / "summary.md"), "TEST_STATUSES": statuses,
                   "TEST_SMOKE_STATUS": str(smoke_status)}
            result = subprocess.run(["bash", "-c", script], env=env, capture_output=True, text=True, timeout=15)
            calls = (evidence / "calls.txt").read_text().splitlines() if (evidence / "calls.txt").exists() else []
            files = sorted(str(path.relative_to(evidence)) for path in evidence.rglob("*") if path.is_file())
            summary = (root / "summary.md").read_text() if (root / "summary.md").exists() else ""
            installed = (evidence / "installed-on-boot.txt").read_text().strip() if (evidence / "installed-on-boot.txt").exists() else None
            return result, calls, files, summary, installed

    def test_startup_rejection_reboots_before_exactly_one_smoke(self):
        result, calls, files, _, installed = self.boot("75,0")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["ready-1", "ready-2", "smoke"], calls)
        self.assertEqual("2", installed)
        self.assertIn("boot-1/emulator-last-anr.txt", files)
        self.assertIn("boot-1/emulator-system-log.txt", files)
        self.assertIn("boot-2/emulator-boot.txt", files)

    def test_three_rejected_boots_fail_and_name_the_anr_without_installing(self):
        result, calls, files, summary, installed = self.boot("75,75,75")
        self.assertEqual(1, result.returncode)
        self.assertEqual(["ready-1", "ready-2", "ready-3"], calls)
        self.assertIsNone(installed)
        self.assertIn("com.android.systemui failed to complete startup", summary)
        for attempt in (1, 2, 3):
            self.assertIn(f"boot-{attempt}/emulator-system-app-anr.txt", files)

    def test_other_boot_failures_and_app_failures_never_retry(self):
        for statuses, smoke_status, calls in (("1", 0, ["ready-1"]), ("0", 7, ["ready-1", "smoke"])):
            with self.subTest(statuses=statuses, smoke_status=smoke_status):
                result, observed, _, _, _ = self.boot(statuses, smoke_status)
                self.assertEqual(smoke_status or 1, result.returncode, result.stderr)
                self.assertEqual(calls, observed)


class SeededRowTest(unittest.TestCase):
    """#88 01b review, item 3: the second row is tapped, and its own account must open."""

    def run_row(self, device):
        with TemporaryDirectory() as directory, patch.object(runtime_smoke.subprocess, "run", device.run), \
                patch.object(runtime_smoke, "time", device):
            device.screen = "Overview"
            return Smoke(Path(directory), "test-device").open_seeded_row("row", runtime_smoke.DEBUG_PACKAGE)

    def test_the_second_row_opens_its_own_account(self):
        device = FakeDevice()
        result = self.run_row(device)
        self.assertEqual({"tapped": "Kimi (synthetic)", "shownOnScreen": ["Grok (synthetic)", "Kimi (synthetic)"]}, result)
        self.assertEqual("Kimi (synthetic)", device.opened)

    def test_a_duplicate_label_fails_before_tapping_even_when_only_one_node_is_pressable(self):
        class DuplicateLabel(FakeDevice):
            def hierarchy(self):
                xml = super().hierarchy().decode()
                return xml.replace("</hierarchy>", f'<node package="{self.PACKAGE}" '
                                   'text="Kimi (synthetic)" bounds="[40,700][680,740]"/></hierarchy>').encode()
        device = DuplicateLabel()
        with self.assertRaisesRegex(AssertionError, "Ambiguous seeded row label"):
            self.run_row(device)
        self.assertIsNone(device.opened)
        self.assertFalse(any(command[:3] == ("shell", "input", "tap") for command, _, _ in device.calls))

    def test_a_tap_that_opens_the_first_account_instead_fails(self):
        class WrongRow(FakeDevice):
            def answer(self, command):
                answer = super().answer(command)
                if self.screen == "Detail":
                    self.opened = self.ROWS[0]
                return answer
        with self.assertRaisesRegex(AssertionError, "UI did not reach row-detail"):
            self.run_row(WrongRow())


class OverviewReadyTest(unittest.TestCase):
    """#88 01b review, item 2: the baseline may be either release; this build is held to C1."""

    @staticmethod
    def overview(marker, package="com.usagelimits", selected="true"):
        return ET.fromstring(
            f'<hierarchy><node package="{package}" enabled="true" clickable="false" selected="{selected}" '
            f'bounds="[0,1400][180,1560]"><node package="{package}" text="Overview" bounds="[0,1400][180,1560]"/></node>'
            f'<node package="{package}" text="{marker}" bounds="[40,300][680,360]"/></hierarchy>')

    def test_a_baseline_from_before_the_redesign_is_ready_on_its_count(self):
        self.assertTrue(runtime_smoke.overview_ready(self.overview("0 accounts monitored"), "previous"))

    def test_a_baseline_from_after_the_redesign_is_ready_on_c1(self):
        self.assertTrue(runtime_smoke.overview_ready(self.overview("Nothing to monitor yet"), "previous"))

    def test_this_build_is_ready_on_c1_only(self):
        self.assertTrue(runtime_smoke.overview_ready(self.overview("Nothing to monitor yet"), "current"))
        self.assertFalse(runtime_smoke.overview_ready(self.overview("0 accounts monitored"), "current"))

    def test_a_marker_outside_the_package_or_the_selected_tab_does_not_count(self):
        for root in (self.overview("Nothing to monitor yet", package="com.usagelimits.debug"),
                     self.overview("0 accounts monitored", selected="false")):
            self.assertFalse(runtime_smoke.overview_ready(root, "previous"))


class SettingsReadinessTest(unittest.TestCase):
    @staticmethod
    def settings(marker):
        return ET.fromstring(f'''<hierarchy>
          <node package="com.usagelimits" scrollable="true" bounds="[0,150][720,1300]">
            <node package="com.usagelimits" text="{marker}" bounds="[28,277][692,310]"/>
          </node>
          <node package="com.usagelimits" enabled="true" selected="true"
                clickable="false" bounds="[489,1336][720,1476]">
            <node package="com.usagelimits" text="Settings"/>
          </node>
        </hierarchy>''')

    def test_current_settings_returns_to_appearance_from_a_restored_lower_position(self):
        lower, top = self.settings("Provider icons"), self.settings("Appearance")
        with TemporaryDirectory() as directory:
            smoke = Smoke(Path(directory), "test-device")
            with patch.object(smoke, "dump", side_effect=[lower, lower, lower, top, top]), \
                    patch.object(smoke, "scroll") as scroll, patch.object(smoke, "tap") as tap, \
                    patch.object(smoke, "capture"), patch.object(smoke, "adb", return_value="1234"):
                smoke.navigate("Settings", "Appearance", "restored-settings")
            scroll.assert_called_once_with(lower, up=False)
            tap.assert_not_called()

    def test_main_reaches_current_appearance_before_searching_for_the_persisted_tier(self):
        with TemporaryDirectory() as directory:
            directory = Path(directory)
            aapt = directory / "build-tools" / "37.0.0" / "aapt"
            aapt.parent.mkdir(parents=True)
            aapt.touch()
            checksum = directory / "sha256.txt"
            checksum.write_text("a" * 64)
            args = SimpleNamespace(previous_apk=Path("previous.apk"), apk=Path("current.apk"),
                                   debug_apk=None, apk_sha256_file=checksum, evidence=directory)
            smoke = unittest.mock.Mock(spec=Smoke)
            navigator = Smoke(directory, "emulator-test")
            top = self.settings("Appearance")
            baseline = self.settings("Provider icons")
            tier = SettingTest().setting_tree("false")
            reached = []

            def dump(name, **kwargs):
                if name.startswith("current-persisted") and name != "current-persisted-0":
                    return tier
                return baseline if name.startswith("previous-") else top

            def navigate(tab, marker, name, *args):
                if tab == "Settings":
                    navigator.navigate(tab, marker, name, *args)
                    reached.append(name)

            def adb(*args, **kwargs):
                if args[:3] == ("shell", "getprop", "ro.kernel.qemu"):
                    return "1"
                if args[:3] == ("shell", "getprop", "ro.build.version.sdk"):
                    return "37"
                if "resolve-activity" in args:
                    return "com.android.launcher3/.Launcher"
                if args == ("shell", "pm", "path", "com.usagelimits"):
                    return "package:/data/app/base.apk"
                if args[0] == "pull":
                    Path(args[2]).touch()
                return "1234"

            smoke.adb.side_effect = adb
            smoke.installed.side_effect = [
                {"versionCode": "1", "versionName": "old", "appId": "10000", "firstInstallTime": "same"},
                {"versionCode": "2", "versionName": "new", "appId": "10000", "firstInstallTime": "same"},
            ]
            smoke.setting.return_value = SettingTest().setting_tree("true")[1]
            smoke.control.return_value = tier[1]
            smoke.navigate.side_effect = navigate
            smoke.setting_is_disabled.side_effect = lambda name: (
                navigator.setting_is_disabled(name) if name == "current-persisted" else None)
            smoke.place_widgets.return_value = []
            smoke.verify_theme_launches.return_value = []
            smoke.seeded_steps.return_value = {}
            metadata = [{"package": "com.usagelimits", "versionCode": code, "sha256": "a" * 64,
                         "versionName": name} for code, name in ((1, "old"), (2, "new"))]
            with patch.object(runtime_smoke, "Smoke", return_value=smoke), \
                    patch.object(runtime_smoke, "argument_parser") as parser, \
                    patch.object(runtime_smoke, "apk_metadata", side_effect=metadata), \
                    patch.object(runtime_smoke, "digest", return_value="a" * 64), \
                    patch.object(runtime_smoke, "run", side_effect=["commit", "List of devices attached\nemulator-test device"]), \
                    patch.dict(os.environ, ANDROID_HOME=str(directory), GITHUB_SHA="commit", GITHUB_RUN_ID="test"), \
                    patch.object(navigator, "dump", side_effect=dump), \
                    patch.object(navigator, "adb", return_value="1234"), \
                    patch.object(navigator, "capture"), patch.object(navigator, "scroll") as scroll, \
                    patch.object(runtime_smoke.time, "sleep"):
                parser.return_value.parse_args.return_value = args
                runtime_smoke.main()
            self.assertEqual(["previous-settings", "previous-relaunch-settings", "current-settings"], reached)
            self.assertEqual([unittest.mock.call(top, up=True)], scroll.call_args_list,
                             "Only the later tier search scrolls down; Settings readiness is already at the top")
            calls = smoke.method_calls
            self.assertLess(next(i for i, call in enumerate(calls) if call[0] == "navigate"
                                 and call.args[2] == "current-settings"),
                            calls.index(unittest.mock.call.setting_is_disabled("current-persisted")))


class InvocationTest(unittest.TestCase):
    """#88 01b review, item 1: both workflows' invocations of the runner parse, and only Android CI seeds.

    The command lines are read from the workflow files, so a workflow that changes its call is
    tested as it is.
    """

    WORKFLOWS = Path(__file__).resolve().parent.parent / ".github" / "workflows"

    def invocation(self, workflow):
        text = (self.WORKFLOWS / workflow).read_text()
        line = next(line for line in text.splitlines() if "bash tools/run_android_runtime.sh" in line)
        return line.split("bash tools/run_android_runtime.sh", 1)[1].split()

    def test_the_release_workflow_runs_without_a_debug_build(self):
        args = runtime_smoke.argument_parser().parse_args(self.invocation("release.yml"))
        self.assertIsNone(args.debug_apk)

    def test_android_ci_passes_its_debug_build(self):
        args = runtime_smoke.argument_parser().parse_args(self.invocation("android.yml"))
        self.assertEqual(Path("runtime/debug/app-debug.apk"), args.debug_apk)

    def test_android_ci_refuses_to_run_without_the_debug_build(self):
        text = (self.WORKFLOWS / "android.yml").read_text()
        guard = text.index("test -s runtime/debug/app-debug.apk")
        self.assertLess(guard, text.index("bash tools/run_android_runtime.sh"), "the guard runs before the smoke")

    def test_without_a_debug_build_the_seeded_steps_are_skipped_and_said_to_be(self):
        with TemporaryDirectory() as directory:
            smoke = Smoke(Path(directory), "test-device")
            with patch.object(smoke, "adb", side_effect=AssertionError("nothing may be installed or sent")):
                self.assertEqual({"seededList": "skipped: no --debug-apk"}, smoke.seeded_steps(None))

    def test_with_a_debug_build_every_seeded_step_runs(self):
        with TemporaryDirectory() as directory:
            smoke = Smoke(Path(directory), "test-device")
            calls = []
            with patch.object(smoke, "seed_list", side_effect=lambda *a: calls.append("seed") or {"overview": ["a"]}), \
                    patch.object(smoke, "verify_chip_press", side_effect=lambda *a: calls.append("chip")), \
                    patch.object(smoke, "open_seeded_row",
                                 side_effect=lambda *a: calls.append("row") or {"tapped": "b", "shownOnScreen": ["a", "b"]}), \
                    patch.object(smoke, "verify_figure_switch", side_effect=lambda *a: calls.append("figure")):
                record = smoke.seeded_steps(Path("app-debug.apk"))
        self.assertEqual(["seed", "chip", "row", "figure"], calls)
        self.assertEqual("passed", record["seededList"])


class FigureSwitchTest(unittest.TestCase):
    """05a: the switch under the moving Claude Code figure takes a tap; animations end off on every way out."""

    def run_step(self, tap_lands=True, switch_shown=True, scale_after_restore="0.0"):
        """verify_figure_switch on a stand-in device; returns what it raised (or None), the scale puts and the report."""
        state = {"scale": "0.0", "puts": [], "checked": "true", "taps": 0}

        def adb(*args, **kwargs):
            if args[:3] == ("shell", "settings", "put"):
                state["puts"].append(args[-1])
                state["scale"] = args[-1] if args[-1] != "0.0" else scale_after_restore
            if args[:3] == ("shell", "settings", "get"):
                return state["scale"]
            return ""

        def dump(*args, **kwargs):
            switch = (f'<node package="{runtime_smoke.DEBUG_PACKAGE}" content-desc="{runtime_smoke.ANIMATE_CLAUDE}" '
                      f'enabled="true" clickable="true" checkable="true" checked="{state["checked"]}" bounds="[600,300][700,360]"/>')
            return ET.fromstring(f'<hierarchy>{switch if switch_shown else ""}</hierarchy>')

        def tap(node, deadline=None):
            state["taps"] += 1
            if tap_lands:
                state["checked"] = "false" if state["checked"] == "true" else "true"

        with TemporaryDirectory() as directory, patch.object(runtime_smoke.time, "sleep", lambda seconds: None):
            smoke = Smoke(Path(directory), "test-device")
            with patch.object(smoke, "adb", side_effect=adb), patch.object(smoke, "dump", side_effect=dump), \
                    patch.object(smoke, "tap", side_effect=tap), patch.object(smoke, "navigate"), \
                    patch.object(smoke, "capture"), patch.object(smoke, "scroll"):
                try:
                    smoke.verify_figure_switch("figure", runtime_smoke.DEBUG_PACKAGE)
                    raised = None
                except AssertionError as error:
                    raised = error
            report = json.loads((Path(directory) / "figure.json").read_text())
        return raised, state, report

    def test_a_tap_that_lands_turns_the_switch_off_then_on_and_animations_end_off(self):
        raised, state, report = self.run_step()
        self.assertIsNone(raised)
        self.assertEqual((2, "true"), (state["taps"], state["checked"]))
        self.assertEqual(["1.0", "0.0"], state["puts"])
        self.assertEqual(("false", "true", "0.0"),
                         (report["switchReadAfterTap"], report["switchReadAfterSecondTap"], report["animatorScaleRestored"]))

    def test_a_lost_tap_fails_and_animations_still_end_off(self):
        raised, state, report = self.run_step(tap_lands=False)
        self.assertRegex(str(raised), "UI did not reach figure-false")
        self.assertEqual(["1.0", "0.0"], state["puts"])
        self.assertEqual("0.0", report["animatorScaleRestored"])

    def test_no_switch_means_no_figure_and_fails(self):
        raised, _, _ = self.run_step(switch_shown=False)
        self.assertRegex(str(raised), "does not show the Claude Code figure")

    def test_animations_left_on_fail_the_smoke(self):
        raised, _, _ = self.run_step(scale_after_restore="1.0")
        self.assertRegex(str(raised), "not turned off again")


class CreditHierarchyTest(unittest.TestCase):
    def tree(self, label, checked="true", package=runtime_smoke.DEBUG_PACKAGE):
        return ET.fromstring(f'''<hierarchy><node package="{package}" enabled="true"
            clickable="true" checkable="true" checked="{checked}" bounds="[0,10][300,60]">
            <node package="{package}" text="{label}" bounds="[10,10][200,60]"/>
            </node></hierarchy>''')

    def test_localized_credit_controls_require_the_requested_checked_state(self):
        for label in ("Compact", "Kompakt", "Show credits", "Guthaben anzeigen",
                      "Credit accounts", "Guthabenkonten"):
            with self.subTest(label=label):
                self.assertIsNotNone(runtime_smoke.credit_checked(self.tree(label), label, True))
                self.assertIsNone(runtime_smoke.credit_checked(self.tree(label, "false"), label, True))
                self.assertIsNotNone(runtime_smoke.credit_checked(self.tree(label, "false"), label, False))
                self.assertIsNone(runtime_smoke.credit_checked(self.tree(label, package="other"), label, True))

    def test_a_switch_pushed_below_the_view_is_followed_and_one_in_view_is_only_waited_for(self):
        label = "Guthaben anzeigen"
        gone = ET.fromstring(f'''<hierarchy><node package="{runtime_smoke.DEBUG_PACKAGE}" scrollable="true"
            bounds="[0,100][720,1500]"/></hierarchy>''')
        with TemporaryDirectory() as directory:
            smoke = Smoke(Path(directory), "test-device")
            with patch.object(smoke, "dump", side_effect=[self.tree(label, "false"), gone, self.tree(label)]) as dump, \
                    patch.object(smoke, "adb") as adb, patch.object(runtime_smoke.time, "sleep") as sleep:
                root = smoke.credit_selection("shown", label, True)
            self.assertIsNotNone(runtime_smoke.credit_checked(root, label, True))
            self.assertEqual(3, dump.call_count)
            self.assertEqual(1, sleep.call_count)
            self.assertEqual([("shell", "input", "swipe", "360", "1150", "360", "450", "400")],
                             [call.args for call in adb.call_args_list])
            with patch.object(smoke, "dump", return_value=self.tree(label, "false")), \
                    patch.object(smoke, "adb") as adb, patch.object(runtime_smoke.time, "sleep"):
                with self.assertRaisesRegex(AssertionError, "UI did not reach shown"):
                    smoke.credit_selection("shown", label, True)
            self.assertEqual([], adb.call_args_list)

    class LayoutDevice(FakeDevice):
        """The API 37 selector ancestors and the 310px compact-preview reflow from run 44875789454."""

        def __init__(self, label, outcome="compact"):
            super().__init__()
            self.label = label
            self.outcome = outcome
            self.compact = False
            self.above_view = False

        def answer(self, command):
            if command[:3] == ("shell", "input", "tap"):
                x, y = map(int, command[3:5])
                if 190 <= x < 336 and 317 <= y < 401 and self.outcome != "lost":
                    self.compact = self.outcome == "compact"
                    self.above_view = True
                return b""
            if command[:3] == ("shell", "input", "swipe"):
                if int(command[4]) < int(command[6]):
                    self.above_view = False
                return b""
            return super().answer(command)

        def hierarchy(self):
            pkg = self.PACKAGE

            def option(label, checked, box, text_box):
                return (f'<node package="{pkg}" enabled="true" checkable="true" checked="{str(checked).lower()}" '
                        f'clickable="{str(not checked).lower()}" bounds="{box}">'
                        f'<node package="{pkg}" enabled="true" clickable="false" checkable="false" checked="false" '
                        f'bounds="{box}"><node package="{pkg}" text="{label}" bounds="{text_box}"/></node></node>')

            layout = "" if self.above_view else (
                option("Normal", not self.compact, "[60,317][190,401]", "[85,345][165,374]")
                + option(self.label, self.compact, "[190,317][336,401]", "[215,345][311,374]"))
            order = (option("Guthabenkonten", True, "[285,216][511,300]", "[310,244][486,273]")
                     if self.above_view else
                     option("Guthabenkonten", True, "[285,526][511,610]", "[310,554][486,583]"))
            return (f'<hierarchy><node package="{pkg}" enabled="true" scrollable="true" '
                    f'bounds="[0,144][720,1336]">{layout}{order}</node></hierarchy>').encode()

    def test_compact_selection_is_found_above_the_view_after_its_preview_reflows(self):
        for label in ("Compact", "Kompakt"):
            with self.subTest(label=label), TemporaryDirectory() as directory:
                device = self.LayoutDevice(label)
                with patch.object(runtime_smoke.subprocess, "run", device.run), \
                        patch.object(runtime_smoke, "time", device):
                    smoke = Smoke(Path(directory), "test-device")
                    smoke.tap(smoke.credit_control("compact", label, up=False))
                    root = smoke.credit_selection("compact-selected", label, True, up=False)
                self.assertIsNotNone(runtime_smoke.credit_checked(root, label, True))
                self.assertIsNotNone(runtime_smoke.credit_checked(root, "Normal", False))

    def test_lost_taps_and_wrong_layout_still_fail_the_selected_assertion(self):
        for outcome in ("lost", "normal"):
            with self.subTest(outcome=outcome), TemporaryDirectory() as directory:
                device = self.LayoutDevice("Kompakt", outcome)
                with patch.object(runtime_smoke.subprocess, "run", device.run), \
                        patch.object(runtime_smoke, "time", device):
                    smoke = Smoke(Path(directory), "test-device")
                    smoke.tap(smoke.credit_control("compact", "Kompakt", up=False))
                    with self.assertRaisesRegex(AssertionError, "UI did not reach compact-selected"):
                        smoke.credit_selection("compact-selected", "Kompakt", True, up=False)
                root = ET.fromstring(device.hierarchy())
                self.assertIsNotNone(runtime_smoke.credit_checked(root, "Kompakt", False))
                self.assertIsNotNone(runtime_smoke.credit_checked(root, "Normal", True))

    RADIO_CALLERS = {
        "credits-en-normal-layout": "credits-en-normal-selected",
        "credits-en-compact-layout": "credits-en-compact-selected",
        "credits-en-show-first": "credits-en-usage-first-selected",
        "credits-en-credit-first": "credits-en-credit-first-selected",
        "credits-en-restore-normal": "credits-en-normal-restored",
        "credits-de-layout": "credits-de-normal-selected",
        "credits-de-compact": "credits-de-compact-selected",
        "credits-de-show-first": "credits-de-usage-first-selected",
        "credits-de-credit-first": "credits-de-credit-first-selected",
    }

    class SelectionDevice(FakeDevice):
        """Historical CI target clipping and preview-above-controls reflow; #123 has no such reflow."""

        def __init__(self, restore_top=407, failed=None, outcome="selected", preselected=False, historical=False):
            super().__init__()
            self.restore_top, self.failed, self.outcome = restore_top, failed, outcome
            self.preselected = preselected
            self.historical = historical
            self.states = {}
            self.state = None

        def prepare(self, name, label):
            if label == "Normal":
                other = "Kompakt" if name.startswith("credits-de-") else "Compact"
                top = self.restore_top if name == "credits-en-restore-normal" else 407
                delta, left, right, other_left, other_right = 310, 60, 189, 189, 337
            elif label in ("Compact", "Kompakt"):
                other, top, delta, left, right = "Normal", 317, -310, 190, 336
                other_left, other_right = 60, 190
            else:
                other, top, left, right, other_left, other_right = {
                    "Usage providers": ("Credit accounts", 522, 60, 286, 286, 506),
                    "Credit accounts": ("Usage providers", 522, 286, 506, 60, 286),
                    "Nutzungskonten": ("Guthabenkonten", 334, 60, 286, 286, 511),
                    "Guthabenkonten": ("Nutzungskonten", 334, 286, 511, 60, 286),
                }[label]
                delta = 0
            if not self.historical:
                delta = 0
            checked = self.preselected and name in (
                "credits-en-normal-layout", "credits-en-show-first", "credits-de-layout", "credits-de-show-first")
            self.state = {"name": name, "label": label, "other": other, "top": top,
                          "delta": delta, "left": left, "right": right,
                          "other_left": other_left, "other_right": other_right,
                          "initial_checked": checked, "checked": checked,
                          "taps": [], "tap_bounds": [], "observations": []}
            self.states[name] = self.state

        def answer(self, command):
            if self.state is None:
                return super().answer(command)
            state = self.state
            if command[:3] == ("shell", "input", "tap"):
                x, y = map(int, command[3:5])
                if state["left"] <= x < state["right"] and state["top"] <= y < state["top"] + 84:
                    state["taps"].append((x, y))
                    state["tap_bounds"].append((state["top"], state["top"] + 84))
                    outcome = self.outcome if state["name"] == self.failed else "selected"
                    if 144 <= y < 1336 and not state["checked"] and outcome != "lost":
                        state["checked"] = outcome == "selected"
                        if outcome != "visible-wrong":
                            state["top"] += state["delta"]
                return b""
            if command[:3] == ("shell", "input", "swipe"):
                state["top"] += 596 if int(command[4]) < int(command[6]) else -596
                return b""
            return super().answer(command)

        def hierarchy(self):
            state, pkg = self.state, self.PACKAGE
            top = state["top"]
            bottom = top + (79 if top == 102 else 84)

            def option(label, checked, left, right):
                if bottom <= 144 or top >= 1336:
                    return ""
                return (f'<node package="{pkg}" enabled="true" checkable="true" checked="{str(checked).lower()}" '
                        f'clickable="{str(not checked).lower()}" bounds="[{left},{top}][{right},{bottom}]">'
                        f'<node package="{pkg}" enabled="true" clickable="false" bounds="[{left},{top}][{right},{bottom}]">'
                        f'<node package="{pkg}" text="{label}" '
                        f'bounds="[{left + 25},{max(144, top + 28)}][{right - 25},{min(1336, top + 57)}]"/>'
                        '</node></node>')

            radios = option(state["label"], state["checked"], state["left"], state["right"])
            radios += option(state["other"], not state["checked"], state["other_left"], state["other_right"])
            return (f'<hierarchy><node package="{pkg}" enabled="true" scrollable="true" '
                    f'bounds="[0,144][720,1336]">{radios}</node></hierarchy>').encode()

    def selection_journey(self, device, directory):
        """Keep actual credit_steps radio callers and their predicates; bypass unrelated screens only."""
        smoke = Smoke(Path(directory), "test-device")
        control, dump, wait_for = smoke.credit_control, smoke.dump, smoke.wait_for
        unrelated = self.unrelated_credit_screens()
        callers = self.RADIO_CALLERS
        observed = tuple(callers) + tuple(callers.values())

        def reach(name, label, **kwargs):
            if name in callers:
                device.prepare(name, label)
                return control(name, label, **kwargs)
            return self.tree(label)[0]

        def observe(name, **kwargs):
            if any(name == stem or name.removeprefix(stem + "-").isdigit() for stem in observed):
                root = dump(name, **kwargs)
                device.state["observations"].append(root)
                return root
            if name in ("credits-de-hidden-settings", "credits-de-hidden-again",
                        "credits-de-persisted-hidden", "credits-de-final-hidden") \
                    or name.startswith(("credits-de-hidden-settings-", "credits-de-hidden-again-")):
                return self.tree("Guthaben anzeigen", "false")
            if name.startswith("credits-de-restored-visible-"):
                return self.tree("Guthaben anzeigen")
            if name == "credits-de-persisted-no-credit":
                return ET.fromstring("<hierarchy/>")
            return unrelated

        def wait(name, predicate, **kwargs):
            if name in callers.values():
                return wait_for(name, predicate, **kwargs)
            return unrelated

        with patch.object(runtime_smoke.subprocess, "run", device.run), \
                patch.object(runtime_smoke, "time", device), \
                patch.object(smoke, "seed_credit_only"), patch.object(smoke, "launch"), \
                patch.object(smoke, "navigate_credit"), patch.object(smoke, "capture"), \
                patch.object(smoke, "credit_control", side_effect=reach), \
                patch.object(smoke, "dump", side_effect=observe), \
                patch.object(smoke, "wait_for", side_effect=wait):
            return smoke.credit_steps(Path("fixture.apk"))

    def test_controls_before_preview_keep_checked_coordinates_without_a_second_tap(self):
        for preselected in (False, True):
            with self.subTest(preselected=preselected), TemporaryDirectory() as directory:
                device = self.SelectionDevice(preselected=preselected)
                result = self.selection_journey(device, directory)
                self.assertEqual("hidden choice persisted", result["creditRelaunch"])
                for state in device.states.values():
                    with self.subTest(caller=state["name"]):
                        self.assertEqual(1, len(state["taps"]))
                        top, bottom = state["tap_bounds"][0]
                        self.assertTrue(144 <= top < bottom <= 1336)
                        selected = runtime_smoke.credit_checked(state["observations"][-1], state["label"], True)
                        self.assertIsNotNone(selected)
                        self.assertEqual(top, bounds(selected)[1])
                        self.assertIsNotNone(runtime_smoke.credit_checked(
                            state["observations"][-1], state["other"], False))

    def test_current_control_callers_still_reject_lost_or_wrong_taps(self):
        for caller, assertion in self.RADIO_CALLERS.items():
            for outcome in ("lost", "visible-wrong"):
                with self.subTest(caller=caller, outcome=outcome), TemporaryDirectory() as directory:
                    device = self.SelectionDevice(failed=caller, outcome=outcome)
                    with self.assertRaisesRegex(AssertionError, f"UI did not reach {assertion}"):
                        self.selection_journey(device, directory)
                    self.assertEqual(1, len(device.state["taps"]))
                    self.assertFalse(device.state["checked"])

    def test_credit_settings_restarts_from_appearance_before_finding_controls_above_restored_position(self):
        package = runtime_smoke.DEBUG_PACKAGE
        for tab, heading in (("Settings", "Appearance"), ("Einstellungen", "Erscheinungsbild")):
            with self.subTest(tab=tab), TemporaryDirectory() as directory:
                smoke = Smoke(Path(directory), "test-device")
                bottom = ET.fromstring(f'<hierarchy><node package="{package}" text="Manage credit accounts"/></hierarchy>')
                top = ET.fromstring(f'<hierarchy><node package="{package}" text="{heading}"/></hierarchy>')
                reached = ET.fromstring(f'<hierarchy><node package="{package}" text="Show credits"/></hierarchy>')
                target = ET.fromstring('<node selected="true"/>')
                with patch.object(runtime_smoke, "navigation_target", return_value=target), \
                        patch.object(smoke, "dump", side_effect=[bottom, bottom, top, top, reached]), \
                        patch.object(smoke, "wait_for"), patch.object(smoke, "capture"), \
                        patch.object(smoke, "scroll") as scroll, patch.object(smoke, "adb", return_value="1234"):
                    smoke.navigate_credit(tab, "Show credits", "settings")
                self.assertEqual([unittest.mock.call(bottom, up=False, package=package),
                                  unittest.mock.call(top, up=True, package=package)], scroll.call_args_list)

    def test_navigation_reaches_required_layout_below_show_credits_before_returning(self):
        package = runtime_smoke.DEBUG_PACKAGE
        first = ET.fromstring(f'<hierarchy><node package="{package}" text="Appearance"/>'
                              f'<node package="{package}" text="Show credits"/></hierarchy>')
        reached = ET.fromstring(f'<hierarchy><node package="{package}" text="Show credits"/>'
                                f'<node package="{package}" text="Normal"/></hierarchy>')
        with TemporaryDirectory() as directory:
            smoke = Smoke(Path(directory), "test-device")
            target = ET.fromstring('<node selected="true"/>')
            with patch.object(runtime_smoke, "navigation_target", return_value=target), \
                    patch.object(smoke, "dump", side_effect=[first, first, first, reached]), \
                    patch.object(smoke, "wait_for") as wait, patch.object(smoke, "capture"), \
                    patch.object(smoke, "scroll") as scroll, patch.object(smoke, "adb", return_value="1234"):
                smoke.navigate_credit("Settings", "Show credits", "settings", required_label="Normal")
            self.assertEqual([unittest.mock.call(first, up=True, package=package)], scroll.call_args_list)
            with patch.object(runtime_smoke, "selected_tab", return_value=True):
                self.assertFalse(wait.call_args.args[1](first))
                self.assertTrue(wait.call_args.args[1](reached))

    def test_historical_radio_callers_reach_full_targets_and_follow_preview_reflow(self):
        # Historical CI supplied 102/313; lower-edge cases keep the measured +310px reflow regression.
        for restore_top in (102, 313, 1100, 1270):
            for preselected in (False, True):
                with self.subTest(restore_top=restore_top, preselected=preselected), TemporaryDirectory() as directory:
                    device = self.SelectionDevice(restore_top, preselected=preselected, historical=True)
                    result = self.selection_journey(device, directory)
                    self.assertEqual("hidden choice persisted", result["creditRelaunch"])
                    for state in device.states.values():
                        with self.subTest(caller=state["name"]):
                            self.assertIsNotNone(runtime_smoke.credit_checked(
                                state["observations"][0], state["label"], state["initial_checked"]))
                            self.assertTrue(state["checked"])
                            self.assertEqual(1, len(state["taps"]), "one tap, not a retry after reflow")
                            self.assertTrue(144 <= state["taps"][0][1] < 1336, "tap lies inside the viewport")
                            top, bottom = state["tap_bounds"][0]
                            self.assertTrue(144 <= top < bottom <= 1336, "the entire original target is visible")
                            selected = runtime_smoke.credit_checked(state["observations"][-1], state["label"], True)
                            self.assertIsNotNone(selected)
                            self.assertIsNotNone(runtime_smoke.credit_checked(
                                state["observations"][-1], state["other"], False))
                            if state["initial_checked"]:
                                self.assertEqual(top, bounds(selected)[1], "a preselected tap does not grow the preview")

    def test_radio_callers_reject_lost_taps_and_visible_wrong_selection(self):
        for caller, assertion in self.RADIO_CALLERS.items():
            for outcome in ("lost", "wrong", "visible-wrong"):
                with self.subTest(caller=caller, outcome=outcome), TemporaryDirectory() as directory:
                    device = self.SelectionDevice(1100, caller, outcome, historical=True)
                    with self.assertRaisesRegex(AssertionError, f"UI did not reach {assertion}"):
                        self.selection_journey(device, directory)
                    self.assertFalse(device.state["checked"])
                    self.assertEqual(1, len(device.state["taps"]))
                    self.assertIsNotNone(runtime_smoke.credit_checked(
                        ET.fromstring(device.hierarchy()), device.state["label"], False))

    class HideDevice(FakeDevice):
        """Current stable switch, plus optional historical 549px preview-above-controls reflow."""

        def __init__(self, first_top, second_top, outcome="hidden", failed_hide=1, historical=False):
            super().__init__()
            self.top = first_top
            self.second_top = second_top
            self.checked = True
            self.outcome = outcome
            self.failed_hide = failed_hide
            self.reflow = 549 if historical else 0
            self.hide = 0

        def answer(self, command):
            if command[:3] == ("shell", "input", "tap"):
                x, y = map(int, command[3:5])
                if 573 <= x < 664 and max(144, self.top) <= y < min(1336, self.top + 84):
                    if self.checked:
                        self.hide += 1
                        outcome = self.outcome if self.hide == self.failed_hide else "hidden"
                        if outcome != "lost":
                            self.checked = outcome != "hidden"
                            self.top -= self.reflow
                    else:
                        self.checked = True
                        self.top += self.reflow
                return b""
            if command[:3] == ("shell", "input", "swipe"):
                self.top += 596 if int(command[4]) < int(command[6]) else -596
                return b""
            return super().answer(command)

        def hierarchy(self):
            pkg = self.PACKAGE
            label, switch = "", ""
            if self.top + 31 > 144 and self.top + 2 < 1336:
                label = (f'<node package="{pkg}" text="Guthaben anzeigen" '
                         f'bounds="[56,{max(144, self.top + 2)}][261,{min(1336, self.top + 31)}]"/>')
            if self.top + 84 > 144 and self.top < 1336:
                switch = (f'<node package="{pkg}" enabled="true" clickable="true" checkable="true" '
                          f'checked="{str(self.checked).lower()}" bounds="[573,{self.top}][664,{self.top + 84}]">'
                          f'<node package="{pkg}" content-desc="Guthaben anzeigen" clickable="false" '
                          f'bounds="[573,{max(144, self.top + 14)}][664,{min(1336, self.top + 70)}]"/></node>')
            return (f'<hierarchy><node package="{pkg}" enabled="true" scrollable="true" '
                    f'bounds="[0,144][720,1336]"><node package="{pkg}" enabled="true" '
                    f'bounds="[56,102][664,{min(1336, self.top + 361)}]">{label}{switch}'
                    '</node></node></hierarchy>').encode()

    def unrelated_credit_screens(self):
        labels = ("Synthetic credit", "$3.18", "Name", "Compact", "Kompakt", "Guthabenkonten",
                  "Credits · 1 balance", "No usage accounts yet", "Guthaben · 1 Konto", "Noch keine Nutzungskonten",
                  "Normal", "Usage providers", "Nutzungskonten", "Credit accounts")
        return ET.fromstring('<hierarchy>' + ''.join(
            f'<node package="{runtime_smoke.DEBUG_PACKAGE}" enabled="true" checkable="true" checked="true" '
            f'clickable="true" bounds="[0,{10 + i * 90}][300,{60 + i * 90}]" '
            f'content-desc="{label}, Low balance: Synthetic credit 3.18, Niedriges Guthaben: Synthetic credit 3,18">'
            f'<node package="{runtime_smoke.DEBUG_PACKAGE}" text="{label}" bounds="[0,{10 + i * 90}][200,{60 + i * 90}]"/>'
            '</node>' for i, label in enumerate(labels)) + '</hierarchy>')

    def hide_journey(self, device, directory):
        """Skip unrelated screens; keep the real credit_steps Hide/Show callers and adb selection path."""
        smoke = Smoke(Path(directory), "test-device")
        control, dump, wait_for = smoke.credit_control, smoke.dump, smoke.wait_for
        unrelated = self.unrelated_credit_screens()

        def reach(name, label, **kwargs):
            if name in ("credits-de-hide", "credits-de-hide-again", "credits-de-restored-show"):
                if name == "credits-de-hide-again":
                    device.top = device.second_top
                elif name == "credits-de-restored-show":
                    device.top = 953
                return control(name, label, **kwargs)
            return self.tree(label)[0]

        def observe(name, **kwargs):
            if name.startswith(("credits-de-hide-", "credits-de-hidden-", "credits-de-restored-")) \
                    or name in ("credits-de-persisted-hidden", "credits-de-final-hidden"):
                return dump(name, **kwargs)
            if name == "credits-de-persisted-no-credit":
                return ET.fromstring("<hierarchy/>")
            return unrelated

        def wait(name, predicate, **kwargs):
            if name in ("credits-de-hidden-settings", "credits-de-hidden-again"):
                return wait_for(name, predicate, **kwargs)
            return unrelated

        with patch.object(runtime_smoke.subprocess, "run", device.run), \
                patch.object(runtime_smoke, "time", device), \
                patch.object(smoke, "seed_credit_only"), patch.object(smoke, "launch"), \
                patch.object(smoke, "navigate_credit"), patch.object(smoke, "capture"), \
                patch.object(smoke, "credit_control", side_effect=reach), \
                patch.object(smoke, "dump", side_effect=observe), \
                patch.object(smoke, "wait_for", side_effect=wait):
            return smoke.credit_steps(Path("fixture.apk"))

    def test_historical_hide_callers_observe_the_unchecked_switch_after_reflow(self):
        for first_top, second_top in ((488, 664), (1228, 488), (1228, 664)):
            with self.subTest(first_top=first_top, second_top=second_top), TemporaryDirectory() as directory:
                device = self.HideDevice(first_top, second_top, historical=True)
                result = self.hide_journey(device, directory)
                self.assertEqual("hidden choice persisted", result["creditRelaunch"])
                self.assertIsNotNone(runtime_smoke.credit_checked(
                    ET.fromstring(device.hierarchy()), "Guthaben anzeigen", False))
                self.assertFalse(device.checked)

    def test_show_first_switch_keeps_its_coordinates_through_both_hide_callers(self):
        with TemporaryDirectory() as directory:
            device = self.HideDevice(488, 664)
            result = self.hide_journey(device, directory)
            self.assertEqual("hidden choice persisted", result["creditRelaunch"])
            self.assertEqual(2, device.hide)
            self.assertFalse(device.checked)
            taps = [command for command, _, _ in device.calls if command[:3] == ("shell", "input", "tap")]
            self.assertIn(("shell", "input", "tap", "618", "530"), taps)
            self.assertIn(("shell", "input", "tap", "618", "706"), taps)

    def test_hide_callers_reject_lost_taps_and_wrong_selection(self):
        for failed_hide, name in ((1, "credits-de-hidden-settings"), (2, "credits-de-hidden-again")):
            for outcome in ("lost", "wrong"):
                with self.subTest(hide=failed_hide, outcome=outcome), TemporaryDirectory() as directory:
                    device = self.HideDevice(488, 488, outcome, failed_hide)
                    with self.assertRaisesRegex(AssertionError, f"UI did not reach {name}"):
                        self.hide_journey(device, directory)
                    self.assertIsNotNone(runtime_smoke.credit_checked(
                        ET.fromstring(device.hierarchy()), "Guthaben anzeigen", True))
                    self.assertTrue(device.checked)

    class RestoredCreditDevice(FakeDevice):
        """Run 44999291762: restored Show credits at y1201; its layout is below the fold."""
        def __init__(self, checked=True):
            super().__init__()
            self.show = False
            self.layout_checked = checked
            self.offset = 0
            self.taps = []

        def answer(self, command):
            if command[:3] == ("shell", "input", "tap"):
                self.taps.append(command[3:])
                self.show = True
                return b""
            if command[:3] == ("shell", "input", "swipe"):
                self.offset += 596 if int(command[4]) > int(command[6]) else -596
                return b""
            return super().answer(command)

        def hierarchy(self):
            pkg = self.PACKAGE
            toggle = (f'<node package="{pkg}" enabled="true" clickable="true" checkable="true" '
                      f'checked="{str(self.show).lower()}" bounds="[573,1201][664,1285]">'
                      f'<node package="{pkg}" content-desc="Guthaben anzeigen" bounds="[573,1215][664,1271]"/></node>')
            layout = ""
            if self.show and self.offset > 0:
                # Captured layout y1185 plus the unchanged switch's y1201-895 offset = y1491.
                # One existing 596px upward swipe brings that below-fold row fully into view.
                top = 1491 - self.offset
                layout = (f'<node package="{pkg}" enabled="true" checkable="true" '
                          f'checked="{str(self.layout_checked).lower()}" clickable="{str(not self.layout_checked).lower()}" '
                          f'bounds="[190,{top}][337,{top + 84}]">'
                          f'<node package="{pkg}" text="Kompakt" bounds="[214,{top + 28}][311,{top + 57}]"/></node>')
            heading = (f'<node package="{pkg}" text="Guthabenkonten" bounds="[56,1334][265,1336]"/>'
                       if self.show and self.offset == 0 else "")
            return (f'<hierarchy><node package="{pkg}" scrollable="true" enabled="true" '
                    f'bounds="[0,144][720,1336]">{toggle}{heading}{layout}</node></hierarchy>').encode()

    def restored_layout_journey(self, directory, checked=True):
        device = self.RestoredCreditDevice(checked)
        smoke = Smoke(Path(directory), "test-device")
        original_control, original_dump, original_selection = smoke.credit_control, smoke.dump, smoke.credit_selection
        unrelated = self.unrelated_credit_screens()
        def reach(name, label, **kwargs):
            if name in ("credits-de-restored-show", "credits-de-persisted-layout"):
                return original_control(name, label, **kwargs)
            return self.tree(label)[0]
        def observe(name, **kwargs):
            if name.startswith(("credits-de-restored-show-", "credits-de-restored-visible-", "credits-de-persisted-layout-")):
                return original_dump(name, **kwargs)
            if name in ("credits-de-persisted-hidden", "credits-de-final-hidden"):
                return self.tree("Guthaben anzeigen", "false")
            if name == "credits-de-persisted-no-credit":
                return ET.fromstring('<hierarchy/>')
            return unrelated
        with patch.object(runtime_smoke.subprocess, "run", device.run), patch.object(runtime_smoke, "time", device), \
                patch.object(smoke, "seed_credit_only"), patch.object(smoke, "launch"), \
                patch.object(smoke, "navigate_credit"), patch.object(smoke, "capture"), \
                patch.object(smoke, "tap", side_effect=lambda node: Smoke.tap(smoke, node)
                             if node.get("checkable") == "true" and node.get("bounds") == "[573,1201][664,1285]" else None), \
                patch.object(smoke, "credit_control", side_effect=reach), \
                patch.object(smoke, "credit_selection", side_effect=lambda name, *args, **kwargs:
                             original_selection(name, *args, **kwargs) if name == "credits-de-restored-visible" else unrelated), \
                patch.object(smoke, "dump", side_effect=observe), patch.object(smoke, "wait_for", return_value=unrelated):
            result = smoke.credit_steps(Path("fixture.apk"))
        return result, device

    def test_restored_show_reaches_persisted_layout_below_the_unchanged_switch(self):
        with TemporaryDirectory() as directory:
            result, device = self.restored_layout_journey(directory)
        self.assertEqual("hidden choice persisted", result["creditRelaunch"])
        self.assertEqual([("618", "1243")], device.taps)
        swipes = [command for command, _, _ in device.calls if command[:3] == ("shell", "input", "swipe")]
        self.assertEqual([("shell", "input", "swipe", "360", "1038", "360", "442", "400")], swipes)

    def test_restored_visible_wrong_layout_still_fails_its_persistence_assertion(self):
        with TemporaryDirectory() as directory:
            with self.assertRaisesRegex(AssertionError, "Compact layout did not persist after force-stop"):
                self.restored_layout_journey(directory, checked=False)

    def test_selected_semantics_without_checked_attribute(self):
        root = ET.fromstring(f'''<hierarchy><node package="{runtime_smoke.DEBUG_PACKAGE}" enabled="true"
            selected="true" clickable="false" bounds="[0,10][300,60]">
            <node package="{runtime_smoke.DEBUG_PACKAGE}" text="Compact" bounds="[10,10][200,60]"/>
            </node></hierarchy>''')
        self.assertIsNotNone(runtime_smoke.credit_checked(root, "Compact", True))
        self.assertIsNone(runtime_smoke.credit_checked(root, "Compact", False))

    def test_selected_wins_over_checked_false_on_noncheckable_node(self):
        root = ET.fromstring(f'''<hierarchy><node package="{runtime_smoke.DEBUG_PACKAGE}" enabled="true"
            checkable="false" checked="false" selected="true" clickable="false" bounds="[0,10][300,60]">
            <node package="{runtime_smoke.DEBUG_PACKAGE}" text="Compact" bounds="[10,10][200,60]"/>
            </node></hierarchy>''')
        self.assertIsNotNone(runtime_smoke.credit_checked(root, "Compact", True))
        self.assertIsNone(runtime_smoke.credit_checked(root, "Compact", False))

    def test_checked_radio_semantics_are_honored(self):
        root = ET.fromstring(f'''<hierarchy><node package="{runtime_smoke.DEBUG_PACKAGE}"
            class="android.widget.RadioButton" enabled="true" checked="true" selected="false"
            clickable="false" bounds="[0,10][300,60]"><node package="{runtime_smoke.DEBUG_PACKAGE}"
            text="Compact" bounds="[10,10][200,60]"/></node></hierarchy>''')
        self.assertIsNotNone(runtime_smoke.credit_checked(root, "Compact", True))
        self.assertIsNone(runtime_smoke.credit_checked(root, "Compact", False))

    def test_show_first_selection_ignores_same_text_heading(self):
        root = ET.fromstring(f'''<hierarchy>
          <node package="{runtime_smoke.DEBUG_PACKAGE}" text="Credit accounts" bounds="[0,0][200,40]"/>
          <node package="{runtime_smoke.DEBUG_PACKAGE}" enabled="true" clickable="true" checkable="true"
                checked="true" bounds="[0,50][200,100]">
            <node package="{runtime_smoke.DEBUG_PACKAGE}" text="Credit accounts" bounds="[0,50][200,100]"/>
          </node>
        </hierarchy>''')
        selected = runtime_smoke.credit_checked(root, "Credit accounts", True)
        self.assertIsNotNone(selected)
        self.assertEqual((0, 50, 200, 100), bounds(selected))

    def test_localized_compact_labels_are_exact_and_package_scoped(self):
        for label in ("Credits · 1 balance", "Guthaben · 1 Konto"):
            with self.subTest(label=label):
                self.assertTrue(runtime_smoke.matches(self.tree(label), label, runtime_smoke.DEBUG_PACKAGE))
                self.assertFalse(runtime_smoke.matches(self.tree(label + "s"), label, runtime_smoke.DEBUG_PACKAGE))
                self.assertFalse(runtime_smoke.matches(self.tree(label, package="other"), label, runtime_smoke.DEBUG_PACKAGE))
                spoken = ET.fromstring(f'''<hierarchy><node package="{runtime_smoke.DEBUG_PACKAGE}"
                    content-desc="{label}, localized low-balance hint" bounds="[0,10][300,60]"/></hierarchy>''')
                self.assertTrue(runtime_smoke.credit_matches(spoken, label, runtime_smoke.DEBUG_PACKAGE))
                self.assertFalse(runtime_smoke.matches(spoken, label, runtime_smoke.DEBUG_PACKAGE))

    def compact_order_journey(self, directory, wrong_order=None, old_label=None, foreign=None):
        """Keep credit_steps' actual EN/DE C1 lookup, geometry and balance accessibility checks."""
        device = FakeDevice()
        smoke = Smoke(Path(directory), "test-device")
        unrelated = self.unrelated_credit_screens()
        compact = []
        def observe(name, **kwargs):
            if name in ("credits-de-persisted-hidden", "credits-de-final-hidden"):
                return self.tree("Guthaben anzeigen", "false")
            if name == "credits-de-persisted-no-credit":
                return ET.fromstring('<hierarchy/>')
            if name in ("credits-en-compact-order", "credits-de-compact-order"):
                locale = "en" if "-en-" in name else "de"
                compact.append(locale)
                label = ("No usage accounts yet" if locale == "en" else "Noch keine Nutzungskonten")
                if old_label == locale:
                    label = "Nothing to monitor yet" if locale == "en" else "Noch nichts zu verfolgen"
                title = "Credits · 1 balance" if locale == "en" else "Guthaben · 1 Konto"
                hint = "Low balance: Synthetic credit $3.18" if locale == "en" else "Niedriges Guthaben: Synthetic credit 3,18"
                # Run 44971270130/artifact 14736240076, credits-en-compact-order.xml:
                # the credit header [28,277][692,361] precedes C1 [28,634][389,675].
                y = 800 if wrong_order == locale else 277
                package = "other.package" if foreign == locale else runtime_smoke.DEBUG_PACKAGE
                return ET.fromstring(f'<hierarchy><node package="{runtime_smoke.DEBUG_PACKAGE}" '
                    f'content-desc="{title}, {hint}" bounds="[28,{y}][692,{y + 84}]"/>'
                    f'<node package="{package}" text="{label}" bounds="[28,634][389,675]"/></hierarchy>')
            return unrelated
        with patch.object(runtime_smoke.subprocess, "run", device.run), patch.object(runtime_smoke, "time", device), \
                patch.object(smoke, "seed_credit_only"), patch.object(smoke, "launch"), \
                patch.object(smoke, "navigate_credit"), patch.object(smoke, "capture"), \
                patch.object(smoke, "credit_control", side_effect=lambda name, label, **kwargs: self.tree(label)[0]), \
                patch.object(smoke, "credit_selection"), patch.object(smoke, "dump", side_effect=observe), \
                patch.object(smoke, "wait_for", return_value=unrelated):
            result = smoke.credit_steps(Path("fixture.apk"))
        return result, compact

    def test_credit_only_c1_titles_reach_both_real_compact_order_assertions(self):
        with TemporaryDirectory() as directory:
            result, compact = self.compact_order_journey(directory)
        self.assertEqual(["en", "de"], compact)
        self.assertEqual(["en-US", "de-DE"], result["creditLocales"])
        self.assertEqual("hidden choice persisted", result["creditRelaunch"])

    def test_credit_only_c1_geometric_order_remains_required_in_both_locales(self):
        for locale, title in (("en", "Credits"), ("de", "Guthaben")):
            with self.subTest(locale=locale), TemporaryDirectory() as directory:
                with self.assertRaisesRegex(AssertionError, "Show first did not put " + title + " above usage providers"):
                    self.compact_order_journey(directory, wrong_order=locale)

    def test_obsolete_or_foreign_c1_titles_cannot_satisfy_the_credit_consumer(self):
        for locale in ("en", "de"):
            for option in ("old_label", "foreign"):
                with self.subTest(locale=locale, option=option), TemporaryDirectory() as directory:
                    with self.assertRaises(StopIteration):
                        self.compact_order_journey(directory, **{option: locale})

    def test_english_and_german_credit_journey_labels_are_exact(self):
        expected = (
            "Collapse", "Einklappen", "Manage credit accounts", "Guthaben verwalten",
            "Normal", "Compact", "Kompakt", "Usage providers", "Nutzungskonten",
            "Credit accounts", "Guthabenkonten", "Show credits", "Guthaben anzeigen",
            "Add credit balance", "Guthaben hinzufügen", "Name", "Low-balance figure",
            "Warnschwelle für niedriges Guthaben",
        )
        for label in expected:
            with self.subTest(label=label):
                self.assertTrue(runtime_smoke.matches(self.tree(label), label, runtime_smoke.DEBUG_PACKAGE))
                self.assertFalse(runtime_smoke.matches(self.tree(label, package="other"), label,
                                                       runtime_smoke.DEBUG_PACKAGE))


class SeedListTest(unittest.TestCase):
    """#88 01b: the seeded debug build is granted notifications before its first launch.

    Run 1 on CI (44670254725) stopped here: Android's notification prompt covered the List.
    The fake device shows that prompt until the debug package has been granted the permission.
    """

    class Device(FakeDevice):
        def __init__(self):
            super().__init__()
            self.granted = False
            self.token = None

        def answer(self, command):
            if command[:3] == ("shell", "pm", "grant") and command[3] == runtime_smoke.DEBUG_PACKAGE:
                self.granted = True
                return b""
            if command[:2] == ("shell", "am") and command[2] == "broadcast":
                self.token = command[command.index("token") + 1]
                return b"Broadcast completed"
            if command[:3] == ("exec-out", "run-as", runtime_smoke.DEBUG_PACKAGE):
                aliases = [f"evidence-{n}" for n in range(6)]
                return json.dumps({"token": self.token, "success": True, "accounts": aliases,
                                   "overview": aliases}).encode()
            if command[:2] == ("install", "-r"):
                return b"Success"
            return super().answer(command)

        def hierarchy(self):
            if self.screen is not None and not self.granted:
                return (b'<hierarchy><node package="com.android.permissioncontroller" '
                        b'text="Allow Usage Limits to send you notifications?" bounds="[88,683][631,796]"/></hierarchy>')
            return super().hierarchy()

    def test_the_seeded_list_is_reached_with_notifications_granted(self):
        device = self.Device()
        with TemporaryDirectory() as directory, patch.object(runtime_smoke.subprocess, "run", device.run), \
                patch.object(runtime_smoke, "time", device):
            state = Smoke(Path(directory), "test-device").seed_list("seeded", Path("app-debug.apk"))
        self.assertTrue(device.granted)
        self.assertEqual(6, len(state["overview"]))
        grant = next(i for i, call in enumerate(device.calls) if call[0][:3] == ("shell", "pm", "grant"))
        start = next(i for i, call in enumerate(device.calls) if call[0][:3] == ("shell", "am", "start"))
        self.assertLess(grant, start, "granted before the first launch")


class MotionCaptureTest(unittest.TestCase):
    """#81: the slow-motion frame capture is evidence: it fails nothing, except a scale it changed and left unknown."""

    MOTION = str(runtime_smoke.MOTION_SCALE)

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
                return f"priority=0\n{runtime_smoke.DEBUG_PACKAGE}/com.usagelimits.UsageLimitsActivity"
            return ""
        return state, adb

    def capture(self, device, write=None):
        """capture_motion on the fake device and its clock: what it returned or raised, and what it reported.

        The job summary it appended to is kept in `self.summary`.
        """
        with TemporaryDirectory() as directory, patch.object(runtime_smoke.subprocess, "run", device.run), \
                patch.object(runtime_smoke, "time", device), \
                patch.dict(os.environ, {"GITHUB_STEP_SUMMARY": str(Path(directory) / "summary.md")}):
            smoke = Smoke(Path(directory), "test-device")
            if write is not None:
                text = smoke.text
                smoke.text = lambda name, value: write(name, value) if name == "motion.json" else text(name, value)
            try:
                outcome = smoke.capture_motion("motion", runtime_smoke.DEBUG_PACKAGE)
            except BaseException as error:
                outcome = error
            report, summary = Path(directory) / "motion.json", Path(directory) / "summary.md"
            self.summary = summary.read_text() if summary.exists() else None
            return outcome, json.loads(report.read_text()) if report.exists() else None

    def assert_within_budget(self, device):
        """No call may be allowed to run past the capture's budget, nor the restore past its own allowance."""
        budget_end = device.started + runtime_smoke.MOTION_BUDGET_SECONDS
        restore = device.restores()[0]
        for command, timeout, started in device.calls[:restore]:
            self.assertLessEqual(started + timeout, budget_end + 1e-6, command)
        restore_end = device.calls[restore][2] + runtime_smoke.MOTION_RESTORE_SECONDS
        for command, timeout, started in device.calls[restore:]:
            self.assertLessEqual(started + timeout, restore_end + 1e-6, command)
        self.assertLessEqual(device.calls[restore][2], budget_end + 1e-6)
        self.assertLessEqual(device.now, budget_end + runtime_smoke.MOTION_RESTORE_SECONDS + 1e-6)

    def test_a_sequence_counts_its_distinct_frames(self):
        moving = runtime_smoke.motion_sequence("tab", "crossfade", [{"sha256": "a"}, {"sha256": "b"}, {"sha256": "b"}])
        self.assertEqual((2, True), (moving["distinctFrames"], moving["framesDiffer"]))
        still = runtime_smoke.motion_sequence("tab", "crossfade", [{"sha256": "a"}, {"sha256": "a"}])
        self.assertEqual((1, False), (still["distinctFrames"], still["framesDiffer"]))
        # Two whole-screen screenshots that differ in a byte are not a measurement of motion.
        self.assertNotIn("motionObserved", moving)

    def test_a_failed_capture_is_recorded_and_animations_are_turned_off_again(self):
        state, adb = self.device()
        with TemporaryDirectory() as directory:
            smoke = Smoke(Path(directory), "test-device")
            with patch.object(smoke, "adb", side_effect=adb), \
                    patch.object(smoke, "wait_for", side_effect=AssertionError("UI did not reach motion-launched")):
                self.assertEqual("capture failed", smoke.capture_motion("motion", runtime_smoke.DEBUG_PACKAGE))
            report = json.loads((Path(directory) / "motion.json").read_text())
        self.assertEqual([str(runtime_smoke.MOTION_SCALE), "0.0"], state["puts"])
        self.assertEqual("0.0", report["animatorScaleRestored"])
        self.assertIn("UI did not reach motion-launched", report["error"])
        # The launch frames taken before the failure are kept.
        self.assertEqual(["launch"], [sequence["name"] for sequence in report["sequences"]])
        self.assertFalse(report["sequences"][0]["framesDiffer"])

    def test_animations_left_on_fail_the_smoke(self):
        _, adb = self.device(scale_after_restore=str(runtime_smoke.MOTION_SCALE))
        with TemporaryDirectory() as directory:
            smoke = Smoke(Path(directory), "test-device")
            with patch.object(smoke, "adb", side_effect=adb), \
                    patch.object(smoke, "wait_for", side_effect=AssertionError("UI did not reach motion-launched")):
                with self.assertRaisesRegex(AssertionError, "not turned off again"):
                    smoke.capture_motion("motion", runtime_smoke.DEBUG_PACKAGE)

    def test_a_deadline_shortens_a_call_and_never_lengthens_it(self):
        device = FakeDevice()
        with TemporaryDirectory() as directory, patch.object(runtime_smoke.subprocess, "run", device.run), \
                patch.object(runtime_smoke, "time", device):
            smoke = Smoke(Path(directory), "test-device")
            smoke.adb("shell", "true")
            smoke.adb("shell", "true", timeout=5, deadline=device.now + 100)
            smoke.adb("shell", "true", deadline=device.now + 3)
            with self.assertRaises(runtime_smoke.BudgetExhausted):
                smoke.adb("shell", "true", deadline=device.now)
            # Without a deadline the retries pause a second each, as they always did.
            before = device.now
            with self.assertRaisesRegex(AssertionError, "UI did not reach never"):
                smoke.wait_for("never", lambda root: False)
            self.assertEqual(12 * (3 * 0.25 + 1), device.now - before)
        self.assertEqual([40, 5, 3] + [40] * 36, [timeout for _, timeout, _ in device.calls])

    def test_the_five_motions_are_captured_within_the_budget_and_animations_end_off(self):
        device = FakeDevice()
        outcome, report = self.capture(device)
        self.assertEqual("captured", outcome)
        self.assertEqual(["launch", "tab", "segment", "push", "pop"], [sequence["name"] for sequence in report["sequences"]])
        self.assertEqual((self.MOTION, "0.0", 1), (report["animatorScaleReadBack"], report["animatorScaleRestored"],
                                                  report["animatorScaleRestoreAttempts"]))
        self.assertEqual(("0.0", "Overview", "All"), (device.scale, device.screen, device.filter))
        self.assertNotIn("error", report)
        self.assertNotIn("reason", report)
        self.assert_within_budget(device)

    def test_a_set_command_that_times_out_after_android_applied_it_is_still_restored(self):
        device = FakeDevice()
        device.seconds = lambda command: float("inf") if command == device.SCALE + (self.MOTION,) else 0.25
        outcome, report = self.capture(device)
        self.assertEqual("capture failed", outcome)
        self.assertIn("TimeoutExpired", report["error"])
        # The device did take the value, so only the restore stands between it and the rest of the smoke.
        self.assertEqual([self.MOTION, "0.0"], [call[0][-1] for call in device.calls if call[0][:-1] == device.SCALE])
        self.assertEqual(("0.0", "0.0"), (device.scale, report["animatorScaleRestored"]))
        self.assertEqual((0, []), (device.frames, report["sequences"]))
        self.assert_within_budget(device)

    def test_a_capture_that_fails_before_it_asked_for_a_scale_does_not_touch_the_device_again(self):
        for case, scale in (("the device is fine", "0.0"), ("the device would not even answer a restore", None)):
            with self.subTest(case):
                device = FakeDevice()

                def before(command):
                    if "resolve-activity" in command or scale is None:
                        raise subprocess.CalledProcessError(1, command)
                device.before = before
                outcome, report = self.capture(device)
                # Nothing was changed, so nothing is owed: no restore, no read-back, and no failed job.
                self.assertEqual("capture failed", outcome)
                self.assertIn("CalledProcessError", report["error"])
                self.assertEqual(1, len(device.calls))
                self.assertNotIn("animatorScaleRestored", report)
                self.assertEqual("Motion capture (optional evidence): capture failed; " + report["error"] + "\n", self.summary)

    def test_a_report_that_cannot_be_written_does_not_fail_the_job(self):
        def full_disk(name, value):
            raise OSError(28, "No space left on device")
        device = FakeDevice()
        outcome, report = self.capture(device, write=full_disk)
        self.assertEqual("captured", outcome)
        self.assertIsNone(report)
        self.assertEqual("0.0", device.scale)
        self.assertIn("No space left on device", self.summary)

    def test_the_outcome_and_its_reason_go_to_the_job_summary(self):
        device = FakeDevice()
        self.assertEqual("captured", self.capture(device)[0])
        self.assertEqual("Motion capture (optional evidence): captured\n", self.summary)
        device = FakeDevice()
        device.scale = "null"
        device.before = lambda command: b"" if command == device.SCALE + (self.MOTION,) else None
        self.assertEqual("skipped", self.capture(device)[0])
        self.assertEqual("Motion capture (optional evidence): skipped; animator scale read back as 'null', not 10\n", self.summary)

    def test_a_ui_that_never_becomes_ready_or_a_call_that_hangs_ends_as_skipped_within_the_budget(self):
        dump = ("shell", "uiautomator", "dump")
        budget = runtime_smoke.MOTION_BUDGET_SECONDS

        def slow_start(command):
            """Three slow answers at the start leave less than a default timeout for a call that hangs later."""
            return "resolve-activity" in command or command[:2] == ("shell", "am")

        for case, seconds, tree, step, kept, launch_frames in (
            ("the tree never shows the target", lambda command: 20 if command[:3] == dump else 0.25,
             b"<hierarchy/>", "wait for launched", ["launch"], 24),
            ("uiautomator never answers", lambda command: float("inf") if command[:3] == dump else 0.25,
             None, "wait for launched", ["launch"], 24),
            ("screenshots crawl", lambda command: 40 if command[:2] == ("exec-out", "screencap") else 0.25,
             None, "launch frames", ["launch"], 3),
            ("a tap hangs late in the budget", lambda command: float("inf") if command[:3] == ("shell", "input", "tap")
             else 35 if slow_start(command) else 0.25, None, "tap Resets tab", ["launch"], 24),
            ("the back key hangs late in the budget", lambda command: float("inf") if command[-1] == "BACK"
             else 35 if slow_start(command) else 0.25, None, "pop trigger", ["launch", "tab", "segment", "push"], 24),
        ):
            with self.subTest(case):
                device = FakeDevice()
                device.seconds = seconds
                device.before = lambda command: tree if command[:2] == ("exec-out", "cat") else None
                outcome, report = self.capture(device)
                self.assertEqual("skipped", outcome)
                self.assertEqual(f"time budget of {budget} s exhausted at step {step}", report["reason"])
                # What was photographed before the budget ran out stays in the report.
                self.assertEqual(kept, [sequence["name"] for sequence in report["sequences"]])
                self.assertEqual(launch_frames, len(report["sequences"][0]["frames"]))
                self.assertEqual((self.MOTION, "0.0", "0.0"),
                                 (report["animatorScaleReadBack"], device.scale, report["animatorScaleRestored"]))
                self.assert_within_budget(device)
                # The budget was used up, and the restore still ran on its own allowance.
                self.assertGreaterEqual(device.calls[device.restores()[0]][2], device.started + budget)

    def test_a_scale_that_does_not_read_back_is_skipped_without_frames(self):
        for read_back in ("0.0", "null", "1.0"):
            with self.subTest(read_back=read_back):
                device = FakeDevice()
                device.scale = read_back
                device.before = lambda command: b"" if command == device.SCALE + (self.MOTION,) else None
                outcome, report = self.capture(device)
                self.assertEqual("skipped", outcome)
                self.assertEqual(f"animator scale read back as {read_back!r}, not {self.MOTION}", report["reason"])
                self.assertEqual((0, []), (device.frames, report["sequences"]))
                self.assertFalse([call for call in device.calls if call[0][:2] == ("shell", "am")])
                self.assertEqual(("0.0", "0.0"), (device.scale, report["animatorScaleRestored"]))

    def test_a_restore_that_fails_once_is_tried_again(self):
        restore = FakeDevice.SCALE + ("0.0",)
        read = ("shell", "settings", "get", "global", "animator_duration_scale")

        def fault(command, device):
            raise subprocess.CalledProcessError(1, command)

        for case, target, first, attempts in (
            ("the device ignores the first restore", restore, lambda command, device: b"", 2),
            ("the first restore command fails", restore, fault, 2),
            ("the first read-back fails", read, fault, 2),
            ("the restore is applied although adb times out", restore, None, 1),
        ):
            with self.subTest(case):
                device = FakeDevice()
                seen = []

                def before(command):
                    # Only once the capture is over: the same read is also what checks the motion scale.
                    if command == target and device.restores():
                        seen.append(command)
                        if first is not None and len(seen) == 1:
                            return first(command, device)

                device.before = before
                if first is None:
                    device.seconds = lambda command: float("inf") if command == restore else 0.25
                outcome, report = self.capture(device)
                self.assertEqual("captured", outcome)
                self.assertEqual(("0.0", "0.0", attempts), (device.scale, report["animatorScaleRestored"],
                                                            report["animatorScaleRestoreAttempts"]))
                self.assertNotIn("smokeStopped", report)
                self.assert_within_budget(device)

    def test_a_restore_that_never_reads_zero_stops_the_smoke(self):
        restore = FakeDevice.SCALE + ("0.0",)
        for case, read_back in (("the device keeps the motion scale", self.MOTION), ("adb stops answering", None)):
            with self.subTest(case):
                device = FakeDevice()
                if read_back is None:
                    device.seconds = lambda command: float("inf") if device.restores() else 0.25
                device.before = lambda command: b"" if command == restore else None
                outcome, report = self.capture(device)
                self.assertIsInstance(outcome, AssertionError)
                self.assertRegex(str(outcome), "not turned off again")
                # The capture itself went through; what stops the smoke is named in the report.
                self.assertEqual(("captured", read_back, 3), (report["result"], report["animatorScaleRestored"],
                                                              report["animatorScaleRestoreAttempts"]))
                self.assertEqual(3, len(device.restores()))
                self.assertIn("Animations were not turned off again", report["smokeStopped"])
                self.assertIn(report["smokeStopped"], self.summary)
                self.assert_within_budget(device)

    def test_an_interrupt_during_a_burst_restores_the_scale_and_propagates(self):
        device = FakeDevice()

        def before(command):
            if command[:2] == ("exec-out", "screencap") and device.frames == 2:
                raise KeyboardInterrupt
        device.before = before
        outcome, report = self.capture(device)
        self.assertIsInstance(outcome, KeyboardInterrupt)
        self.assertEqual(("0.0", "0.0"), (device.scale, report["animatorScaleRestored"]))
        self.assertEqual("capture failed", report["result"])
        self.assertIn("KeyboardInterrupt", report["error"])
        self.assertEqual([("launch", 2)], [(sequence["name"], len(sequence["frames"])) for sequence in report["sequences"]])


class ThemeLaunchTest(unittest.TestCase):
    """09a: neither a settled matching frame nor one correctly styled bar proves a themed launch."""

    @staticmethod
    def window(phase="system", dark=True, navigation_dark=None):
        navigation_dark = dark if navigation_dark is None else navigation_dark
        title = "Splash Screen com.usagelimits" if phase == "system" else "com.usagelimits/com.usagelimits.UsageLimitsActivity"
        owner = "Window{abc u0 " + title + "}"
        return f"""WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)
  Display: mDisplayId=0 (organized)
  DisplayPolicy
    mTopFullscreenOpaqueWindowState={owner}
    mNavBarColorWindowCandidate={owner}
    mLastAppearance={'LIGHT_NAVIGATION_BARS' if navigation_dark else 'OPAQUE_NAVIGATION_BARS'}
    mLastStatusBarAppearanceRegions=
      AppearanceRegion{{{'LIGHT_STATUS_BARS' if dark else ''} bounds=[0,0][100,200]}}
WINDOW MANAGER TOKENS (dumpsys window tokens)
WINDOW MANAGER WINDOWS (dumpsys window windows)
  Window #0 {owner}:
    mDisplayId=0
    mAttrs={{ty={'APPLICATION_STARTING' if phase == 'system' else 'BASE_APPLICATION'}}}
    mViewVisibility=0x0 mHaveFrame=true mObscured=false
    mHasSurface=true isReadyForDisplay()=true mWindowRemovalAllowed=false
    mDrawState=HAS_DRAWN
    isOnScreen=true
    isVisible=true
  mCurrentFocus={owner}
"""

    @staticmethod
    def captured_window(dark=False):
        # Run 44939973373, artifact 14721373919: the two selected DisplayPolicy/MainActivity dumps,
        # with unrelated display and window fields removed. Pure black omits the zero appearance.
        owner = "Window{" + ("1930eae" if dark else "e4068a") + " u0 com.usagelimits/com.usagelimits.MainActivity}"
        appearance = "    mLastAppearance=LIGHT_STATUS_BARS LIGHT_NAVIGATION_BARS\n" if dark else ""
        return f"""WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)
  Display: mDisplayId=0 (organized)
  DisplayPolicy
    mCarDockEnablesAccelerometer=true mDeskDockEnablesAccelerometer=true
    mDockMode=EXTRA_DOCK_STATE_UNDOCKED mLidState=LID_OPEN
    mAwake=true mScreenOnEarly=true mScreenOnFully=true
    mKeyguardDrawComplete=true mWindowManagerDrawComplete=true
    mHdmiPlugged=false
{appearance}    mLastBehavior=DEFAULT
    mShowingDream=false mDreamingLockscreen=false
    mStatusBar=Window{{a53cf43 u0 StatusBar}}
    mExpandedPanel=Window{{f56cc52 u0 NotificationShade}}
    isKeyguardShowing=false
    mNavigationBar=Window{{14bc325 u0 Taskbar}}
    mNavBarOpacityMode=0
    mNavigationBarCanMove=true
    mHasBottomNavigationBar=true
    mTopGestureHost=Window{{a53cf43 u0 StatusBar}}
    mBottomGestureHost=Window{{14bc325 u0 Taskbar}}
    mFocusedWindow={owner}
    mTopFullscreenOpaqueWindowState={owner}
    mSystemUiControllingWindow={owner}
    mNavBarColorWindowCandidate={owner}
    mNavBarBackgroundWindowCandidate={owner}
    mLastStatusBarAppearanceRegions=
      AppearanceRegion{{{'LIGHT_STATUS_BARS' if dark else ''} bounds=[0,0][720,1560]}}
    mLastLetterboxDetails=
    mStatusBarBackgroundWindows=
      {owner}
    mImeInsetsConsumed=false
    mForceShowNavigationBarEnabled=false mAllowLockscreenWhenOn=false
    mAllowsSystemBarRemoteInsetsController=false
    mDecorInsetsInfo:
      ROTATION_0={{overrideNonDecorInsets=[0,144][0,84], overrideConfigInsets=[0,144][0,84], overrideNonDecorFrame=[0,144][720,1476], overrideConfigFrame=[0,144][720,1476]}}
  DisplayRotation
    mRotation=0 mDeferredRotationPauseCount=0
WINDOW MANAGER TOKENS (dumpsys window tokens)
WINDOW MANAGER WINDOWS (dumpsys window windows)
  Window #7 {owner}:
    mDisplayId=0
    mOwnerUid=10156 showForAllUsers=false package=com.usagelimits appop=NONE
    mAttrs={{(0,0)(fillxfill) sim={{adjust=pan}} layoutInDisplayCutoutMode=always ty=BASE_APPLICATION wanim=0x103030d
      fl=LAYOUT_IN_SCREEN LAYOUT_INSET_DECOR SPLIT_TOUCH HARDWARE_ACCELERATED DRAWS_SYSTEM_BAR_BACKGROUNDS
      pfl=NO_MOVE_ANIMATION EDGE_TO_EDGE_ENFORCED FIT_INSETS_CONTROLLED
      bhv=DEFAULT}}
    mViewVisibility=0x0 mHaveFrame=true mObscured=false
    mHasSurface=true isReadyForDisplay()=true mWindowRemovalAllowed=false
      Surface: shown=true      mDrawState=HAS_DRAWN       mLastHidden=false
    isOnScreen=true
    isVisible=true
  mCurrentFocus={owner}
"""

    @staticmethod
    def pixels(ground=(243, 240, 232), artwork=False, settled=False):
        def pixel(x, y):
            if artwork and 44 <= x < 56 and 85 <= y < 115:
                return bytes((100, 80, 30, 255))
            if settled and 10 <= x < 90 and 140 <= y < 160:
                return bytes((100, 80, 30, 255))
            return bytes((*ground, 255))
        return png_bytes(100, 200, 4, [b"".join(pixel(x, y) for x in range(100)) for y in range(200)])

    def test_system_and_app_frames_measure_distinct_evidence_and_both_bars(self):
        for ground, dark in (((243, 240, 232), True), ((0, 0, 0), False)):
            with self.subTest(ground=ground):
                for phase in ("system", "app"):
                    window = self.window(phase, dark)
                    measured = runtime_smoke.theme_launch_frame(
                        self.pixels(ground, artwork=phase == "app"), ground, window, window, dark)
                    self.assertTrue(measured[phase + "Evidence"], measured)
                    self.assertTrue(measured["barsCorrect"])
                    self.assertEqual(dark, measured["before"]["statusDarkIcons"])
                    self.assertEqual(dark, measured["before"]["navigationDarkIcons"])

    def test_historical_anr_does_not_replace_or_duplicate_live_windows(self):
        live = self.window()
        history = live.split("WINDOW MANAGER WINDOWS (dumpsys window windows)\n", 1)[1]
        dump = "WINDOW MANAGER LAST ANR (dumpsys window lastanr)\n" + history + live
        self.assertEqual(runtime_smoke.launch_window_state(live), runtime_smoke.launch_window_state(dump))

    def test_handoff_matches_the_system_ground_then_the_drawn_app_overlay(self):
        splash, app = self.window(), self.window("app")
        app_window = app[app.index("  Window #0 "):app.index("  mCurrentFocus=")]
        overlap = splash.replace("  mCurrentFocus=", app_window.replace("Window #0", "Window #1") + "  mCurrentFocus=")
        ground = (243, 240, 232)
        bare, icon = self.pixels(ground), self.pixels(ground, artwork=True)
        self.assertIsNone(runtime_smoke.launch_window_state(overlap)["phase"])
        self.assertTrue(runtime_smoke.theme_launch_frame(bare, ground, splash, overlap, True)["systemEvidence"])
        self.assertTrue(runtime_smoke.theme_launch_frame(bare, ground, overlap, overlap, True)["systemEvidence"])
        self.assertFalse(runtime_smoke.theme_launch_frame(bare, ground, overlap, app, True)["systemEvidence"])
        app_policy = overlap.replace(
            splash[splash.index("    mTopFullscreen"):splash.index("    mLastAppearance")],
            app[app.index("    mTopFullscreen"):app.index("    mLastAppearance")],
        )
        self.assertFalse(runtime_smoke.theme_launch_frame(bare, ground, app_policy, app_policy, True)["systemEvidence"])
        self.assertFalse(runtime_smoke.theme_launch_frame(icon, ground, splash, overlap, True)["systemEvidence"])
        self.assertFalse(runtime_smoke.theme_launch_frame(self.pixels((15, 15, 14)), ground,
            overlap, overlap, True)["systemEvidence"])
        self.assertTrue(runtime_smoke.theme_launch_frame(icon, ground, overlap, overlap, True)["appEvidence"])
        self.assertTrue(runtime_smoke.theme_launch_frame(icon, ground, overlap, app, True)["appEvidence"])
        self.assertFalse(runtime_smoke.theme_launch_frame(icon, ground, overlap,
            app.replace("Window{abc u0", "Window{def u0"), True)["appEvidence"])
        self.assertFalse(runtime_smoke.theme_launch_frame(icon, ground, overlap,
            overlap.replace("mLastAppearance=LIGHT_NAVIGATION_BARS", "mLastAppearance=OPAQUE_NAVIGATION_BARS"), True)["appEvidence"])
        self.assertFalse(runtime_smoke.theme_launch_frame(icon, ground, overlap,
            overlap.replace("ty=BASE_APPLICATION", "ty=APPLICATION_OVERLAY"), True)["appEvidence"])

    def test_wrong_ground_missing_artwork_settled_ui_or_wrong_phase_cannot_pass(self):
        cases = [
            (self.pixels((15, 15, 14)), self.window(), self.window(), "systemEvidence"),
            (self.pixels(), self.window("app"), self.window("app"), "systemEvidence"),
            (self.pixels(artwork=True), self.window(), self.window(), "systemEvidence"),
            (self.pixels(), self.window("app"), self.window("app"), "appEvidence"),
            (self.pixels(artwork=True, settled=True), self.window("app"), self.window("app"), "appEvidence"),
            (self.pixels(artwork=True), self.window(), self.window("app"), "appEvidence"),
        ]
        for pixels, before, after, key in cases:
            with self.subTest(key=key, before=before):
                result = runtime_smoke.theme_launch_frame(pixels, (243, 240, 232), before, after, True)
                self.assertFalse(result[key], result)
                self.assertIn("groundFraction", result)
                self.assertIn("artworkPixels", result)

    def test_mixed_status_regions_are_unknown_not_light_icons_and_combined_flags_parse(self):
        dark = self.window().replace("LIGHT_NAVIGATION_BARS", "OPAQUE_NAVIGATION_BARS LIGHT_NAVIGATION_BARS")
        dark = dark.replace("LIGHT_STATUS_BARS bounds", "OPAQUE_STATUS_BARS LIGHT_STATUS_BARS bounds")
        self.assertTrue(runtime_smoke.theme_launch_frame(self.pixels(), (243, 240, 232), dark, dark, True)["systemEvidence"])
        mixed = self.window(dark=False).replace("bounds=[0,0][100,200]}",
            "bounds=[0,0][50,200]}\n      AppearanceRegion{LIGHT_STATUS_BARS bounds=[50,0][100,200]}")
        self.assertIsNone(runtime_smoke.launch_window_state(mixed)["statusDarkIcons"])
        self.assertFalse(runtime_smoke.theme_launch_frame(self.pixels((0, 0, 0)), (0, 0, 0), mixed, mixed, False)["systemEvidence"])

    def test_captured_zero_policy_decodes_status_and_navigation_independently(self):
        zero = self.captured_window()
        for window, status, navigation in (
            (zero, False, False),
            (zero.replace("AppearanceRegion{ bounds=", "AppearanceRegion{LIGHT_STATUS_BARS bounds="), True, False),
            (zero.replace("    mLastBehavior=", "    mLastAppearance=LIGHT_NAVIGATION_BARS\n    mLastBehavior="), False, True),
        ):
            with self.subTest(status=status, navigation=navigation):
                state = runtime_smoke.launch_window_state(window)
                self.assertTrue(state["appearanceKnown"])
                self.assertIs(status, state["statusDarkIcons"])
                self.assertIs(navigation, state["navigationDarkIcons"])
                measured = runtime_smoke.theme_launch_frame(
                    self.pixels((0, 0, 0), artwork=True), (0, 0, 0), window, window, False)
                self.assertEqual(not status and not navigation, measured["appEvidence"])

    def test_missing_foreign_duplicate_or_truncated_zero_policy_stays_unknown(self):
        zero = self.captured_window()
        for window in (
            "",
            zero.replace("mDisplayId=0", "mDisplayId=1"),
            zero.replace("com.usagelimits", "com.example.foreign"),
            zero.replace("  DisplayPolicy\n", "  MissingPolicy\n"),
            zero.replace("    mHdmiPlugged=false\n", ""),
            zero.replace("    mDecorInsetsInfo:\n", ""),
            zero.replace("mLastStatusBarAppearanceRegions=", "missingRegions="),
            zero.replace("    mDecorInsetsInfo:\n", "  DisplayPolicy\n    mDecorInsetsInfo:\n"),
            zero.partition("    mDecorInsetsInfo:")[0],
        ):
            with self.subTest(window=window):
                state = runtime_smoke.launch_window_state(window)
                self.assertFalse(state["appearanceKnown"])
                self.assertIsNone(state["statusDarkIcons"])
                self.assertIsNone(state["navigationDarkIcons"])

    def test_another_display_or_neighbouring_section_cannot_supply_missing_appearance(self):
        zero = self.captured_window()
        for extra in ("  Display: mDisplayId=1 (organized)\n  DisplayPolicy\n    mLastAppearance=LIGHT_NAVIGATION_BARS\n",
                      "  DisplayRotation\n    mLastAppearance=LIGHT_NAVIGATION_BARS\n"):
            window = zero.replace("WINDOW MANAGER TOKENS", extra + "WINDOW MANAGER TOKENS")
            state = runtime_smoke.launch_window_state(window)
            self.assertTrue(state["appearanceKnown"])
            self.assertIs(False, state["navigationDarkIcons"])

    def test_missing_navigation_appearance_is_unknown_even_on_pure_black(self):
        window = self.window(dark=False).replace("    mLastAppearance=OPAQUE_NAVIGATION_BARS\n", "")
        state = runtime_smoke.launch_window_state(window)
        self.assertIsNone(state["navigationDarkIcons"])
        self.assertFalse(state["appearanceKnown"])
        measured = runtime_smoke.theme_launch_frame(self.pixels((0, 0, 0)), (0, 0, 0), window, window, False)
        self.assertFalse(measured["barsCorrect"])
        self.assertFalse(measured["systemEvidence"])

    def test_each_bar_and_unproven_foreground_fail_independently(self):
        for window in (self.window(dark=False, navigation_dark=True),
                       self.window(dark=True, navigation_dark=False),
                       self.window().replace("mHasSurface=true", "mHasSurface=false"),
                       self.window().replace("mObscured=false", "mObscured=true"),
                       self.window().replace("mLastStatusBarAppearanceRegions=", "missingRegions="),
                       self.window().replace("com.usagelimits", "com.example.foreign")):
            with self.subTest(window=window):
                result = runtime_smoke.theme_launch_frame(self.pixels(), (243, 240, 232), window, window, True)
                self.assertFalse(result["systemEvidence"], result)


class ThemeLaunchCaptureTest(unittest.TestCase):
    def capture(self, transform=lambda phase, data, window: (data, window), fail_start=False, fail_capture=False):
        events, frame_number = [], 0
        clock = FakeDevice()
        with TemporaryDirectory() as directory:
            smoke = Smoke(Path(directory), "test-device")
            def phase():
                return "system" if frame_number < 3 else "app"
            def adb(*args, **kwargs):
                nonlocal frame_number
                clock.now += 0.25
                events.append(args)
                if args[:2] == ("exec-out", "screencap"):
                    if fail_capture:
                        raise subprocess.CalledProcessError(1, args)
                    frame_number += 1
                    data = ThemeLaunchTest.pixels(artwork=phase() == "app")
                    return transform(phase(), data, ThemeLaunchTest.window(phase()))[0]
                if args == ("shell", "dumpsys", "window", "-a"):
                    return transform(phase(), b"", ThemeLaunchTest.window(phase()))[1]
                return ""
            class Start:
                returncode = 1 if fail_start else 0
                def communicate(self, timeout):
                    events.append(("joined",))
                    return b"Starting: Intent", b"failed" if fail_start else b""
                def kill(self):
                    events.append(("killed",))
            def popen(*args, **kwargs):
                events.append(("start", args[0]))
                return Start()
            with patch.object(smoke, "adb", side_effect=adb), \
                    patch.object(runtime_smoke.subprocess, "Popen", side_effect=popen), \
                    patch.object(runtime_smoke, "time", clock):
                try:
                    result = smoke.capture_theme_launch("theme", "com.usagelimits/.Alias", (243, 240, 232), True)
                except Exception as error:
                    result = error
            report = json.loads((Path(directory) / "theme.json").read_text())
            self.assertTrue(all((Path(directory) / item["file"]).exists() for item in report["frames"]))
            return result, report, events

    def test_sampling_is_armed_before_async_start_and_both_phases_are_mandatory(self):
        result, report, events = self.capture()
        self.assertEqual("passed", report["result"], result)
        self.assertEqual("passed", result)
        self.assertEqual(("shell", "dumpsys", "window", "-a"), events[0])
        self.assertEqual("start", events[1][0])
        self.assertNotIn("-W", events[1][1])
        self.assertEqual(("exec-out", "screencap", "-p"), events[2])
        self.assertLess(events.index(events[2]), events.index(("joined",)))
        self.assertTrue(report["systemFrames"])
        self.assertTrue(report["appFrames"])
        self.assertEqual({"status": "dark", "navigation": "dark"}, report["expectedIcons"])
        self.assertTrue(all("measurement" in sample for sample in report["frames"]))

    def test_wrong_theme_missing_phases_either_wrong_bar_and_failed_start_fail_with_measurements(self):
        cases = {
            "wrong theme": lambda phase, data, window: (ThemeLaunchTest.pixels((15, 15, 14), artwork=phase == "app"), window),
            "only settled app": lambda phase, data, window: (ThemeLaunchTest.pixels(settled=True), ThemeLaunchTest.window("app")),
            "no app artwork": lambda phase, data, window: (ThemeLaunchTest.pixels(), window),
            "no system window": lambda phase, data, window: (data, ThemeLaunchTest.window("app")),
            "only system": lambda phase, data, window: (ThemeLaunchTest.pixels(), ThemeLaunchTest.window()),
            "reversed phases": lambda phase, data, window: (
                ThemeLaunchTest.pixels(artwork=phase == "system"),
                ThemeLaunchTest.window("app" if phase == "system" else "system")),
            "wrong status": lambda phase, data, window: (data, ThemeLaunchTest.window(phase, dark=False, navigation_dark=True)),
            "wrong navigation": lambda phase, data, window: (data, ThemeLaunchTest.window(phase, dark=True, navigation_dark=False)),
        }
        for label, transform in cases.items():
            with self.subTest(label=label):
                result, report, events = self.capture(transform)
                self.assertIsInstance(result, AssertionError)
                self.assertEqual("failed", report["result"])
                self.assertRegex(report["error"], "Missing themed|Wrong themed launch frame")
                self.assertTrue(report["frames"])
                self.assertTrue(all("measurement" in sample for sample in report["frames"]))
                self.assertIn(("joined",), events)
        seen = []
        def flash(phase, data, window):
            if data:
                seen.append(phase)
                if len(seen) == 1:
                    data = ThemeLaunchTest.pixels((0, 0, 0))
            return data, window
        result, report, _ = self.capture(flash)
        self.assertIsInstance(result, AssertionError)
        self.assertIn("Wrong themed launch frame", report["error"])
        self.assertTrue(report["systemFrames"], "A later fitting system frame must not excuse the wrong first frame")
        result, report, _ = self.capture(fail_start=True)
        self.assertIsInstance(result, AssertionError)
        self.assertIn("Launch command failed", report["error"])
        self.assertTrue(report["frames"])
        result, report, events = self.capture(fail_capture=True)
        self.assertIsInstance(result, subprocess.CalledProcessError)
        self.assertEqual("failed", report["result"])
        self.assertEqual([], report["frames"])
        self.assertIn(("joined",), events)


class ThemeSelectionTest(unittest.TestCase):
    @staticmethod
    def picker(label, selected):
        # UIAutomator reports a selected Compose radio as checked but not clickable/selected.
        return ET.fromstring(f'<hierarchy><node package="com.usagelimits" enabled="true" checkable="true" '
            f'clickable="{str(not selected).lower()}" checked="{str(selected).lower()}" selected="false" bounds="[20,200][180,300]">'
            f'<node package="com.usagelimits" text="{label}"/></node></hierarchy>')

    def test_exact_ui_labels_select_wait_stop_capture_and_reach_existing_setting_for_both_themes(self):
        events, current = [], {"label": None, "selected": False}
        with TemporaryDirectory() as directory:
            smoke = Smoke(Path(directory), "test-device")
            def navigate(tab, marker, name):
                events.append(("navigate", tab, marker))
                if not name.endswith("-relaunch-settings"):
                    current.update(label="Light paper" if current["label"] is None else "Pure black", selected=False)
            def tap(node, **kwargs):
                current["selected"] = True
                events.append(("tap", current["label"]))
            def wait(name, predicate, **kwargs):
                root = (OverviewReadyTest.overview("Nothing to monitor yet") if name.endswith("-overview")
                        else self.picker(current["label"], current["selected"]))
                self.assertTrue(predicate(root))
                events.append(("selected", current["label"]))
                return root
            def adb(*args, **kwargs):
                events.append(args)
                if "resolve-activity" in args:
                    return "com.usagelimits/.ExistingAlias"
                if args == ("shell", "dumpsys", "window", "-a"):
                    return ThemeLaunchTest.window("app", dark=current["label"] == "Light paper")
                if args[:3] == ("shell", "settings", "get"):
                    return "10"
                return ""
            def capture(name, activity, ground, dark):
                events.append(("capture", current["label"], activity, ground, dark))
                return "passed"
            with patch.object(smoke, "navigate", side_effect=navigate), \
                    patch.object(smoke, "dump", side_effect=lambda *a: self.picker(current["label"], current["selected"])), \
                    patch.object(smoke, "tap", side_effect=tap), patch.object(smoke, "wait_for", side_effect=wait), \
                    patch.object(smoke, "adb", side_effect=adb), patch.object(smoke, "capture_theme_launch", side_effect=capture), \
                    patch.object(smoke, "setting_is_disabled", side_effect=lambda *a: events.append(("setting", current["label"]))), \
                    patch.object(smoke, "capture"), patch.object(smoke, "widget_home"), \
                    patch.object(smoke, "restore_animations", return_value=True):
                self.assertEqual(["appearance-light-paper.json", "appearance-pure-black.json"], smoke.verify_theme_launches())
        self.assertEqual([("navigate", "Settings", "Appearance")] * 4,
                         [event for event in events if event[0] == "navigate"])
        captures = [event for event in events if event[0] == "capture"]
        self.assertEqual([
            ("capture", "Light paper", "com.usagelimits/.ExistingAlias", (243, 240, 232), True),
            ("capture", "Pure black", "com.usagelimits/.ExistingAlias", (0, 0, 0), False),
        ], captures)
        for label in ("Light paper", "Pure black"):
            tap = events.index(("tap", label))
            selected = events.index(("selected", label))
            captured = next(i for i, event in enumerate(events) if event[:2] == ("capture", label))
            stopped = next(i for i in range(selected, captured) if events[i] == ("shell", "am", "force-stop", "com.usagelimits"))
            bars = next(i for i in range(selected, stopped) if events[i] == ("shell", "dumpsys", "window", "-a"))
            self.assertLess(tap, selected)
            self.assertLess(selected, bars)
            self.assertLess(bars, stopped)
            self.assertLess(captured, events.index(("setting", label)))

    def test_foreground_close_handoff_cannot_consume_the_system_launch_phase(self):
        # Run 44894441451: old Settings, Home with DRAW_PENDING windows, then drawn overlay;
        # no bare system pixels. Run 44888487707 captured bare ground before the same handoff.
        clock = FakeDevice()
        current = {"label": None, "selected": False, "home": False, "homeDumps": 0,
                   "launching": False, "frame": -1, "stoppedFromHome": False}
        launcher = "com.android.launcher3"
        home = ET.fromstring(f'<hierarchy><node package="{launcher}" '
            f'resource-id="{launcher}:id/workspace"/></hierarchy>')

        def navigate(tab, marker, name):
            current.update(home=False, launching=False)
            if not name.endswith("-relaunch-settings"):
                current.update(label="Light paper" if current["label"] is None else "Pure black", selected=True)

        def dump(name, **kwargs):
            if name.endswith("-overview"):
                return OverviewReadyTest.overview("Nothing to monitor yet")
            if current["home"]:
                current["homeDumps"] += 1
                if current["homeDumps"] >= 2:
                    return home
            return self.picker(current["label"], current["selected"])

        def windows():
            dark = current["label"] == "Light paper"
            splash, app = ThemeLaunchTest.window(dark=dark), ThemeLaunchTest.window("app", dark=dark)
            app_window = app[app.index("  Window #0 "):app.index("  mCurrentFocus=")]
            overlap = splash.replace("  mCurrentFocus=",
                app_window.replace("Window #0", "Window #1") + "  mCurrentFocus=")
            if not current["launching"]:
                return app.replace("com.usagelimits", launcher) if current["home"] else app
            if current["stoppedFromHome"]:
                return splash if current["frame"] < 2 else overlap
            if current["frame"] <= 0:
                return overlap.replace("isReadyForDisplay()=true", "isReadyForDisplay()=false").replace(
                    "mDrawState=HAS_DRAWN", "mDrawState=DRAW_PENDING")
            return overlap

        def adb(*args, **kwargs):
            clock.now += 0.25
            if args == ("shell", "input", "keyevent", "KEYCODE_HOME"):
                current.update(home=True, homeDumps=0)
            elif args == ("shell", "am", "force-stop", runtime_smoke.PACKAGE):
                current["stoppedFromHome"] = current["home"] and current["homeDumps"] >= 2
            elif "resolve-activity" in args:
                return "com.usagelimits/.MainActivity"
            elif args[:3] == ("shell", "settings", "get"):
                return "10"
            elif args == ("shell", "dumpsys", "window", "-a"):
                return windows()
            elif args[:2] == ("exec-out", "screencap"):
                ground = (243, 240, 232) if current["label"] == "Light paper" else (0, 0, 0)
                if current["launching"]:
                    current["frame"] += 1
                    if current["stoppedFromHome"]:
                        return ThemeLaunchTest.pixels(ground, artwork=current["frame"] >= 2)
                    if current["frame"] == 0:
                        return ThemeLaunchTest.pixels(ground, settled=True)
                    if current["frame"] == 1:
                        return ThemeLaunchTest.pixels((60, 70, 90))
                    return ThemeLaunchTest.pixels(ground, artwork=True)
                return ThemeLaunchTest.pixels(ground)
            return ""

        class Start:
            returncode = 0
            def communicate(self, timeout):
                return b"Starting: Intent", b""

        def popen(*args, **kwargs):
            current.update(launching=True, frame=-1)
            return Start()

        with TemporaryDirectory() as directory:
            smoke = Smoke(Path(directory), "test-device")
            smoke.launcher = launcher
            with patch.object(smoke, "navigate", side_effect=navigate), \
                    patch.object(smoke, "dump", side_effect=dump), patch.object(smoke, "adb", side_effect=adb), \
                    patch.object(smoke, "setting_is_disabled"), patch.object(smoke, "capture"), \
                    patch.object(smoke, "restore_animations", return_value=True), \
                    patch.object(runtime_smoke.subprocess, "Popen", side_effect=popen), \
                    patch.object(runtime_smoke, "time", clock):
                reports = smoke.verify_theme_launches()
            for name in reports:
                report = json.loads((Path(directory) / name).read_text())
                self.assertEqual("passed", report["result"], report.get("error"))
                self.assertTrue(report["systemFrames"])
                self.assertTrue(report["appFrames"])
                self.assertEqual([], report["wrongFrames"])

    def test_api_37_zero_appearance_reaches_both_launches_through_real_selection(self):
        self.replay_captured_selection()

    def test_captured_selection_rejects_each_wrong_bar_and_unknown_policy(self):
        for name, transform in {
            "status": lambda text: text.replace("AppearanceRegion{ bounds=", "AppearanceRegion{LIGHT_STATUS_BARS bounds="),
            "navigation": lambda text: text.replace("    mLastBehavior=", "    mLastAppearance=LIGHT_NAVIGATION_BARS\n    mLastBehavior="),
            "missing display": lambda text: text.replace("mDisplayId=0", "mDisplayId=1"),
            "foreign owner": lambda text: text.replace("com.usagelimits", "com.example.foreign"),
            "missing region": lambda text: text.replace("mLastStatusBarAppearanceRegions=", "missingRegions="),
            "incomplete policy": lambda text: text.replace("    mDecorInsetsInfo:", "    missingDecorInsetsInfo:"),
        }.items():
            with self.subTest(name=name):
                self.replay_captured_selection(transform, rejected=True)

    def replay_captured_selection(self, transform=lambda text: text, rejected=False):
        clock = FakeDevice()
        current = {"label": None, "selected": False, "launching": False, "frame": -1}
        launches, taps, stopped = [], [], []

        def navigate(tab, marker, name):
            current["launching"] = False
            if not name.endswith("-relaunch-settings"):
                current.update(label="Light paper" if current["label"] is None else "Pure black", selected=False)

        def answer(command):
            if command[:2] == ("exec-out", "cat"):
                if current["launching"]:
                    return ET.tostring(OverviewReadyTest.overview("Nothing to monitor yet"))
                root = self.picker(current["label"], current["selected"])
                # The actual Pure black tile is fully visible at [374,635][657,719].
                root[0].set("bounds", "[374,635][657,719]")
                return ET.tostring(root)
            if command[:3] == ("shell", "input", "tap"):
                taps.append((current["label"], command[3:]))
                current["selected"] = True
            elif command == ("shell", "dumpsys", "window", "-a"):
                text = ThemeLaunchTest.captured_window(current["label"] == "Light paper")
                if current["launching"] and current["frame"] < 2:
                    text = text.replace("com.usagelimits/com.usagelimits.MainActivity", "Splash Screen com.usagelimits").replace(
                        "ty=BASE_APPLICATION", "ty=APPLICATION_STARTING")
                return (transform(text) if current["label"] == "Pure black" else text).encode()
            elif command[:2] == ("exec-out", "screencap"):
                current["frame"] += 1
                ground = (243, 240, 232) if current["label"] == "Light paper" else (0, 0, 0)
                return ThemeLaunchTest.pixels(ground, artwork=current["frame"] >= 2)
            elif "resolve-activity" in command:
                return b"com.usagelimits/.MainActivity"
            elif command[:-1] == clock.SCALE:
                clock.scale = command[-1]
            elif command[:3] == ("shell", "settings", "get"):
                return clock.scale.encode()
            elif command == ("shell", "am", "force-stop", runtime_smoke.PACKAGE):
                stopped.append(current["label"])
            return b""

        class Start:
            returncode = 0
            def communicate(self, timeout):
                return b"Starting: Intent", b""

        def popen(*args, **kwargs):
            launches.append(current["label"])
            current.update(launching=True, frame=-1)
            return Start()

        clock.answer = answer
        with TemporaryDirectory() as directory:
            smoke = Smoke(Path(directory), "test-device")
            with patch.object(smoke, "navigate", side_effect=navigate), patch.object(smoke, "widget_home"), \
                    patch.object(smoke, "setting_is_disabled"), patch.object(smoke, "capture"), \
                    patch.object(runtime_smoke.subprocess, "run", side_effect=clock.run), \
                    patch.object(runtime_smoke.subprocess, "Popen", side_effect=popen), \
                    patch.object(runtime_smoke, "time", clock):
                if rejected:
                    with self.assertRaisesRegex(AssertionError, "Selected Pure black did not apply both bar styles"):
                        smoke.verify_theme_launches()
                else:
                    self.assertEqual(["appearance-light-paper.json", "appearance-pure-black.json"], smoke.verify_theme_launches())
            self.assertEqual([("Light paper", ("515", "677")), ("Pure black", ("515", "677"))], taps)
            self.assertEqual(["Light paper"] if rejected else ["Light paper", "Pure black"], stopped)
            self.assertEqual(stopped, launches)
            selection = json.loads((Path(directory) / "appearance-pure-black-selection.json").read_text())
            self.assertEqual(12 if rejected else 1, len(selection["attempts"]))
            if not rejected:
                self.assertTrue(selection["attempts"][0]["appearanceKnown"])
                self.assertIs(False, selection["attempts"][0]["statusDarkIcons"])
                self.assertIs(False, selection["attempts"][0]["navigationDarkIcons"])
                report = json.loads((Path(directory) / "appearance-pure-black.json").read_text())
                self.assertEqual("passed", report["result"])
                self.assertTrue(report["systemFrames"])
                self.assertTrue(report["appFrames"])
                self.assertEqual([], report["wrongFrames"])

    def test_wrong_case_label_or_bar_style_stops_before_force_stop(self):
        for label, window, message in (("Light Paper", ThemeLaunchTest.window("app"), "Missing exact theme UI label"),
                                       ("Light paper", ThemeLaunchTest.window("app", dark=False), "did not apply both bar styles")):
            with self.subTest(label=label), TemporaryDirectory() as directory:
                smoke = Smoke(Path(directory), "test-device")
                tree = self.picker(label, False)
                with patch.object(smoke, "navigate"), patch.object(smoke, "dump", return_value=tree), \
                        patch.object(smoke, "tap"), patch.object(smoke, "wait_for"), \
                        patch.object(smoke, "adb", return_value=window) as adb, patch.object(runtime_smoke.time, "sleep"):
                    with self.assertRaisesRegex(AssertionError, message):
                        smoke.verify_theme_launches()
                self.assertFalse(any(call.args[:3] == ("shell", "am", "force-stop") for call in adb.call_args_list))


class FontOptionTest(unittest.TestCase):
    @staticmethod
    def picker(label, checked="true", clickable="false"):
        root = ET.fromstring(f'<hierarchy><node package="com.usagelimits" enabled="true" '
            f'checkable="true" checked="{checked}" clickable="{clickable}" bounds="[20,200][680,300]">'
            '<node package="com.usagelimits" text=""/></node></hierarchy>')
        root[0][0].set("text", label)
        return root

    def test_exact_font_name_and_merged_decorative_sample_find_the_radio_parent(self):
        for label in ("Figtree", "61%\nFigtree", "Figtree\n61%"):
            for checked, clickable in (("true", "false"), ("false", "true")):
                with self.subTest(label=label, checked=checked):
                    root = self.picker(label, checked, clickable)
                    self.assertIs(root[0], Smoke.font_option(root, "Figtree"))
                    self.assertEqual(checked == "true", Smoke.selected(Smoke.font_option(root, "Figtree")))

    def test_wrong_name_package_disabled_or_non_radio_cannot_confirm_a_font(self):
        for label in ("figtree", "Figtree Extra", "61%"):
            self.assertIsNone(Smoke.font_option(self.picker(label), "Figtree"))
        for attribute, value in (("package", "com.example.other"), ("enabled", "false"),
                                 ("checkable", "false"), ("bounds", "[20,200][20,200]")):
            with self.subTest(attribute=attribute):
                root = self.picker("Figtree")
                root[0].set(attribute, value)
                self.assertIsNone(Smoke.font_option(root, "Figtree"))

    def test_duplicate_matching_radios_fail_closed(self):
        root = self.picker("IBM Plex Mono")
        duplicate = ET.fromstring(ET.tostring(root[0]))
        duplicate.set("bounds", "[20,300][680,400]")
        root.append(duplicate)
        with self.assertRaisesRegex(AssertionError, "Ambiguous.*IBM Plex Mono"):
            Smoke.font_option(root, "IBM Plex Mono")


class FontDevice(FakeDevice):
    """Release Settings at the adb boundary; no accounts or provider requests."""
    PACKAGE = runtime_smoke.PACKAGE
    FONT_BOXES = {"Figtree": (20, 400, 680, 500), "IBM Plex Mono": (20, 600, 680, 700),
                  "Font licences": (20, 800, 680, 900), "Done": (500, 1100, 680, 1200)}

    def __init__(self):
        super().__init__()
        self.screen, self.page = "Overview", 0
        self.fonts = set()
        self.persist = True
        self.licence_title = "Font licences"
        self.licence_text = "Copyright 2026 Test authors\nSIL OPEN FONT LICENSE\nVersion 1.1\nFull test licence text"

    def answer(self, command):
        if command[:3] == ("shell", "input", "swipe"):
            self.page = max(0, min(3, self.page + (1 if int(command[4]) > int(command[6]) else -1)))
            return b""
        if command[:3] == ("shell", "input", "tap"):
            x, y = int(command[3]), int(command[4])
            for label, box in self.FONT_BOXES.items():
                if box[0] <= x < box[2] and box[1] <= y < box[3]:
                    if label in ("Figtree", "IBM Plex Mono"):
                        self.fonts.add(label)
                    elif label == "Font licences":
                        self.screen = "Licences"
                    else:
                        self.screen = "Settings"
                    return b""
        if command[:3] == ("shell", "am", "force-stop") and not self.persist:
            self.fonts.clear()
        if command[:3] == ("shell", "pidof", self.PACKAGE):
            return b"1234"
        return super().answer(command)

    def hierarchy(self):
        if self.screen not in ("Settings", "Licences"):
            return super().hierarchy()
        pkg = self.PACKAGE
        root = ET.Element("hierarchy")
        def text(parent, label, **attributes):
            return ET.SubElement(parent, "node", {"package": pkg, "text": label, **attributes})
        def button(parent, label):
            text(parent, label, enabled="true", clickable="true",
                 bounds="[{},{}][{},{}]".format(*self.FONT_BOXES[label]))
        if self.screen == "Licences":
            text(root, self.licence_title)
            text(root, self.licence_text)
            button(root, "Done")
        else:
            content = text(root, "", scrollable="true", bounds="[0,150][720,1300]")
            if self.page == 0:
                text(content, "Appearance")
            elif self.page in (1, 2):
                label = "Figtree" if self.page == 1 else "IBM Plex Mono"
                chosen = label in self.fonts
                radio = text(content, "", enabled="true", checkable="true",
                             checked=str(chosen).lower(), clickable=str(not chosen).lower(),
                             bounds="[{},{}][{},{}]".format(*self.FONT_BOXES[label]))
                text(radio, "61%\n" + label)
            else:
                button(content, "Font licences")
            bar = text(root, "", bounds="[0,1400][540,1560]")
            for label in self.TABS:
                item = text(bar, "", enabled="true", selected=str(label == "Settings").lower(),
                            clickable=str(label != "Settings").lower(),
                            bounds="[{},{}][{},{}]".format(*self.BOXES[label]))
                text(item, label)
        return ET.tostring(root)


class OfflineFontsTest(unittest.TestCase):
    def journey(self, device, directory):
        with patch.object(runtime_smoke.subprocess, "run", device.run), patch.object(runtime_smoke, "time", device):
            Smoke(Path(directory), "test-device").verify_fonts_offline()

    def test_offline_settings_pair_licence_and_saved_radios_after_force_stop(self):
        device = FontDevice()
        with TemporaryDirectory() as directory:
            self.journey(device, directory)
            report = json.loads((Path(directory) / "fonts.json").read_text())
            self.assertEqual("passed", report["result"])
            self.assertEqual(["Figtree", "IBM Plex Mono"], report["selectedFonts"])
            for name in ("fonts-selected-Figtree", "fonts-selected-IBM-Plex-Mono", "fonts-licences",
                         "fonts-persisted-Figtree", "fonts-persisted-IBM-Plex-Mono"):
                self.assertTrue((Path(directory) / (name + ".xml")).exists())
                self.assertTrue((Path(directory) / (name + ".png")).exists())
        commands = [call[0] for call in device.calls]
        stop = commands.index(("shell", "am", "force-stop", runtime_smoke.PACKAGE))
        self.assertEqual(1, sum(command[:3] == ("shell", "am", "start") for command in commands[:stop]))
        self.assertEqual(1, sum(command[:3] == ("shell", "am", "start") for command in commands[stop:]))
        self.assertFalse(any("airplane-mode" in command or "set-wifi-enabled" in command for command in commands))
        self.assertEqual({"Figtree", "IBM Plex Mono"}, device.fonts)

    def test_a_pair_lost_on_process_restart_fails_the_saved_radio_assertion(self):
        class LostFigureFont(FontDevice):
            def answer(self, command):
                answer = super().answer(command)
                if command[:3] == ("shell", "am", "force-stop"):
                    self.fonts.discard("IBM Plex Mono")
                return answer
        for device, lost in ((FontDevice(), "Figtree"), (LostFigureFont(), "IBM Plex Mono")):
            device.persist = lost != "Figtree"
            with self.subTest(lost=lost), TemporaryDirectory() as directory, \
                    self.assertRaisesRegex(AssertionError, "Saved " + lost + " selection was lost"):
                self.journey(device, directory)

    def test_a_previously_saved_pair_is_not_toggled_off_on_upgrade(self):
        device = FontDevice()
        device.fonts = {"Figtree", "IBM Plex Mono"}
        with TemporaryDirectory() as directory:
            self.journey(device, directory)
        font_tap_centres = {(350, 450), (350, 650)}
        self.assertFalse(any(call[0][:3] == ("shell", "input", "tap")
                             and (int(call[0][3]), int(call[0][4])) in font_tap_centres for call in device.calls))

    def test_only_the_exact_dialog_heading_with_bundled_licence_text_counts(self):
        for title, content in (("Font Licences", "SIL OPEN FONT LICENSE Version 1.1"),
                               ("Font licences", "Unable to load licence while offline")):
            with self.subTest(title=title, content=content), TemporaryDirectory() as directory:
                device = FontDevice()
                device.licence_title, device.licence_text = title, content
                with self.assertRaisesRegex(AssertionError, "UI did not reach fonts-licences-loaded"):
                    self.journey(device, directory)
                self.assertFalse(any(call[0][:3] == ("shell", "am", "force-stop") for call in device.calls))


class OfflineFontNetworkTest(unittest.TestCase):
    class Device(FontDevice):
        def __init__(self):
            super().__init__()
            self.airplane, self.wifi = "disabled", "enabled"
            self.font_open_states = []

        def answer(self, command):
            if command[:4] == ("shell", "cmd", "connectivity", "airplane-mode"):
                if len(command) == 5:
                    self.airplane = "enabled" if command[4] == "enable" else "disabled"
                return self.airplane.encode()
            if command == ("shell", "cmd", "wifi", "status"):
                return ("Wifi is " + self.wifi).encode()
            if command[:4] == ("shell", "cmd", "wifi", "set-wifi-enabled"):
                self.wifi = command[4]
                return b""
            if command == ("shell", "dumpsys", "connectivity"):
                online = self.airplane == "disabled" and self.wifi == "enabled"
                networks = ("  NetworkAgentInfo{network{100}  ni{WIFI CONNECTED} created=0 "
                            " nc{[ Capabilities: INTERNET&VALIDATED UnderlyingNetworks: Null ]} "
                            " factorySerialNumber=1}\n") if online else ""
                return (("Active default network: 100\n" if online else "Active default network: none\n")
                        + "Current Networks:\n" + networks + "Status for known UIDs:\n").encode()
            if command[:3] == ("shell", "am", "start"):
                self.font_open_states.append((self.airplane, self.wifi))
            return super().answer(command)

    def test_existing_offline_window_covers_font_check_and_restores_even_when_fonts_fail(self):
        for persist in (True, False):
            with self.subTest(persist=persist), TemporaryDirectory() as directory:
                device = self.Device()
                device.persist = persist
                widget_states = []
                smoke = Smoke(Path(directory), "test-device")
                def widgets(placed, phase):
                    widget_states.append((phase, device.airplane, device.wifi))
                with patch.object(runtime_smoke.subprocess, "run", device.run), \
                        patch.object(runtime_smoke, "time", device), \
                        patch.object(smoke, "verify_widgets_after_upgrade", side_effect=widgets):
                    if persist:
                        smoke.verify_widget_network_lifecycle([])
                    else:
                        with self.assertRaisesRegex(AssertionError, "Saved Figtree selection was lost"):
                            smoke.verify_widget_network_lifecycle([])
                self.assertEqual(("disabled", "enabled"), (device.airplane, device.wifi))
                self.assertEqual([("enabled", "disabled"), ("enabled", "disabled")], device.font_open_states)
                self.assertEqual([("offline", "enabled", "disabled")]
                                 + ([("online", "disabled", "enabled")] if persist else []), widget_states)
                report = json.loads((Path(directory) / "widgets-network.json").read_text())
                self.assertTrue(report["restored"]["state"]["online"])
                if persist:
                    self.assertEqual("fonts.json", report["offline"]["fontReport"])
                    self.assertTrue(report["offline"]["afterFonts"]["offline"])
                commands = [call[0] for call in device.calls]
                self.assertEqual(2, sum(command[:4] == ("shell", "cmd", "wifi", "set-wifi-enabled")
                                        for command in commands))


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
