#!/usr/bin/env python3
"""Upgrade and navigate the actual release APK on an empty API 36 CI emulator.

Uses only adb and the Python standard library. No accounts, credentials or provider calls.
https://developer.android.com/tools/adb documents install -r, am start and screencap.
All touch coordinates come from the device's current UIAutomator hierarchy.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
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

    def inspect_launcher(self, component):
        """Capture discovery evidence only; never guess a widget-placement gesture."""
        launcher = component.splitlines()[-1].split("/", 1)[0]
        report = {"launcher": launcher, "status": "home_only"}
        root = None
        opened_menu = False

        def capture_launcher(name):
            # Preserve pixels even if an unresponsive launcher prevents a hierarchy dump.
            (self.directory / (name + ".png")).write_bytes(self.adb("exec-out", "screencap", "-p", binary=True))
            return self.dump(name)

        def error_dialog_visible():
            return root is not None and any(node.get("resource-id", "").startswith("android:id/aerr_")
                                            for node in root.iter("node"))

        try:
            self.adb("shell", "input", "keyevent", "KEYCODE_HOME")
            root = capture_launcher("launcher-home")
            if error_dialog_visible():
                report["reason"] = "System error dialog present; no launcher interaction attempted."
                return
            candidates = []
            for node in root.iter("node"):
                if (node.get("package") != launcher or node.get("enabled") != "true"
                        or node.get("long-clickable") != "true" or not bounds(node)):
                    continue
                is_workspace = node.get("resource-id") == launcher + ":id/workspace"
                is_app_icon = "Usage Limits" in (node.get("text"), node.get("content-desc"))
                if not (is_workspace or is_app_icon):
                    continue
                x1, y1, x2, y2 = bounds(node)
                x, y = (x1 + x2) // 2, (y1 + y2) // 2
                # A workspace's centre is usable only when the dump shows no content there.
                occupied = is_workspace and any(
                    child is not node and bounds(child)
                    and (child.get("clickable") == "true" or child.get("long-clickable") == "true"
                         or child.get("text") or child.get("content-desc"))
                    and bounds(child)[0] <= x < bounds(child)[2]
                    and bounds(child)[1] <= y < bounds(child)[3]
                    for child in node.iter("node"))
                if not occupied:
                    candidates.append(node)
            if len(candidates) != 1:
                report["reason"] = f"Found {len(candidates)} unambiguous long-click targets; captured Home only."
                return
            target = candidates[0]
            report["longClickTarget"] = dict(target.attrib)
            x1, y1, x2, y2 = bounds(target)
            x, y = str((x1 + x2) // 2), str((y1 + y2) // 2)
            self.adb("shell", "input", "swipe", x, y, x, y, "1000")
            opened_menu = True
            root = capture_launcher("launcher-longpress")
            if error_dialog_visible():
                report["reason"] = "System error dialog present after long press; no further interaction."
                return
            parents = {child: parent for parent in root.iter() for child in parent}
            controls = {}
            for node in root.iter("node"):
                if node.get("package") != launcher or "Widgets" not in (node.get("text"), node.get("content-desc")):
                    continue
                while node is not None and node.get("package") == launcher:
                    if node.get("enabled") == "true" and node.get("clickable") == "true" and bounds(node):
                        controls[bounds(node)] = node
                        break
                    node = parents.get(node)
            if len(controls) != 1:
                report["reason"] = f"Found {len(controls)} enabled Widgets controls; picker was not opened."
                return
            self.tap(next(iter(controls.values())))
            root = capture_launcher("launcher-picker")
            report["status"] = "post_widgets_capture"
            if error_dialog_visible():
                report.update(status="diagnostic_error", reason="System error dialog present after Widgets tap.")
        except (subprocess.SubprocessError, ET.ParseError) as error:
            root = None  # Do not navigate based on the hierarchy from before the failed capture.
            report.update(status="diagnostic_error", reason=str(error))
        finally:
            # Never dismiss an observed system error dialog. Normal app launch still follows.
            if opened_menu and root is not None and not error_dialog_visible():
                try:
                    self.adb("shell", "input", "keyevent", "KEYCODE_BACK")
                    self.adb("shell", "input", "keyevent", "KEYCODE_HOME")
                except subprocess.SubprocessError as error:
                    report["cleanupError"] = str(error)
            self.text("launcher-discovery.json", json.dumps(report, indent=2))

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

    def diagnostics(self):
        complete = True
        for filename, command in {
            "crash-log.txt": ("logcat", "-b", "crash", "-d", "-v", "threadtime"),
            "activity-exits.txt": ("shell", "dumpsys", "activity", "exit-info", PACKAGE),
            "widget-providers.txt": ("shell", "dumpsys", "appwidget"),
        }.items():
            try:
                self.text(filename, self.adb(*command))
            except subprocess.SubprocessError as error:
                self.text(filename, str(error))
                complete = False
        return complete


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
    smoke.text("environment.json", json.dumps(environment, indent=2))
    smoke.adb("logcat", "-c")
    try:
        # The AVD is fresh. No uninstall, clear-data, downgrade or synthetic account injection.
        smoke.text("previous-install.txt", smoke.adb("install", str(args.previous_apk), timeout=180))
        previous = smoke.installed("previous")
        assert int(previous["versionCode"]) == old["versionCode"] and previous["versionName"] == old["versionName"]
        smoke.adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
        smoke.inspect_launcher(environment["launcher"])
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
    except Exception:
        try:
            smoke.capture("failure")
        except (subprocess.SubprocessError, ET.ParseError):
            pass
        raise
    finally:
        diagnostics_complete = smoke.diagnostics()
    assert diagnostics_complete, "Could not collect the runtime diagnostics"
    crash_log = (args.evidence / "crash-log.txt").read_text()
    assert not re.search(r"Process: com\.usagelimits(?:,|\s)|>>> com\.usagelimits <<<", crash_log), "Application crash recorded"
    exits = (args.evidence / "activity-exits.txt").read_text()
    # ApplicationExitInfo reasons: crash=4, native crash=5, ANR=6, initialization failure=7.
    assert not re.search(r"\breason=(?:4|5|6|7)\b", exits), "Application crash or ANR exit recorded"
    smoke.text("result.json", json.dumps({"result": "passed", "commit": os.environ["GITHUB_SHA"],
        "apkSha256": new["sha256"], "upgradePreservedSetting": SETTING, "tabs": ["Overview", "Accounts", "Resets", "Settings"],
        "scope": "Empty-account release smoke; widget placement and provider-authenticated flows were not tested."}, indent=2))
    print("Release APK upgrade, persisted setting, four tabs, relaunch and crash checks passed.")


if __name__ == "__main__":
    main()
