"""Device-evidence parser/assertion seam; no emulator or APK needed."""
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

from status_icon_evidence import (
    Evidence, SystemDialog, SystemUIBootANR,
    assert_snapshot, parse_notifications, swipe_accounts, systemui_anr,
)


# Shape of NotificationRecord.dump on Android 11/16, including extras and ranking.
PAIR = """    NotificationRecord(0x123: pkg=com.usagelimits.debug user=UserHandle{0} id=2001 tag=status-icon:uuid-claude importance=2 key=0|com.usagelimits.debug|2001|status-icon:uuid-claude|10123: Notification(channel=status_icons shortcut=null contentView=null vibrate=null sound=null defaults=0x0 flags=0x30a color=0x00000000 group=status-icon:uuid-claude vis=PRIVATE))
      uid=10123 userId=0
      key=0|com.usagelimits.debug|2001|status-icon:uuid-claude|10123
      groupKey=0|com.usagelimits.debug|g:status-icon:uuid-claude
      overrideGroupKey=null
      android.title=String (Claude)
      mRankingTimeMs=1794880000002
      when=1794880000001
      globalSortKey=intrsv=0:grnk=0x0000:gsmry=0:gsk=nshd:rnk=0x0000
    NotificationRecord(0x124: pkg=com.usagelimits.debug user=UserHandle{0} id=2002 tag=status-icon:uuid-claude importance=2 key=0|com.usagelimits.debug|2002|status-icon:uuid-claude|10123: Notification(channel=status_icons shortcut=null contentView=null vibrate=null sound=null defaults=0x0 flags=0x10a color=0x00000000 group=status-icon:uuid-claude vis=PRIVATE))
      uid=10123 userId=0
      key=0|com.usagelimits.debug|2002|status-icon:uuid-claude|10123
      groupKey=0|com.usagelimits.debug|g:status-icon:uuid-claude
      overrideGroupKey=null
      android.title=String (Claude)
      mRankingTimeMs=1794880000003
      when=1794880000002
      globalSortKey=intrsv=0:grnk=0x0000:gsmry=1:gsk=nshd:rnk=0x0001
"""
SYSTEM = """    NotificationRecord(0x125: pkg=android user=UserHandle{0} id=26 tag=null importance=2 key=0|android|26|null|1000: Notification(channel=DEVELOPER flags=0x2))
      uid=1000 userId=0
"""
ALERT = """    NotificationRecord(0x126: pkg=com.usagelimits.debug user=UserHandle{0} id=1001 tag=null importance=3 key=0|com.usagelimits.debug|1001|null|10123: Notification(channel=usage_alerts flags=0x10))
      uid=10123 userId=0
"""


def dump(body=PAIR):
    return "Current Notification Manager state:\n  Notification List:\n" + body + "\n  Snoozed notifications:\n" + PAIR


