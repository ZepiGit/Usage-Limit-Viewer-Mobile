#!/usr/bin/env python3
"""Capture real status-icon evidence; record ranking, never infer visible icon order.

The debug receiver uses synthetic repository accounts only. No provider is contacted.
Screenshots require human visual acceptance; notification records are not icon counts.
"""
import argparse
from collections import Counter
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import threading
import time
import uuid
import xml.etree.ElementTree as ET

from android_runtime_smoke import bounds, png_frame, png_bytes, scaled_crop


PACKAGE = "com.usagelimits.debug"
RECEIVER = PACKAGE + "/com.usagelimits.evidence.StatusIconEvidenceReceiver"
ACCOUNTS = ["evidence-" + name for name in ("claude", "codex", "gemini", "xai", "copilot", "cursor")]
PIN_ORDER = [ACCOUNTS[1], ACCOUNTS[2], ACCOUNTS[0]]
DUPLICATE_ACCOUNT = "evidence-claude-work"
STATUS_CHANNEL = "status_icons"
ALERT_CHANNEL = "usage_alerts"
SETTLE_SECONDS = 5
HEADS_UP_MAX_DELAY_MS = 4000
UPTIME_RESOLUTION_MS = 10


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
        channel_dump = field(r"(?m)^\s*effectiveNotificationChannel=(NotificationChannel\{[^\n]*\})")
        channel_fields = {}
        if channel_dump:
            channel_fields = dict(re.findall(r"\b(mId|mImportance|mSound|mVibrationEnabled|mLights|mShowBadge)=([^,}]+)", channel_dump))
        numeric_flags = int(flags, 0) if re.fullmatch(r"0x[0-9a-fA-F]+|\d+", flags) else None
        required_flags = (("ONGOING_EVENT", 0x2), ("ONLY_ALERT_ONCE", 0x8), ("LOCAL_ONLY", 0x100))
        records.append({
            "importance": field(r"\bimportance=(-?\d+)", header),
            "effectiveImportance": field(r"(?m)^\s*mImportance=(\S+)"),
            "effectiveChannel": channel_fields,
            "visibility": field(r"\bvis=([^\s,)]+)", header),
            "requiredFlags": {name: bool(numeric_flags & bit) if numeric_flags is not None else name in flags.split("|") for name, bit in required_flags},
            **{name: field(r"(?m)^\s*" + name + r"=\s*([^\n]*?)\s*$") for name in ("mSound", "mVibration", "mLight")},
            "package": package, "id": int(notification_id), "tag": tag,
            "channel": channel, "key": key, "flags": flags, "summary": summary,
            "uid": field(r"(?m)^\s*uid=(\d+)\b"),
            "group": field(r"\b(?:group|groupKey)=([^\s,)]+)", header),
            "groupKey": field(r"(?m)^\s*groupKey=(\S+)"),
            "overrideGroupKey": field(r"(?m)^\s*(?:mOverrideGroupKey|overrideGroupKey)=(\S+)"),
            "title": field(r"android\.title=String \(([^\n]*)\)"),
            "text": field(r"android\.text=String \(([^\n]*)\)"),
            "subText": field(r"android\.subText=String \(([^\n]*)\)"),
            "largeIcon": field(r"android\.largeIcon=([^\n]+)"),
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
    for record in status:
        key = record["key"]
        assert record["importance"] == "3" and record["effectiveImportance"] in ("3", "DEFAULT"), "Expected DEFAULT record importance: " + key
        assert record["effectiveChannel"] == {"mId": "'status_icons'", "mImportance": "3", "mSound": "null", "mVibrationEnabled": "false", "mLights": "false", "mShowBadge": "false"}, "Expected quiet DEFAULT effective channel: " + key
        assert all(record[name] == "null" for name in ("mSound", "mVibration", "mLight")), "Non-null or missing record alert field: " + key
        assert all(record["requiredFlags"].values()), "Missing ongoing/only-alert-once/local-only flags: " + key
        assert record["visibility"] == "PRIVATE", "Expected normal PRIVATE visibility: " + key
    baseline_alerts = [] if baseline is None else [record for record in baseline if record["package"] == PACKAGE and record["channel"] == ALERT_CHANNEL]
    assert {record["key"] for record in alerts} == {record["key"] for record in baseline_alerts}, "Unexpected app alert records"
    assert len(app) == len(status) + len(alerts), "Unexpected app notification channel records"
    if baseline is not None:
        assert {record["key"] for record in status} == {record["key"] for record in baseline if record["package"] == PACKAGE and record["channel"] == STATUS_CHANNEL}, "Status notification keys changed"
    alert_events = parse_alert_events(events)
    order = list(dict.fromkeys(record["tag"].removeprefix("status-icon:") for record in status))
    return {"statusCount": len(status), "alertCount": len(alerts), "alertEvents": alert_events,
            "systemCount": len(records) - len(app), "accountDumpOrder": order,
            "ranking": status}


def posted_order(records, local_ids):
    """Pinned accounts by the `when` the app stamped on them, newest first: the order it posted.

    The app gives each account one real clock reading, later for an account earlier in its
    order, on summary and child alike. This is what the app asked for, not what SystemUI drew.
    """
    aliases = {local: alias for alias, local in local_ids.items()}
    stamps = {}
    for record in records:
        if record["package"] != PACKAGE or record["channel"] != STATUS_CHANNEL:
            continue
        alias = aliases.get(record["tag"].removeprefix("status-icon:"))
        assert alias, "Status record for an unknown account: " + record["key"]
        stamp = int(record["when"])
        assert stamps.setdefault(alias, stamp) == stamp, "Summary and child of " + alias + " carry different times"
    assert len(set(stamps.values())) == len(stamps), "Two pinned accounts carry the same time: " + str(stamps)
    return sorted(stamps, key=stamps.get, reverse=True)


def assert_order_split(state, records, pins_in):
    """ADR 0004: Overview and All accounts rank by headline; pinned icons keep the stored order.

    `overview` and `allAccounts` are the driver's reading of the two automatic-order seams,
    `accounts` the stored order and `pinOrder` the order the publisher posts pins in.
    """
    for name in ("overview", "allAccounts", "pinOrder"):
        assert isinstance(state.get(name), list), "Receiver result missing " + name
    assert state["accounts"] == ACCOUNTS, "Stored order moved: " + str(state["accounts"])
    assert sorted(state["overview"]) == sorted(ACCOUNTS), "Overview lost or gained accounts: " + str(state["overview"])
    assert state["allAccounts"] == state["overview"], f"All accounts {state['allAccounts']} disagrees with Overview {state['overview']}"
    assert state["overview"] != state["accounts"], "Automatic order equals the stored order; the scenario cannot tell them apart"
    assert state["pinOrder"] == pins_in, f"Pins left the stored order: {state['pinOrder']}"
    posted = posted_order(records, state["localIds"])
    assert posted == pins_in, f"Posted icon order {posted} is not the stored order {pins_in}"
    return {"overview": state["overview"], "allAccounts": state["allAccounts"], "pins": state["pins"],
            "pinOrder": state["pinOrder"], "posted": posted}


def parse_alert_events(events):
    """AOSP event: notification key, buzz, beep, blink, optional politeness/mute reason."""
    observations = []
    for line in events.splitlines():
        if "notification_alert" not in line or PACKAGE not in line:
            continue
        match = re.search(r"\bnotification_alert\s*:\s*\[([^\]]*)\]", line)
        assert match, "Malformed app notification_alert: " + line
        fields = [value.strip() for value in match[1].split(",")]
        assert len(fields) in (4, 5, 6) and "|" + PACKAGE + "|" in fields[0] and all(re.fullmatch(r"-?\d+", value) for value in fields[1:]), "Unrecognized app notification_alert: " + line
        buzz, beep, blink = map(int, fields[1:4])
        assert (buzz, beep, blink) == (0, 0, 0), "Nonzero app notification_alert: " + line
        observations.append({"key": fields[0], "buzz": buzz, "beep": beep, "blink": blink,
                             "politeness": fields[4] if len(fields) > 4 else None,
                             "muteReason": fields[5] if len(fields) > 5 else None, "raw": line})
    return observations


def parse_swipe_events(before, after, keys):
    """Keep log rows added between the two snapshots around the real gesture."""
    previous = Counter(before.splitlines())
    result = {"cancellations": [], "systemUiDismissEvents": [], "frameEvents": []}
    for line in after.splitlines():
        if previous[line]:
            previous[line] -= 1
            continue
        match = re.search(r"\bnotification_canceled:\s*\[([^,\]]+),\s*(\d+)(?=[,\]])", line)
        if match and match[1] in keys:
            result["cancellations"].append({"key": match[1], "reason": int(match[2]), "raw": line})
        elif re.search(r"\bsysui_multi_action:\s*\[[^\]]*\bnotification_dismissed\b", line):
            result["systemUiDismissEvents"].append(line)
        elif "FrameTracker:" in line and "NOTIFICATION_SHADE_ROW_SWIPE" in line:
            result["frameEvents"].append(line)
    return result


def dump_section(text, heading, *, end_heading=None, indent_fallback=None):
    matches = list(re.finditer(r"(?m)^([ \t]*)" + heading + r"[ \t]*$", text))
    assert len(matches) == 1, "Missing/ambiguous dump section: " + heading
    match = matches[0]
    section = text[match.end():]
    if end_heading is not None:
        end = re.search(r"(?m)^[ \t]*" + re.escape(end_heading) + r"[ \t]*$", section)
        if end is not None:
            return section[:end.start()].rstrip("\n")
        assert match[0].strip() == indent_fallback, "Missing dump section boundary: " + end_heading
    indent = len(match[1].expandtabs(4))
    lines = []
    for line in section.splitlines():
        expanded = line.expandtabs(4)
        if re.fullmatch(r"\s*-+\s*", line):
            continue
        if line.strip() and len(expanded) - len(expanded.lstrip()) <= indent:
            break
        lines.append(line)
    return "\n".join(lines)


def assert_no_heads_up(systemui, records):
    """Only the manager's active entries are authoritative; other sections repeat keys."""
    # API36/37 can outdent the heading or a field independently of the sibling.
    section = dump_section(systemui, r"HeadsUpManager(?:Phone)? state:",
                           end_heading="ShadeTouchableRegionManager state:",
                           indent_fallback="HeadsUpManagerPhone state:")
    keys = {record["key"] for record in records if record["package"] == PACKAGE and record["channel"] == STATUS_CHANNEL}
    assert not any(key in section for key in keys), "Status record in active heads-up manager entry"
    known = (r"mTouchAcceptanceDelay=\d+", r"mSnoozeLengthMs=\d+", r"now=\d+", r"mUser=\d+",
             r"snoozed packages: 0", r"mBarState=\d+", r"mTouchableRegion=SkRegion\(.*\)",
             r"m(?:HasPinnedNotification|HeadsUpPinnedMode)=false")
    required = ("mTouchAcceptanceDelay=", "mSnoozeLengthMs=", "now=", "mUser=", "snoozed packages:")
    assert all(name in section for name in required), "Unrecognized heads-up manager section"
    for line in section.splitlines():
        if line.strip():
            assert any(re.fullmatch(pattern, line.strip()) for pattern in known), "Unrecognized heads-up active section: " + line.strip()
    flags = re.findall(r"\b(?:mHeadsUpPinnedMode|mTrackingHeadsUp|trackingHeadsUp|mHeadsUpAnimatingAway|mExpandingFromHeadsUp)(?:=|:\s*)(true|false)\b", systemui)
    assert "true" not in flags, "Active heads-up presentation flag"
    return {"activeStatusKeys": [], "managerSection": section, "presentationFlags": flags,
            "scope": "Snapshot only; finite early frames cannot exclude every transient peek"}


def assert_alerting_section(systemui, records, api, shade=None):
    children = [record for record in records if record["package"] == PACKAGE and record["channel"] == STATUS_CHANNEL and not record["summary"]]
    if api == 30:
        assert shade is not None, "API30 needs actual shade title bounds"
        notifications = [bounds(node) for node in shade.iter("node") if node.get("text") == "Notifications" and bounds(node)]
        silent = [bounds(node) for node in shade.iter("node") if node.get("text") == "Silent" and bounds(node)]
        assert len(notifications) == 1 and len(silent) <= 1, "Missing/ambiguous shade Notifications/Silent headers"
        positions = {}
        for title in dict.fromkeys(record["title"] for record in children):
            matching = [record for record in children if record["title"] == title]
            titles = sorted({bounds(node) for node in shade.iter("node") if node.get("package") == "com.android.systemui" and node.get("resource-id") == "android:id/title" and node.get("text") == title and bounds(node)})
            assert len(titles) == len(matching), "Account title rows do not cover distinct children: " + str(title)
            assert all(box[1] >= notifications[0][3] and (not silent or box[3] <= silent[0][1]) for box in titles), "Account title not under Notifications (not Silent): " + str(title)
            for record, box in zip(matching, titles):
                positions[record["key"]] = box
        return {"assertion": "title positions under Notifications (not Silent)", "titleBounds": positions}
    section = dump_section(systemui, r"ShadeListBuilder shade notifications:")
    entries = re.findall(r"(?m)^\s*\[[^\]]+\]\s+(\S+)\s+\(parent=[^\n]*?\)\s+rank=\d+\s+section=([^\s]+)", section)
    for record in children:
        names = [name for key, name in entries if key == record["key"]]
        assert len(names) == 1 and names[0].split(":")[-1] == "Alerting", "Missing/non-Alerting account shade child: " + record["key"]
    return {"assertion": "Alerting", "childKeys": [record["key"] for record in children]}


def assert_provider_headers(records, root, local_ids):
    parents = {child: node for node in root.iter() for child in node}
    report = {}
    for alias, provider in zip(ACCOUNTS[:3], ("Claude", "OpenAI Codex", "Antigravity")):
        pair = [record for record in records if record["tag"] == "status-icon:" + local_ids[alias]]
        for record in pair:
            assert record["subText"] == provider, f"Expected provider-only sub-text {provider!r} on {record['key']}; got {record['subText']!r}"
        title = next(record["title"] for record in pair if not record["summary"])
        titles = [node for node in root.iter("node") if node.get("package") == "com.android.systemui"
                  and node.get("resource-id") == "android:id/title" and node.get("text") == title]
        header_nodes = []
        for title_node in titles:
            row = title_node
            while row in parents:
                row = parents[row]
                if row.get("resource-id") == "android:id/status_bar_latest_event_content":
                    break
            else:
                continue
            for node in row.iter("node"):
                if node.get("package") != "com.android.systemui":
                    continue
                observed = {field: node.get(field) for field in ("text", "content-desc", "resource-id", "bounds")}
                values = [value for value in (observed["text"], observed["content-desc"]) if value]
                if not values:
                    continue
                combined = any({"Usage Limits", provider} <= set(re.split(r"\s*[·•,]\s*", value)) for value in values)
                if observed["resource-id"] == "android:id/header_text":
                    assert provider in values or combined, f"Wrong shade header for {alias}: expected {provider!r}; got {values!r}"
                elif not (combined and observed["resource-id"] in ("android:id/notification_header", "android:id/notification_top_line", "android:id/app_name_text")):
                    continue
                if observed not in header_nodes:
                    header_nodes.append(observed)
        report[alias] = {"subText": provider, "title": title, "titleBounds": [node.get("bounds") for node in titles],
                         "shadeStatus": "verified" if header_nodes else "not_exposed", "shadeNodes": header_nodes}
    return report


def assert_duplicate_rows(records, root, local_ids, require_names=True):
    accounts = []
    surface = [{field: node.get(field) for field in ("text", "content-desc", "resource-id", "bounds")}
               for node in root.iter("node") if node.get("package") == "com.android.systemui" and (node.get("text") or node.get("content-desc"))]
    for alias, title, name in ((ACCOUNTS[0], "42% left", "Claude (synthetic)"),
                              (DUPLICATE_ACCOUNT, "87% left", "Claude work (synthetic)")):
        pair = [record for record in records if record["package"] == PACKAGE and record["channel"] == STATUS_CHANNEL and record["tag"] == "status-icon:" + local_ids[alias]]
        assert len(pair) == 2, "Missing personal/work same-provider group: " + alias
        sub_text = "Claude · " + name
        assert all(record["title"] == title and record["subText"] == sub_text for record in pair), "Duplicate account reading title/sub-text mismatch: " + alias
        assert all(record["text"] and "Resets" in record["text"] and name not in record["text"] for record in pair), "Duplicate account window/reset text missing: " + alias
        assert all(record["largeIcon"] and record["largeIcon"] != "null" for record in pair), "Duplicate provider artwork large icon missing: " + alias
        assert len({record["largeIcon"] for record in pair}) == 1, "Summary/child provider artwork differs: " + alias
        matches = [node for node in surface if name in (node["text"] or "") or name in (node["content-desc"] or "")]
        assert matches or not require_names, "Duplicate account name absent from actual shade accessibility XML: " + name
        accounts.append({"alias": alias, "name": name, "title": title, "nameExposed": bool(matches),
                         "subText": pair[0]["subText"], "largeIcon": pair[0]["largeIcon"], "shadeNodes": matches})
    return {"accounts": accounts, "namesExposed": all(account["nameExposed"] for account in accounts),
            "accessibilitySurface": surface, "scope": "Actual XML text/content descriptions; not a claim of TalkBack speech or unredacted lock-screen content"}


def assert_visible_icons(systemui, records):
    heading = r"(?:\[[^\]]+\] )?(?:NotificationIconContainerStatusBarViewModel|StatusBarIconViewModel):"
    if not re.search(r"(?m)^\s*" + heading + r"[ \t]*$", systemui):
        return {"status": "unsupported", "reason": "No status-bar visibleIcons metadata; human pixel inspection required (shelf/AOD are not substitutes)"}
    matches = list(re.finditer(r"(?m)^\s*" + heading + r"[ \t]*$", systemui))
    assert len(matches) == 1, "Ambiguous status-bar visibility model"
    tail = systemui[matches[0].end():]
    end = re.search(r"(?m)^\s*CRITICAL dump took[^\n]*$", tail)
    assert end, "Unterminated status-bar visibility model"
    section = tail[:end.start()]
    lists = re.findall(r"\bvisibleIcons=\[([^\]]*)\]", section)
    assert len(lists) == 1, "Missing/ambiguous status-bar visibleIcons metadata"
    assert not re.search(r"\b(?:visibleState|iconState)=(?:DOT|HIDDEN|1|2)\b|\b(?:hidden|isHidden|showingDot)=true\b", lists[0], re.I), "Status-bar visibleIcons reports a hidden/dot icon"
    keys = re.findall(r"\bnotifKey=([^,\s)]+)", lists[0])
    assert lists[0].count("NotificationIconInfo(") == len(keys), "Unrecognized visibleIcons item"
    app_keys = [key for key in keys if "|" + PACKAGE + "|" in key]
    expected = {record["key"] for record in records if record["package"] == PACKAGE and record["channel"] == STATUS_CHANNEL and not record["summary"]}
    assert len(app_keys) == len(expected) and set(app_keys) == expected, "Status-bar visibleIcons do not match account children (hidden/dot/wrong keys)"
    return {"status": "verified", "keys": app_keys, "scope": "SystemUI status-bar visibility model, not pixel proof or icon-order guarantee"}


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


def _has_launcher_workspace(root, package):
    workspace_id = package + ":id/workspace"
    return sum(1 for node in root.iter("node")
               if node.get("package") == package and node.get("resource-id") == workspace_id
               and node.get("enabled") == "true" and bounds(node)) == 1


def swipe_layout(root, title, api):
    parents = {child: parent for parent in root.iter() for child in parent}
    rows, collapse = set(), set()
    for node in root.iter("node"):
        if node.get("package") != "com.android.systemui":
            continue
        is_title = node.get("text") == title
        is_collapse = (node.get("resource-id") == "android:id/expand_button" and
                       node.get("content-desc") == "Collapse" and node.get("clickable") == "true")
        if not (is_title or is_collapse):
            continue
        row = content = None
        ancestor = parents.get(node)
        while ancestor is not None:
            resource = ancestor.get("resource-id", "")
            if resource.endswith("/expandableNotificationRow") and bounds(ancestor):
                row = bounds(ancestor)
            if api == 30 and content is None and resource == "android:id/status_bar_latest_event_content":
                content = bounds(ancestor)
            ancestor = parents.get(ancestor)
        # API 30 omits the group row ID; use its observed notification content instead.
        if row := row or content:
            if is_title:
                rows.add(row)
            if is_collapse and (button := bounds(node)):
                collapse.add(button)
    return sorted(rows), sorted(collapse)


class Evidence:
    def __init__(self, directory):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True)
        self.report = {"result": "incomplete", "installed": False, "lastAssertion": "boot readiness",
                       "scenarios": [], "visualAcceptance": "Pending human inspection of HOME/statusbar/shade screenshots; dump order is not visible icon order."}
        self.state = None
        self.last_post = 0
        self.ready_deadline = None
        self.early = None

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
                if focuses == [expanded] and _has_launcher_workspace(root, package):
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
            expected_aliases = set(ACCOUNTS) | ({DUPLICATE_ACCOUNT} if command == "same-provider" else set())
            assert set(state.get("localIds", {})) == expected_aliases, "Receiver missing/unexpected fixture localIds mapping"
            if command == "same-provider":
                assert self.state and state["accounts"] == [*self.state["accounts"], DUPLICATE_ACCOUNT], "Duplicate fixture changed previous account order"
                assert self.state and all(state["localIds"][alias] == self.state["localIds"][alias] for alias in ACCOUNTS), "Duplicate fixture changed original local IDs"
                assert state["pins"] == [ACCOUNTS[0], DUPLICATE_ACCOUNT] and state["enabled"], "Duplicate scenario must enable exactly the personal/work Claude pair"
                assert state.get("duplicateLocalIds") == {"personal": state["localIds"][ACCOUNTS[0]], "work": state["localIds"][DUPLICATE_ACCOUNT]}, "Duplicate completion missing personal/work identity mapping"
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
        self.home()
        self.settled()
        time.sleep(1)
        self.screen(name + "-home", crop=True)
        self.xml(name + "-home")
        notification = self.adb("shell", "dumpsys", "notification", "--noredact")
        self.save(name + "-notification.txt", notification)
        events = self.adb("shell", "logcat", "-b", "events", "-d", "-v", "threadtime")
        self.save(name + "-events.txt", events)
        presentation = {}
        for suffix, command in (
            ("statusbar", ("shell", "dumpsys", "statusbar")),
            ("systemui", ("shell", "dumpsys", "activity", "service", "com.android.systemui/.SystemUIService")),
        ):
            presentation[suffix] = self.adb(*command)
            self.save(name + "-" + suffix + ".txt", presentation[suffix])
        self.adb("shell", "cmd", "statusbar", "expand-notifications")
        time.sleep(2)
        self.screen(name + "-shade")
        shade = self.xml(name + "-shade")
        records = parse_notifications(notification)
        entry["observedRecords"] = records
        entry["shadeAccessibility"] = [{field: node.get(field) for field in ("text", "content-desc", "resource-id", "bounds", "focused", "accessibility-focused")} for node in shade.iter("node") if node.get("package") == "com.android.systemui" and (node.get("text") or node.get("content-desc"))]
        entry.update(statusCount=sum(record["package"] == PACKAGE and record["channel"] == STATUS_CHANNEL for record in records),
                     alertCount=sum(record["package"] == PACKAGE and record["channel"] == ALERT_CHANNEL for record in records),
                     systemCount=sum(record["package"] != PACKAGE for record in records))
        self.save(name + "-ranking.json", json.dumps(records, indent=2))
        self.save(name + "-ranking.txt", "\n".join(f"{record['key']} rankingTimeMs={record['rankingTimeMs']} when={record['when']} globalSortKey={record['globalSortKey']}" for record in records))
        local_ids = [self.state["localIds"][account] for account in expected]
        entry.update(assert_snapshot(records, local_ids, baseline=baseline, events=events))
        if name == "03-enabled-default":
            assert self.early is not None, f"{name}: heads-up window not observed: missing early SystemUI dump"
            try:
                self._assert_first_post_collection(name, entry["ranking"])
            finally:
                self._save_early_timing(name, self.early)
        entry["headsUp"] = assert_no_heads_up(presentation["systemui"], records)
        entry["visibleIcons"] = assert_visible_icons(presentation["systemui"], records)
        if name in ("03-enabled", "05-reranked"):
            entry["providerHeaders"] = assert_provider_headers(entry["ranking"], shade, self.state["localIds"])
        if name == "14-same-provider":
            entry["duplicateRows"] = assert_duplicate_rows(records, shade, self.state["localIds"])
        if expected:
            entry["shadeSection"] = assert_alerting_section(presentation["systemui"], records, self.report["api"], shade)
        if self.early is not None:
            entry["early"] = {"samples": self.early["samples"], "commandStarted": self.early["commandStarted"],
                              "headsUp": assert_no_heads_up(self.early["systemui"], records),
                              "alertEvents": parse_alert_events(self.early["events"])}
            assert not re.search(r"Application (?:Not Responding|not responding|Error):", self.early["window"]), "System error during early frames"
            self.early = None
        entry["result"] = "passed"
        self.adb("shell", "cmd", "statusbar", "collapse")
        self.summary()
        return records

    def default_policy(self, name):
        notification = self.adb("shell", "dumpsys", "notification", "--noredact")
        systemui = self.adb("shell", "dumpsys", "activity", "service", "com.android.systemui/.SystemUIService")
        self.save(name + "-notification.txt", notification)
        self.save(name + "-systemui.txt", systemui)
        result = {"hideSilentStatusBar": re.findall(r"\bhideSilentStatusBar=(true|false)\b", notification),
                  "showSilentStatusIcons": re.findall(r"\bshowSilentStatusIcons=(true|false)\b", systemui)}
        assert all(len(values) <= 1 for values in result.values()), "Ambiguous default visibility policy"
        if self.report["api"] >= 36:
            assert len(result["hideSilentStatusBar"]) == 1, "Missing hideSilentStatusBar policy"
        if self.report["api"] == 37:
            assert result == {"hideSilentStatusBar": ["true"], "showSilentStatusIcons": ["false"]}, "API37 clean-image silent-icon default changed"
        result["unsupportedFields"] = [field for field, values in result.items() if not values]
        return result

    def home(self):
        self.adb("shell", "cmd", "statusbar", "collapse")
        self.adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        self.adb("shell", "input", "keyevent", "KEYCODE_HOME")

    def _early_systemui(self, name, timing, completed):
        window = timing["headsUpWindow"]
        dump_error = None
        try:
            response = self.adb("shell", "sh", "-c",
                                'read -r uptime _ < /proc/uptime && printf "systemuiDumpStartedUptime=%s\\n" "$uptime" && '
                                'exec dumpsys activity service com.android.systemui/.SystemUIService', timeout=8)
        except (OSError, subprocess.SubprocessError) as error:
            dump_error = str(error)
            response = (getattr(error, "stdout", None) or b"").decode(errors="replace").strip()
        stamp, _, systemui = response.partition("\n")
        match = re.fullmatch(r"systemuiDumpStartedUptime=([0-9]+)\.([0-9]{2})", stamp)
        started = int(match[1]) * 1000 + int(match[2]) * UPTIME_RESOLUTION_MS if match else None
        completed = completed if type(completed) is int and completed >= 0 else None
        delay = started - completed if started is not None and completed is not None else None
        # /proc/uptime rounds down; the entire interval must fit in the window.
        window.update(completedElapsedRealtimeMs=completed, systemuiDumpStartedElapsedRealtimeMs=started, delayMs=delay,
                      startInWindow=dump_error is None and bool(systemui.strip()) and delay is not None
                      and 0 <= delay <= HEADS_UP_MAX_DELAY_MS - UPTIME_RESOLUTION_MS)
        if dump_error is not None:
            window["dumpError"] = dump_error
        timing["systemui"] = systemui if match else response
        self.save(name + "-early-systemui.txt", timing["systemui"])

    def _save_early_timing(self, name, timing):
        self.save(name + "-early-timing.json", json.dumps({key: value for key, value in timing.items() if key not in ("window", "systemui", "logcat", "events")}, indent=2))

    def _assert_first_post_collection(self, name, ranking):
        window = self.early["headsUpWindow"]
        expected = {record["key"] for record in ranking if not record["summary"]}
        check = window["firstPostCollection"] = {"expectedChildKeys": sorted(expected)}
        try:
            section = dump_section(self.early["systemui"], r"NotifCollection unsorted/unfiltered notifications:(?:[ \t]+\d+)?")
            keys = set(re.findall(r"(?m)^[ \t]*\[\d+\][ \t]+(\S+)", section))
            check["missingChildKeys"] = sorted(expected - keys)
            assert expected and not check["missingChildKeys"], "early NotifCollection lacks expected child keys"
        except AssertionError as error:
            check.update(result="failed", reason=str(error))
            raise AssertionError(f"{name}: heads-up window not observed: {error}") from None
        check["result"] = "verified"
        window["observed"] = True

    def early_frames(self, name, ready, start, timing):
        samples = []
        try:
            self.screen(name + "-early-0000ms-home", crop=True)
            ready.set()
            assert start.wait(20), "Posting command did not start during early observer"
            if "commandStarted" not in timing:
                return
            for offset, label in ((0.25, "0250ms"), (0.5, "0500ms"), (1.0, "1000ms"), (2.0, "2000ms")):
                time.sleep(max(0, timing["commandStarted"] + offset - time.monotonic()))
                sample = {"requestedSeconds": offset, "actualStartSeconds": time.monotonic() - timing["commandStarted"]}
                self.screen(name + "-early-" + label + "-home", crop=True)
                sample["actualEndSeconds"] = time.monotonic() - timing["commandStarted"]
                samples.append(sample)
            for suffix, command in (
                ("window", ("shell", "dumpsys", "window")),
                ("logcat", ("shell", "logcat", "-d", "-v", "threadtime")),
                ("events", ("shell", "logcat", "-b", "events", "-d", "-v", "threadtime")),
            ):
                timing[suffix] = self.adb(*command, timeout=8)
                self.save(name + "-early-" + suffix + ".txt", timing[suffix])
        except (AssertionError, RuntimeError, OSError, subprocess.SubprocessError) as error:
            timing["observerError"] = str(error)
        finally:
            ready.set()
            timing["samples"] = samples

    def transition(self, command, name):
        self.report["lastAssertion"] = name + ": pre-command HOME readiness"
        self.home()
        root = self.xml(name + "-early-precommand-home")
        package = self.report["preflight"]["component"].split("/", 1)[0]
        assert _has_launcher_workspace(root, package), (
            "Pre-command HOME must expose exactly one enabled launcher workspace with bounds"
        )
        window = {"completedElapsedRealtimeMs": None, "systemuiDumpStartedElapsedRealtimeMs": None,
                  "delayMs": None, "maximumDelayMs": HEADS_UP_MAX_DELAY_MS,
                  "stampResolutionMs": UPTIME_RESOLUTION_MS, "startInWindow": False, "observed": False}
        self.report.setdefault("earlyHeadsUpWindows", {})[name] = window
        ready, start, timing = threading.Event(), threading.Event(), {"headsUpWindow": window}
        observer = threading.Thread(target=self.early_frames, args=(name, ready, start, timing), daemon=True)
        observer.start()
        try:
            assert ready.wait(15), "Early observer did not establish pre-command HOME frame"
            assert "observerError" not in timing, timing.get("observerError")
            timing["commandStarted"] = time.monotonic()
            start.set()
            state = self.command(command)
            self.report["lastAssertion"] = name + ": early heads-up window"
            # SystemUI can receive the notifications after the receiver has finished.
            time.sleep(1)
            self._early_systemui(name, timing, state.get("completedElapsedRealtimeMs"))
            assert window["startInWindow"], f"{name}: heads-up window not observed"
            window["observed"] = command != "enable"
        finally:
            start.set()
            observer.join(90)
            assert not observer.is_alive(), "Early observer exceeded bounded collection window"
            self._save_early_timing(name, timing)
        assert "observerError" not in timing, timing.get("observerError")
        self.early = timing

    def lockscreen(self, baseline, name="03-enabled-lockscreen", expected=None):
        if expected is None:
            expected = ACCOUNTS[:3]
        self.report["lastAssertion"] = "non-secure emulator lock-screen capture"
        self.home()
        disabled = self.adb("shell", "locksettings", "get-disabled").strip()
        self.save(name + "-original-disabled.txt", disabled)
        assert disabled in ("true", "false"), "Unrecognized original keyguard disabled state"
        before = self.adb("shell", "dumpsys", "activity", "service", "com.android.systemui/.SystemUIService")
        self.save(name + "-before-systemui.txt", before)
        security = dump_section(before, r"KeyguardStateController:")
        assert re.findall(r"(?m)^\s*mSecure: (true|false)\s*$", security) == ["false"], "SystemUI must report non-secure keyguard before capture"
        changed = disabled == "true"
        try:
            if changed:
                self.save(name + "-enable-swipe.txt", self.adb("shell", "locksettings", "set-disabled", "false"))
                assert self.adb("shell", "locksettings", "get-disabled").strip() == "false", "Swipe keyguard did not enable"
            self.adb("shell", "input", "keyevent", "KEYCODE_SLEEP")
            time.sleep(0.5)
            self.adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
            deadline = time.monotonic() + 10
            while True:
                window = self.adb("shell", "dumpsys", "window", timeout=8)
                delegate = dump_section(window, r"KeyguardServiceDelegate")
                if re.search(r"(?m)^\s*showing=true\s*$", delegate) and re.search(r"(?m)^\s*occluded=false\s*$", delegate):
                    break
                assert time.monotonic() < deadline, "Keyguard showing=true not observed from WINDOW"
                time.sleep(0.25)
            secure = re.findall(r"(?m)^\s*secure=(true|false)\s*$", delegate)
            assert secure in ([], ["false"]), "Secure keyguard is outside emulator capture scope"
            time.sleep(1)
            self.screen(name)
            lock_xml = self.xml(name)
            policy = {"window": self.adb("shell", "dumpsys", "window")}
            for suffix, command in (
                ("window-policy", ("shell", "dumpsys", "window", "policy")),
                ("trust", ("shell", "dumpsys", "trust")),
                ("systemui", ("shell", "dumpsys", "activity", "service", "com.android.systemui/.SystemUIService")),
                ("notification", ("shell", "dumpsys", "notification", "--noredact")),
            ):
                policy[suffix] = self.adb(*command)
            for suffix, text in policy.items():
                self.save(name + "-" + suffix + ".txt", text)
            delegate = dump_section(policy["window"], r"KeyguardServiceDelegate")
            assert re.search(r"(?m)^\s*showing=true\s*$", delegate), "Keyguard stopped showing during capture"
            security = dump_section(policy["systemui"], r"KeyguardStateController:")
            assert re.findall(r"(?m)^\s*mSecure: (true|false)\s*$", security) == ["false"], "Keyguard security changed during emulator capture"
            records = parse_notifications(policy["notification"])
            result = assert_snapshot(records, [self.state["localIds"][account] for account in expected], baseline=baseline)
            result.update(label="Emulator non-secure swipe keyguard only; normal PRIVATE notification policy, no AOD/secure-phone claim",
                          keyguardShowing=True, secure=False, originalDisabled=disabled, temporarySwipeEnabled=changed,
                          accessibility=[{field: node.get(field) for field in ("text", "content-desc", "resource-id", "bounds", "focused", "accessibility-focused")} for node in lock_xml.iter("node") if node.get("package") == "com.android.systemui" and (node.get("text") or node.get("content-desc"))],
                          headsUp=assert_no_heads_up(policy["systemui"], records))
            if name == "14-same-provider-lockscreen":
                result["duplicateRows"] = assert_duplicate_rows(records, lock_xml, self.state["localIds"], require_names=False)
            self.report.setdefault("lockscreens", {})[name] = result
        finally:
            try:
                self.adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
                self.adb("shell", "wm", "dismiss-keyguard")
            finally:
                if changed:
                    self.save(name + "-restore-swipe.txt", self.adb("shell", "locksettings", "set-disabled", "true"))
                restored = self.adb("shell", "locksettings", "get-disabled").strip()
                self.save(name + "-restored-disabled.txt", restored)
                assert restored == disabled, "Original keyguard disabled state not restored"
            self.home()

    def stable_swipe_row(self, title, api, evidence):
        deadline = time.monotonic() + 30
        previous, tapped = None, set()
        for index in range(1, 9):
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            name = f"swipe-layout-{index}"
            sample = {"dump": name + ".xml"}
            evidence["layoutSamples"].append(sample)
            try:
                root = self.xml(name, timeout=min(8, remaining / 3))
            except (ET.ParseError, subprocess.SubprocessError) as error:
                sample["error"] = str(error)
                previous = None
                continue
            layout = swipe_layout(root, title, api)
            rows, collapse = layout
            sample.update(rowBounds=rows, collapseButtons=collapse)
            if time.monotonic() >= deadline:
                break
            if len(rows) == 1 and previous == layout:
                if not collapse:
                    self.save("swipe-before-shade.xml", ET.tostring(root, encoding="unicode"))
                    return rows[0]
                # Collapse an observed control, not the row; re-resolve the moved target afterward.
                button = collapse[0]
                if button not in tapped:
                    x1, y1, x2, y2 = button
                    self.adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2),
                             timeout=min(8, deadline - time.monotonic()))
                    sample["tappedCollapse"] = button
                    tapped.add(button)
                    previous = None
                    continue
            previous = layout
        raise AssertionError("Claude shade layout did not stabilize; refusing guessed swipe coordinates")

    def swipe_claude(self, records):
        tag = "status-icon:" + self.state["localIds"][ACCOUNTS[0]]
        target = [record for record in records if record["package"] == PACKAGE and record["tag"] == tag]
        titles = {record["title"] for record in target}
        assert len(titles) == 1 and None not in titles, "Cannot resolve Claude notification title from dump"
        title = next(iter(titles))
        api = self.report["api"]
        self.report["lastAssertion"] = "real Claude notification-row swipe"
        evidence = self.report["swipe"] = {"title": title, "api": api, "layoutSamples": []}
        self.adb("shell", "cmd", "statusbar", "expand-notifications")
        box = self.stable_swipe_row(title, api, evidence)
        self.screen("swipe-before-shade")
        x1, y1, x2, y2 = box
        width = png_frame((self.directory / "swipe-before-shade.png").read_bytes(), 0)[0]
        assert 0 <= x1 < x2 <= width, "Observed shade row outside screenshot"
        start, end, y = x1 + (x2 - x1) // 4, width - 2, (y1 + y2) // 2
        duration = 500
        evidence.update(rowBounds=box, start=[start, y], end=[end, y], durationMs=duration)
        event_log = ("shell", "logcat", "-b", "events", "-b", "main", "-d", "-v", "threadtime", "-s",
                     "notification_canceled:I", "sysui_multi_action:I", "FrameTracker:W")
        before = self.adb(*event_log)
        self.save("swipe-before-events.txt", before)
        self.save("swipe.json", json.dumps(evidence, indent=2))
        self.adb("shell", "input", "swipe", str(start), str(y), str(end), str(y), str(duration))
        self.last_post = time.monotonic()
        self.settled()
        after = self.adb(*event_log)
        self.save("swipe-after-events.txt", after)
        evidence.update(parse_swipe_events(before, after, {record["key"] for record in target}))
        self.screen("swipe-after-shade")
        after_root = self.xml("swipe-after-shade")
        evidence["rowBoundsAfter"], _ = swipe_layout(after_root, title, api)
        if any(event["reason"] == 2 for event in evidence["cancellations"]):
            evidence["outcome"] = "target-dismissal-observed"
        elif evidence["cancellations"]:
            evidence["outcome"] = "target-cancel-for-other-reason"
        elif evidence["rowBoundsAfter"] == [box]:
            evidence["outcome"] = "row-retained-without-target-cancel"
        else:
            evidence["outcome"] = "no-target-dismissal-observed"
        self.save("swipe.json", json.dumps(evidence, indent=2))

    def scenario(self, apk):
        self.report["api"] = int(self.adb("shell", "getprop", "ro.build.version.sdk"))
        assert self.report["api"] in (30, 36, 37), "Evidence requires API30/36/37"
        self.report["defaultBefore"] = self.default_policy("defaults-before")
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
        self.transition("enable", "03-enabled-default")
        assert self.state["enabled"], "Feature did not enable"
        default = self.capture("03-enabled-default", "OFF with three pins -> ON; unchanged platform silent-icon preference", ACCOUNTS[:3])
        full = self.capture("03-enabled", "DEFAULT importance, quiet channel; untouched silent-icon policy; initial account order differs pin order", ACCOUNTS[:3], baseline=default)
        self.lockscreen(full)
        self.transition("update", "04-updated")
        full = self.capture("04-updated", "full six; Claude42 ->87 through repository/cacheMoved", ACCOUNTS[:3], baseline=full)
        before = assert_order_split(self.state, full, pins_in=ACCOUNTS[:3])
        self.transition("rerank", "05-reranked")
        full = self.capture("05-reranked", "full six; synthetic Antigravity reading 100 -> 2 through repository/cacheMoved over the seeded legacy stored order", ACCOUNTS[:3], baseline=full)
        after = assert_order_split(self.state, full, pins_in=ACCOUNTS[:3])
        assert self.state["pins"] == before["pins"], "The reading changed the stored pins"
        assert before["overview"][-1] == ACCOUNTS[2] and after["overview"][:2] == [ACCOUNTS[3], ACCOUNTS[2]], (
            f"The reading did not move Antigravity up the automatic order: {before['overview']} -> {after['overview']}")
        self.report["orderSplit"] = {"before": before, "after": after}
        self.command("states")
        self.capture("06-states", "full six; original pinned accounts become exhausted/stale/error through repository/cacheMoved", ACCOUNTS[:3])
        self.command("reset-readings")
        full = self.capture("07-restored", "full six state-glyph records; reset-readings via repository/cacheMoved retains the stored order and pins", ACCOUNTS[:3])
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
        self.command("same-provider")
        duplicate = [ACCOUNTS[0], DUPLICATE_ACCOUNT]
        pair = self.capture("14-same-provider", "two pinned Claude accounts, personal42/work87; chosen claude-color provider artwork; four records", duplicate)
        self.lockscreen(pair, name="14-same-provider-lockscreen", expected=duplicate)

    def summary(self):
        self.report["screenshots"] = sorted(path.name for path in self.directory.glob("*.png"))
        self.save("capture-manifest.json", json.dumps(self.report, indent=2))
        lines = ["# Status-icon device evidence — API " + str(self.report.get("api", "preflight")), "", "Result: " + self.report["result"], "", "Last assertion: " + self.report["lastAssertion"], "", self.report["visualAcceptance"], "", "| Scenario | Starting state | Status records | App alert records | System records | Result |", "|---|---|---:|---:|---:|---|"]
        for entry in self.report["scenarios"]:
            lines.append("| " + " | ".join(str(value).replace("|", "\\|") for value in (entry["name"], entry["startingState"], entry.get("statusCount", "?"), entry.get("alertCount", "?"), entry.get("systemCount", "?"), entry["result"])) + " |")
        lines += ["", "## Untouched platform defaults", "", json.dumps({key: self.report.get(key) for key in ("defaultBefore", "defaultAfter", "defaultFlagsUnchanged")}, sort_keys=True), "", "03-enabled-default retains the API37 hideSilentStatusBar=true/showSilentStatusIcons=false default while the DEFAULT channel remains eligible for status-bar icons. 03-enabled is the same unchanged-policy normal capture; there is no Settings navigation.", "", "## Emulator lock screen", "", json.dumps(self.report.get("lockscreens", {}), sort_keys=True)]
        if split := self.report.get("orderSplit"):
            lines += ["", "## Automatic order and pinned order (ADR 0004)", "",
                      "Asserted from the driver's projections and the `when` the app stamped on each pinned account; "
                      "visible icon and shade order remain observations below. The stored order was seeded with the "
                      "repository writer as fixture set-up; the change between the two rows is a synthetic reading."]
            for label, state in (("Before the reading", split["before"]), ("After the reading", split["after"])):
                lines.append(f"- {label}: Overview {', '.join(state['overview'])}; All accounts {', '.join(state['allAccounts'])}; "
                             f"pins posted {', '.join(state['posted'])}")
        if swipe := self.report.get("swipe"):
            lines += ["", "## Real Claude row swipe", "",
                      f"- Layout samples: {len(swipe['layoutSamples'])}; diagnostics: `swipe-layout-*.xml`."]
            if "durationMs" in swipe:
                lines.append(f"- Stroke: {swipe['start']} → {swipe['end']} in {swipe['durationMs']} ms (no retry).")
            if "outcome" in swipe:
                reasons = ", ".join(str(event["reason"]) for event in swipe["cancellations"]) or "none"
                lines.append(f"- Pre-HOME observation: {swipe['outcome']}; target cancel reasons: {reasons}; "
                             f"SystemUI dismiss markers: {len(swipe['systemUiDismissEvents'])}; "
                             f"row-swipe frame warnings: {len(swipe['frameEvents'])}. See `swipe.json` and before/after logs.")
        if windows := self.report.get("earlyHeadsUpWindows"):
            lines += ["", "## Early heads-up window", "",
                      "Device elapsed time; dump start is rounded down to 10 ms, so the full timing interval must fit the bound."]
            for name, window in windows.items():
                delay = window["delayMs"]
                maximum = window["maximumDelayMs"] / 1000
                measured = "delay unavailable" if delay is None else f"dump start − receiver completion: {delay / 1000:.3f} s"
                result = "within bound" if window["startInWindow"] else "heads-up window not observed"
                if check := window.get("firstPostCollection"):
                    if check["result"] == "verified":
                        result += "; all expected child keys in the first NotifCollection snapshot"
                    else:
                        result += "; heads-up window not observed: " + check["reason"]
                lines.append(f"- {name}: {measured} (maximum {maximum:.1f} s); {result}.")
        lines += ["", "## Notification header evidence", ""]
        for entry in self.report["scenarios"]:
            headers = [*entry.get("providerHeaders", {}).values(), *entry.get("duplicateRows", {}).get("accounts", [])]
            for row in headers:
                fields = [{key: node[key] for key in ("text", "content-desc")} for node in row["shadeNodes"]]
                shade = json.dumps(fields, ensure_ascii=False) if fields else "not exposed in a recognized shade XML header; inspect PNG"
                lines.append(f"- {entry['name']}, {row['title']}: summary and child sub-text `{row['subText']}`; shade XML: {shade}")
        lines += ["", "## Evidence limits", "", "Visible-icon metadata describes only the SystemUI status-bar model. Unsupported images require human pixel inspection; records, shelf and AOD entries never substitute for visible icons. Icon and shade order are observations, not assertions. Standard-template sub-text always names the provider and adds the account label only for same-provider pins. Both duplicate account names must be present in shade XML; lock-screen visibility is report-only. Contracted shade rows may omit header nodes from XML; raw XML and PNGs are retained for inspection. XML shows exposed text and descriptions, not actual TalkBack speech. Early pre-enable/update/rerank frames target 0.25, 0.5, 1 and 2 seconds with actual timings; finite frames and manager snapshots cannot prove that no transient peek ever occurred. No physical-phone, audible-output, OEM or always-on-display claim.", "", "## Ranking observations"]
        for entry in self.report["scenarios"]:
            if "accountDumpOrder" in entry:
                aliases = {local: alias for alias, local in entry["driver"]["localIds"].items()}
                lines += ["", "Dump account order (not visible icon order), " + entry["name"] + ": " + ", ".join(aliases.get(local, local) for local in entry["accountDumpOrder"]), ""]
                if entry["name"] in ("03-enabled", "05-reranked"):
                    account_order = [alias for alias in entry["driver"].get("accounts", []) if alias in entry["expectedAccounts"]]
                    if account_order:
                        lines.append("- App account order: " + ", ".join(account_order))
                    headers = entry.get("providerHeaders", {})
                    positions = [(box[1], alias) for alias, row in headers.items() if len(row["titleBounds"]) == 1
                                 if (box := bounds({"bounds": row["titleBounds"][0] or ""}))]
                    if positions and len({y for y, _ in positions}) == len(headers):
                        lines.append("- Shade order from XML: " + ", ".join(alias for _, alias in sorted(positions)))
                    else:
                        lines.append("- Shade row order not resolved from XML; inspect the shade screenshot.")
                    model = entry.get("visibleIcons", {})
                    if model.get("status") == "verified":
                        key_aliases = {record["key"]: aliases.get(record["tag"].removeprefix("status-icon:"), record["key"]) for record in entry["ranking"] if record["tag"]}
                        lines.append("- Status-bar model order (inspect the pixels separately): " + ", ".join(key_aliases.get(key, key) for key in model["keys"]))
                    else:
                        lines.append("- This image does not expose status-bar model order; inspect the magnified crop.")
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
        if "defaultBefore" in evidence.report:
            try:
                evidence.report["defaultAfter"] = evidence.default_policy("defaults-after")
                assert evidence.report["defaultBefore"] == evidence.report["defaultAfter"], "Platform visibility default flags changed"
                evidence.report["defaultFlagsUnchanged"] = True
            except (AssertionError, OSError, subprocess.SubprocessError) as error:
                evidence.report.update(result="failed", error=str(error), defaultFlagsUnchanged=False)
                status = 1
        evidence.summary()
    print(f"Status-icon evidence: {evidence.report['result']}; {args.evidence / 'summary.md'}")
    return status


if __name__ == "__main__":
    raise SystemExit(main())
