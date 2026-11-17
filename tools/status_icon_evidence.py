#!/usr/bin/env python3
"""Capture real status-icon evidence; record ranking, never infer visible icon order.

The debug receiver uses synthetic repository accounts only. No provider is contacted.
Screenshots require human visual acceptance; notification records are not icon counts.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

from android_runtime_smoke import bounds, png_frame, png_bytes, scaled_crop


PACKAGE = "com.usagelimits.debug"
RECEIVER = PACKAGE + "/com.usagelimits.evidence.StatusIconEvidenceReceiver"
ACCOUNTS = ["evidence-" + name for name in ("claude", "codex", "gemini", "xai", "copilot", "cursor")]
PIN_ORDER = [ACCOUNTS[1], ACCOUNTS[2], ACCOUNTS[0]]
STATUS_CHANNEL = "status_icons"
ALERT_CHANNEL = "usage_alerts"
SETTLE_SECONDS = 5


def parse_notifications(text):
    """Read only the active list, not snoozed/archive/ranking copies of records."""
    section = re.search(r"(?m)^([ \t]*)Notification List:[ \t]*$", text)
    assert section, "Unrecognized dump: missing Notification List"
    active = text[section.end():]
    boundary = re.search(r"(?m)^ {0," + str(len(section[1])) + r"}\S", active)
    if boundary:
        active = active[:boundary.start()]
    records = []
    for block in re.split(r"(?m)^\s*(?=NotificationRecord\()", active):
        if not block.startswith("NotificationRecord("):
            continue
        header = block.splitlines()[0]

        def field(pattern, source=block):
            match = re.search(pattern, source)
            return match[1] if match else None

        package = field(r"\bpkg=([^\s,)]+)", header)
        notification_id = field(r"\bid=(-?\d+)\b", header)
        channel = field(r"\bchannel=([^\s,)]+)", header) or field(r"mId='([^']+)'", block)
        key = field(r"(?m)^\s*key=(\S+)") or field(r"\bkey=(\S+?)(?=: Notification\(|\s)", header)
        flags = field(r"\bflags=([^\s,)]+)", header)
        assert package and notification_id and channel and key and flags, "Malformed active NotificationRecord: " + header
        summary = bool(int(flags, 0) & 0x200) if re.fullmatch(r"0x[0-9a-fA-F]+|\d+", flags) else "GROUP_SUMMARY" in flags.split("|")
        tag = field(r"\btag=([^\s,)]+)", header)
        records.append({
            "package": package, "id": int(notification_id), "tag": tag,
            "channel": channel, "key": key, "flags": flags, "summary": summary,
            "uid": field(r"(?m)^\s*uid=(\d+)\b"),
            "group": field(r"\b(?:group|groupKey)=([^\s,)]+)", header),
            "groupKey": field(r"(?m)^\s*groupKey=(\S+)"),
            "overrideGroupKey": field(r"(?m)^\s*(?:mOverrideGroupKey|overrideGroupKey)=(\S+)"),
            "title": field(r"android\.title=String \(([^\n]*)\)"),
            "rankingTimeMs": field(r"\bmRankingTimeMs=(\S+)"),
            "when": field(r"\bwhen=(\S+)"),
            "globalSortKey": field(r"\b(?:mGlobalSortKey|globalSortKey)=(\S+)"),
        })
    return records


def assert_snapshot(records, expected_accounts, baseline=None, events=""):
    app = [record for record in records if record["package"] == PACKAGE]
    status = [record for record in app if record["channel"] == STATUS_CHANNEL]
    alerts = [record for record in app if record["channel"] == ALERT_CHANNEL]
    for record in app:
        assert not any(re.search(r"Aggregate_|ranker_group", str(record.get(name) or ""), re.I)
                       for name in ("group", "groupKey", "overrideGroupKey", "tag", "key")), "Forbidden auto-group: " + record["key"]
        assert record["overrideGroupKey"] in (None, "null"), "Unexpected group override: " + record["key"]
    assert len(status) == len(expected_accounts) * 2, f"Expected {len(expected_accounts) * 2} status records; observed {len(status)}"
    expected_tags = {"status-icon:" + account for account in expected_accounts}
    assert {record["tag"] for record in status} == expected_tags, "Unexpected status account groups"
    assert len({record["key"] for record in status}) == len(status), "Duplicate notification keys"
    for tag in expected_tags:
        pair = [record for record in status if record["tag"] == tag]
        assert len(pair) == 2 and {record["id"] for record in pair} == {2001, 2002}, "Malformed account group: " + tag
        for record in pair:
            assert record["group"] == tag, "Malformed notification group: " + record["key"]
            assert record["groupKey"] and record["groupKey"].endswith("|g:" + tag), "Malformed groupKey: " + record["key"]
            assert record["summary"] == (record["id"] == 2001), "Malformed summary/child flags: " + record["key"]
            assert all(record[name] is not None for name in ("rankingTimeMs", "when", "globalSortKey")), "Missing ranking observations: " + record["key"]
    baseline_alerts = [] if baseline is None else [record for record in baseline if record["package"] == PACKAGE and record["channel"] == ALERT_CHANNEL]
    assert {record["key"] for record in alerts} == {record["key"] for record in baseline_alerts}, "Unexpected app alert records"
    assert len(app) == len(status) + len(alerts), "Unexpected app notification channel records"
    if baseline is not None:
        assert {record["key"] for record in status} == {record["key"] for record in baseline if record["package"] == PACKAGE and record["channel"] == STATUS_CHANNEL}, "Status notification keys changed"
    uids = {record["uid"] for record in app if record["uid"]}
    for line in events.splitlines():
        if "notification_alert" in line:
            assert PACKAGE not in line and not any(re.search(r"[\[,\s]" + re.escape(uid) + r"[,\]\s]", line) for uid in uids), "App notification_alert event: " + line
    order = list(dict.fromkeys(record["tag"].removeprefix("status-icon:") for record in status))
    return {"statusCount": len(status), "alertCount": len(alerts),
            "systemCount": len(records) - len(app), "accountDumpOrder": order,
            "ranking": status}


def swipe_accounts(api):
    assert api in (30, 36, 37), "Unsupported evidence API: " + str(api)
    return ACCOUNTS[:3] if api == 30 else ACCOUNTS[1:3]


def _systemui_dialog(xml, window=""):
    try:
        root = ET.fromstring(xml)
        message = any(node.get("resource-id") in ("android:id/aerr_message", "android:id/alertTitle") and
                      node.get("text", "").replace("\u2019", "'").rstrip(".") == "System UI isn't responding"
                      for node in root.iter("node"))
    except ET.ParseError:
        message = False
    # WindowManager retains the exact ANR process dialog even if UIAutomator hangs.
    dialog = re.search(r"Application (?:Not Responding|not responding): com\.android\.systemui(?=[\s}])", window)
    return bool(message or dialog)


def systemui_anr(xml, lastanr, window="", dropbox=""):
    """Retry only an exact live SystemUI dialog with independent ANR process proof."""
    process = any(re.search(r"(?m)^\s*(?:ANR in|Process:)\s+com\.android\.systemui(?:\s|$|\()", dump)
                  for dump in (lastanr, dropbox))
    return bool(process and _systemui_dialog(xml, window))


class SystemUIBootANR(RuntimeError):
    pass


class SystemDialog(RuntimeError):
    pass


class Evidence:
    def __init__(self, directory):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True)
        self.report = {"result": "incomplete", "installed": False, "lastAssertion": "boot readiness",
                       "scenarios": [], "visualAcceptance": "Pending human inspection of HOME/statusbar/shade screenshots; dump order is not visible icon order."}
        self.state = None
        self.last_post = 0
        self.ready_deadline = None

    def adb(self, *args, binary=False, timeout=40):
        if self.ready_deadline is not None:
            remaining = self.ready_deadline - time.monotonic()
            if remaining <= 0:
                raise subprocess.TimeoutExpired(args, 120)
            timeout = min(timeout, remaining)
        command = ["adb"]
        if os.environ.get("ANDROID_SERIAL"):
            command += ["-s", os.environ["ANDROID_SERIAL"]]
        command += ["shell", shlex.join(args[1:])] if args[0] == "shell" else list(args)
        result = subprocess.run(command, check=True, capture_output=True, timeout=timeout)
        return result.stdout if binary else result.stdout.decode(errors="replace").strip()

    def save(self, name, value):
        (self.directory / name).write_text(value + "\n")

    def xml(self, name, timeout=12):
        remote = "/sdcard/status-icon-evidence.xml"
        self.adb("shell", "rm", "-f", remote, timeout=timeout)
        self.adb("shell", "uiautomator", "dump", remote, timeout=timeout)
        xml = self.adb("exec-out", "cat", remote, timeout=timeout)
        self.save(name + ".xml", xml)
        return ET.fromstring(xml)

    def screen(self, name, crop=False):
        data = self.adb("exec-out", "screencap", "-p", binary=True, timeout=12)
        (self.directory / (name + ".png")).write_bytes(data)
        frame = png_frame(data, 95)
        if crop:
            width, channels, rows = frame
            assert len(rows) == 96, "Screenshot is shorter than statusbar crop"
            (self.directory / (name + "-statusbar.png")).write_bytes(png_bytes(width, 96, channels, rows))
            (self.directory / (name + "-statusbar-x4.png")).write_bytes(scaled_crop(frame, (0, 0, width, 96), 4))

    def diagnostics(self, name):
        errors = {}
        commands = {
            "lastanr.txt": ("shell", "dumpsys", "activity", "lastanr"),
            "system-app-anr.txt": ("shell", "dumpsys", "dropbox", "--print", "system_app_anr"),
            "window.txt": ("shell", "dumpsys", "window"),
            "activity.txt": ("shell", "dumpsys", "activity", "activities"),
            "logcat.txt": ("shell", "logcat", "-d", "-v", "threadtime"),
            "events.txt": ("shell", "logcat", "-b", "events", "-d", "-v", "threadtime"),
            "notification.txt": ("shell", "dumpsys", "notification", "--noredact"),
        }
        for suffix, command in commands.items():
            try:
                self.save(name + "-" + suffix, self.adb(*command, timeout=12))
            except (subprocess.SubprocessError, OSError) as error:
                errors[suffix] = str(error)
        for suffix, capture in (("png", self.screen), ("xml", self.xml)):
            try:
                capture(name)
            except (subprocess.SubprocessError, OSError, AssertionError, ET.ParseError) as error:
                errors[suffix] = str(error)
        self.save(name + "-collection-errors.json", json.dumps(errors, indent=2))

    def check_boot_dialog(self, xml, anr, window, name):
        if _systemui_dialog(xml, window):
            dropbox = ""
            try:
                dropbox = self.adb("shell", "dumpsys", "dropbox", "--print", "system_app_anr", timeout=8)
                self.save(name + "-system-app-anr.txt", dropbox)
            except (subprocess.SubprocessError, OSError) as error:
                self.save(name + "-system-app-anr-error.txt", str(error))
            if systemui_anr(xml, anr, window, dropbox=dropbox):
                raise SystemUIBootANR("Exact pre-install SystemUI ANR; dialog was not dismissed")
            raise SystemDialog("SystemUI dialog without ANR process proof; not dismissed")
        if re.search(r"Application (?:Not Responding|not responding|Error):", window):
            raise SystemDialog("System error window; not dismissed")

    def preflight(self):
        deadline = time.monotonic() + 120
        self.ready_deadline = deadline
        last_error = "No HOME observation"
        while time.monotonic() < deadline:
            xml, window, anr = "", "", ""
            try:
                anr = self.adb("shell", "dumpsys", "activity", "lastanr", timeout=8)
                self.save("preflight-lastanr.txt", anr)
                window = self.adb("shell", "dumpsys", "window", timeout=8)
                self.save("preflight-window.txt", window)
                self.check_boot_dialog(xml, anr, window, "preflight")
                component = self.adb("shell", "cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME", timeout=8).splitlines()[-1]
                assert re.fullmatch(r"[^\s/]+/[^\s/]+", component), "Unrecognized resolved HOME"
                root = self.xml("preflight", timeout=8)
                xml = ET.tostring(root, encoding="unicode")
                self.check_boot_dialog(xml, anr, window, "preflight")
                if any(node.get("resource-id", "").startswith("android:id/aerr_") for node in root.iter("node")):
                    raise SystemDialog("System error dialog; not dismissed")
                if "<no ANR has occurred since boot>" not in anr:
                    raise SystemDialog("Unexpected pre-install ANR report")
                package, activity = component.split("/", 1)
                expanded = package + "/" + (package + activity if activity.startswith(".") else activity)
                focuses = re.findall(r"(?m)^\s*mCurrentFocus=Window\{[^\n]* u\d+ ([^\s{}]+/[^\s{}]+)\}", window)
                focuses = [pkg + "/" + (pkg + act if act.startswith(".") else act) for pkg, act in (focus.split("/", 1) for focus in focuses)]
                workspaces = [node for node in root.iter("node") if node.get("package") == package and node.get("resource-id") == package + ":id/workspace" and node.get("enabled") == "true" and bounds(node)]
                if focuses == [expanded] and len(workspaces) == 1:
                    self.screen("preflight-home", crop=True)
                    ready_anr = self.adb("shell", "dumpsys", "activity", "lastanr", timeout=8)
                    self.save("preflight-ready-lastanr.txt", ready_anr)
                    ready_window = self.adb("shell", "dumpsys", "window", timeout=8)
                    self.save("preflight-ready-window.txt", ready_window)
                    self.check_boot_dialog(xml, ready_anr, ready_window, "preflight-ready")
                    if "<no ANR has occurred since boot>" not in ready_anr:
                        raise SystemDialog("Unexpected ANR during final HOME observation")
                    self.ready_deadline = None
                    self.report["preflight"] = {"result": "passed", "component": component, "focusedHOME": True, "workspace": True}
                    return
                last_error = "Waiting for focused HOME plus enabled workspace"
            except SystemUIBootANR:
                raise
            except (subprocess.SubprocessError, OSError, AssertionError, ET.ParseError, IndexError) as error:
                last_error = str(error)
                self.check_boot_dialog(xml, anr, window, "preflight")
                if "<no ANR has occurred since boot>" not in anr and anr:
                    raise AssertionError("Non-retryable pre-install ANR: " + last_error) from error
            time.sleep(min(1, max(0, deadline - time.monotonic())))
        raise AssertionError("HOME readiness failed within 120 seconds: " + last_error)

    def command(self, command, account=None):
        self.report["lastAssertion"] = "receiver " + command + (": " + account if account else "")
        token = uuid.uuid4().hex
        args = ["shell", "am", "broadcast", "-f", "0x20", "-n", RECEIVER, "--es", "command", command, "--es", "token", token]
        if account:
            args += ["--es", "account", account]
        self.save("command-" + token + ".txt", self.adb(*args, timeout=120))
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            try:
                response = self.adb("exec-out", "run-as", PACKAGE, "cat", "files/status-icon-evidence.json", timeout=8)
                state = json.loads(response)
            except (subprocess.SubprocessError, json.JSONDecodeError):
                time.sleep(0.5)
                continue
            if state.get("token") != token:
                time.sleep(0.5)
                continue
            self.save("command-" + token + ".json", json.dumps(state, indent=2))
            assert state.get("command") == command, "Receiver command mismatch"
            assert state.get("success") is True, "Receiver failed: " + str(state.get("error"))
            assert isinstance(state.get("enabled"), bool) and isinstance(state.get("accounts"), list) and isinstance(state.get("pins"), list), "Receiver result missing state"
            assert set(state.get("localIds", {})) == set(ACCOUNTS), "Receiver missing fixture localIds mapping"
            self.state = state
            self.last_post = time.monotonic()
            return state
        raise AssertionError("No matching receiver completion token for " + command)

    def settled(self):
        time.sleep(max(0, self.last_post + SETTLE_SECONDS - time.monotonic()))

    def capture(self, name, starting, expected, baseline=None):
        self.report["lastAssertion"] = name + ": " + starting
        entry = {"name": name, "startingState": starting, "expectedAccounts": expected, "driver": self.state, "result": "incomplete"}
        self.report["scenarios"].append(entry)
        window = self.adb("shell", "dumpsys", "window")
        self.save(name + "-window.txt", window)
        assert not re.search(r"Application (?:Not Responding|not responding|Error):", window), "System error window; no navigation or dismissal attempted"
        self.adb("shell", "cmd", "statusbar", "collapse")
        self.adb("shell", "input", "keyevent", "KEYCODE_HOME")
        self.settled()
        time.sleep(1)
        self.screen(name + "-home", crop=True)
        self.xml(name + "-home")
        notification = self.adb("shell", "dumpsys", "notification", "--noredact")
        self.save(name + "-notification.txt", notification)
        events = self.adb("shell", "logcat", "-b", "events", "-d", "-v", "threadtime")
        self.save(name + "-events.txt", events)
        for suffix, command in (
            ("statusbar", ("shell", "dumpsys", "statusbar")),
            ("systemui", ("shell", "dumpsys", "activity", "service", "com.android.systemui/.SystemUIService")),
        ):
            self.save(name + "-" + suffix + ".txt", self.adb(*command))
        self.adb("shell", "cmd", "statusbar", "expand-notifications")
        time.sleep(2)
        self.screen(name + "-shade")
        self.xml(name + "-shade")
        records = parse_notifications(notification)
        entry["observedRecords"] = records
        entry.update(statusCount=sum(record["package"] == PACKAGE and record["channel"] == STATUS_CHANNEL for record in records),
                     alertCount=sum(record["package"] == PACKAGE and record["channel"] == ALERT_CHANNEL for record in records),
                     systemCount=sum(record["package"] != PACKAGE for record in records))
        self.save(name + "-ranking.json", json.dumps(records, indent=2))
        self.save(name + "-ranking.txt", "\n".join(f"{record['key']} rankingTimeMs={record['rankingTimeMs']} when={record['when']} globalSortKey={record['globalSortKey']}" for record in records))
        local_ids = [self.state["localIds"][account] for account in expected]
        entry.update(assert_snapshot(records, local_ids, baseline=baseline, events=events))
        entry["result"] = "passed"
        self.adb("shell", "cmd", "statusbar", "collapse")
        self.summary()
        return records

    def show_silent_icons(self):
        """Allow silent icons through the user preference, retaining the default evidence."""
        visibility = self.report["silentIconVisibility"] = {"defaultHidden": None, "setupChanged": False}

        def dumps(name):
            notification = self.adb("shell", "dumpsys", "notification", "--noredact")
            systemui = self.adb("shell", "dumpsys", "activity", "service", "com.android.systemui/.SystemUIService")
            self.save(name + "-notification.txt", notification)
            self.save(name + "-systemui.txt", systemui)
            flags = re.findall(r"\bhideSilentStatusBar=(true|false)\b", notification)
            showing = re.findall(r"\bshowSilentStatusIcons=(true|false)\b", systemui)
            return flags, showing[0] == "true" if len(showing) == 1 else None

        flags, showing = dumps("silent-icons-before")
        visibility["systemuiDefaultShowing"] = showing if self.report["api"] == 37 else "unsupported dump field"
        if self.report["api"] == 30:
            visibility["observation"] = "API30 NMS visibility dump unsupported; Run1 default icons observed visible; no setting change. Inspect current 03-enabled-default screenshots separately."
            return
        assert len(flags) == 1, "Missing or ambiguous hideSilentStatusBar dump field"
        visibility["defaultHidden"] = flags[0] == "true"
        if not visibility["defaultHidden"]:
            if self.report["api"] == 37:
                assert showing is True, "SystemUI must explicitly show silent icons when API37 NMS allows them"
                visibility["allowedSilentVerified"] = True
            return
        assert self.report["api"] == 37, "Only API37 hidden-default Settings setup is supported"
        self.report["lastAssertion"] = "allow silent icons through observed Settings switch"
        self.adb("shell", "am", "start", "-a", "android.settings.NOTIFICATION_SETTINGS")

        def control(root):
            titles = [node for node in root.iter("node") if node.get("text") == "Hide silent notifications in status bar" and node.get("package") == "com.android.settings"]
            assert len(titles) <= 1, "Ambiguous silent notification Settings title"
            if not titles:
                return None
            parents = {child: parent for parent in root.iter() for child in parent}
            row = parents.get(titles[0])
            while row is not None and row.tag == "node" and row.get("scrollable") != "true":
                switches = [node for node in row.iter("node") if node.get("checkable") == "true" and node.get("class", "").endswith("Switch")]
                if switches:
                    assert len(switches) == 1 and bounds(switches[0]) and switches[0].get("enabled") == "true", "Ambiguous or disabled silent notification switch"
                    assert switches[0].get("checked") in ("true", "false"), "Missing silent notification switch state"
                    return switches[0]
                row = parents.get(row)
            raise AssertionError("Silent notification title has no same-row switch")

        for probe in range(9):
            name = f"silent-icons-before-{probe}"
            root = self.xml(name)
            self.screen(name)
            switch = control(root)
            if switch is not None:
                break
            assert probe < 8, "Silent notification Settings title missing after eight scrolls"
            containers = [node for node in root.iter("node") if node.get("package") == "com.android.settings"
                          and node.get("resource-id") == "com.android.settings:id/recycler_view"
                          and node.get("class") == "androidx.recyclerview.widget.RecyclerView"]
            assert len(containers) == 1, "Missing or ambiguous silent notification Settings scroll container"
            container = containers[0]
            assert container.get("scrollable") == "true" and container.get("enabled") == "true" and bounds(container), "Invalid silent notification Settings scroll viewport"
            parents = {child: parent for parent in root.iter() for child in parent}
            parent = parents[container]
            assert bounds(parent), "Missing silent notification Settings viewport parent bounds"
            x1, y1, x2, y2 = bounds(container)
            px1, py1, px2, py2 = bounds(parent)
            assert px1 <= x1 < x2 <= px2 and py1 <= y1 < y2 <= py2, "Silent notification Settings viewport outside parent bounds"
            x, span = (x1 + x2) // 2, y2 - y1
            self.adb("shell", "input", "swipe", str(x), str(y2 - span // 4), str(x), str(y1 + span // 4), "300")
        if switch.get("checked") == "true":
            x1, y1, x2, y2 = bounds(switch)
            self.adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
            visibility["setupChanged"] = True
        deadline = time.monotonic() + 5
        for probe in range(10):
            name = f"silent-icons-after-{probe}"
            root = self.xml(name)
            self.screen(name)
            switch = control(root)
            flags, showing = dumps(name)
            if switch is not None and switch.get("checked") == "false" and flags == ["false"] and showing:
                visibility["allowedSilentVerified"] = True
                self.adb("shell", "input", "keyevent", "KEYCODE_HOME")
                return
            assert time.monotonic() < deadline, "Silent icon setting failed UI/NMS/SystemUI verification"
            time.sleep(0.5)
        raise AssertionError("Silent icon setting failed bounded verification")

    def swipe_claude(self, records):
        titles = {record["title"] for record in records if record["package"] == PACKAGE and record["tag"] == "status-icon:" + self.state["localIds"][ACCOUNTS[0]]}
        assert len(titles) == 1 and None not in titles, "Cannot resolve Claude notification title from dump"
        title = next(iter(titles))
        self.report["lastAssertion"] = "real Claude notification-row swipe"
        self.adb("shell", "cmd", "statusbar", "expand-notifications")
        time.sleep(2)
        root = self.xml("swipe-before-shade")
        self.screen("swipe-before-shade")
        parents = {child: parent for parent in root.iter() for child in parent}
        rows = {}
        for node in root.iter("node"):
            if node.get("text") != title or node.get("package") != "com.android.systemui":
                continue
            row = None
            content = None
            while node is not None:
                if node.get("resource-id", "").endswith("/expandableNotificationRow") and bounds(node):
                    row = node
                if self.report["api"] == 30 and content is None and node.get("resource-id") == "android:id/status_bar_latest_event_content" and bounds(node):
                    content = node
                node = parents.get(node)
            # API30's observed hierarchy omits row IDs; keep modern outer account groups.
            if row is None:
                row = content
            if row is not None:
                rows[bounds(row)] = row
        assert len(rows) == 1, "Expected one observed Claude shade row; refusing guessed swipe coordinates"
        box = next(iter(rows))
        x1, y1, x2, y2 = box
        width = png_frame((self.directory / "swipe-before-shade.png").read_bytes(), 0)[0]
        assert 0 <= x1 < x2 <= width, "Observed shade row outside screenshot"
        start, end, y = x1 + (x2 - x1) // 4, width - 2, (y1 + y2) // 2
        self.save("swipe.json", json.dumps({"title": title, "rowBounds": box, "start": [start, y], "end": [end, y], "api": self.report["api"]}, indent=2))
        self.adb("shell", "input", "swipe", str(start), str(y), str(end), str(y), "300")
        self.last_post = time.monotonic()
        self.settled()
        self.screen("swipe-after-shade")
        self.xml("swipe-after-shade")

    def scenario(self, apk):
        self.report["api"] = int(self.adb("shell", "getprop", "ro.build.version.sdk"))
        assert self.report["api"] in (30, 36, 37), "Evidence requires API30/36/37"
        self.adb("shell", "svc", "power", "stayon", "true")
        self.save("device.txt", self.adb("shell", "getprop") + "\n" + self.adb("shell", "wm", "size") + "\n" + self.adb("shell", "wm", "density"))
        self.save("install-started.txt", "HOME readiness passed; installing " + str(apk))
        self.report["lastAssertion"] = "debug APK installation"
        self.save("install.txt", self.adb("install", "-r", str(apk), timeout=180))
        self.report["installed"] = True
        if self.report["api"] >= 33:
            self.adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
        self.adb("shell", "logcat", "-c")
        self.command("seed")
        assert self.state["accounts"] == ACCOUNTS and self.state["pins"] == [] and not self.state["enabled"], "Seed must restore initial account order, OFF and no pins"
        self.capture("01-seed-off", "clean seed: initial readings42/5/100, extra exhausted/stale/error; OFF, no pins", [])
        for index, account in enumerate(PIN_ORDER):
            self.command("pin", account)
            assert not self.state["enabled"] and set(self.state["pins"]) == set(PIN_ORDER[:index + 1]), "Pin while OFF changed wrong settings"
            self.capture(f"02-pin-off-{index + 1}", "OFF; add " + account + " in Codex,Antigravity,Claude pin order", [])
        self.command("enable")
        assert self.state["enabled"], "Feature did not enable"
        default = self.capture("03-enabled-default", "OFF with three pins -> ON; unchanged platform silent-icon preference", ACCOUNTS[:3])
        self.show_silent_icons()
        full = self.capture("03-enabled", "Allowed-silent scenario; initial app order Claude,Codex,Antigravity differs pin order; no app event during Settings setup", ACCOUNTS[:3], baseline=default)
        self.command("update")
        full = self.capture("04-updated", "full six; Claude42 ->87 through repository/cacheMoved", ACCOUNTS[:3], baseline=full)
        self.command("reorder")
        assert self.state["accounts"] == [ACCOUNTS[2], ACCOUNTS[0], ACCOUNTS[1], *ACCOUNTS[3:]], "Repository reorder did not complete"
        full = self.capture("05-reordered", "full six; repository/presentationChanged order Antigravity,Claude,Codex,XAI,Kimi,Devin", ACCOUNTS[:3], baseline=full)
        self.command("states")
        self.capture("06-states", "full six; original pinned accounts become exhausted/stale/error through repository/cacheMoved", ACCOUNTS[:3])
        self.command("reset-readings")
        full = self.capture("07-restored", "full six state-glyph records; reset-readings via repository/cacheMoved retains reordered accounts and pins", ACCOUNTS[:3])
        self.swipe_claude(full)
        remaining = swipe_accounts(self.report["api"])
        self.capture("08-swiped", "full six initial readings; real swipe of observed Claude group (API30 ongoing remains; API36/37 absent)", remaining)
        self.command("presentation")
        self.capture("09-presentation", "post-swipe state; presentation-only event must not restore dismissed Claude", remaining)
        self.command("unpin", ACCOUNTS[2])
        remaining = [account for account in remaining if account != ACCOUNTS[2]]
        self.capture("10-unpin-gemini", "post-swipe/presentation; unpin Antigravity (evidence-gemini)", remaining)
        self.command("pin", ACCOUNTS[3])
        remaining = [*remaining, ACCOUNTS[3]]
        self.capture("11-pin-xai", "Antigravity unpinned; pin XAI without restoring dismissed Claude", remaining)
        self.command("cache")
        self.capture("12-cache-restored", "Claude,Codex,XAI pinned; cacheMoved restores full six including dismissed Claude", [ACCOUNTS[0], ACCOUNTS[1], ACCOUNTS[3]])
        self.command("unpin", ACCOUNTS[3])
        self.capture("13-unpin-from-full", "full six Claude,Codex,XAI; unpin XAI ->four", ACCOUNTS[:2])

    def summary(self):
        self.report["screenshots"] = sorted(path.name for path in self.directory.glob("*.png"))
        self.save("capture-manifest.json", json.dumps(self.report, indent=2))
        lines = ["# Status-icon device evidence — API " + str(self.report.get("api", "preflight")), "", "Result: " + self.report["result"], "", "Last assertion: " + self.report["lastAssertion"], "", self.report["visualAcceptance"], "", "| Scenario | Starting state | Status records | App alert records | System records | Result |", "|---|---|---:|---:|---:|---|"]
        for entry in self.report["scenarios"]:
            lines.append("| " + " | ".join(str(value).replace("|", "\\|") for value in (entry["name"], entry["startingState"], entry.get("statusCount", "?"), entry.get("alertCount", "?"), entry.get("systemCount", "?"), entry["result"])) + " |")
        lines += ["", "## Silent icon visibility", "", "Inspected baseline run44133402554: API37 had 0 status icons, 6 active status records and 3 shade rows with hideSilentStatusBar=true/showSilentStatusIcons=false; API30 and API36 showed 3 status icons.", "", "The current platform default is preserved in 03-enabled-default. API37 default may hide all silent icons; the conditional allowed-silent scenario uses user Setting off (Hide silent notifications in status bar), not an unchanged-default claim.", "", json.dumps(self.report.get("silentIconVisibility", {}), sort_keys=True), "", "Default and allowed-silent captures are separate; unchanged notification keys are compared across setup, with no toggle when the platform already allows silent icons.", "", "## Ranking observations"]
        for entry in self.report["scenarios"]:
            if "accountDumpOrder" in entry:
                aliases = {local: alias for alias, local in entry["driver"]["localIds"].items()}
                lines += ["", "Dump account order (not visible icon order), " + entry["name"] + ": " + ", ".join(aliases.get(local, local) for local in entry["accountDumpOrder"]), ""]
                for record in entry["ranking"]:
                    lines.append("- `" + record["key"] + "`: mRankingTimeMs=" + str(record["rankingTimeMs"]) + ", when=" + str(record["when"]) + ", globalSortKey=`" + str(record["globalSortKey"]) + "`")
        if self.report.get("error"):
            lines += ["", "Failure: " + self.report["error"], "", "See failure.png/.xml and failure-{notification,lastanr,system-app-anr,window,activity,logcat,events}.txt (collection errors retained)."]
        self.save("summary.md", "\n".join(lines))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--preflight", action="store_true", help="Observe boot HOME before installation; exit75 only for proven SystemUI ANR")
    args = parser.parse_args()
    if not args.preflight and args.apk is None:
        parser.error("--apk is required except with --preflight")
    evidence = Evidence(args.evidence)
    status = 1
    try:
        evidence.preflight()
        if not args.preflight:
            evidence.scenario(args.apk)
        evidence.report["result"] = "passed"
        status = 0
    except (AssertionError, RuntimeError, OSError, subprocess.SubprocessError, ET.ParseError, ValueError, KeyError) as error:
        evidence.report.update(result="failed", error=str(error))
        evidence.ready_deadline = None
        evidence.diagnostics("failure")
        if isinstance(error, SystemUIBootANR) and args.preflight and not evidence.report["installed"]:
            evidence.report["retryReason"] = "proven-preinstall-SystemUI-ANR"
            status = 75
    finally:
        evidence.summary()
    print(f"Status-icon evidence: {evidence.report['result']}; {args.evidence / 'summary.md'}")
    return status


if __name__ == "__main__":
    raise SystemExit(main())
