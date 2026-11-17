#!/usr/bin/env python3
"""Observe visibility at untouched device defaults, never equating records to icons.

Debug repository commands only; no direct posts or notification-setting mutations.
Promoted quota displays are plumbing controls, not semantically valid Live Updates.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import threading
import time
import xml.etree.ElementTree as ET

from status_icon_evidence import (
    ACCOUNTS, ALERT_CHANNEL, PACKAGE, PIN_ORDER, Evidence, parse_notifications,
)


CHANNELS = {"low": "status_icons", "default": "prototype_status_default",
            "promoted": "prototype_status_promoted",
            "promoted-ungrouped": "prototype_status_promoted",
            "promoted16-colorized": "prototype_status_promoted16_colorized",
            "promoted16-colorized-ungrouped": "prototype_status_promoted16_colorized"}
CANDIDATES = [("low", "low", 3), ("default", "default", 3),
              ("promoted-one", "promoted", 1), ("promoted-three", "promoted", 3),
              ("promoted-ungrouped-one", "promoted-ungrouped", 1),
              ("promoted-ungrouped-three", "promoted-ungrouped", 3)]
LEGACY_CONTROLS = [(recipe + "-" + suffix, recipe, count)
                   for recipe in ("promoted16-colorized", "promoted16-colorized-ungrouped")
                   for suffix, count in (("one", 1), ("three", 3))]
PROMOTED_FLAG = 0x40000


def parse_observations(text):
    """Enrich the existing active-list parser, retaining raw runtime field values."""
    records = parse_notifications(text)
    section = re.search(r"(?m)^([ \t]*)Notification List:[ \t]*$", text)
    active = text[section.end():]
    boundary = re.search(r"(?m)^ {0," + str(len(section[1])) + r"}\S", active)
    if boundary:
        active = active[:boundary.start()]
    blocks = re.split(r"(?m)^\s*(?=NotificationRecord\()", active)
    active_blocks = [block for block in blocks if block.startswith("NotificationRecord(")]
    for record, block in zip(records, active_blocks):
        header = block.splitlines()[0]

        def field(pattern):
            match = re.search(pattern, block)
            return match[1] if match else None

        importance = re.search(r"\bimportance=(-?\d+)", header)
        record["importance"] = int(importance[1]) if importance else None
        for name in ("mSound", "mVibration", "mLight", "mIsInterruptive", "mSuppressedVisualEffects", "mPromoted"):
            record[name] = field(r"(?m)^\s*" + name + r"=([^\n]*)")
        flags = record["flags"]
        numeric = int(flags, 0) if re.fullmatch(r"0x[\da-fA-F]+|\d+", flags) else None
        record["numericFlags"] = numeric
        record["promoted"] = bool(numeric & PROMOTED_FLAG) if numeric is not None else any(
            flag.removeprefix("FLAG_") == "PROMOTED_ONGOING" for flag in flags.split("|"))
        for name, extra in (("requestPromotedOngoing", "requestPromotedOngoing"),
                            ("shortCriticalText", "shortCriticalText"), ("style", "template")):
            record[name] = field(r"android\." + extra + r"=\w+ \(([^\n]*)\)")
        record["channelGroup"] = field(r"\bmGroup='([^']*)'")
        record["channelDump"] = field(r"(?m)^\s*(?:mChannel|channel)=(.*)")
        record["rawBlock"] = block.rstrip()
    return records


def parse_alert_events(events, uids):
    """notification_alert is [uid,buzz,beep,blink] on AOSP; keep unfamiliar rows."""
    observations = []
    for line in events.splitlines():
        match = re.search(r"\bnotification_alert\s*:\s*\[([^\]]*)\]", line)
        if not match:
            continue
        fields = [field.strip() for field in match[1].split(",")]
        if not fields:
            continue
        keyed = "|" + PACKAGE + "|" in fields[0]
        if not keyed and fields[0] not in uids:
            continue
        values = fields[1:4] if keyed else fields[1:]
        if not keyed and len(fields) >= 7 and fields[1] == PACKAGE:
            values = fields[-3:]
        observation = {"uid": None if keyed else fields[0], "sound": None, "vibrate": None, "blink": None, "raw": line}
        if keyed:
            observation.update(key=fields[0], politeness=fields[4] if len(fields) > 4 else None,
                               muteReason=fields[5] if len(fields) > 5 else None)
        if len(values) == 3 and all(re.fullmatch(r"\d+", value) for value in values):
            observation.update(vibrate=int(values[0]), sound=int(values[1]), blink=int(values[2]))
        observations.append(observation)
    return observations


def observe_snapshot(records, expected_accounts, channel, grouped, events="", uids=()):
    """Validate core account structure; promotion, ranking and alerts are observations."""
    app = [record for record in records if record["package"] == PACKAGE]
    status = [record for record in app if record["channel"] == channel]
    originals = [record for record in status if (record.get("tag") or "").startswith("status-icon:")
                 and record["id"] in (2001, 2002)]
    violations = []

    def require(condition, message):
        if not condition:
            violations.append(message)

    require(not any(record["channel"] == ALERT_CHANNEL for record in app), "Unexpected app usage_alerts records")
    require(all(record["channel"] == channel for record in app), "Unexpected app notification channel records")
    require(len({record["key"] for record in app}) == len(app), "Duplicate notification keys")
    expected_tags = {"status-icon:" + account for account in expected_accounts}
    require({record["tag"] for record in originals} == expected_tags, "Unexpected status account tags")
    require(len(originals) == len(expected_accounts) * (2 if grouped else 1), "Unexpected original status record count")
    auto_groups = [record["key"] for record in app if any(
        re.search(r"Aggregate_|ranker_group", str(record.get(name) or ""), re.I)
        for name in ("group", "groupKey", "overrideGroupKey", "tag", "key"))]
    if grouped:
        require(len(status) == len(originals), "Unexpected grouped aggregate records")
        require(not auto_groups, "Forbidden grouped auto-group")
        require(all(record.get("overrideGroupKey") in (None, "null") for record in app), "Unexpected grouped override")
    for tag in expected_tags:
        group = [record for record in originals if record["tag"] == tag]
        require({record["id"] for record in group} == ({2001, 2002} if grouped else {2002}), "Malformed account IDs: " + tag)
        if grouped:
            require(all(record["group"] == tag and (record.get("groupKey") or "").endswith("|g:" + tag)
                        for record in group), "Malformed account group: " + tag)
            require(all(record["summary"] == (record["id"] == 2001) for record in group), "Malformed summary flags: " + tag)
        require(all(all(record.get(name) is not None for name in ("rankingTimeMs", "when", "globalSortKey"))
                    for record in group), "Missing ranking observations: " + tag)
    known_uids = set(uids) | {record["uid"] for record in app if record.get("uid")}
    return {"statusCount": len(status), "originalCount": len(originals),
            "alertCount": sum(record["channel"] == ALERT_CHANNEL for record in app),
            "systemCount": len(records) - len(app), "promotedCount": sum(record.get("promoted", False) for record in originals),
            "accountDumpOrder": list(dict.fromkeys(record["tag"].removeprefix("status-icon:") for record in originals)),
            "autoGroups": auto_groups, "ranking": status, "violations": violations,
            "alertEvents": parse_alert_events(events, known_uids),
            "visibleIcons": "Pending human inspection; records are not icon or chip counts"}


class VisibilityEvidence(Evidence):
    def __init__(self, directory, recipe, api, uid):
        super().__init__(directory)
        self.recipe = recipe
        self.grouped = "ungrouped" not in recipe
        self.uid = uid
        self.report.update(api=api, recipe=recipe, grouped=self.grouped,
                           label="plumbing control; ambient quota is not a semantic Live Update" if "promoted" in recipe else "grouped visibility candidate",
                           failures=[], observations=[])

    def summary(self):
        self.report["screenshots"] = sorted(path.name for path in self.directory.glob("*.png"))
        self.save("summary.json", json.dumps(self.report, indent=2))
        self.save("capture-manifest.json", json.dumps(self.report, indent=2))
        lines = ["# Visibility candidate: " + self.recipe, "", self.report["label"], "",
                 "Result: " + self.report["result"], "",
                 "Default platform settings are untouched. Records, promoted flags, dump order and visible chips are separate observations.", "",
                 "| Step | Original records | Promoted records | Alert events | Structure |", "|---|---:|---:|---:|---|"]
        for entry in self.report["scenarios"]:
            lines.append("| " + " | ".join(str(value).replace("|", "\\|") for value in (
                entry["name"], entry.get("originalCount", "?"), entry.get("promotedCount", "?"),
                len(entry.get("alertEvents", [])), entry["result"])) + " |")
        lines += ["", "## Observed tradeoffs", "", json.dumps(self.report["observations"], indent=2),
                  "", "## Structural failures", "", json.dumps(self.report["failures"], indent=2), "",
                  "Lock-screen captures use sleep/wake without keyguard dismissal. Policy is observed, not forced; no AOD claim.",
                  "Human screenshot review is required for icons, chips, heads-up peeks, shade sections and lock-screen content.",
                  "The cache step deliberately observes the existing quota lifecycle. Restoring a dismissed promoted notification conflicts with Live Update guidance; it is a measured cost, not a recommended behavior."]
        self.save("summary.md", "\n".join(lines))

    def home(self):
        self.adb("shell", "cmd", "statusbar", "collapse")
        self.adb("shell", "input", "keyevent", "KEYCODE_HOME")

    def early_frames(self, name, ready, start, timing):
        samples = []
        try:
            self.screen(name + "-early-0000ms-home", crop=True)
            ready.set()
            if not start.wait(20):
                raise RuntimeError("Posting command did not start during early observer")
            for offset, label in ((0.25, "0250ms"), (0.5, "0500ms"), (1.0, "1000ms"), (2.0, "2000ms")):
                time.sleep(max(0, timing["commandStarted"] + offset - time.monotonic()))
                sample = {"requestedSeconds": offset, "actualStartSeconds": time.monotonic() - timing["commandStarted"]}
                self.screen(name + "-early-" + label + "-home", crop=True)
                sample["actualEndSeconds"] = time.monotonic() - timing["commandStarted"]
                samples.append(sample)
            for suffix, command in (
                ("window", ("shell", "dumpsys", "window")),
                ("systemui", ("shell", "dumpsys", "activity", "service", "com.android.systemui/.SystemUIService")),
                ("logcat", ("shell", "logcat", "-d", "-v", "threadtime")),
                ("events", ("shell", "logcat", "-b", "events", "-d", "-v", "threadtime")),
            ):
                self.save(name + "-early-" + suffix + ".txt", self.adb(*command, timeout=8))
        except (AssertionError, RuntimeError, OSError, subprocess.SubprocessError) as error:
            timing["observerError"] = str(error)
        finally:
            ready.set()
            timing["samples"] = samples
            self.save(name + "-early-timing.json", json.dumps(timing, indent=2))

    def transition(self, command, name, account=None, early=False):
        self.home()
        self.adb("shell", "logcat", "-b", "all", "-c")
        if not early:
            return self.command(command, account)
        ready, start, timing = threading.Event(), threading.Event(), {}
        observer = threading.Thread(target=self.early_frames, args=(name, ready, start, timing))
        observer.start()
        try:
            assert ready.wait(15), "Early observer did not establish pre-command HOME frame"
            timing["commandStarted"] = time.monotonic()
            start.set()
            return self.command(command, account)
        finally:
            start.set()
            observer.join(120)
            assert not observer.is_alive(), "Early observer exceeded bounded collection window"
            if timing.get("observerError"):
                self.report["failures"].append(name + ": early observer: " + timing["observerError"])
            self.report["observations"].append({"step": name, "earlyTiming": timing})

    def capture(self, name, expected, allow_dismiss_difference=False):
        self.report["lastAssertion"] = name
        entry = {"name": name, "expectedAccounts": expected, "driver": self.state, "result": "incomplete"}
        self.report["scenarios"].append(entry)
        try:
            self.home()
            self.settled()
            self.screen(name + "-home", crop=True)
            self.xml(name + "-home")
            raw = {}
            for suffix, command in (
                ("notification", ("shell", "dumpsys", "notification", "--noredact")),
                ("window", ("shell", "dumpsys", "window")),
                ("statusbar", ("shell", "dumpsys", "statusbar")),
                ("systemui", ("shell", "dumpsys", "activity", "service", "com.android.systemui/.SystemUIService")),
                ("logcat", ("shell", "logcat", "-d", "-v", "threadtime")),
                ("events", ("shell", "logcat", "-b", "events", "-d", "-v", "threadtime")),
            ):
                raw[suffix] = self.adb(*command)
                self.save(name + "-" + suffix + ".txt", raw[suffix])
            self.adb("shell", "cmd", "statusbar", "expand-notifications")
            time.sleep(2)
            self.screen(name + "-shade")
            self.xml(name + "-shade")
            records = parse_observations(raw["notification"])
            entry["observedRecords"] = records
            local_ids = [self.state["localIds"][account] for account in expected]
            entry.update(observe_snapshot(records, local_ids, self.state.get("channel", CHANNELS[self.recipe]),
                                          self.grouped, raw["events"], (self.uid,)))
            observed = self.accounts_in(records)
            if allow_dismiss_difference and set(observed) ^ set(expected) == {ACCOUNTS[0]}:
                entry["dismissalDifference"] = {"expected": expected, "observed": observed}
                self.report["observations"].append({"step": name, "dismissalDifference": entry["dismissalDifference"]})
                # A retained promoted row is a tradeoff, not a malformed account group.
                actual_local = [self.state["localIds"][account] for account in observed]
                entry.update(observe_snapshot(records, actual_local, self.state.get("channel", CHANNELS[self.recipe]),
                                              self.grouped, raw["events"], (self.uid,)))
            entry["systemuiSections"] = [line for line in raw["systemui"].splitlines()
                                          if re.search(r"section|heads.?up|promot|chip", line, re.I)]
            entry["result"] = "failed" if entry["violations"] else "passed"
            self.report["failures"].extend(name + ": " + failure for failure in entry["violations"])
            self.save(name + "-ranking.json", json.dumps(records, indent=2))
            self.save(name + "-ranking.txt", "\n".join(
                f"{record['key']} rankingTimeMs={record['rankingTimeMs']} when={record['when']} globalSortKey={record['globalSortKey']}" for record in records))
            self.save(name + "-alerts.json", json.dumps(entry["alertEvents"], indent=2))
            return records
        except (AssertionError, RuntimeError, OSError, subprocess.SubprocessError, ET.ParseError, ValueError, KeyError) as error:
            entry.update(result="failed", error=str(error))
            self.report["failures"].append(name + ": " + str(error))
            self.diagnostics(name + "-failure")
            return []
        finally:
            self.adb("shell", "cmd", "statusbar", "collapse")
            self.summary()

    def accounts_in(self, records):
        tags = {record.get("tag") for record in records if record["package"] == PACKAGE and record["id"] in (2001, 2002)}
        return [account for account, local in self.state["localIds"].items() if "status-icon:" + local in tags]

    def lockscreen(self):
        name = "01-posted-lockscreen"
        try:
            self.home()
            self.adb("shell", "input", "keyevent", "KEYCODE_SLEEP")
            time.sleep(0.5)
            self.adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
            time.sleep(1)
            self.screen(name)
            self.xml(name)
            policy = {}
            for suffix, command in (
                ("window", ("shell", "dumpsys", "window")),
                ("window-policy", ("shell", "dumpsys", "window", "policy")),
                ("trust", ("shell", "dumpsys", "trust")),
                ("power", ("shell", "dumpsys", "power")),
                ("systemui", ("shell", "dumpsys", "activity", "service", "com.android.systemui/.SystemUIService")),
                ("notification", ("shell", "dumpsys", "notification", "--noredact")),
            ):
                policy[suffix] = self.adb(*command)
                self.save(name + "-" + suffix + ".txt", policy[suffix])
            combined = "\n".join(policy.values())
            showing = re.findall(r"\b(?:mKeyguardShowing|isKeyguardShowing|keyguardShowing)=(true|false)\b", policy["window-policy"])
            self.report["lockscreen"] = {
                "keyguardShowingObserved": (showing[0] == "true") if showing and len(set(showing)) == 1 else None,
                "keyguardShowingValues": showing,
                "policyLines": [line for line in combined.splitlines() if re.search(r"keyguard|secure|credential|deviceLocked|deviceSecure|mShowingLockscreen|isStatusBarKeyguard", line, re.I)],
                "interpretation": "Sleep/wake without dismissal; if keyguard showing is not verified, this is wake policy evidence, not a verified locked-content capture. No AOD claim.",
            }
            self.save(name + "-policy.json", json.dumps(self.report["lockscreen"], indent=2))
        finally:
            self.adb("shell", "wm", "dismiss-keyguard")
            self.home()

    def lifecycle(self, pin_count):
        self.command("seed")
        assert not self.state["enabled"] and not self.state["pins"] and self.state["accounts"] == ACCOUNTS, "Seed did not reset OFF/pins/order"
        self.command("recipe", self.recipe)
        assert self.state["recipe"] == self.recipe and self.state["grouped"] == self.grouped, "Recipe completion mismatch"
        pins = PIN_ORDER if pin_count == 3 else ACCOUNTS[:1]
        for account in pins:
            self.command("pin", account)
        self.transition("enable", "01-posted", early=True)
        full = self.capture("01-posted", pins)
        self.lockscreen()
        self.transition("update", "02-updated", early=True)
        self.capture("02-updated", pins)
        self.transition("reorder", "03-reordered", early=True)
        full = self.capture("03-reordered", pins)
        remaining = pins
        try:
            self.adb("shell", "logcat", "-b", "all", "-c")
            self.swipe_claude(full)
            self.report["dismissalEvidence"] = {"result": "gesture-performed"}
            expected = pins if self.report["api"] == 30 else [account for account in pins if account != ACCOUNTS[0]]
            records = self.capture("04-postswipe", expected, allow_dismiss_difference=True)
            remaining = self.accounts_in(records)
        except (AssertionError, RuntimeError, OSError, subprocess.SubprocessError, ET.ParseError) as error:
            self.report["dismissalEvidence"] = {"result": "unavailable", "reason": str(error)}
            self.report["failures"].append("04-postswipe: swipe unexercised: " + str(error))
            self.report["observations"].append({"swipeUnavailable": str(error)})
            records = self.capture("04-postswipe", pins, allow_dismiss_difference=True)
            remaining = self.accounts_in(records)
        self.transition("presentation", "05-presentation")
        self.capture("05-presentation", remaining)
        if pin_count == 3:
            self.transition("unpin", "10-unpin-gemini", ACCOUNTS[2])
            remaining = [account for account in remaining if account != ACCOUNTS[2]]
            self.capture("10-unpin-gemini", remaining)
            self.transition("pin", "11-pin-xai", ACCOUNTS[3])
            remaining = [*remaining, ACCOUNTS[3]]
            self.capture("11-pin-xai", remaining)
            restored, removed = [ACCOUNTS[0], ACCOUNTS[1], ACCOUNTS[3]], ACCOUNTS[3]
        else:
            self.transition("pin", "06-otherpin", ACCOUNTS[1])
            remaining = [*remaining, ACCOUNTS[1]]
            restored, removed = ACCOUNTS[:2], ACCOUNTS[1]
        self.capture("06-otherpin", remaining)
        self.transition("cache", "07-cache-restored")
        self.capture("07-cache-restored", restored)
        self.transition("unpin", "08-unpinfull", removed)
        self.capture("08-unpinfull", [account for account in restored if account != removed])


def default_policy(evidence, name):
    notification = evidence.adb("shell", "dumpsys", "notification", "--noredact")
    systemui = evidence.adb("shell", "dumpsys", "activity", "service", "com.android.systemui/.SystemUIService")
    evidence.save(name + "-notification.txt", notification)
    evidence.save(name + "-systemui.txt", systemui)
    evidence.save(name + "-global-settings.txt", evidence.adb("shell", "settings", "list", "global"))
    return {"hideSilentStatusBar": re.findall(r"\bhideSilentStatusBar=(true|false)\b", notification),
            "showSilentStatusIcons": re.findall(r"\bshowSilentStatusIcons=(true|false)\b", systemui)}


def write_summary(evidence, report):
    evidence.save("summary.json", json.dumps(report, indent=2))
    lines = ["# Prototype visibility evidence", "", "Result: " + report["result"], "",
             "No silent-icon preference, promotion permission, channel preference or device flag is changed.",
             "Records/promoted flags are not visible icon or chip counts. Ranking is observed, not asserted.",
             "Promoted quota candidates are plumbing controls, not semantically appropriate Live Updates.", "",
             "| Candidate | Result | Detail |", "|---|---|---|"]
    for candidate in report["candidates"]:
        lines.append("| " + " | ".join(str(candidate.get(key, "")).replace("|", "\\|")
                                       for key in ("name", "result", "detail")) + " |")
    lines += ["", "## Untouched default before / after", "", json.dumps(report.get("defaultBefore"), indent=2),
              "", json.dumps(report.get("defaultAfter"), indent=2), "",
              "## Interpretation limits", "",
              "Inspect HOME/statusbar, shade and sleep/wake screenshots for actual presentation. Early observer frames and SystemUI/window logs cover transient heads-up peeks; finite samples cannot prove no transient ever occurred.",
              "notification_alert buzz/beep/blink and NMS sound/vibration/light fields are observations, not a claim about heard sound on a no-audio emulator.",
              "Keyguard showing, secure/credential state and content policy are retained in lock-screen window/policy/trust dumps; a swipe-only AVD may wake unlocked. No AOD or forced private-content claim.",
              "Grouped core violations fail the run, but candidates continue. Retained promoted dismissals and ungrouped auto-group policy are reported as tradeoffs.",
              "Fresh-channel experiments do not prove an existing installation can raise a channel's importance."]
    if report.get("error"):
        lines += ["", "Failure: " + report["error"]]
    evidence.save("summary.md", "\n".join(lines))


def run(evidence, apk, report):
    api = int(evidence.adb("shell", "getprop", "ro.build.version.sdk"))
    assert api in (30, 36, 37), "Visibility evidence requires API30/36/37"
    report["api"] = api
    report["defaultBefore"] = default_policy(evidence, "defaults-before")
    evidence.adb("shell", "svc", "power", "stayon", "true")
    for name, command in (
        ("device", ("shell", "getprop")),
        ("fingerprint", ("shell", "getprop", "ro.build.fingerprint")),
        ("notification-help", ("shell", "cmd", "notification", "help")),
        ("device-flags", ("shell", "cmd", "aflags", "list")),
    ):
        try:
            evidence.save(name + ".txt", evidence.adb(*command))
        except (OSError, subprocess.SubprocessError) as error:
            evidence.save(name + "-unavailable.txt", str(error))
    evidence.save("install.txt", evidence.adb("install", "-r", str(apk), timeout=180))
    report["installed"] = True
    if api >= 33:
        evidence.adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
    package_dump = evidence.adb("shell", "dumpsys", "package", PACKAGE)
    evidence.save("package-permissions.txt", package_dump)
    evidence.save("appops.txt", evidence.adb("shell", "cmd", "appops", "get", PACKAGE))
    uid = re.search(r"\buserId=(\d+)", package_dump)
    assert uid, "Missing installed fixture UID"
    candidates = CANDIDATES + (LEGACY_CONTROLS if api == 36 else [])
    local_ids = None
    for name, recipe, pin_count in candidates:
        item = {"name": name, "recipe": recipe, "pinCount": pin_count, "result": "incomplete"}
        report["candidates"].append(item)
        candidate = VisibilityEvidence(evidence.directory / name, recipe, api, uid[1])
        if api == 30 and "promoted" in recipe:
            item.update(result="unsupported", detail="API30 has no native promoted ongoing API")
            candidate.report.update(result="unsupported", reason=item["detail"])
            candidate.summary()
            write_summary(evidence, report)
            continue
        try:
            candidate.lifecycle(pin_count)
            if local_ids is not None:
                assert candidate.state["localIds"] == local_ids, "Fixture local IDs changed between candidates"
            local_ids = candidate.state["localIds"]
            candidate.report["result"] = "failed" if candidate.report["failures"] else "passed"
        except (AssertionError, RuntimeError, OSError, subprocess.SubprocessError, ET.ParseError, ValueError, KeyError) as error:
            candidate.report.update(result="failed", error=str(error))
            candidate.report["failures"].append(str(error))
            candidate.diagnostics("failure")
        finally:
            candidate.summary()
            item.update(result=candidate.report["result"], detail=str(len(candidate.report["failures"])) + " structural/capture failures; see " + name + "/summary.md")
            write_summary(evidence, report)
    report["result"] = "failed" if any(item["result"] == "failed" for item in report["candidates"]) else "passed"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apkpath", "--apk", dest="apk", type=Path, required=True)
    parser.add_argument("--evidenceDIR", "--evidence", dest="evidence", type=Path, required=True)
    args = parser.parse_args()
    evidence = Evidence(args.evidence)
    report = {"result": "incomplete", "installed": False, "candidates": []}
    try:
        evidence.preflight()
        report["preflight"] = evidence.report["preflight"]
        run(evidence, args.apk, report)
    except (AssertionError, RuntimeError, OSError, subprocess.SubprocessError, ET.ParseError, ValueError, KeyError) as error:
        report.update(result="failed", error=str(error))
        evidence.ready_deadline = None
        evidence.diagnostics("failure")
    finally:
        if "defaultBefore" in report:
            try:
                report["defaultAfter"] = default_policy(evidence, "defaults-after")
                before, after = report["defaultBefore"], report["defaultAfter"]
                for field in before:
                    assert before[field] == after[field], "Default flag changed: " + field
                if report["api"] >= 36:
                    assert len(before["hideSilentStatusBar"]) == 1, "Missing/ambiguous default hideSilentStatusBar"
                report["defaultFlagsUnchanged"] = True
                report["defaultFlagsUnsupported"] = [field for field, values in before.items() if not values]
            except (AssertionError, OSError, subprocess.SubprocessError) as error:
                report.update(result="failed", error=str(error), defaultFlagsUnchanged=False)
        write_summary(evidence, report)
    print(f"Prototype visibility evidence: {report['result']}; {args.evidence / 'summary.md'}")
    return 0 if report["result"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
