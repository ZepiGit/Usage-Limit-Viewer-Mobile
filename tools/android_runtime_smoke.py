#!/usr/bin/env python3
"""Upgrade and navigate the actual release APK on an empty API 36 CI emulator.

Uses only adb and the Python standard library. No accounts, credentials or provider calls.
https://developer.android.com/tools/adb documents install -r, am start and screencap.
All touch coordinates come from the device's current UIAutomator hierarchy.
"""

import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import statistics
import subprocess
import time
import xml.etree.ElementTree as ET


PACKAGE = "com.usagelimits"
SETTING = "Show subscription tier"


def run(*args, timeout=40, binary=False):
    result = subprocess.run(args, check=True, capture_output=True, timeout=timeout)
    return result.stdout if binary else result.stdout.decode().strip()


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def bounds(node):
    values = [int(value) for value in re.findall(r"\d+", node.get("bounds", ""))]
    if len(values) != 4 or values[2] <= values[0] or values[3] <= values[1]:
        return None
    return tuple(values)


def matches(root, label):
    return [node for node in root.iter("node") if node.get("package") == PACKAGE
            and label in (node.get("text"), node.get("content-desc"))]


def navigation_target(root, label):
    parents = {child: parent for parent in root.iter() for child in parent}
    candidates = {}
    for node in matches(root, label):
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


def selected_tab(root, label):
    target = navigation_target(root, label)
    return target is not None and target.get("selected") == "true"