class NotificationEvidenceTest(unittest.TestCase):
    def test_counts_only_active_records_and_preserves_ranking_observations(self):
        records = parse_notifications(dump(PAIR + SYSTEM))
        report = assert_snapshot(records, ["uuid-claude"])
        self.assertEqual((2, 0, 1), (report["statusCount"], report["alertCount"], report["systemCount"]))
        self.assertEqual("1794880000002", records[0]["rankingTimeMs"])
        self.assertEqual("1794880000001", records[0]["when"])
        self.assertEqual("Claude", records[0]["title"])
        self.assertIn("grnk=0x0000", records[0]["globalSortKey"])

    def test_header_identity_is_used_when_dump_has_no_separate_key_line(self):
        text = "\n".join(line for line in PAIR.splitlines() if not line.strip().startswith("key="))
        report = assert_snapshot(parse_notifications(dump(text)), ["uuid-claude"])
        self.assertEqual("0|com.usagelimits.debug|2001|status-icon:uuid-claude|10123", report["ranking"][0]["key"])

    def test_android16_symbolic_flags_and_prefixed_sort_key_are_preserved(self):
        modern = PAIR.replace("flags=0x30a", "flags=ONGOING_EVENT|ONLY_ALERT_ONCE|GROUP_SUMMARY|LOCAL_ONLY").replace("flags=0x10a", "flags=ONGOING_EVENT|ONLY_ALERT_ONCE|LOCAL_ONLY").replace("globalSortKey=", "mGlobalSortKey=").replace(" group=status-icon:", " groupKey=status-icon:")
        report = assert_snapshot(parse_notifications(dump(modern)), ["uuid-claude"])
        self.assertEqual(2, report["statusCount"])
        self.assertIn("grnk=0x0000", report["ranking"][0]["globalSortKey"])

    def test_missing_ranking_is_not_silently_accepted(self):
        with self.assertRaisesRegex(AssertionError, "ranking"):
            assert_snapshot(parse_notifications(dump(PAIR.replace("mRankingTimeMs=", "missing="))), ["uuid-claude"])

    def test_missing_child_fails_record_count(self):
        one = PAIR.split("    NotificationRecord(0x124:")[0]
        with self.assertRaisesRegex(AssertionError, "status records"):
            assert_snapshot(parse_notifications(dump(one)), ["uuid-claude"])

    def test_system_auto_group_override_fails_even_when_count_matches(self):
        for override in ("Aggregate_status_icons", "ranker_group"):
            with self.subTest(override=override), self.assertRaisesRegex(AssertionError, "auto.group"):
                assert_snapshot(parse_notifications(dump(PAIR.replace("overrideGroupKey=null", "overrideGroupKey=" + override))), ["uuid-claude"])

    def test_non_null_override_is_rejected_without_guessing_its_meaning(self):
        with self.assertRaisesRegex(AssertionError, "override"):
            assert_snapshot(parse_notifications(dump(PAIR.replace("overrideGroupKey=null", "overrideGroupKey=unexpected"))), ["uuid-claude"])

    def test_two_children_do_not_count_as_a_valid_account_group(self):
        with self.assertRaisesRegex(AssertionError, "summary"):
            assert_snapshot(parse_notifications(dump(PAIR.replace("flags=0x30a", "flags=0x10a"))), ["uuid-claude"])

    def test_malformed_group_fails_even_with_expected_tag_and_count(self):
        malformed = PAIR.replace("group=status-icon:uuid-claude", "group=wrong")
        with self.assertRaisesRegex(AssertionError, "group"):
            assert_snapshot(parse_notifications(dump(malformed)), ["uuid-claude"])

    def test_alert_record_leak_is_not_counted_as_a_status_record(self):
        with self.assertRaisesRegex(AssertionError, "alert records"):
            assert_snapshot(parse_notifications(dump(PAIR + ALERT)), ["uuid-claude"])

    def test_update_preserves_keys_and_rejects_new_app_alert_events(self):
        baseline = parse_notifications(dump())
        updated = parse_notifications(dump(PAIR.replace("1794880000002", "1794880000087")))
        assert_snapshot(updated, ["uuid-claude"], baseline=baseline)
        for events in (
            "11-17 12:00:00.000 1000 1000 I notification_alert: [10123,com.usagelimits.debug,2001,status-icon:uuid-claude,1,0,0]",
            "11-17 12:00:00.000 1000 1000 I notification_alert: [10123,0,1,0]",
        ):
            with self.subTest(events=events), self.assertRaisesRegex(AssertionError, "notification_alert"):
                assert_snapshot(updated, ["uuid-claude"], baseline=baseline, events=events)
        with self.assertRaisesRegex(AssertionError, "keys"):
            assert_snapshot(parse_notifications(dump(PAIR.replace("|10123", "|10124"))), ["uuid-claude"], baseline=baseline)

    def test_reorder_records_observations_without_asserting_sort_order(self):
        codex = PAIR.replace("uuid-claude", "uuid-codex").replace("Claude", "Codex")
        before = parse_notifications(dump(PAIR + codex))
        after = parse_notifications(dump(codex + PAIR))
        report = assert_snapshot(after, ["uuid-claude", "uuid-codex"], baseline=before)
        self.assertEqual(["uuid-codex", "uuid-claude"], report["accountDumpOrder"])

    def test_unknown_dump_shape_fails_instead_of_passing_empty_state(self):
        with self.assertRaisesRegex(AssertionError, "Notification List"):
            parse_notifications("adb transport closed")

    def test_api30_attempt_keeps_ongoing_group_but_api36_and37_dismiss_it(self):
        self.assertEqual(["evidence-claude", "evidence-codex", "evidence-gemini"], swipe_accounts(30))
        for api in (36, 37):
            self.assertEqual(["evidence-codex", "evidence-gemini"], swipe_accounts(api))


