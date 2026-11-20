#!/usr/bin/env python3
"""Upgrade and navigate the actual release APK on an empty API 37 CI emulator.

Uses only adb and the Python standard library. No credentials or provider calls; the release app
stays empty. With --debug-apk (Android CI), the List is then checked in this run's debug build, with
synthetic accounts from its debug-only evidence receiver, installed beside the release app after
every release check and left installed until the emulator is discarded. That lane shows the List,
a chip's press highlight and a row opening its account; it does not observe the debug build's later
exits or crashes, which the final failure check reads for the release package only.
https://developer.android.com/tools/adb documents install -r, am start and screencap.
All touch coordinates come from the device's current UIAutomator hierarchy.
"""

import argparse
import copy
import hashlib
import json
import math
import os
from pathlib import Path
import re
import statistics
import struct
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET
import zlib


PACKAGE = "com.usagelimits"
# This run's debug build: its own package beside the release app, used only for the seeded List.
DEBUG_PACKAGE = PACKAGE + ".debug"
DEBUG_RECEIVER = DEBUG_PACKAGE + "/com.usagelimits.evidence.StatusIconEvidenceReceiver"
SETTING = "Show subscription tier"
# What an empty Overview says once it is ready. This build is held to C1. The upgrade baseline is
# the newest published release, so it may be one from before the redesign (whose Overview counted
# its accounts) or one after it; either known empty state counts for it, inside its own package
# and with Overview selected.
EMPTY_OVERVIEW = "Nothing to monitor yet"
BASELINE_EMPTY_OVERVIEW = ("0 accounts monitored", EMPTY_OVERVIEW)
TABS = ("Overview", "Resets", "Settings")
# The animator scale while motion frames are captured: slow enough for `screencap`, which takes
# a few hundred milliseconds a frame on the software-rendered emulator.
MOTION_SCALE = 10
# The motion frames are optional evidence at the end of a job with a 60-minute limit, so they get
# a fixed share of it: the five bursts need about 25 s on the CI emulator, and whatever is not
# captured after this many seconds is given up.
MOTION_BUDGET_SECONDS = 150
# Turning animations off again has an allowance of its own, so a capture that used up its budget
# cannot take the restore's time: three attempts of one command and one read-back each.
MOTION_RESTORE_SECONDS = 60
MOTION_RESTORE_ATTEMPTS = 3


class BudgetExhausted(Exception):
    """A deadline was reached before the next step under it could start."""


class CaptureSkipped(Exception):
    """The motion capture decided not to take frames; the message is the reason it reports."""


def scale_is(text, value):
    """Whether `settings get` printed this animator scale; "null" or anything unreadable is not it."""
    try:
        return float(text) == value
    except ValueError:
        return False


def remaining(deadline, step):
    """Seconds left until a `time.monotonic()` deadline; none left ends the caller's work."""
    left = deadline - time.monotonic()
    if left <= 0:
        raise BudgetExhausted(step)
    return left


def run(*args, timeout=40, binary=False, deadline=None):
    # A deadline only ever shortens the timeout: nothing started under it runs past it.
    if deadline is not None:
        timeout = min(timeout, remaining(deadline, " ".join(map(str, args))))
    result = subprocess.run(args, check=True, capture_output=True, timeout=timeout)
    return result.stdout if binary else result.stdout.decode().strip()


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def bounds(node):
    values = [int(value) for value in re.findall(r"\d+", node.get("bounds", ""))]
    if len(values) != 4 or values[2] <= values[0] or values[3] <= values[1]:
        return None
    return tuple(values)


def picker_geometry(root, launcher, home):
    """The API 37 Browse list and its unobscured viewport, or None during picker transitions."""
    namespace = launcher + ".widgetpicker:id/"

    def unique(scope, name, scrollable=False):
        found = [node for node in scope.iter("node") if node.get("resource-id") == namespace + name
                 and (not scrollable or node.get("scrollable") == "true")]
        assert len(found) <= 1, f"Ambiguous picker {name}"
        return next((node for node in found if node.get("package") == launcher
                     and node.get("enabled") == "true" and bounds(node)), None)

    catalog = unique(root, "widgets_catalog")
    if catalog is None:
        return None
    picker = unique(catalog, "personal_widgets_list", scrollable=True)
    featured = unique(catalog, "featured_widgets_tab")
    browse = unique(catalog, "personal_widgets_tab")
    if picker is None or featured is None or browse is None:
        return None
    if browse.get("checked") != "true" or featured.get("checked") != "false":
        return None
    parents = {child: parent for parent in catalog.iter() for child in parent}
    row = parents.get(featured)
    assert row is not None and row is parents.get(browse), "Cannot identify the observed floating picker tab row"
    overlay = parents.get(row)
    assert overlay is not None and overlay is not catalog and all(
        node.get("package") == launcher and node.get("enabled") == "true" and bounds(node)
        for node in (row, overlay)
    ), "Cannot identify the observed floating picker tab wrapper"
    box, overlay_box = bounds(picker), bounds(overlay)
    assert all(overlay_box[0] <= tab[0] < tab[2] <= overlay_box[2]
               and overlay_box[1] <= tab[1] < tab[3] <= overlay_box[3]
               for tab in (bounds(featured), bounds(browse))), "Floating picker wrapper does not contain its tabs"
    assert box[0] <= overlay_box[0] < overlay_box[2] <= box[2] and (
        box[1] < overlay_box[1] < overlay_box[3] <= box[3]
    ), "Floating picker wrapper is outside the Browse list"
    # The observed wrapper includes background padding above the tabs; exclude it across the full list width.
    visible = (max(home[0], box[0]), max(home[1], box[1]),
               min(home[2], box[2]), min(home[3], box[3], overlay_box[1]))
    assert visible[2] > visible[0] and visible[3] > visible[1], "No observed picker content viewport"
    return picker, visible