class Smoke:
    def __init__(self, directory, serial):
        self.directory = directory
        self.serial = serial
        self.directory.mkdir(parents=True, exist_ok=True)

    def adb(self, *args, **kwargs):
        return run("adb", "-s", self.serial, *args, **kwargs)

    def text(self, name, value):
        (self.directory / name).write_text(value + "\n")

    def dump(self, name):
        remote = "/sdcard/usage-runtime-window.xml"
        self.adb("shell", "rm", "-f", remote)
        self.adb("shell", "uiautomator", "dump", remote)
        xml = self.adb("exec-out", "cat", remote)
        self.text(name + ".xml", xml)
        return ET.fromstring(xml)

    def capture(self, name):
        root = self.dump(name)
        (self.directory / (name + ".png")).write_bytes(self.adb("exec-out", "screencap", "-p", binary=True))
        return root

    @staticmethod
    def assert_no_system_error(root):
        assert not any(node.get("resource-id", "").startswith("android:id/aerr_")
                       for node in root.iter("node")), "System error dialog blocks widget smoke; it was not dismissed"

    def widget_capture(self, name):
        # Keep transition/failure pixels if UIAutomator hangs, then capture the settled UI too.
        (self.directory / (name + "-before-dump.png")).write_bytes(self.adb("exec-out", "screencap", "-p", binary=True))
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
                    (self.directory / (attempt + ".png")).write_bytes(probe("exec-out", "screencap", "-p", binary=True))
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
            if target is not None and scroll and package == self.launcher:
                picker = next((node for node in root.iter("node")
                               if node.get("resource-id") == self.launcher + ":id/primary_widgets_list_view"
                               and node.get("scrollable") == "true" and bounds(node)), None)
                if picker is not None:
                    search = [node for node in root.iter("node")
                              if node.get("resource-id") == self.launcher + ":id/search_bar_container" and bounds(node)]
                    assert len(search) == 1, "Cannot identify the observed picker search overlay"
                    home = self.launcher_content_viewport
                    box = bounds(picker)
                    visible = (max(home[0], box[0]), max(home[1], box[1], bounds(search[0])[3]),
                               min(home[2], box[2]), min(home[3], box[3]))
                    assert visible[2] > visible[0] and visible[3] > visible[1], "No observed picker content viewport"
                    original = bounds(target)
                    clipped = (max(original[0], visible[0]), max(original[1], visible[1]),
                               min(original[2], visible[2]), min(original[3], visible[3]))
                    # Accessibility bounds may include pixels behind search/navigation.
                    # Tap the visible intersection, including controls at the list end.
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
                scrollables = [node for node in root.iter("node") if node.get("package") == package
                               and node.get("scrollable") == "true" and bounds(node)
                               and (node.get("resource-id") == self.launcher + ":id/primary_widgets_list_view"
                                    if package == self.launcher else bool(matches(root, "Save widget")))]
                if scrollables:
                    x1, y1, x2, y2 = max((bounds(node) for node in scrollables),
                                          key=lambda box: (box[2] - box[0]) * (box[3] - box[1]))
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
            self.tap(self.widget_control(name + "-add", self.launcher,
                                         resource_id=self.launcher + ":id/widget_add_button", scroll=True))
            self.widget_configuration(name + "-config", label, color, save=True)
            self.find_placed_widget(name + "-placed", label)
            bindings = self.widget_bindings(name)
            component = PACKAGE + ".widget." + receiver
            assert component in bindings, f"{label} was not bound to the launcher"
            placed.append({"label": label, "component": component, "color": color, "id": bindings[component]})
            self.text("widgets-placement.json", json.dumps(placed, indent=2))
        assert len(placed) == 4, "All four widget types must be placed"
        return placed

    def verify_widgets_after_upgrade(self, placed, phase="current"):
        expected = {item["component"]: item["id"] for item in placed}
        assert self.widget_bindings(phase + "-widgets") == expected, "Placed widget IDs changed"
        for index, item in enumerate(placed):
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
            self.tap(self.widget_control(name + "-edit", self.launcher,
                                         resource_id=self.launcher + ":id/widget_reconfigure_button"))
            self.widget_configuration(name + "-config", item["label"], item["color"], save=False)
            assert self.widget_bindings(name + "-verified") == expected, "Reconfigure replaced a widget ID"
        self.text(phase + "-widgets-verified.json", json.dumps({"result": "passed", "phase": phase, "widgets": placed,
                  "scope": "Visible empty-account host content, retained IDs and saved appearance selections; no fresh quota measurement."}, indent=2))

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
        self.text("widgets-process-loss.json", json.dumps({"before": sorted(before), "after": sorted(after)}, indent=2))
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
        """Descriptive process-cold startup observations, separate from normal process loss."""
        expected_widgets = {item["component"]: item["id"] for item in placed}
        assert len(expected_widgets) == 4 and self.widget_bindings(phase + "-performance") == expected_widgets
        activity = self.adb("shell", "cmd", "package", "resolve-activity", "--brief", PACKAGE).splitlines()[-1]
        assert activity.startswith(PACKAGE + "/"), activity
        report = {
            "apk": apk, "environmentReport": "environment.json", "widgets": placed,
            "nightMode": self.adb("shell", "cmd", "uimode", "night"),
            "method": "One excluded warmup and three process-cold force-stop launches; empty Overview; five seconds idle before process meminfo.",
            "limits": "Initial display, not fully ready; not storage-cold. Sequential shared CI emulator, uncontrolled ART/file caches and host load. No timing thresholds or improvement claim. Memory reports are snapshots, exclude launcher memory, and are retained raw until their platform format is verified.",
            "samples": [],
        }

        def pids():
            try:
                return self.adb("shell", "pidof", PACKAGE).split()
            except subprocess.CalledProcessError as error:
                if error.returncode != 1:
                    raise
                return []

        self.launch(phase + "-performance-prepare")
        self.navigate("Overview", "0 accounts monitored", phase + "-performance-prepare-overview")
        for index in range(4):
            name = f"{phase}-performance-{index}"
            self.adb("shell", "am", "force-stop", PACKAGE)
            for _ in range(12):
                if not pids():
                    break
                time.sleep(1)
            assert not pids(), "App process survived performance force-stop"
            launch = self.adb("shell", "am", "start", "-W", "-n", activity,
                              "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER")
            self.text(name + "-launch.txt", launch)
            assert re.search(r"^Status: ok$", launch, re.M), "Performance launch did not succeed"
            assert re.search(r"^LaunchState: COLD$", launch, re.M), "Performance sample was not process-cold"
            total = re.search(r"^TotalTime: (\d+)$", launch, re.M)
            assert total, "Performance launch did not report TotalTime"
            wait = re.search(r"^WaitTime: (\d+)$", launch, re.M)
            root = self.wait_for(name + "-overview", lambda root: selected_tab(root, "Overview")
                                 and bool(matches(root, "0 accounts monitored")))
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
            # Preserve the actual API 36 report; do not guess a PSS column before seeing it.
            report["samples"].append({
                "index": index, "warmup": index == 0, "totalTimeMs": int(total[1]),
                "waitTimeMs": int(wait[1]) if wait else None, "pid": observed_pids[0],
                "memoryDelaySeconds": memory_delay, "memoryReport": name + "-meminfo.txt",
                "memoryPssKiB": None, "memoryStatus": "raw report retained; PSS format not yet verified",
            })
            measured = [sample["totalTimeMs"] for sample in report["samples"] if not sample["warmup"]]
            if measured:
                report["totalTimeMs"] = {"count": len(measured), "median": statistics.median(measured),
                                         "min": min(measured), "max": max(measured)}
            self.text(phase + "-performance.json", json.dumps(report, indent=2))

    def wait_for(self, name, predicate):
        last_error = None
        for _ in range(12):
            try:
                root = self.dump(name)
                if predicate(root):
                    return root
            except (subprocess.SubprocessError, ET.ParseError) as error:
                last_error = error
            time.sleep(1)
        raise AssertionError(f"UI did not reach {name}; last dump error: {last_error}")

    def tap(self, node):
        x1, y1, x2, y2 = bounds(node)
        self.adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def launch(self, name):
        activity = self.adb("shell", "cmd", "package", "resolve-activity", "--brief", PACKAGE).splitlines()[-1]
        assert activity.startswith(PACKAGE + "/"), activity
        self.text(name + "-launch.txt", self.adb("shell", "am", "start", "-W", "-n", activity))
        self.wait_for(name, lambda root: navigation_target(root, "Overview") is not None)

    def navigate(self, tab, marker, name):
        root = self.dump(name + "-before")
        target = navigation_target(root, tab)
        assert target is not None, f"No enabled {tab} navigation item"
        if target.get("selected") != "true":
            assert target.get("clickable") == "true", f"Unselected {tab} tab is not clickable"
            self.tap(target)
        self.wait_for(name + "-selected", lambda root: selected_tab(root, tab))
        # A restored Settings destination can retain the previous scroll position.
        if tab == "Settings":
            for attempt in range(9):
                root = self.dump(f"{name}-top-{attempt}")
                if matches(root, marker):
                    break
                self.scroll(root, up=False)
        self.wait_for(name, lambda root: selected_tab(root, tab) and bool(matches(root, marker)))
        self.capture(name)
        assert self.adb("shell", "pidof", PACKAGE), "App process exited after navigation"

    def setting(self, name):
        for attempt in range(9):
            root = self.dump(f"{name}-{attempt}")
            target = next((node for node in matches(root, SETTING)
                           if node.get("checkable") == "true" and bounds(node)), None)
            if target is not None:
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
        self.wait_for(name + "-loaded", lambda root: any(node.get("checked") == "false"
            for node in matches(root, SETTING) if node.get("checkable") == "true"))

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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--previous-apk", type=Path, required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--apk-sha256-file", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools"
    aapt = sorted(tools.glob("*/aapt"), key=lambda path: tuple(int(n) for n in re.findall(r"\d+", path.parent.name)))[-1]
    old = apk_metadata(aapt, args.previous_apk)
    new = apk_metadata(aapt, args.apk)
    assert old["package"] == new["package"] == PACKAGE, "Package identity changed"
    assert old["versionName"] == "0.2.0", "Expected the published v0.2.0 baseline"
    assert new["versionCode"] > old["versionCode"], "Upgrade must increase versionCode"
    expected_hash = args.apk_sha256_file.read_text().split()[0].lower()
    assert re.fullmatch(r"[0-9a-f]{64}", expected_hash) and new["sha256"] == expected_hash, "APK differs from assemble-job evidence"
    assert run("git", "rev-parse", "HEAD") == os.environ["GITHUB_SHA"], "Checkout differs from this workflow run"
    devices = [line.split()[0] for line in run("adb", "devices").splitlines()[1:]
               if len(line.split()) >= 2 and line.split()[1] == "device"]
    assert len(devices) == 1 and devices[0].startswith("emulator-"), "Requires exactly one CI emulator"
    smoke = Smoke(args.evidence, devices[0])
    assert smoke.adb("shell", "getprop", "ro.kernel.qemu") == "1", "Refusing to modify a physical device"
    assert smoke.adb("shell", "getprop", "ro.build.version.sdk") == "36", "Expected API 36"
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
        assert setting.get("checked") == "true", "Fresh v0.2.0 setting did not have its documented default"
        smoke.tap(setting)
        smoke.wait_for("previous-setting-changed", lambda root: any(node.get("checked") == "false" for node in matches(root, SETTING) if node.get("checkable") == "true"))
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
        for tab, marker in [("Overview", "0 accounts monitored"), ("Accounts", "0 of 0 shown"),
                            ("Resets", "No upcoming resets"), ("Settings", "Provider icons")]:
            smoke.navigate(tab, marker, "current-" + tab.lower())
        smoke.setting_is_disabled("current-persisted")
        smoke.capture("current-persisted")
        smoke.adb("shell", "am", "force-stop", PACKAGE)
        smoke.launch("current-relaunch")
        smoke.navigate("Overview", "0 accounts monitored", "current-relaunch-overview")
        smoke.verify_widgets_after_upgrade(placed_widgets)
        smoke.performance_observations("current", new, placed_widgets)
        smoke.verify_widget_lifecycle(placed_widgets)
    except Exception:
        try:
            smoke.capture("failure")
        except (subprocess.SubprocessError, ET.ParseError):
            pass
        raise
    finally:
        diagnostics_complete = smoke.diagnostics()
    assert diagnostics_complete, "Could not collect the runtime diagnostics"
    smoke.assert_no_app_failures()
    smoke.text("result.json", json.dumps({"result": "passed", "commit": os.environ["GITHUB_SHA"],
        "apkSha256": new["sha256"], "upgradePreservedSetting": SETTING, "tabs": ["Overview", "Accounts", "Resets", "Settings"],
        "widgets": placed_widgets,
        "scope": "Empty-account release smoke with four launcher widgets; provider-authenticated quotas were not tested."}, indent=2))
    print("Release APK upgrade, persisted setting, four tabs, four widgets, night mode, process loss, launcher restart, reboot and offline/online checks passed; startup observations recorded.")


if __name__ == "__main__":
    main()