class PreflightEvidenceTest(unittest.TestCase):
    def test_clean_lastanr_requires_exact_dialog_and_dropbox_process_proof(self):
        # Captured issue66 startup ANR: lastanr stays clean while DropBox retains it.
        clean = "ACTIVITY MANAGER LAST ANR (dumpsys activity lastanr)\n  <no ANR has occurred since boot>"
        xml = '<hierarchy><node package="android" resource-id="android:id/alertTitle" text="System UI isn\'t responding"/><node resource-id="android:id/aerr_wait" text="Wait"/></hierarchy>'
        window = "mCurrentFocus=Window{7a8589f u0 Application Not Responding: com.android.systemui}"
        dropbox = """Drop box contents: 56 entries
Searching for: system_app_anr
========================================
2026-11-17 09:30:04 system_app_anr (compressed text, 10725 bytes)
SystemUptimeMs: 47623
Process: com.android.systemui
PID: 1056
Subject: Process ProcessRecord{557c576 1056:com.android.systemui/u0a124} failed to complete startup
"""
        self.assertTrue(systemui_anr(xml, clean, dropbox=dropbox))
        self.assertTrue(systemui_anr("not xml", clean, window, dropbox=dropbox))
        for proof in ("", dropbox.replace("com.android.systemui", "com.usagelimits.debug"),
                      dropbox.replace("com.android.systemui", "com.android.systemui.other")):
            with self.subTest(proof=proof):
                self.assertFalse(systemui_anr(xml, clean, window, dropbox=proof))
        self.assertFalse(systemui_anr("<hierarchy/>", clean, dropbox=dropbox))
        self.assertFalse(systemui_anr(xml.replace("System UI", "Launcher"), clean, dropbox=dropbox))
        self.assertFalse(systemui_anr("not xml", clean, window.replace("com.android.systemui", "com.android.systemui.other"), dropbox=dropbox))

    def test_preflight_retains_dropbox_before_rejecting_clean_lastanr_dialog(self):
        clean = "<no ANR has occurred since boot>"
        dialog = '<hierarchy><node resource-id="android:id/alertTitle" text="System UI isn\'t responding"/></hierarchy>'
        window = "mCurrentFocus=Window{7a8589f u0 Application Not Responding: com.android.systemui}"
        for window_dump, xml in ((window, "<hierarchy/>"), ("", dialog)):
            for process, expected in (("com.android.systemui", SystemUIBootANR),
                                      ("com.usagelimits.debug", SystemDialog), ("", SystemDialog)):
                with self.subTest(window=window_dump, process=process), tempfile.TemporaryDirectory() as directory:
                    evidence = Evidence(directory)
                    dropbox = "Process: " + process
                    outputs = {
                        ("shell", "dumpsys", "activity", "lastanr"): clean,
                        ("shell", "dumpsys", "window"): window_dump,
                        ("shell", "dumpsys", "dropbox", "--print", "system_app_anr"): dropbox,
                    }
                    with patch.object(evidence, "adb", side_effect=lambda *args, **kwargs: outputs.get(args, "com.android.launcher3/.Launcher")), \
                            patch.object(evidence, "xml", return_value=ET.fromstring(xml)), \
                            self.assertRaises(expected):
                        evidence.preflight()
                    self.assertEqual(dropbox + "\n", (evidence.directory / "preflight-system-app-anr.txt").read_text())
                    self.assertFalse(evidence.report["installed"])

    def test_final_home_observation_rejects_new_dialog_even_when_lastanr_is_clean(self):
        clean = "<no ANR has occurred since boot>"
        home_window = "mCurrentFocus=Window{ab12 u0 com.android.launcher3/.Launcher}"
        dialog_window = "mCurrentFocus=Window{7a8589f u0 Application Not Responding: com.android.systemui}"
        root = ET.fromstring('<hierarchy><node package="com.android.launcher3" resource-id="com.android.launcher3:id/workspace" enabled="true" bounds="[0,0][720,1560]"/></hierarchy>')
        with tempfile.TemporaryDirectory() as directory:
            evidence = Evidence(directory)
            windows = iter((home_window, dialog_window))

            def adb(*args, **kwargs):
                if args == ("shell", "dumpsys", "window"):
                    return next(windows)
                if args == ("shell", "dumpsys", "activity", "lastanr"):
                    return clean
                if args == ("shell", "dumpsys", "dropbox", "--print", "system_app_anr"):
                    return "Process: com.android.systemui"
                return "com.android.launcher3/.Launcher"

            with patch.object(evidence, "adb", side_effect=adb), patch.object(evidence, "xml", return_value=root), \
                    patch.object(evidence, "screen"), self.assertRaises(SystemUIBootANR):
                evidence.preflight()
            self.assertNotIn("preflight", evidence.report)

    def test_retry_requires_exact_systemui_dialog_and_lastanr_process_proof(self):
        xml = '<hierarchy><node resource-id="android:id/aerr_message" text="System UI isn\'t responding"/></hierarchy>'
        anr = "ACTIVITY MANAGER LAST ANR (dumpsys activity lastanr)\n  ANR in com.android.systemui\n  Reason: Input dispatching timed out\n"
        self.assertTrue(systemui_anr(xml, anr))
        self.assertTrue(systemui_anr(xml.replace("android:id/aerr_message", "android:id/alertTitle"), anr))
        self.assertFalse(systemui_anr(xml, anr.replace("com.android.systemui", "com.usagelimits.debug")))
        self.assertFalse(systemui_anr(xml.replace("System UI", "Launcher"), anr))
        self.assertFalse(systemui_anr(xml.replace("isn't", "keeps"), anr))
        self.assertFalse(systemui_anr("not xml", anr))
        window = "mCurrentFocus=Window{ab12 u0 Application Not Responding: com.android.systemui}"
        self.assertTrue(systemui_anr("not xml", anr, window))
        self.assertFalse(systemui_anr("not xml", anr, window.replace("com.android.systemui", "com.android.launcher3")))


if __name__ == "__main__":
    unittest.main()
