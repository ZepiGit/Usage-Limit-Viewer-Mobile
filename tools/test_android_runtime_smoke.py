"""Runtime smoke pixel, picker geometry, host focus and setting checks: python3 -m unittest discover -s tools"""

import json
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
    The screen is the empty-account app as far as the capture walks it: two tabs, the Accounts filter
    and the Add account screen.
    """

    STILL = png_bytes(2, 2, 4, [GROUND * 2, GROUND * 2])
    BOXES = {"Overview": (0, 1400, 180, 1560), "Accounts": (180, 1400, 360, 1560), "Add account": (40, 600, 680, 700),
             "All": (40, 200, 200, 260), "Needs attention": (200, 200, 400, 260)}
    SCALE = ("shell", "settings", "put", "global", "animator_duration_scale")

    def __init__(self):
        self.now = self.started = 1000.0
        self.scale = "0.0"
        self.calls = []
        self.frames = 0
        self.screen = None
        self.filter = "All"
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
            return b"priority=0\ncom.usagelimits/.UsageLimitsActivity"
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
            elif label:
                self.screen = label
        return b""

    def visible(self):
        return {"Overview": ("Overview", "Accounts", "Add account"),
                "Accounts": ("Overview", "Accounts", "All", "Needs attention")}.get(self.screen, ())

    def hierarchy(self):
        def node(label):
            chosen = str(label in (self.screen, self.filter)).lower()
            state = (f'checkable="true" checked="{chosen}"' if label in ("All", "Needs attention")
                     else f'selected="{chosen}"')
            box = "[{},{}][{},{}]".format(*self.BOXES[label])
            return (f'<node package="com.usagelimits" enabled="true" clickable="{str(chosen == "false").lower()}" '
                    f'{state} bounds="{box}"><node package="com.usagelimits" text="{label}" bounds="{box}"/></node>')

        nodes = "".join(node(label) for label in self.visible())
        if self.screen == "Add account":
            nodes = ('<node package="com.usagelimits" text="You can connect several accounts per provider." '
                     'bounds="[40,300][680,360]"/>')
        return f"<hierarchy>{nodes}</hierarchy>".encode()

    def restores(self):
        """The index of every call that asked for the scale to go back to 0."""
        return [index for index, call in enumerate(self.calls) if call[0] == self.SCALE + ("0.0",)]


class MotionCaptureTest(unittest.TestCase):
    """#81: the slow-motion frame capture is evidence, and must always hand animations back off."""

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
                return "priority=0\ncom.usagelimits/.UsageLimitsActivity"
            return ""
        return state, adb

    def capture(self, device):
        """capture_motion on the fake device and its clock: what it returned or raised, and what it reported."""
        with TemporaryDirectory() as directory, patch.object(runtime_smoke.subprocess, "run", device.run), \
                patch.object(runtime_smoke, "time", device):
            try:
                outcome = Smoke(Path(directory), "test-device").capture_motion("motion")
            except BaseException as error:
                outcome = error
            report = Path(directory) / "motion.json"
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
                self.assertEqual("capture failed", smoke.capture_motion("motion"))
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
                    smoke.capture_motion("motion")

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

    def test_a_failed_activity_resolution_is_recorded_and_animations_are_confirmed_off(self):
        device = FakeDevice()

        def before(command):
            if "resolve-activity" in command:
                raise subprocess.CalledProcessError(1, command)
        device.before = before
        outcome, report = self.capture(device)
        self.assertEqual("capture failed", outcome)
        self.assertIn("CalledProcessError", report["error"])
        self.assertEqual(1, len(device.restores()))
        self.assertEqual(("0.0", "0.0"), (device.scale, report["animatorScaleRestored"]))
        self.assertEqual((0, []), (device.frames, report["sequences"]))

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
             else 35 if slow_start(command) else 0.25, None, "tap Accounts tab", ["launch"], 24),
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
                self.assertIn("animations", report["smokeStopped"])
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