def png_frame(data, last_row):
    """Rows 0..last_row of an 8-bit, non-interlaced RGB(A) PNG, the format `screencap -p` writes."""
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "Not a PNG"
    offset, header, compressed = 8, None, []
    while offset < len(data):
        length, kind = struct.unpack(">I4s", data[offset:offset + 8])
        body = data[offset + 8:offset + 8 + length]
        if kind == b"IHDR":
            header = struct.unpack(">IIBBBBB", body)
        elif kind == b"IDAT":
            compressed.append(body)
        elif kind == b"IEND":
            break
        offset += 12 + length
    assert header is not None, "PNG has no header"
    width, height, depth, color, _, _, interlace = header
    assert depth == 8 and color in (2, 6) and interlace == 0, f"Unsupported PNG format: {header}"
    channels = 4 if color == 6 else 3
    stride = width * channels
    raw = zlib.decompress(b"".join(compressed))
    rows, previous = [], bytearray(stride)
    for y in range(min(height, last_row + 1)):
        start = y * (stride + 1)
        kind, line = raw[start], bytearray(raw[start + 1:start + 1 + stride])
        if kind == 1:
            for i in range(channels, stride):
                line[i] = (line[i] + line[i - channels]) & 255
        elif kind == 2:
            for i in range(stride):
                line[i] = (line[i] + previous[i]) & 255
        elif kind == 3:
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                line[i] = (line[i] + (left + previous[i]) // 2) & 255
        elif kind == 4:
            for i in range(stride):
                a = line[i - channels] if i >= channels else 0
                b, c = previous[i], previous[i - channels] if i >= channels else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[i] = (line[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        else:
            assert kind == 0, f"Unknown PNG filter {kind}"
        rows.append(bytes(line))
        previous = line
    return width, channels, rows


def png_bytes(width, height, channels, lines):
    def chunk(kind, body):
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body))
    header = struct.pack(">IIBBBBB", width, height, 8, 6 if channels == 4 else 2, 0, 0, 0)
    pixels = zlib.compress(b"".join(b"\0" + line for line in lines), 6)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", pixels) + chunk(b"IEND", b"")


def changed_pixels(before, after, box):
    """Pixels inside box (x1, y1, x2, y2; exclusive ends) that differ between two decoded frames."""
    _, channels, rows = before
    x1, y1, x2, y2 = box
    found = []
    for y in range(y1, y2):
        a, b = rows[y][x1 * channels:x2 * channels], after[2][y][x1 * channels:x2 * channels]
        if a != b:
            found += [(x1 + i // channels, y) for i in range(0, len(a), channels) if a[i:i + channels] != b[i:i + channels]]
    return found


def rounded_rect_distance(box, radius, x, y):
    """Signed distance in px from the centre of pixel (x, y) to a rounded rectangle's outline; negative inside."""
    qx = abs(x + 0.5 - (box[0] + box[2]) / 2) - ((box[2] - box[0]) / 2 - radius)
    qy = abs(y + 0.5 - (box[1] + box[3]) / 2) - ((box[3] - box[1]) / 2 - radius)
    return math.hypot(max(qx, 0), max(qy, 0)) + min(max(qx, qy), 0) - radius


def judge_press(changed, pill, radius, idle_pill, fringe=1.0):
    """Sorts the pixels a held press changed by the rounded pill they must stay in.

    Pixels whose centre lies within `fringe` px outside the outline count as its anti-aliased edge. Everything
    further out is spill, including the pill rectangle's corners beyond the rounding. The idle pill belongs to the
    segment that stays selected; any change there means the frame shows a release, not a press.
    """
    distance = {point: rounded_rect_distance(pill, radius, *point) for point in changed}
    outside = sorted(point for point, value in distance.items() if value > fringe)

    def in_box(point, box):
        return box[0] <= point[0] < box[2] and box[1] <= point[1] < box[3]

    corner_mask = [(x, y) for x in range(pill[0], pill[2]) for y in range(pill[1], pill[3])
                   if (min(x - pill[0], pill[2] - 1 - x) < radius and min(y - pill[1], pill[3] - 1 - y) < radius
                       and rounded_rect_distance(pill, radius, x, y) > fringe)]
    return {
        "changedInPill": sum(1 for value in distance.values() if value <= 0),
        "changedInFringe": sum(1 for value in distance.values() if 0 < value <= fringe),
        "changedOutsidePill": len(outside),
        "cornerMaskPixels": len(corner_mask),
        "changedInExcludedCorners": sum(1 for point in corner_mask if point in distance),
        "changedInIdlePill": sum(1 for point in changed if in_box(point, idle_pill)),
        "firstChangedOutsidePill": outside[:8],
    }


def scaled_crop(frame, box, scale):
    """A nearest-neighbour enlargement of box, so single pixels stay readable."""
    _, channels, rows = frame
    x1, y1, x2, y2 = box
    lines = []
    for y in range(y1, y2):
        line = b"".join(rows[y][x * channels:(x + 1) * channels] * scale for x in range(x1, x2))
        lines += [line] * scale
    return png_bytes((x2 - x1) * scale, (y2 - y1) * scale, channels, lines)


def motion_sequence(name, says, samples):
    """One captured motion: its frames in order, and how many of the screenshots differ.

    Frames are compared by the bytes `screencap -p` wrote for the whole screen, so `framesDiffer`
    is already true when the status-bar clock ticks during the burst. It does not measure motion
    and nothing may read it as proof of one; the pictures are the evidence, this is their index.
    """
    distinct = len({sample["sha256"] for sample in samples})
    return {"name": name, "says": says, "frames": samples, "distinctFrames": distinct,
            "framesDiffer": distinct > 1}


def matches(root, label, package=PACKAGE):
    """Nodes of `package` whose text or description is `label`, or any of the labels in a tuple."""
    labels = label if isinstance(label, tuple) else (label,)
    return [node for node in root.iter("node") if node.get("package") == package
            and (node.get("text") in labels or node.get("content-desc") in labels)]


def navigation_target(root, label, package=PACKAGE):
    parents = {child: parent for parent in root.iter() for child in parent}
    candidates = {}
    for node in matches(root, label, package):
        while node is not None:
            box = bounds(node)
            if box and node.get("enabled") == "true" and (
                node.get("clickable") == "true" or node.get("selected") == "true"
            ):
                candidates[box] = node
                break
            node = parents.get(node)
    # Portrait navigation is below any identically named screen heading.
    return max(candidates.values(), key=lambda node: bounds(node)[3], default=None)


def selected_tab(root, label, package=PACKAGE):
    target = navigation_target(root, label, package)
    return target is not None and target.get("selected") == "true"


def overview_ready(root, phase):
    """The empty Overview is shown, selected, in this app's package: the readiness marker of `phase`.

    `previous` is the published baseline and may show either known empty state; `current` is this
    build, held to C1 alone.
    """
    marker = BASELINE_EMPTY_OVERVIEW if phase == "previous" else EMPTY_OVERVIEW
    return selected_tab(root, "Overview") and bool(matches(root, marker))


def bar_items(root, package=PACKAGE):
    """The labels of the navigation bar's items, left to right.

    The bar is the parent of the Overview item: every child of it is one item (selected or
    clickable, enabled), whatever its label, so an extra or renamed item is counted too.
    """
    overview = navigation_target(root, "Overview", package)
    if overview is None:
        return []
    parents = {child: parent for parent in root.iter() for child in parent}
    bar = parents.get(overview)
    items = [child for child in bar if child.get("package") == package and child.get("enabled") == "true"
             and (child.get("clickable") == "true" or child.get("selected") == "true")] if bar is not None else []
    return [" ".join(node.get("text") for node in item.iter("node") if node.get("text")) for item in
            sorted(items, key=lambda item: bounds(item)[0])]


def three_tabs(root, package=PACKAGE):
    """The bar has exactly Overview, Resets and Settings, in that order, and nothing else (#88 01b)."""
    return bar_items(root, package) == list(TABS)


def focused_widget(root, launcher, label):
    """The unique placed launcher host, only when it can receive keyboard input."""
    hosts = [node for node in root.iter("node") if node.get("package") == launcher
             and node.get("class", "").endswith("AppWidgetHostView")
             and node.get("content-desc") == label and bounds(node)]
    assert len(hosts) <= 1, f"Ambiguous {label} host"
    return next((node for node in hosts if all(node.get(attribute) == "true"
                 for attribute in ("enabled", "focusable", "focused"))), None)


def post_drop_frame(root, launcher, label, initial):
    """The unique focused launcher ancestor of the resize frame enclosing the moved host."""
    frames = [node for node in root.iter("node")
              if node.get("resource-id") == launcher + ":id/widget_resize_frame"]
    hosts = [node for node in root.iter("node") if node.get("package") == launcher
             and node.get("class", "").endswith("AppWidgetHostView")
             and node.get("content-desc") == label and bounds(node)]
    assert len(frames) <= 1, "Ambiguous launcher resize frame"
    assert len(hosts) <= 1, f"Ambiguous {label} host"
    if not frames or not hosts:
        return None
    frame, host = frames[0], hosts[0]
    box, moved = bounds(frame), bounds(host)
    if frame.get("package") != launcher or frame.get("enabled") != "true" or not box or (
        host.get("enabled") != "true" or moved == initial
        or not (box[0] < moved[0] < moved[2] < box[2] and box[1] < moved[1] < moved[3] < box[3])
    ):
        return None
    parents = {child: parent for parent in root.iter() for child in parent}
    owners = []
    node = parents.get(frame)
    while node is not None:
        if node.get("package") == launcher and bounds(node) == box and all(
            node.get(attribute) == "true" for attribute in ("enabled", "focused")
        ):
            owners.append(node)
        node = parents.get(node)
    assert len(owners) <= 1, "Ambiguous launcher resize frame focus owner"
    return next(iter(owners), None)


class Smoke:
    def __init__(self, directory, serial):
        self.directory = directory
        self.serial = serial
        self.directory.mkdir(parents=True, exist_ok=True)

    def adb(self, *args, **kwargs):
        return run("adb", "-s", self.serial, *args, **kwargs)

    def text(self, name, value):
        (self.directory / name).write_text(value + "\n")

    def dump(self, name, deadline=None):
        remote = "/sdcard/usage-runtime-window.xml"
        self.adb("shell", "rm", "-f", remote, deadline=deadline)
        self.adb("shell", "uiautomator", "dump", remote, deadline=deadline)
        xml = self.adb("exec-out", "cat", remote, deadline=deadline)
        self.text(name + ".xml", xml)
        return ET.fromstring(xml)

    def write_screenshot(self, name, data):
        (self.directory / (name + ".png")).write_bytes(data)
        png_frame(data, 0)

    def capture(self, name):
        root = self.dump(name)
        self.write_screenshot(name, self.adb("exec-out", "screencap", "-p", binary=True))
        return root

    @staticmethod
    def assert_no_system_error(root):
        assert not any(node.get("resource-id", "").startswith("android:id/aerr_")
                       for node in root.iter("node")), "System error dialog blocks widget smoke; it was not dismissed"

    def widget_capture(self, name):
        # Keep transition/failure pixels if UIAutomator hangs, then capture the settled UI too.
        self.write_screenshot(name + "-before-dump", self.adb("exec-out", "screencap", "-p", binary=True))
        root = self.capture(name)
        self.assert_no_system_error(root)
        return root

    def wait_for_launcher(self, name, component):
        """Observe the booted Home before sending any input or installing an APK."""
        assert component.startswith(self.launcher + "/"), "Unexpected HOME component"

        def expanded(value):
            package, activity = value.split("/", 1)
            return package + "/" + (package + activity if activity.startswith(".") else activity)

        started = time.monotonic()
        deadline = started + 120
        report = {"component": component, "result": "failed", "attempts": 0, "lastProbeError": None}

        def probe(*args, binary=False):
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise subprocess.TimeoutExpired(args, 120)
            return self.adb(*args, binary=binary, timeout=min(10, remaining))

        def check_anr(filename):
            anr = probe("shell", "dumpsys", "activity", "lastanr")
            self.text(filename, anr)
            assert anr.splitlines() == ["ACTIVITY MANAGER LAST ANR (dumpsys activity lastanr)",
                                       "  <no ANR has occurred since boot>"], (
                "Boot readiness found an ANR or an unrecognized ANR report; no input was sent")

        try:
            while time.monotonic() < deadline:
                attempt = f"{name}-{report['attempts']}"
                report["attempts"] += 1
                try:
                    check_anr(attempt + "-last-anr.txt")
                    remote = "/sdcard/usage-runtime-boot.xml"
                    probe("shell", "rm", "-f", remote)
                    probe("shell", "uiautomator", "dump", remote)
                    xml = probe("exec-out", "cat", remote)
                    self.text(attempt + ".xml", xml)
                    root = ET.fromstring(xml)
                    self.assert_no_system_error(root)
                    # Android 16 emits mCurrentFocus in DisplayContent.dump, not the windows subdump.
                    window = probe("shell", "dumpsys", "window")
                    self.text(attempt + "-window.txt", window)
                    focus = re.findall(r"^\s*mCurrentFocus=(.+)$", window, re.M)
                    focused = re.fullmatch(r"Window\{[0-9a-f]+ u\d+ ([^\s{}]+/[^\s{}]+)\}", focus[0]) if len(focus) == 1 else None
                    workspace = [node for node in root.iter("node")
                                 if node.get("package") == self.launcher
                                 and node.get("resource-id") == self.launcher + ":id/workspace"
                                 and node.get("enabled") == "true" and bounds(node)]
                    self.write_screenshot(attempt, probe("exec-out", "screencap", "-p", binary=True))
                    if focused and expanded(focused[1]) == expanded(component) and len(workspace) == 1:
                        check_anr(attempt + "-ready-last-anr.txt")
                        report["result"] = "passed"
                        return
                except (subprocess.SubprocessError, ET.ParseError) as error:
                    report["lastProbeError"] = str(error)
                    self.text(attempt + "-probe-error.txt", str(error))
                time.sleep(min(1, max(0, deadline - time.monotonic())))
            raise AssertionError("Launcher did not expose both its focused Home window and workspace within 120 seconds")
        finally:
            report["waitSeconds"] = time.monotonic() - started
            self.text(name + ".json", json.dumps(report, indent=2))

    @staticmethod
    def control(root, package, label=None, resource_id=None):
        parents = {child: parent for parent in root.iter() for child in parent}
        controls = {}
        for node in root.iter("node"):
            if node.get("package") != package:
                continue
            if label is not None and label not in (node.get("text"), node.get("content-desc")):
                continue
            if resource_id is not None and node.get("resource-id") != resource_id:
                continue
            while node is not None and node.get("package") == package:
                # A selected Compose radio remains checked but is no longer clickable.
                if node.get("enabled") == "true" and bounds(node) and (
                    node.get("clickable") == "true"
                    or (node.get("checkable") == "true" and node.get("checked") == "true")
                ):
                    controls[bounds(node)] = node
                    break
                node = parents.get(node)
        assert len(controls) <= 1, f"Ambiguous {package} control: {label or resource_id}"
        return next(iter(controls.values()), None)

    def widget_control(self, name, package, label=None, resource_id=None, scroll=False, text_only=False):
        for attempt in range(12):
            root = self.widget_capture(f"{name}-{attempt}")
            if text_only:
                targets = [node for node in root.iter("node") if node.get("package") == package
                           and node.get("text") == label and bounds(node)]
                assert len(targets) <= 1, f"Ambiguous displayed text: {label}"
                target = next(iter(targets), None)
            else:
                target = self.control(root, package, label, resource_id)
            scroll_down = False
            visible = None
            if scroll and package == self.launcher:
                geometry = picker_geometry(root, self.launcher, self.launcher_content_viewport)
                if geometry is None:
                    target = None
                else:
                    _, visible = geometry
                    if target is not None:
                        original = bounds(target)
                        clipped = (max(original[0], visible[0]), max(original[1], visible[1]),
                                   min(original[2], visible[2]), min(original[3], visible[3]))
                        # Accessibility bounds may include pixels behind the floating tabs or navigation.
                        if clipped[2] > clipped[0] and clipped[3] > clipped[1]:
                            target = copy.deepcopy(target)
                            target.set("bounds", f"[{clipped[0]},{clipped[1]}][{clipped[2]},{clipped[3]}]")
                            self.text(f"{name}-{attempt}-tap.json", json.dumps({
                                "target": label or resource_id, "original": original,
                                "visiblePicker": visible, "tapBounds": clipped}))
                        else:
                            scroll_down = original[3] <= visible[1]
                            target = None
            if target is not None:
                return target
            if scroll:
                if package != self.launcher:
                    scrollables = [node for node in root.iter("node") if node.get("package") == package
                                   and node.get("scrollable") == "true" and bounds(node)
                                   and bool(matches(root, "Save widget"))]
                    if scrollables:
                        visible = max((bounds(node) for node in scrollables),
                                      key=lambda box: (box[2] - box[0]) * (box[3] - box[1]))
                if visible is not None:
                    x1, y1, x2, y2 = visible
                    x = str((x1 + x2) // 2)
                    start, end = y1 + (y2 - y1) * 3 // 4, y1 + (y2 - y1) // 4
                    if scroll_down:
                        start, end = end, start
                    self.adb("shell", "input", "swipe", x, str(start), x, str(end), "400")
            time.sleep(1)
        raise AssertionError(f"Missing observed {package} control: {label or resource_id}; see {name} captures")

    def widget_home(self, name):
        self.adb("shell", "input", "keyevent", "KEYCODE_HOME")
        self.widget_capture(name + "-arrival")
        self.wait_for(name, lambda root: any(node.get("resource-id") == self.launcher + ":id/workspace"
                                            for node in root.iter("node")))
        return self.widget_capture(name)

    def open_widget_picker(self, name):
        root = self.widget_home(name + "-home")
        home_content = [node for node in root.iter("node")
                        if node.get("resource-id") == self.launcher + ":id/accessibility_action_view" and bounds(node)]
        assert len(home_content) == 1, "Cannot identify the observed launcher content viewport"
        self.launcher_content_viewport = bounds(home_content[0])
        self.text(name + "-home-viewport.json", json.dumps({"bounds": self.launcher_content_viewport}))
        workspaces = [node for node in root.iter("node")
                      if node.get("resource-id") == self.launcher + ":id/workspace" and bounds(node)]
        assert len(workspaces) == 1, "Expected one observed Launcher workspace"
        workspace = workspaces[0]
        # API 36 Quickstep's observed workspace uses a custom long-press gesture even
        # though its accessibility long-clickable flag is false. Stay within its page.
        pages = [node for node in workspace if bounds(node)]
        assert len(pages) == 1, "Cannot identify the current workspace page from the hierarchy"
        page = bounds(pages[0])
        occupied = [bounds(node) for node in workspace.iter("node") if node is not workspace and bounds(node)
                    and (node.get("clickable") == "true" or node.get("long-clickable") == "true"
                         or node.get("class", "").endswith("AppWidgetHostView"))]
        xs = sorted({page[0], page[2]} | {max(page[0], min(page[2], edge)) for box in occupied for edge in (box[0], box[2])})
        ys = sorted({page[1], page[3]} | {max(page[1], min(page[3], edge)) for box in occupied for edge in (box[1], box[3])})
        free = [(x1, y1, x2, y2) for x1, x2 in zip(xs, xs[1:]) for y1, y2 in zip(ys, ys[1:])
                if x2 > x1 and y2 > y1 and not any(
                    box[0] < x2 and x1 < box[2] and box[1] < y2 and y1 < box[3] for box in occupied)]
        assert free, "No observed empty workspace region for the normal Widgets menu"
        x1, y1, x2, y2 = max(free, key=lambda box: (box[2] - box[0]) * (box[3] - box[1]))
        self.text(name + "-longpress-region.json", json.dumps({"page": page, "emptyRegion": [x1, y1, x2, y2]}))
        x, y = str((x1 + x2) // 2), str((y1 + y2) // 2)
        self.adb("shell", "input", "swipe", x, y, x, y, "1000")
        self.tap(self.widget_control(name + "-menu", self.launcher, label="Widgets"))
        # The API 37 picker opens on Featured rather than the app list.
        self.tap(self.widget_control(name + "-browse", self.launcher, label="Browse widgets"))
        self.tap(self.widget_control(name + "-app", self.launcher, label="Usage Limits", scroll=True))

    @staticmethod
    def selected(node):
        return any(child.get("checked") == "true" or child.get("selected") == "true" for child in node.iter("node"))

    def widget_configuration(self, name, label, color, save):
        self.wait_for(name + "-loaded", lambda root: bool(matches(root, label)) and bool(matches(root, "Save widget")))
        for control_label in [color + " background", "Auto", "All accounts"]:
            target = self.widget_control(name + "-" + control_label.replace(" ", "-"), PACKAGE,
                                         label=control_label, scroll=True)
            if save:
                if not self.selected(target):
                    self.tap(target)
                self.wait_for(name + "-selected", lambda root: (
                    (target := self.control(root, PACKAGE, label=control_label)) is not None and self.selected(target)))
            else:
                assert self.selected(target), f"Saved {control_label} selection was lost after upgrade"
            self.widget_capture(name + "-verified-" + control_label.replace(" ", "-"))
            if control_label == color + " background":
                self.widget_control(name + "-opacity", PACKAGE, label="Opacity · 100 %", scroll=True, text_only=True)
        button = "Save widget" if save else "Cancel"
        self.tap(self.widget_control(name + "-finish", PACKAGE, label=button))
        self.wait_for(name + "-returned", lambda root: not matches(root, "Save widget")
                      and not matches(root, "Saving…") and any(
                          node.get("resource-id") == self.launcher + ":id/workspace" for node in root.iter("node")))
        self.widget_capture(name + "-returned")

    def widget_bindings(self, name):
        dump = self.adb("shell", "dumpsys", "appwidget")
        self.text(name + "-appwidget.txt", dump)
        assert "\nWidgets:\n" in dump and "\nHosts:\n" in dump, "Unrecognized appwidget dump"
        section = dump.split("\nWidgets:\n", 1)[1].split("\nHosts:\n", 1)[0]
        result = {}
        for widget_id, block in re.findall(r"(?ms)^  \[\d+\] id=(\d+)\n(.*?)(?=^  \[\d+\]|\Z)", section):
            provider = re.search(r"provider=ProviderId\{[^\n]*cmp:ComponentInfo\{com\.usagelimits/([^}]+)\}", block)
            if provider:
                assert provider[1] not in result, "Duplicate provider instances make the fixture ambiguous"
                assert f"pkg:{self.launcher}" in block, "Widget is not bound to the actual launcher"
                result[provider[1]] = int(widget_id)
        return result

    def find_placed_widget(self, name, label):
        self.widget_home(name + "-home")
        # Add may create another workspace page. Search only by observed host descriptions.
        for direction in ["left", "right"]:
            for attempt in range(6):
                root = self.widget_capture(f"{name}-{direction}-{attempt}")
                hosts = [node for node in root.iter("node") if node.get("class", "").endswith("AppWidgetHostView")
                         and node.get("content-desc") == label and bounds(node)]
                assert len(hosts) <= 1, f"Ambiguous placed {label} widget"
                if hosts:
                    return hosts[0]
                workspaces = [node for node in root.iter("node")
                              if node.get("resource-id") == self.launcher + ":id/workspace" and bounds(node)]
                assert len(workspaces) == 1, "No observed workspace for page navigation"
                x1, y1, x2, y2 = bounds(workspaces[0])
                start, end = x1 + (x2 - x1) * 3 // 4, x1 + (x2 - x1) // 4
                if direction == "right":
                    start, end = end, start
                self.adb("shell", "input", "swipe", str(start), str((y1 + y2) // 2), str(end), str((y1 + y2) // 2), "400")
        raise AssertionError(f"The launcher does not expose the placed {label} widget")

    def place_widgets(self):
        assert not self.widget_bindings("widgets-before"), "Expected a fresh launcher with no Usage Limits widgets"
        fixtures = [
            ("Usage Summary", "CompactUsageWidgetReceiver", "Navy"),
            ("Usage Bars", "DetailedUsageWidgetReceiver", "White"),
            ("Account Rings", "MinimalUsageWidgetReceiver", "Navy"),
            ("Mini Rings", "MiniRingsWidgetReceiver", "White"),
        ]
        placed = []
        for index, (label, receiver, color) in enumerate(fixtures):
            name = f"previous-widget-{index}"
            self.open_widget_picker(name)
            self.tap(self.widget_control(name + "-preview", self.launcher, label=label, scroll=True))
            self.tap(self.widget_control(name + "-add", self.launcher, label=f"Add {label} widget", scroll=True))
            self.widget_configuration(name + "-config", label, color, save=True)
            self.find_placed_widget(name + "-placed", label)
            bindings = self.widget_bindings(name)
            component = PACKAGE + ".widget." + receiver
            assert component in bindings, f"{label} was not bound to the launcher"
            placed.append({"label": label, "component": component, "color": color, "id": bindings[component]})
            self.text("widgets-placement.json", json.dumps(placed, indent=2))
        assert len(placed) == 4, "All four widget types must be placed"
        return placed

    def verify_widgets_after_upgrade(self, placed, phase="current", only_label=None):
        expected = {item["component"]: item["id"] for item in placed}
        assert self.widget_bindings(phase + "-widgets") == expected, "Placed widget IDs changed"
        verified = [item for item in placed if only_label is None or item["label"] == only_label]
        assert only_label is None or len(verified) == 1, "Expected one selected widget for verification"
        for index, item in enumerate(placed):
            if item not in verified:
                continue
            name = f"{phase}-widget-{index}"
            self.find_placed_widget(name, item["label"])
            marker = "Stale" if item["label"] == "Usage Summary" else "No matching accounts"
            host = None
            for attempt in range(12):
                root = self.widget_capture(f"{name}-content-{attempt}")
                hosts = [node for node in root.iter("node") if node.get("class", "").endswith("AppWidgetHostView")
                         and node.get("content-desc") == item["label"] and bounds(node)]
                assert len(hosts) <= 1, f"Ambiguous placed {item['label']} widget"
                if hosts:
                    text = [node.get("text", "") for node in hosts[0].iter("node")]
                    assert not any("Can't show content" in value or "Problem loading widget" in value for value in text), text
                    if any(marker in value for value in text):
                        host = hosts[0]
                        break
                time.sleep(1)
            assert host is not None, f"{item['label']} did not display its empty-account content"
            x1, y1, x2, y2 = bounds(host)
            x, y = str((x1 + x2) // 2), str((y1 + y2) // 2)
            self.adb("shell", "input", "swipe", x, y, x, y, "1000")
            self.tap(self.widget_control(name + "-edit", self.launcher, label="Settings"))
            self.widget_configuration(name + "-config", item["label"], item["color"], save=False)
            assert self.widget_bindings(name + "-verified") == expected, "Reconfigure replaced a widget ID"
        self.text(phase + "-widgets-verified.json", json.dumps({"result": "passed", "phase": phase, "widgets": verified,
                  "retainedWidgetIds": expected,
                  "scope": "Visible empty-account host content, retained IDs and saved appearance selections; no fresh quota measurement."}, indent=2))

    def verify_widget_move_resize(self, placed):
        """Exercise the existing Mini Rings binding using normal launcher keyboard actions."""
        started = time.monotonic()
        fixtures = [item for item in placed if item["label"] == "Mini Rings"]
        assert len(fixtures) == 1, "Expected one Mini Rings fixture"
        item = fixtures[0]
        expected = {entry["component"]: entry["id"] for entry in placed}
        assert len(expected) == 4, "Move/resize must retain all four placed widgets"
        resize_labels = {"Increase width", "Decrease width", "Increase height", "Decrease height"}
        report = {"result": "incomplete", "widget": item, "retainedWidgetIds": expected,
                  "widgetFocus": [], "steps": []}

        def record():
            self.text("widgets-move-resize.json", json.dumps(report, indent=2))

        def focus_widget(name):
            selected = bounds(self.find_placed_widget(name + "-locate", item["label"]))
            focus_report = {"action": name, "selectedBounds": selected, "steps": []}
            report["widgetFocus"].append(focus_report)
            record()
            # API 37 keyboard shortcuts require the actual ItemInfo-bearing host, not its null-named Compose frame.
            for index in range(41):
                capture = f"{name}-focus-{index}"
                root = self.widget_capture(capture)
                host = focused_widget(root, self.launcher, item["label"])
                focus_report["steps"].append({
                    "capture": capture, "key": None if index == 0 else "TAB",
                    "focusedNodes": [{key: node.get(key, "") for key in (
                        "package", "class", "resource-id", "text", "content-desc", "bounds",
                        "enabled", "focusable", "focused")}
                        for node in root.iter("node") if node.get("focused") == "true"],
                })
                record()
                assert host_box(root) == selected, "Mini Rings host moved, lost content or remained behind launcher action UI during focus traversal"
                if host is not None:
                    assert bounds(host) == selected, "Keyboard focus belongs to a different Mini Rings host"
                    return
                if index < 40:
                    self.adb("shell", "input", "keyboard", "keyevent", "KEYCODE_TAB")
            raise AssertionError("Mini Rings launcher host did not acquire input focus within 40 TAB keys")

        def host_box(root):
            self.assert_no_system_error(root)
            # Wait for the actual unobscured host after the launcher closes its action UI.
            if any(node.get("resource-id") in (self.launcher + ":id/widget_resize_frame",
                                                self.launcher + ":id/system_shortcuts_container")
                   or (node.get("resource-id") == self.launcher + ":id/bubble_text"
                       and node.get("text") in resize_labels)
                   or (node.get("resource-id") == self.launcher + ":id/label"
                       and node.get("text", "").startswith("Move to row "))
                   for node in root.iter("node")):
                return None
            hosts = [node for node in root.iter("node") if node.get("package") == self.launcher
                     and node.get("class", "").endswith("AppWidgetHostView")
                     and node.get("content-desc") == item["label"] and bounds(node)]
            assert len(hosts) <= 1, "Ambiguous Mini Rings host"
            if not hosts:
                return None
            content = [node.get("text", "") for node in hosts[0].iter("node")]
            assert not any("Can't show content" in text or "Problem loading widget" in text for text in content), content
            return bounds(hosts[0]) if any("No matching accounts" in text for text in content) else None

        def observe(name, predicate):
            self.wait_for(name, lambda root: (box := host_box(root)) is not None and predicate(box))
            box = host_box(self.widget_capture(name))
            assert box is not None and predicate(box), "Mini Rings geometry/content changed during capture"
            assert self.widget_bindings(name) == expected, "Move/resize changed a placed widget ID"
            return box

        def size(box):
            return box[2] - box[0], box[3] - box[1]

        def move_target(root):
            self.assert_no_system_error(root)
            nodes = [node for node in root.iter("node") if node.get("package") == self.launcher
                     and node.get("resource-id") == self.launcher + ":id/label" and bounds(node)]
            assert len(nodes) <= 1, "Ambiguous launcher keyboard move target"
            if not nodes:
                return None
            text = nodes[0].get("text", "")
            target = re.fullmatch(r"Move to row (\d+) column (\d+) in (Home screen \d+ of \d+|New home screen page)", text)
            assert target, f"Unrecognized launcher move target: {text}"
            parents = {child: parent for parent in root.iter() for child in parent}
            popup = parents.get(nodes[0])
            while popup is not None and popup.get("focusable") != "true":
                popup = parents.get(popup)
            assert popup is not None and popup.get("package") == self.launcher and (
                popup.get("class") == "android.widget.LinearLayout" and popup.get("enabled") == "true"
            ), "Cannot identify the keyboard move popup's input-focus owner"
            return {"row": int(target[1]), "column": int(target[2]), "page": target[3], "text": text,
                    "focused": popup.get("focused") == "true"}

        self.find_placed_widget("move-resize-before-locate", item["label"])
        initial = observe("move-resize-before", lambda box: True)
        report["beforeBounds"] = initial
        record()
        focus_widget("move")
        self.adb("shell", "input", "keyboard", "keycombination", "KEYCODE_CTRL_LEFT", "KEYCODE_M")
        self.wait_for("move-target-loaded", lambda root: move_target(root) is not None)
        original_target = move_target(self.widget_capture("move-target-original"))
        assert original_target is not None and original_target["page"].startswith("Home screen "), "No existing-page move origin"
        report["moveOrigin"] = original_target
        report["moveFocus"] = []
        report["moveTargets"] = []
        target = original_target
        # Relative focus search rejects a sole candidate; acquire the popup with
        # bounded directional navigation before traversing virtual targets with TAB.
        for index, key in enumerate(("DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT", "DPAD_UP")):
            if target["focused"]:
                break
            self.adb("shell", "input", "keyboard", "keyevent", "KEYCODE_" + key)
            focus_step = {"key": key}
            report["moveFocus"].append(focus_step)
            record()
            target = move_target(self.widget_capture(f"move-focus-{index}"))
            focus_step["target"] = target
            record()
            assert target is not None, "Keyboard move UI disappeared while acquiring input focus"
        assert target["focused"], "Keyboard move popup did not acquire input focus within four directional keys"

        def is_destination(candidate):
            return candidate["page"] == original_target["page"] and (
                candidate["row"], candidate["column"]) != (original_target["row"], original_target["column"])

        # A focus-acquisition key may already have selected a valid destination.
        selected = target if is_destination(target) else None
        seen = {target["text"]}
        # TAB traverses valid cells in reading order; spatial DPAD navigation can change pages.
        for index in range(40):
            if selected is not None:
                break
            previous = target["text"]
            self.adb("shell", "input", "keyboard", "keyevent", "KEYCODE_TAB")
            self.wait_for(f"move-target-{index}-changed", lambda root: (
                (candidate := move_target(root)) is not None and candidate["focused"] and candidate["text"] != previous))
            target = move_target(self.widget_capture(f"move-target-{index}"))
            assert target is not None, "Keyboard move UI disappeared before a target was chosen"
            assert target["focused"] and target["text"] != previous, "Keyboard move target lost its observed change/focus"
            report["moveTargets"].append({"key": "TAB", "target": target})
            record()
            if is_destination(target):
                selected = target
                break
            assert target["text"] not in seen, "Keyboard move targets cycled without a different same-page destination"
            seen.add(target["text"])
        assert selected is not None, "No different observed move target on the original Home page"
        self.adb("shell", "input", "keyboard", "keyevent", "KEYCODE_ENTER")
        self.wait_for("move-drop-frame", lambda root: post_drop_frame(root, self.launcher, item["label"], initial) is not None)
        assert post_drop_frame(self.widget_capture("move-drop"), self.launcher, item["label"], initial) is not None, (
            "Dropped Mini Rings frame lost focus")
        self.adb("shell", "input", "keyboard", "keyevent", "KEYCODE_ESCAPE")
        moved = observe("move-after", lambda box: size(box) == size(initial) and box[:2] != initial[:2])
        report["steps"].append({"action": "move", "target": selected, "before": initial, "after": moved})
        record()
        self.verify_widgets_after_upgrade(placed, phase="move", only_label=item["label"])

        current = moved
        for axis, dimension in enumerate(["width", "height"]):
            original_size = size(current)
            for action in ["Increase", "Decrease"]:
                label = action + " " + dimension
                name = "resize-" + action.lower() + "-" + dimension
                before = current
                focus_widget(name)
                self.adb("shell", "input", "keyboard", "keycombination", "KEYCODE_CTRL_LEFT", "KEYCODE_R")
                self.tap(self.widget_control(name + "-option", self.launcher, label=label))
                self.widget_capture(name + "-selected")
                self.adb("shell", "input", "keyboard", "keyevent", "KEYCODE_ESCAPE")
                def resized(box):
                    after_size, before_size = size(box), size(before)
                    return after_size[1 - axis] == before_size[1 - axis] and (
                        after_size[axis] > before_size[axis] if action == "Increase"
                        else after_size[axis] == original_size[axis] < before_size[axis])
                current = observe(name + "-after", resized)
                report["steps"].append({"action": label, "before": before, "after": current})
                record()
        assert size(current) == size(initial), "Resize round trip did not restore Mini Rings dimensions"
        self.verify_widgets_after_upgrade(placed, phase="resize", only_label=item["label"])
        report.update({"result": "passed", "afterBounds": current, "verificationSeconds": time.monotonic() - started,
                       "scope": "Same-page Mini Rings move and horizontal/vertical grow-shrink; unchanged IDs for all four widgets; visible empty content and saved Mini Rings appearance read without saving."})
        record()

    def verify_widget_lifecycle(self, placed):
        original = self.adb("shell", "cmd", "uimode", "night")
        mode = re.search(r"Night mode: (auto|no|yes|custom)\b", original)
        assert mode, f"Unrecognized original night mode: {original}"
        try:
            for target, expected_night in [("yes", 0x20), ("no", 0x10)]:
                self.adb("shell", "cmd", "uimode", "night", target)
                state = self.adb("shell", "dumpsys", "uimode")
                self.text(f"widgets-night-{target}-system.txt", state)
                current = re.search(r"mCurUiMode=0x([0-9a-fA-F]+)", state)
                assert current and (int(current[1], 16) & 0x30) == expected_night, "System did not apply the requested night mode"
                self.verify_widgets_after_upgrade(placed, phase="night-" + target)
        finally:
            self.adb("shell", "cmd", "uimode", "night", mode[1])

        self.launch("widgets-before-process-loss")
        self.widget_home("widgets-before-process-loss-home")
        before = set(self.adb("shell", "pidof", PACKAGE).split())
        assert before, "No background app process exists to exercise normal process loss"
        # HOME hides the activity, but newly started Glance SessionWorkers can still
        # hold a JobService binding. Android 16 am kill skips setAdj < SERVICE_ADJ
        # (500), so observe eligibility before issuing the one normal kill request.
        ready = False
        started = time.monotonic()
        report = {"before": sorted(before), "after": None, "eligibility": []}
        while time.monotonic() - started < 120:
            processes = self.adb("shell", "dumpsys", "activity", "processes", PACKAGE)
            blocks = re.split(r"(?m)^  \*(?:APP|PERS)\* UID \d+ ", processes)[1:]
            adjustments = {}
            for block in blocks:
                pid = re.match(r"ProcessRecord\{\S+ (\d+):" + re.escape(PACKAGE) + r"/", block)
                adj = re.search(r"(?m)^    oom adj:.*\bset=(-?\d+)$", block)
                if pid and adj:
                    adjustments[pid[1]] = int(adj[1])
            report["eligibility"].append({"seconds": time.monotonic() - started,
                                          "setAdj": adjustments})
            self.text("widgets-process-loss.json", json.dumps(report, indent=2))
            self.text("widgets-process-loss-processes.txt", processes)
            if len(report["eligibility"]) == 1:
                self.text("widgets-process-loss-initial-processes.txt", processes)
                self.text("widgets-process-loss-initial-services.txt", self.adb(
                    "shell", "dumpsys", "activity", "services", PACKAGE))
                self.text("widgets-process-loss-initial-jobs.txt", self.adb(
                    "shell", "dumpsys", "jobscheduler", PACKAGE))
            if set(adjustments) == before and all(adj >= 500 for adj in adjustments.values()):
                ready = True
                break
            time.sleep(1)
        assert ready, "Background app processes did not become eligible for normal am kill"
        self.adb("shell", "am", "kill", PACKAGE)
        for _ in range(12):
            try:
                after = set(self.adb("shell", "pidof", PACKAGE).split())
            except subprocess.CalledProcessError as error:
                if error.returncode != 1:
                    raise
                after = set()
            if before.isdisjoint(after):
                break
            time.sleep(1)
        report["after"] = sorted(after)
        self.text("widgets-process-loss.json", json.dumps(report, indent=2))
        assert before.isdisjoint(after), "am kill did not end the observed background app process"
        self.verify_widgets_after_upgrade(placed, phase="process-loss")

        self.widget_home("widgets-before-launcher-restart")
        home = self.adb("shell", "cmd", "package", "resolve-activity", "--brief",
                        "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME").splitlines()[-1]
        assert home.startswith(self.launcher + "/"), "The active HOME component changed"
        before = set(self.adb("shell", "pidof", self.launcher).split())
        assert before, "No launcher process exists to exercise a launcher restart"
        restart_started = time.monotonic()
        self.adb("shell", "am", "force-stop", self.launcher)
        self.text("widgets-launcher-restart-start.txt", self.adb(
            "shell", "am", "start", "-W", "-n", home,
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME"))
        for _ in range(12):
            try:
                after = set(self.adb("shell", "pidof", self.launcher, timeout=5).split())
            except subprocess.CalledProcessError as error:
                if error.returncode != 1:
                    raise
                after = set()
            if after and before.isdisjoint(after):
                break
            time.sleep(1)
        restart = {"component": home, "before": sorted(before), "after": sorted(after),
                   "restartSeconds": time.monotonic() - restart_started}
        self.text("widgets-launcher-restart.json", json.dumps(restart, indent=2))
        assert after and before.isdisjoint(after), "Launcher restart did not replace the observed process"
        self.wait_for_launcher("widgets-launcher-restart-home", home)
        verification_started = time.monotonic()
        self.verify_widgets_after_upgrade(placed, phase="launcher-restart")
        restart["verificationSeconds"] = time.monotonic() - verification_started
        self.text("widgets-launcher-restart.json", json.dumps(restart, indent=2))

        # Reboot clears logcat; preserve and check the preceding runtime before it can be lost.
        assert self.diagnostics("pre-reboot-"), "Could not collect the pre-reboot runtime diagnostics"
        self.assert_no_app_failures("pre-reboot-")
        boot_before = self.adb("shell", "cat", "/proc/sys/kernel/random/boot_id")
        boot_id_pattern = r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"
        assert re.fullmatch(boot_id_pattern, boot_before), "Unrecognized pre-reboot kernel boot ID"
        reboot = {"beforeBootId": boot_before, "afterBootId": None, "bootCompleted": None}
        self.text("widgets-reboot.json", json.dumps(reboot, indent=2))
        reboot_started = time.monotonic()
        self.adb("reboot", timeout=30)
        deadline = reboot_started + 180
        ready = False
        last_error = None
        while time.monotonic() < deadline:
            try:
                reboot["afterBootId"] = self.adb("shell", "cat", "/proc/sys/kernel/random/boot_id",
                                                  timeout=min(5, max(0.1, deadline - time.monotonic())))
                reboot["bootCompleted"] = self.adb("shell", "getprop", "sys.boot_completed",
                                                    timeout=min(5, max(0.1, deadline - time.monotonic())))
                ready = bool(re.fullmatch(boot_id_pattern, reboot["afterBootId"])) and (
                    reboot["afterBootId"] != boot_before and reboot["bootCompleted"] == "1")
                if ready:
                    break
            except subprocess.SubprocessError as error:
                last_error = str(error)
            time.sleep(min(1, max(0, deadline - time.monotonic())))
        reboot.update({"bootWaitSeconds": time.monotonic() - reboot_started, "lastProbeError": last_error})
        self.text("widgets-reboot.json", json.dumps(reboot, indent=2))
        assert ready, "Device did not report a completed new boot within 180 seconds"
        self.wait_for_launcher("widgets-reboot-home", home)
        verification_started = time.monotonic()
        self.verify_widgets_after_upgrade(placed, phase="reboot")
        reboot["verificationSeconds"] = time.monotonic() - verification_started
        self.text("widgets-reboot.json", json.dumps(reboot, indent=2))
        self.verify_widget_network_lifecycle(placed)

    def verify_widget_network_lifecycle(self, placed):
        # Android16 ConnectivityService.dump/NetworkAgentInfo.toString and
        # WifiShellCommand.printStatus define these formats. Preserve every raw probe.
        def state(name, timeout=5):
            reports = {}
            for kind, command in {
                "airplane": ("shell", "cmd", "connectivity", "airplane-mode"),
                "wifi": ("shell", "cmd", "wifi", "status"),
                "connectivity": ("shell", "dumpsys", "connectivity"),
            }.items():
                reports[kind] = self.adb(*command, timeout=timeout)
                self.text(name + "-" + kind + ".txt", reports[kind])
            assert reports["airplane"] in ("enabled", "disabled"), "Unknown airplane-mode query output"
            wifi = re.findall(r"^Wifi is (enabled|disabled)$", reports["wifi"], re.M)
            assert len(wifi) == 1, "Unknown Wi-Fi status output"
            defaults = re.findall(r"^Active default network: (none|\d+)$", reports["connectivity"], re.M)
            sections = re.findall(r"(?ms)^Current Networks:\n(.*?)^Status for known UIDs:", reports["connectivity"])
            assert len(defaults) == len(sections) == 1, "Unknown ConnectivityService dump sections"
            lines = [line for line in sections[0].splitlines() if line.strip()]
            networks = {}
            if lines:
                indent = len(lines[0]) - len(lines[0].lstrip())
                assert indent > 0, "Unknown current-network indentation"
                for line in lines:
                    depth = len(line) - len(line.lstrip())
                    assert depth >= indent, "Unknown current-network dump entry"
                    if depth > indent:
                        continue  # Per-network Requests can also contain INTERNET; they are not links.
                    header = line.strip()
                    net = re.match(r"NetworkAgentInfo\{network\{(\d+)\}", header)
                    info = re.search(r"  ni\{(.*?)\} created=", header)
                    caps = re.search(r"  nc\{(\[.*\])\}  factorySerialNumber=-?\d+\}$", header)
                    assert net and info and caps, "Unknown NetworkAgentInfo header"
                    detail = re.fullmatch(r"[^\[\] ]+(?:\[[^\]]*\])? ([A-Z_]+)(?: ROAMING)?(?: extra: .*)?", info[1])
                    assert detail and detail[1] in {
                        "IDLE", "SCANNING", "CONNECTING", "AUTHENTICATING", "OBTAINING_IPADDR",
                        "CONNECTED", "SUSPENDED", "DISCONNECTING", "DISCONNECTED", "FAILED",
                        "BLOCKED", "VERIFYING_POOR_LINK", "CAPTIVE_PORTAL_CHECK",
                    }, "Unknown network detailed state"
                    assert " UnderlyingNetworks: " in caps[1], "Unknown NetworkCapabilities format"
                    capability_list = re.search(r" Capabilities: (\S+)", caps[1])
                    assert capability_list or "Capabilities" not in caps[1], "Unknown network capability list"
                    assert not capability_list or re.fullmatch(r"[A-Z0-9_]+(?:&[A-Z0-9_]+)*", capability_list[1]), "Unknown network capability tokens"
                    assert net[1] not in networks, "Duplicate current-network ID"
                    networks[net[1]] = {"state": detail[1], "capabilities": capability_list[1].split("&") if capability_list else []}
            default = None if defaults[0] == "none" else defaults[0]
            connected = {key for key, value in networks.items()
                         if value["state"] == "CONNECTED" and "INTERNET" in value["capabilities"]}
            return {"airplane": reports["airplane"], "wifi": wifi[0], "defaultNetwork": default,
                    "networks": networks, "offline": default is None and not connected,
                    "online": default in connected and "VALIDATED" in networks[default]["capabilities"]}

        def wait_state(name, predicate):
            started = time.monotonic()
            deadline = started + 60
            attempt = 0
            while time.monotonic() < deadline:
                observed = state(f"{name}-{attempt}", timeout=min(5, max(0.1, (deadline - time.monotonic()) / 3)))
                if predicate(observed):
                    return {"state": observed, "waitSeconds": time.monotonic() - started}
                attempt += 1
                time.sleep(min(1, max(0, deadline - time.monotonic())))
            raise AssertionError(f"Network did not reach {name} within 60 seconds")

        original = state("widgets-network-before")
        assert original["online"], "Network lifecycle requires an initially connected, validated default network"
        is_restored = lambda observed: observed["online"] and observed["airplane"] == original["airplane"] and observed["wifi"] == original["wifi"]
        report = {"original": original, "scope": "System network transitions and empty-account widget persistence; no provider requests."}
        self.text("widgets-network.json", json.dumps(report, indent=2))
        try:
            self.adb("shell", "cmd", "connectivity", "airplane-mode", "enable")
            self.adb("shell", "cmd", "wifi", "set-wifi-enabled", "disabled")
            is_offline = lambda observed: observed["offline"] and observed["airplane"] == "enabled" and observed["wifi"] == "disabled"
            report["offline"] = wait_state("widgets-network-offline", is_offline)
            self.text("widgets-network.json", json.dumps(report, indent=2))
            started = time.monotonic()
            self.verify_widgets_after_upgrade(placed, phase="offline")
            report["offline"]["verificationSeconds"] = time.monotonic() - started
            report["offline"]["afterVerification"] = state("widgets-network-offline-verified")
            assert is_offline(report["offline"]["afterVerification"]), "Offline state changed during widget verification"
        finally:
            try:
                self.adb("shell", "cmd", "connectivity", "airplane-mode",
                         "enable" if original["airplane"] == "enabled" else "disable")
            finally:
                self.adb("shell", "cmd", "wifi", "set-wifi-enabled", original["wifi"])
            report["restored"] = wait_state("widgets-network-restored", is_restored)
            self.text("widgets-network.json", json.dumps(report, indent=2))
        started = time.monotonic()
        self.verify_widgets_after_upgrade(placed, phase="online")
        report["restored"]["verificationSeconds"] = time.monotonic() - started
        report["restored"]["afterVerification"] = state("widgets-network-online-verified")
        assert is_restored(report["restored"]["afterVerification"]), "Restored network state changed during widget verification"
        report["result"] = "passed"
        self.text("widgets-network.json", json.dumps(report, indent=2))

    def performance_observations(self, phase, apk, placed):
        """Descriptive launches after force-stop, grouped by Android's actual launch state."""
        expected_widgets = {item["component"]: item["id"] for item in placed}
        assert len(expected_widgets) == 4 and self.widget_bindings(phase + "-performance") == expected_widgets
        activity = self.adb("shell", "cmd", "package", "resolve-activity", "--brief", PACKAGE).splitlines()[-1]
        assert activity.startswith(PACKAGE + "/"), activity
        report = {
            "apk": apk, "environmentReport": "environment.json", "widgets": placed,
            "nightMode": self.adb("shell", "cmd", "uimode", "night"),
            "method": "One excluded warmup and three launches after force-stop; any previously observed PIDs must be absent, while a new background PID is allowed. Grouped by Android's reported launch state; empty Overview; five seconds idle before process meminfo.",
            "limits": "Background jobs can restart the process before the post-stop PID check or activity launch, so force-stop does not guarantee a process-free interval or a COLD sample. Initial display, not fully ready or storage-cold. Sequential shared CI emulator, uncontrolled ART/file caches and host load. No timing thresholds or improvement claim. Memory reports exclude launcher memory and are retained raw for separate analysis.",
            "samples": [],
        }

        def pids():
            try:
                return self.adb("shell", "pidof", PACKAGE).split()
            except subprocess.CalledProcessError as error:
                if error.returncode != 1:
                    raise
                return []

        # The empty Overview's readiness marker of the build being measured.
        ready = BASELINE_EMPTY_OVERVIEW if phase == "previous" else EMPTY_OVERVIEW
        self.launch(phase + "-performance-prepare")
        self.navigate("Overview", ready, phase + "-performance-prepare-overview")
        assert overview_ready(self.dump(phase + "-performance-ready"), phase), "Empty Overview is not ready"
        for index in range(4):
            name = f"{phase}-performance-{index}"
            before_stop = pids()
            self.adb("shell", "am", "force-stop", PACKAGE)
            stopped_pid_observations = []
            for _ in range(12):
                observed = pids()
                stopped_pid_observations.append(observed)
                if set(before_stop).isdisjoint(observed):
                    break
                time.sleep(1)
            before_launch = pids()
            self.text(name + "-process-transition.json", json.dumps({
                "pidsBeforeForceStop": before_stop, "pidsAfterForceStop": stopped_pid_observations,
                "pidsBeforeLaunch": before_launch,
            }, indent=2))
            assert set(before_stop).isdisjoint(before_launch), "A prior app PID survived performance force-stop"
            launch = self.adb("shell", "am", "start", "-W", "-n", activity,
                              "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER")
            self.text(name + "-launch.txt", launch)
            assert re.search(r"^Status: ok$", launch, re.M), "Performance launch did not succeed"
            launch_states = re.findall(r"^LaunchState: (\S+)$", launch, re.M)
            assert len(launch_states) == 1 and launch_states[0] in ("COLD", "WARM", "HOT"), "Unknown performance launch state"
            launch_state = launch_states[0]
            total = re.search(r"^TotalTime: (\d+)$", launch, re.M)
            assert total, "Performance launch did not report TotalTime"
            wait = re.search(r"^WaitTime: (\d+)$", launch, re.M)
            root = self.wait_for(name + "-overview", lambda root: overview_ready(root, phase))
            self.assert_no_system_error(root)
            ready_at = time.monotonic()
            observed_pids = pids()
            assert len(observed_pids) == 1, "Expected one foreground app process for memory observation"
            time.sleep(5)
            assert pids() == observed_pids, "App process changed during memory settling"
            memory_delay = time.monotonic() - ready_at
            memory = self.adb("shell", "dumpsys", "meminfo", observed_pids[0])
            self.text(name + "-meminfo.txt", memory)
            assert pids() == observed_pids, "App process changed during memory observation"
            # Preserve the complete platform report for separate memory analysis.
            report["samples"].append({
                "index": index, "warmup": index == 0, "launchState": launch_state,
                "pidsBeforeForceStop": before_stop, "pidsBeforeLaunch": before_launch,
                "totalTimeMs": int(total[1]),
                "waitTimeMs": int(wait[1]) if wait else None, "pid": observed_pids[0],
                "memoryDelaySeconds": memory_delay, "memoryReport": name + "-meminfo.txt",
                "memoryPssKiB": None, "memoryStatus": "raw report retained; PSS not parsed by this script",
            })
            report["totalTimeMsByLaunchState"] = {}
            for state in ("COLD", "WARM", "HOT"):
                measured = [sample["totalTimeMs"] for sample in report["samples"]
                            if not sample["warmup"] and sample["launchState"] == state]
                if measured:
                    report["totalTimeMsByLaunchState"][state] = {
                        "count": len(measured), "median": statistics.median(measured),
                        "min": min(measured), "max": max(measured)}
            self.text(phase + "-performance.json", json.dumps(report, indent=2))

    def wait_for(self, name, predicate, deadline=None):
        last_error = None
        for _ in range(12):
            try:
                root = self.dump(name, deadline=deadline)
                if predicate(root):
                    return root
            except (subprocess.SubprocessError, ET.ParseError) as error:
                last_error = error
            # Under a deadline the pause ends with it, and a passed one ends the retries (BudgetExhausted).
            time.sleep(1 if deadline is None else min(1, remaining(deadline, name)))
        raise AssertionError(f"UI did not reach {name}; last dump error: {last_error}")

    def tap(self, node, deadline=None):
        x1, y1, x2, y2 = bounds(node)
        self.adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2), deadline=deadline)

    def launch(self, name, package=PACKAGE):
        activity = self.adb("shell", "cmd", "package", "resolve-activity", "--brief", package).splitlines()[-1]
        assert activity.startswith(package + "/"), activity
        self.text(name + "-launch.txt", self.adb("shell", "am", "start", "-W", "-n", activity))
        self.wait_for(name, lambda root: navigation_target(root, "Overview", package) is not None)

    def navigate(self, tab, marker, name, package=PACKAGE):
        root = self.dump(name + "-before")
        target = navigation_target(root, tab, package)
        assert target is not None, f"No enabled {tab} navigation item"
        if target.get("selected") != "true":
            assert target.get("clickable") == "true", f"Unselected {tab} tab is not clickable"
            self.tap(target)
        self.wait_for(name + "-selected", lambda root: selected_tab(root, tab, package))
        # A restored Settings destination can retain the previous scroll position.
        if tab == "Settings":
            for attempt in range(9):
                root = self.dump(f"{name}-top-{attempt}")
                if matches(root, marker, package):
                    break
                self.scroll(root, up=False)
        self.wait_for(name, lambda root: selected_tab(root, tab, package) and bool(matches(root, marker, package)))
        self.capture(name)
        assert self.adb("shell", "pidof", package), "App process exited after navigation"

    def seed_list(self, name, apk):
        """Installs this run's debug build beside the release app and gives it the synthetic accounts.

        The accounts come from the debug-only evidence receiver's `seed` command, the same fixture the
        status-icon lane uses: six providers, one exhausted, one stale, one failed. Its completion file
        is read back by token; the release app is not touched. Returns the receiver's completion.
        """
        self.text(name + "-install.txt", self.adb("install", "-r", str(apk), timeout=180))
        # As for the release app above: granted before the first launch, or Android's prompt
        # covers the List on API 33 and later.
        self.adb("shell", "pm", "grant", DEBUG_PACKAGE, "android.permission.POST_NOTIFICATIONS")
        token = uuid.uuid4().hex
        self.text(name + "-broadcast.txt", self.adb("shell", "am", "broadcast", "-f", "0x20", "-n", DEBUG_RECEIVER,
                                                     "--es", "command", "seed", "--es", "token", token, timeout=120))
        state = None
        for _ in range(90):
            try:
                state = json.loads(self.adb("exec-out", "run-as", DEBUG_PACKAGE, "cat", "files/status-icon-evidence.json",
                                            timeout=8))
            except (subprocess.SubprocessError, json.JSONDecodeError):
                state = None
            if state is not None and state.get("token") == token:
                break
            time.sleep(1)
        assert state is not None and state.get("token") == token, "The debug build did not confirm the seed"
        self.text(name + "-seed.json", json.dumps(state, indent=2))
        assert state.get("success") is True, "Seed failed: " + str(state.get("error"))
        assert len(state.get("accounts", [])) == 6 and len(state.get("overview", [])) == 6, "Seed did not add six accounts"
        self.launch(name + "-start", DEBUG_PACKAGE)
        self.wait_for(name + "-list", lambda root: three_tabs(root, DEBUG_PACKAGE)
                      and self.control(root, DEBUG_PACKAGE, label="All") is not None
                      and any("(synthetic)" in (node.get("text") or "") for node in root.iter("node")
                              if node.get("package") == DEBUG_PACKAGE))
        self.capture(name + "-list")
        return state

    def seeded_steps(self, debug_apk):
        """The debug-build steps when Android CI passes its debug APK, and their record for result.json.

        Without one (the release workflow) nothing is installed beside the release app and the result
        says the steps were skipped, so a missing seeded check is never mistaken for a passed one.
        """
        if debug_apk is None:
            return {"seededList": "skipped: no --debug-apk"}
        seeded = self.seed_list("seeded", debug_apk)
        self.verify_chip_press("chip", DEBUG_PACKAGE)
        row = self.open_seeded_row("seeded-row", DEBUG_PACKAGE)
        # The order the debug receiver computed is driver evidence; the rows on screen, top to
        # bottom, are what the UI showed (as many as fit). Both are recorded, neither is asserted.
        return {"seededList": "passed", "seededOverviewByDriver": seeded["overview"],
                "seededRowsShownByUi": row["shownOnScreen"], "seededRowTapped": row["tapped"],
                "chipPress": "chip-ripple.json", "motionFrames": {"report": "motion.json"}}

    def open_seeded_row(self, name, package):
        """A tap on a List row opens that row's account without the bar, and Back returns to the List.

        The second row is tapped, not the first, so a tap that always opened the top account would
        fail. A row is identified by its identity line, the account label ("Kimi (synthetic)"); the
        detail must show the same label under its title. Returns the labels of the rows on screen,
        top to bottom, as the UI showed them.
        """
        root = self.dump(name + "-before")
        lines = sorted((node for node in root.iter("node") if node.get("package") == package
                        and (node.get("text") or "").endswith(" (synthetic)")), key=lambda node: bounds(node)[1])
        shown = [node.get("text") for node in lines]
        assert len(shown) >= 2, f"Fewer than two seeded rows on screen: {shown}"
        chosen = shown[1]
        row = self.control(root, package, label=lines[1].get("text"))
        assert row is not None, f"The row of {chosen} is not pressable"
        self.tap(row)
        self.wait_for(name + "-detail", lambda root: navigation_target(root, "Overview", package) is None
                      and bool(matches(root, "Limits", package)) and bool(matches(root, chosen, package)))
        self.capture(name + "-detail")
        self.adb("shell", "input", "keyevent", "BACK")
        self.wait_for(name + "-back", lambda root: selected_tab(root, "Overview", package)
                      and self.control(root, package, label="All") is not None)
        return {"tapped": chosen, "shownOnScreen": shown}

    def verify_chip_press(self, name, package):
        """#26 on the seeded List (#88 01b): a held filter chip lights its rounded 32dp pill, not its 48dp target.

        Compares a frame during a held press with the frame at rest, only around Overview's filter row so the
        status-bar clock cannot interfere. A frame counts only if its capture ended well before the hold could
        release and the still-selected chip is unchanged; other attempts are retried. Releasing on the same
        point selects the held chip, so every attempt restores the default filter. The held chip is "Resets
        today", the one without a count, so its label is the whole of its text.
        """
        held, default = "Resets today", "All"
        density = self.adb("shell", "wm", "density")
        densities = dict(re.findall(r"^(Physical|Override) density: (\d+)$", density, re.M))
        assert "Physical" in densities, f"Unrecognized density report: {density}"
        px = int(densities.get("Override", densities["Physical"])) / 160

        def dp(value):
            return round(value * px)

        # The chip's shape is the app's medium one, radius 8dp; the target adds 8dp above and below.
        radius, hold_ms, margin_ms = 8 * px, 4000, 500
        report = {"result": "incomplete", "held": held, "restored": default, "pxPerDp": px, "attempts": [],
                  "scope": "Overview filter row of the seeded List (debug build, synthetic accounts); the pill is "
                           "the touch target inset by 8dp vertically."}

        def record():
            self.text(name + "-ripple.json", json.dumps(report, indent=2))

        self.navigate("Overview", default, name + "-overview", package)
        # Capture delays after the hold starts; an emulator frame can lag the press.
        for attempt, delay in enumerate([0.7, 1.0, 1.4]):
            root = self.capture(f"{name}-rest-{attempt}")
            target, original = self.control(root, package, label=held), self.control(root, package, label=default)
            assert target is not None and original is not None, "Missing the Overview filter chips"
            assert self.selected(original) and not self.selected(target), "The Overview filter is not at its default"
            node, other = bounds(target), bounds(original)
            assert (node[1], node[3]) == (other[1], other[3]), "The filter chips are not in one row"
            rest = (self.directory / f"{name}-rest-{attempt}.png").read_bytes()

            x, y = str((node[0] + node[2]) // 2), str((node[1] + node[3]) // 2)
            # adb starts the hold no earlier than Popen returns, so it releases no earlier than hold_ms after
            # swipe_start. A capture that ends inside the margin before that point cannot show the release.
            swipe_start = time.monotonic()
            hold = subprocess.Popen(["adb", "-s", self.serial, "shell", "input", "swipe", x, y, x, y, str(hold_ms)],
                                    stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            try:
                time.sleep(delay)
                capture_start = time.monotonic()
                pressed = self.adb("exec-out", "screencap", "-p", binary=True)
                capture_end = time.monotonic()
                self.write_screenshot(f"{name}-pressed-{attempt}", pressed)
            finally:
                _, error = hold.communicate(timeout=30)
            assert hold.returncode == 0, f"Held press failed: {error.decode().strip()}"
            released = self.control(self.capture(f"{name}-released-{attempt}"), package, label=held)
            self.tap(original)
            self.wait_for(f"{name}-restored-{attempt}", lambda root: (
                (restored := self.control(root, package, label=default)) is not None and self.selected(restored)
                and (other_node := self.control(root, package, label=held)) is not None
                and not self.selected(other_node)))

            row = (min(node[0], other[0]) - dp(2), node[1], max(node[2], other[2]) + dp(2), node[3])
            pill = (node[0], node[1] + dp(8), node[2], node[3] - dp(8))
            idle_pill = (other[0], other[1] + dp(8), other[2], other[3] - dp(8))
            before = png_frame(rest, row[3] + dp(24))
            after = png_frame(pressed, row[3] + dp(24))
            assert before[0] == after[0] and len(before[2]) == len(after[2]), "Rest and pressed frames differ in size"
            region = (max(0, row[0] - dp(24)), max(0, row[1] - dp(24)),
                      min(before[0], row[2] + dp(24)), min(len(before[2]), row[3] + dp(24)))
            changed = changed_pixels(before, after, region)
            measured = {
                "attempt": attempt, "holdSeconds": hold_ms / 1000, "captureDelaySeconds": delay,
                "captureStartSeconds": capture_start - swipe_start, "captureEndSeconds": capture_end - swipe_start,
                "latestCaptureEndSeconds": (hold_ms - margin_ms) / 1000,
                "nodeBounds": node, "rowBounds": row, "pill": pill, "idlePill": idle_pill, "radiusPx": radius,
                "fringePx": 1, "comparedRegion": region, "changedInRegion": len(changed),
                **judge_press(changed, pill, radius, idle_pill),
                "heldSegmentSelectedAfterRelease": released is not None and self.selected(released),
            }
            if measured["captureEndSeconds"] > measured["latestCaptureEndSeconds"]:
                measured["rejected"] = "capture ended too close to the release"
            elif measured["changedInIdlePill"]:
                measured["rejected"] = "the selected chip changed, so the frame follows a release"
            elif measured["changedInPill"] == 0:
                measured["rejected"] = "no visible press"
            report["attempts"].append(measured)
            record()
            (self.directory / (name + "-rest.png")).write_bytes(rest)
            (self.directory / (name + "-pressed.png")).write_bytes(pressed)
            (self.directory / (name + "-pressed-crop.png")).write_bytes(scaled_crop(after, region, 4))
            if "rejected" in measured:
                continue
            report["result"] = "spilled" if measured["changedOutsidePill"] else "passed"
            record()
            assert report["result"] == "passed", f"The press highlight left the rounded pill; see {name}-ripple.json"
            return
        report["result"] = "no accepted press frame"
        record()
        timings = [(round(item["captureStartSeconds"], 3), round(item["captureEndSeconds"], 3), item["rejected"])
                   for item in report["attempts"]]
        raise AssertionError(f"No accepted press frame (capture start/end s after the {hold_ms} ms hold began, "
                             f"reason): {timings}; see {name}-ripple.json")

    def burst(self, name, seconds, samples, deadline=None):
        """Screenshots taken back to back for `seconds`, each with when it started and ended.

        They are appended to `samples` as they are written, so the frames taken before a failure,
        the deadline or an interrupt stay with the caller.
        """
        started = time.monotonic()
        while len(samples) < 4 or (time.monotonic() - started < seconds and len(samples) < 24):
            begin = time.monotonic() - started
            data = self.adb("exec-out", "screencap", "-p", binary=True, deadline=deadline)
            filename = f"{name}-{len(samples):02d}"
            self.write_screenshot(filename, data)
            samples.append({"file": filename + ".png", "startSeconds": round(begin, 3),
                            "endSeconds": round(time.monotonic() - started, 3),
                            "sha256": hashlib.sha256(data).hexdigest()})

    def restore_animations(self, report):
        """Put the animator scale back to 0 and read it back; returns whether a read-back said 0.

        It runs on an allowance of its own, whatever the capture used up. A command that fails is
        not the answer: Android can apply a value although adb reports a timeout, so every attempt
        reads the value back, and an attempt that did not confirm 0 is repeated.
        """
        limits = {"timeout": MOTION_RESTORE_SECONDS / (2 * MOTION_RESTORE_ATTEMPTS),
                  "deadline": time.monotonic() + MOTION_RESTORE_SECONDS}
        report["animatorScaleRestored"] = None
        for attempt in range(1, MOTION_RESTORE_ATTEMPTS + 1):
            report["animatorScaleRestoreAttempts"] = attempt
            try:
                self.adb("shell", "settings", "put", "global", "animator_duration_scale", "0.0", **limits)
            except (OSError, subprocess.SubprocessError, BudgetExhausted) as error:
                report["animatorScaleRestoreError"] = f"{type(error).__name__}: {error}"
            try:
                report["animatorScaleRestored"] = self.adb(
                    "shell", "settings", "get", "global", "animator_duration_scale", **limits)
            except (OSError, subprocess.SubprocessError, BudgetExhausted) as error:
                report["animatorScaleRestoreError"] = f"{type(error).__name__}: {error}"
                continue
            if scale_is(report["animatorScaleRestored"], 0):
                return True
        return False

    def capture_motion(self, name, package=PACKAGE):
        """#81: frame sequences of the app's main motions, slowed down so a screenshot can catch them.

        The rest of the smoke runs with animations off. Here the animator scale is MOTION_SCALE, so a
        180 ms fade lasts 1.8 s and several `screencap` frames land inside it; the curve and the
        order of events are the app's own, only stretched. Window and transition scales stay 0, so
        system window motion (the splash screen's exit) is not part of what these frames show.

        This is evidence, not a gate, and runs after every mandatory check has passed and is on
        record. It has MOTION_BUDGET_SECONDS for everything it does: the time left is the upper limit
        of every adb and UIAutomator call and of every pause, and when it is used up the capture ends
        as `skipped` with the frames it has. A capture that goes wrong is recorded as `capture
        failed`, one whose scale did not read back as MOTION_SCALE as `skipped`. None of that, and no
        file it cannot write, fails the job; the outcome goes to the log and the job summary.

        The one exception: from the moment it asks for another animator scale, and not before, it
        owes the device a scale of 0, on every way out, an interrupt included, and read back. A
        scale it changed and cannot confirm as 0 again fails the job: an emulator left in an unknown
        state is not a passed run.
        """
        report = {"result": "incomplete", "animatorScale": MOTION_SCALE, "sequences": [], "package": package,
                  "scope": "This run's debug build with the seeded synthetic accounts: cold start, tab switch, "
                           "filter chip selection, push to an account's detail and pop. Bars, figures and list "
                           "reordering are covered by the Robolectric frame tests under "
                           "com.usagelimits.ui.motion. framesDiffer only "
                           "says that two screenshots of the whole screen differ in some byte, which a "
                           "status-bar clock tick also causes; it does not measure motion."}
        deadline = time.monotonic() + MOTION_BUDGET_SECONDS
        step = "resolve activity"
        scale_touched = False

        def at(label):
            nonlocal step
            step = label

        def adb(*args):
            return self.adb(*args, deadline=deadline)

        def wait(label, predicate):
            at(f"wait for {label}")
            return self.wait_for(f"{name}-{label}", predicate, deadline=deadline)

        def sequence(label, says, seconds, trigger):
            samples = []
            try:
                at(f"{label} trigger")
                trigger()
                at(f"{label} frames")
                self.burst(f"{name}-{label}", seconds, samples, deadline=deadline)
            finally:
                # Whatever ended the burst, the frames it wrote stay in the report.
                if samples:
                    report["sequences"].append(motion_sequence(label, says, samples))

        def tap(node, what):
            at(f"tap {what}")
            assert node is not None, f"Motion capture found no {what}"
            self.tap(node, deadline=deadline)

        try:
            activity = adb("shell", "cmd", "package", "resolve-activity", "--brief", package).splitlines()[-1]
            assert activity.startswith(package + "/"), activity
            at("set animator scale")
            # Before the command, not after it: Android can apply a value that adb reports as failed.
            scale_touched = True
            adb("shell", "settings", "put", "global", "animator_duration_scale", str(MOTION_SCALE))
            read_back = adb("shell", "settings", "get", "global", "animator_duration_scale")
            report["animatorScaleReadBack"] = read_back
            if not scale_is(read_back, MOTION_SCALE):
                # Frames taken at another scale would not show what the sequences say they show.
                raise CaptureSkipped(f"animator scale read back as {read_back!r}, not {MOTION_SCALE}")
            at("stop the app")
            adb("shell", "am", "force-stop", package)
            sequence("launch", "Process start: a bare ground, the launch overlay's icon rising once, then the overlay dissolving over Overview.",
                     9, lambda: adb("shell", "am", "start", "-n", activity))
            root = wait("launched", lambda root: navigation_target(root, "Overview", package) is not None
                        and self.control(root, package, label="Needs attention") is not None)

            sequence("tab", "Overview to Resets: two peers fade through in place.", 3,
                     lambda: tap(navigation_target(root, "Resets", package), "Resets tab"))
            root = wait("resets", lambda root: selected_tab(root, "Resets", package))
            tap(navigation_target(root, "Overview", package), "Overview tab")
            root = wait("overview", lambda root: selected_tab(root, "Overview", package)
                        and self.control(root, package, label="Needs attention") is not None)

            sequence("segment", "Overview filter: the selected chip fades from All to Needs attention, and the "
                     "rows that do not match leave.", 2.5,
                     lambda: tap(self.control(root, package, label="Needs attention"), "Needs attention chip"))
            root = wait("segment-selected", lambda root: (
                (held := self.control(root, package, label="Needs attention")) is not None and self.selected(held)))
            tap(self.control(root, package, label="All"), "All chip")
            root = wait("segment-restored", lambda root: (
                (default := self.control(root, package, label="All")) is not None and self.selected(default)))

            def first_row(root):
                titles = [node for node in root.iter("node") if node.get("package") == package
                          and "(synthetic)" in (node.get("text") or "")]
                return self.control(root, package, label=titles[0].get("text")) if titles else None

            sequence("push", "Overview to an account's detail: the old screen leaves, the new one arrives shifted "
                     "from the end side, and the bottom bar leaves with the old screen.", 4,
                     lambda: tap(first_row(root), "List row"))
            wait("pushed", lambda root: navigation_target(root, "Overview", package) is None
                 and bool(matches(root, "Limits", package)))

            sequence("pop", "Back to Overview: the reverse shift, and the bottom bar returns.", 4,
                     lambda: adb("shell", "input", "keyevent", "BACK"))
            wait("popped", lambda root: selected_tab(root, "Overview", package))
            report["result"] = "captured"
        except CaptureSkipped as skipped:
            report["result"] = "skipped"
            report["reason"] = str(skipped)
        except Exception as error:
            # Optional evidence must not fail the job, so any failure in it is recorded, not raised.
            # A call that the budget cut short fails like any other; the clock tells them apart.
            if isinstance(error, BudgetExhausted) or time.monotonic() >= deadline:
                report["result"] = "skipped"
                report["reason"] = f"time budget of {MOTION_BUDGET_SECONDS} s exhausted at step {step}"
            else:
                report["result"] = "capture failed"
                report["error"] = f"{type(error).__name__}: {error}"
        except BaseException as error:
            # KeyboardInterrupt, SystemExit: passed on once the scale is back and the report is written.
            report["result"] = "capture failed"
            report["error"] = f"{type(error).__name__}: {error}"
            raise
        finally:
            restored = not scale_touched or self.restore_animations(report)
            if not restored:
                report["smokeStopped"] = (
                    f"Animations were not turned off again: the animator scale read "
                    f"{report['animatorScaleRestored']!r} after {MOTION_RESTORE_ATTEMPTS} attempts.")
            outcome = "Motion capture (optional evidence): " + report["result"] + "".join(
                f"; {report[key]}" for key in ("reason", "error", "smokeStopped") if key in report)
            try:
                self.text(name + ".json", json.dumps(report, indent=2))
            except OSError as error:
                outcome += f"; could not write {name}.json: {error}"
            print(outcome)
            if summary := os.environ.get("GITHUB_STEP_SUMMARY"):
                try:
                    with open(summary, "a") as file:
                        file.write(outcome + "\n")
                except OSError as error:
                    print(f"Motion capture could not write the job summary: {error}")
        # The one way this optional capture fails the job; see the docstring.
        assert restored, report["smokeStopped"]
        return report["result"]

    def setting(self, name):
        for attempt in range(9):
            root = self.dump(f"{name}-{attempt}")
            target = self.control(root, PACKAGE, label=SETTING)
            if target is not None and target.get("checkable") == "true":
                return target
            self.scroll(root, up=True)
        raise AssertionError(f"Could not reach {SETTING}")

    def scroll(self, root, up):
        scrollables = [node for node in root.iter("node") if node.get("package") == PACKAGE
                       and node.get("scrollable") == "true" and bounds(node)]
        assert scrollables, "No scrollable app content"
        x1, y1, x2, y2 = max((bounds(node) for node in scrollables), key=lambda b: (b[2] - b[0]) * (b[3] - b[1]))
        x = (x1 + x2) // 2
        start, end = y1 + (y2 - y1) * 3 // 4, y1 + (y2 - y1) // 4
        if not up:
            start, end = end, start
        self.adb("shell", "input", "swipe", str(x), str(start), str(x), str(end), "400")

    def setting_is_disabled(self, name):
        self.setting(name)
        self.wait_for(name + "-loaded", lambda root:
            (target := self.control(root, PACKAGE, label=SETTING)) is not None
            and target.get("checkable") == "true" and target.get("checked") == "false")

    def installed(self, name):
        package = self.adb("shell", "dumpsys", "package", PACKAGE)
        self.text(name + "-package.txt", package)
        fields = {}
        for key, pattern in {
            "versionCode": r"versionCode=(\d+)", "versionName": r"versionName=([^\r\n]+)",
            "appId": r"\bappId=(\d+)", "firstInstallTime": r"firstInstallTime=([^\r\n]+)",
        }.items():
            match = re.search(pattern, package)
            assert match, f"Installed package has no {key}"
            fields[key] = match.group(1).strip()
        return fields

    def diagnostics(self, prefix=""):
        complete = True
        for filename, command in {
            "crash-log.txt": ("logcat", "-b", "crash", "-d", "-v", "threadtime"),
            "system-log.txt": ("logcat", "-d", "-v", "threadtime"),
            "last-anr.txt": ("shell", "dumpsys", "activity", "lastanr"),
            "activity-exits.txt": ("shell", "dumpsys", "activity", "exit-info", PACKAGE),
            "widget-providers.txt": ("shell", "dumpsys", "appwidget"),
        }.items():
            try:
                self.text(prefix + filename, self.adb(*command))
            except subprocess.SubprocessError as error:
                self.text(prefix + filename, str(error))
                complete = False
        return complete

    def assert_no_app_failures(self, prefix=""):
        crash_log = (self.directory / (prefix + "crash-log.txt")).read_text()
        assert not re.search(r"Process: com\.usagelimits(?:,|\s)|>>> com\.usagelimits <<<", crash_log), "Application crash recorded"
        exits = (self.directory / (prefix + "activity-exits.txt")).read_text()
        # ApplicationExitInfo reasons: crash=4, native crash=5, ANR=6, initialization failure=7.
        assert not re.search(r"\breason=(?:4|5|6|7)\b", exits), "Application crash or ANR exit recorded"


def apk_metadata(aapt, path):
    text = run(str(aapt), "dump", "badging", str(path))
    match = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", text, re.M)
    assert match, f"No package metadata in {path}"
    return {"package": match[1], "versionCode": int(match[2]), "versionName": match[3], "sha256": digest(path)}


def argument_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--previous-apk", type=Path, required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--debug-apk", type=Path,
                        help="This run's debug build, installed beside the release app for the seeded List. Android CI "
                             "passes it; the release workflow does not, and then the seeded steps are skipped.")
    parser.add_argument("--apk-sha256-file", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    return parser


def main():
    args = argument_parser().parse_args()
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools"
    aapt = sorted(tools.glob("*/aapt"), key=lambda path: tuple(int(n) for n in re.findall(r"\d+", path.parent.name)))[-1]
    old = apk_metadata(aapt, args.previous_apk)
    new = apk_metadata(aapt, args.apk)
    assert old["package"] == new["package"] == PACKAGE, "Package identity changed"
    assert new["versionCode"] > old["versionCode"], "Upgrade must increase versionCode"
    expected_hash = args.apk_sha256_file.read_text().split()[0].lower()
    assert re.fullmatch(r"[0-9a-f]{64}", expected_hash) and new["sha256"] == expected_hash, "APK differs from assemble-job evidence"
    assert run("git", "rev-parse", "HEAD") == os.environ["GITHUB_SHA"], "Checkout differs from this workflow run"
    devices = [line.split()[0] for line in run("adb", "devices").splitlines()[1:]
               if len(line.split()) >= 2 and line.split()[1] == "device"]
    assert len(devices) == 1 and devices[0].startswith("emulator-"), "Requires exactly one CI emulator"
    smoke = Smoke(args.evidence, devices[0])
    assert smoke.adb("shell", "getprop", "ro.kernel.qemu") == "1", "Refusing to modify a physical device"
    assert smoke.adb("shell", "getprop", "ro.build.version.sdk") == "37", "Expected API 37"
    environment = {
        "commit": os.environ["GITHUB_SHA"], "runId": os.environ["GITHUB_RUN_ID"], "serial": devices[0],
        "previousApk": old, "currentApk": new,
        "fingerprint": smoke.adb("shell", "getprop", "ro.build.fingerprint"),
        "api": smoke.adb("shell", "getprop", "ro.build.version.sdk"),
        "abi": smoke.adb("shell", "getprop", "ro.product.cpu.abi"),
        "display": smoke.adb("shell", "wm", "size"), "density": smoke.adb("shell", "wm", "density"),
        "fontScale": smoke.adb("shell", "settings", "get", "system", "font_scale"),
        "launcher": smoke.adb("shell", "cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME"),
    }
    smoke.launcher = environment["launcher"].splitlines()[-1].split("/", 1)[0]
    smoke.text("environment.json", json.dumps(environment, indent=2))
    try:
        smoke.wait_for_launcher("initial-home", environment["launcher"].splitlines()[-1])
        # The AVD is fresh. No uninstall, clear-data, downgrade or synthetic account injection.
        smoke.text("previous-install.txt", smoke.adb("install", str(args.previous_apk), timeout=180))
        previous = smoke.installed("previous")
        assert int(previous["versionCode"]) == old["versionCode"] and previous["versionName"] == old["versionName"]
        smoke.adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
        smoke.launch("previous-start")
        smoke.navigate("Settings", "Provider icons", "previous-settings")
        setting = smoke.setting("previous-setting")
        assert setting.get("checked") == "true", "Fresh baseline setting did not have its documented default"
        smoke.tap(setting)
        smoke.wait_for("previous-setting-changed", lambda root:
            (target := smoke.control(root, PACKAGE, label=SETTING)) is not None
            and target.get("checkable") == "true" and target.get("checked") == "false")
        smoke.capture("previous-setting-changed")
        smoke.adb("shell", "am", "force-stop", PACKAGE)
        smoke.launch("previous-relaunch")
        smoke.navigate("Settings", "Provider icons", "previous-relaunch-settings")
        smoke.setting_is_disabled("previous-persisted")
        placed_widgets = smoke.place_widgets()
        smoke.performance_observations("previous", old, placed_widgets)

        smoke.text("upgrade-install.txt", smoke.adb("install", "-r", str(args.apk), timeout=180))
        current = smoke.installed("current")
        assert int(current["versionCode"]) == new["versionCode"] and current["versionName"] == new["versionName"]
        assert current["appId"] == previous["appId"] and current["firstInstallTime"] == previous["firstInstallTime"], "Package was replaced instead of updated"
        installed_paths = smoke.adb("shell", "pm", "path", PACKAGE).splitlines()
        assert len(installed_paths) == 1 and installed_paths[0].startswith("package:"), installed_paths
        installed_apk = args.evidence / "installed.apk"
        smoke.adb("pull", installed_paths[0].removeprefix("package:"), str(installed_apk))
        assert digest(installed_apk) == new["sha256"], "Installed APK differs from CI artifact"
        installed_apk.unlink()
        smoke.launch("current-start")
        smoke.wait_for("current-three-tabs", three_tabs)
        for tab, marker in [("Overview", EMPTY_OVERVIEW), ("Resets", "No upcoming resets"), ("Settings", "Provider icons")]:
            smoke.navigate(tab, marker, "current-" + tab.lower())
        smoke.setting_is_disabled("current-persisted")
        smoke.capture("current-persisted")
        smoke.adb("shell", "am", "force-stop", PACKAGE)
        smoke.launch("current-relaunch")
        smoke.navigate("Overview", EMPTY_OVERVIEW, "current-relaunch-overview")
        smoke.verify_widgets_after_upgrade(placed_widgets)
        smoke.performance_observations("current", new, placed_widgets)
        smoke.verify_widget_move_resize(placed_widgets)
        smoke.verify_widget_lifecycle(placed_widgets)
        # Last, so the release app's empty state, upgrade and widget host checks above never see it:
        # this run's debug build with synthetic accounts, for the List and its filter chips.
        seeded = smoke.seeded_steps(args.debug_apk)
    except Exception:
        try:
            smoke.capture("failure")
        except (subprocess.SubprocessError, ET.ParseError, AssertionError, struct.error, zlib.error, IndexError):
            pass
        raise
    finally:
        diagnostics_complete = smoke.diagnostics()
    assert diagnostics_complete, "Could not collect the runtime diagnostics"
    smoke.assert_no_app_failures()
    smoke.text("result.json", json.dumps({"result": "passed", "commit": os.environ["GITHUB_SHA"],
        "apkSha256": new["sha256"], "upgradePreservedSetting": SETTING, "tabs": list(TABS),
        "widgets": placed_widgets, **seeded,
        "scope": "Empty-account release smoke with four launcher widgets"
                 + (", then this run's debug build with synthetic accounts for the List and its filter chips"
                    if args.debug_apk else "; the seeded List steps were skipped (no --debug-apk)")
                 + "; provider-authenticated quotas were not tested."}, indent=2))
    print("Release APK upgrade, persisted setting, three tabs, four widgets, Mini Rings move/resize, night mode, process loss, "
          "launcher restart, reboot and offline/online checks passed; startup observations recorded. "
          + ("Seeded List chip press highlight and row tap passed." if args.debug_apk
             else "Seeded List steps skipped: no --debug-apk."))
    # Optional evidence, last: everything mandatory has passed and is on record above. It needs the
    # seeded debug build, which has a List, filter chips and rows to push; the emulator is discarded
    # with the job, so the debug build is not uninstalled.
    if args.debug_apk:
        smoke.capture_motion("motion", DEBUG_PACKAGE)


if __name__ == "__main__":
    main()
