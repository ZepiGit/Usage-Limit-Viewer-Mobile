"""Prototype visibility observation seam; fixtures need no device or APK."""
import tempfile
import unittest
from unittest.mock import patch

import prototype_visibility_capture as capture
from prototype_visibility_capture import ACCOUNTS, PACKAGE, VisibilityEvidence, observe_snapshot, parse_observations


PAIR = """    NotificationRecord(0x123: pkg=com.usagelimits.debug user=UserHandle{0} id=2001 tag=status-icon:uuid-claude importance=3 key=0|com.usagelimits.debug|2001|status-icon:uuid-claude|10123: Notification(channel=prototype_status_promoted flags=0x30a group=status-icon:uuid-claude vis=PRIVATE))
      uid=10123 userId=0
      key=0|com.usagelimits.debug|2001|status-icon:uuid-claude|10123
      groupKey=0|com.usagelimits.debug|g:status-icon:uuid-claude
      overrideGroupKey=null
      android.title=String (Claude)
      mRankingTimeMs=1794880000002
      when=1794880000001
      globalSortKey=intrsv=0:grnk=0x0000:gsmry=0:rnk=0x0000
      mSound=null
      mVibration=null
      mLight=null
      mIsInterruptive=false
      mSuppressedVisualEffects=0
    NotificationRecord(0x124: pkg=com.usagelimits.debug user=UserHandle{0} id=2002 tag=status-icon:uuid-claude importance=3 key=0|com.usagelimits.debug|2002|status-icon:uuid-claude|10123: Notification(channel=prototype_status_promoted flags=0x4010a group=status-icon:uuid-claude vis=PRIVATE))
      uid=10123 userId=0
      key=0|com.usagelimits.debug|2002|status-icon:uuid-claude|10123
      groupKey=0|com.usagelimits.debug|g:status-icon:uuid-claude
      overrideGroupKey=null
      android.title=String (Claude)
      android.requestPromotedOngoing=Boolean (true)
      android.shortCriticalText=String (42%)
      android.template=String (android.app.Notification$ProgressStyle)
      mPromoted=true
      mRankingTimeMs=1794880000003
      when=1794880000002
      globalSortKey=intrsv=0:grnk=0x0000:gsmry=1:rnk=0x0001
"""


def dump(body=PAIR):
    return "Current Notification Manager state:\n  Notification List:\n" + body + "\n  Snoozed notifications:\n" + PAIR


class VisibilityObservationTest(unittest.TestCase):
    def test_grouped_pair_keeps_promotion_and_alert_observations_without_rejecting_them(self):
        records = parse_observations(dump())
        report = observe_snapshot(records, ["uuid-claude"], "prototype_status_promoted", True,
                                  "I notification_alert: [10123,1,0,1]\nI notification_alert: [99999,0,1,0]")
        self.assertEqual([], report["violations"])
        self.assertEqual((2, 1), (report["statusCount"], report["promotedCount"]))
        self.assertEqual(3, records[0]["importance"])
        self.assertEqual("null", records[0]["mSound"])
        self.assertEqual("false", records[0]["mIsInterruptive"])
        self.assertEqual("true", records[1]["requestPromotedOngoing"])
        self.assertEqual("42%", records[1]["shortCriticalText"])
        self.assertEqual("android.app.Notification$ProgressStyle", records[1]["style"])
        self.assertEqual([{"uid": "10123", "sound": 0, "vibrate": 1, "blink": 1,
                           "raw": "I notification_alert: [10123,1,0,1]"}], report["alertEvents"])

    def test_api37_keyed_alert_tuple_keeps_sound_vibration_and_policy_fields(self):
        records = parse_observations(dump())
        events = ("I notification_alert: [0|com.usagelimits.debug|2002|status-icon:uuid-claude|10123,0,1,0,2,0]\n"
                  "I notification_alert: [0|another.app|1|null|10999,1,1,1,0,0]")
        observed = observe_snapshot(records, ["uuid-claude"], "prototype_status_promoted", True, events)
        self.assertEqual([], observed["violations"])
        self.assertEqual(1, len(observed["alertEvents"]))
        self.assertEqual((1, 0, 0, "2", "0"), tuple(observed["alertEvents"][0][key]
                         for key in ("sound", "vibrate", "blink", "politeness", "muteReason")))

    def test_symbolic_flags_and_stripped_promotion_are_not_false_failures(self):
        text = PAIR.replace("flags=0x30a", "flags=ONGOING_EVENT|GROUP_SUMMARY")
        text = text.replace("flags=0x4010a", "flags=ONGOING_EVENT|PROMOTED_ONGOING")
        records = parse_observations(dump(text))
        self.assertTrue(records[1]["promoted"])
        self.assertIsNone(records[1]["numericFlags"])
        records[1]["promoted"] = False
        self.assertEqual([], observe_snapshot(records, ["uuid-claude"], "prototype_status_promoted", True)["violations"])

    def test_raw_blocks_exclude_archive_and_group_count_failures_remain_visible(self):
        records = parse_observations(dump())
        self.assertNotIn("Snoozed notifications", records[-1]["rawBlock"])
        report = observe_snapshot(records[:1], ["uuid-claude"], "prototype_status_promoted", True)
        self.assertIn("Unexpected original status record count", report["violations"])
        self.assertIn("Malformed account IDs: status-icon:uuid-claude", report["violations"])

    def test_ungrouped_platform_aggregate_is_observed_but_grouped_override_fails(self):
        records = parse_observations(dump())
        records[1]["group"] = None
        records[1]["overrideGroupKey"] = "Aggregate_status_icons"
        aggregate = dict(records[0], id=1000, tag="ranker_group", key="aggregate-key")
        report = observe_snapshot([records[1], aggregate], ["uuid-claude"], "prototype_status_promoted", False)
        self.assertEqual((2, 1), (report["statusCount"], report["originalCount"]))
        self.assertEqual([], report["violations"])
        self.assertTrue(report["autoGroups"])
        grouped = observe_snapshot(records, ["uuid-claude"], "prototype_status_promoted", True)
        self.assertIn("Forbidden grouped auto-group", grouped["violations"])

    def test_usage_alert_leak_is_always_a_violation_even_when_status_is_empty(self):
        alert = dict(parse_observations(dump())[0], channel="usage_alerts")
        report = observe_snapshot([alert], [], "prototype_status_promoted", False)
        self.assertIn("Unexpected app usage_alerts records", report["violations"])


class InstalledFixtureUidTest(unittest.TestCase):
    def test_package_uid_parser_accepts_standard_api_outputs_as_strings(self):
        for api, output, expected in (
            (30, "package:com.usagelimits.debug uid:10123\n", "10123"),
            (36, "package:com.usagelimits.debug uid:10150\n", "10150"),
            (37, "package:com.usagelimits.debug uid:10156\n", "10156"),
            (37, "package:com.usagelimits.debug uid:1010156\n", "1010156"),
        ):
            with self.subTest(api=api, output=output):
                self.assertEqual(expected, capture.parse_package_uid(output))

    def test_package_uid_parser_requires_one_exact_package_line(self):
        exact = "package:com.usagelimits.debug uid:10156\n"
        self.assertEqual("10156", capture.parse_package_uid(
            "package:com.usagelimits.debug.extra uid:10999\n" + exact))
        for output in ("", "userId=10156\n", "appId=10156\n",
                       "permission uid=1000\n", "package:com.usagelimits.debug.extra uid:10156\n",
                       "package:com.usagelimits.debug uid:not-a-uid\n",
                       exact + exact, exact + "package:com.usagelimits.debug uid:1010156\n"):
            with self.subTest(output=output):
                with self.assertRaisesRegex(AssertionError, "Missing/ambiguous installed fixture UID"):
                    capture.parse_package_uid(output)

    def test_run_uses_package_uid_and_retains_both_raw_package_sources(self):
        class FixtureEvidence(capture.Evidence):
            def adb(self, *args, **kwargs):
                self.commands.append(args)
                if args == ("shell", "getprop", "ro.build.version.sdk"):
                    return str(self.api)
                if args == ("shell", "dumpsys", "package", PACKAGE):
                    return self.package_dump
                if args == ("shell", "cmd", "package", "list", "packages", "-U", PACKAGE):
                    return self.uid_output
                return ""

        class FixtureCandidate(VisibilityEvidence):
            def lifecycle(self, pin_count):
                self.state = {"localIds": {}}
                observed_uids.append(self.uid)
                events = "I notification_alert: [" + self.uid + ",1,0,1]"
                observed = observe_snapshot([], [], capture.CHANNELS[self.recipe], self.grouped, events, (self.uid,))
                observed_events.extend(observed["alertEvents"])

            def summary(self):
                pass

        for api, field, app_id, uid in ((30, "userId", "10123", "10123"),
                                        (36, "appId", "10150", "10150"),
                                        (37, "appId", "10156", "1010156")):
            with self.subTest(api=api), tempfile.TemporaryDirectory() as directory:
                evidence = FixtureEvidence(directory)
                evidence.api = api
                evidence.commands = []
                evidence.package_dump = ("Packages:\n  Package [com.usagelimits.debug]:\n    "
                                         + field + "=" + app_id + "\n    permission uid=1000")
                evidence.uid_output = "package:com.usagelimits.debug uid:" + uid
                observed_uids, observed_events = [], []
                report = {"result": "incomplete", "candidates": []}
                with patch.object(capture, "VisibilityEvidence", FixtureCandidate), \
                        patch.object(capture, "CANDIDATES", [("low", "low", 3)]), \
                        patch.object(capture, "LEGACY_CONTROLS", []):
                    capture.run(evidence, "fixture.apk", report)
                self.assertEqual("passed", report["result"])
                self.assertEqual([uid], observed_uids)
                self.assertEqual([uid], [event["uid"] for event in observed_events])
                self.assertIn(("shell", "cmd", "package", "list", "packages", "-U", PACKAGE),
                              evidence.commands)
                self.assertEqual(evidence.package_dump + "\n",
                                 (evidence.directory / "package-permissions.txt").read_text())
                self.assertEqual(evidence.uid_output + "\n",
                                 (evidence.directory / "package-uids.txt").read_text())


class DismissalLifecycleTest(unittest.TestCase):
    def test_unexercised_swipe_fails_evidence_but_finishes_remaining_lifecycle(self):
        class FixtureEvidence(VisibilityEvidence):
            def command(self, command, account=None):
                if command == "seed":
                    self.state = {"enabled": False, "pins": [], "accounts": ACCOUNTS,
                                  "localIds": {alias: alias for alias in ACCOUNTS}}
                elif command == "recipe":
                    self.state.update(recipe=account, grouped=True)
                return self.state

            def transition(self, command, name, account=None, early=False):
                return self.command(command, account)

            def capture(self, name, expected, allow_dismiss_difference=False):
                self.report["scenarios"].append({"name": name})
                return [{"package": PACKAGE, "tag": "status-icon:" + account, "id": 2002}
                        for account in expected]

            def lockscreen(self):
                return None

            def adb(self, *args, **kwargs):
                return ""

            def swipe_claude(self, records):
                raise AssertionError("Claude shade row unavailable")

        with tempfile.TemporaryDirectory() as directory:
            evidence = FixtureEvidence(directory, "low", 37, "10123")
            evidence.lifecycle(1)
            self.assertEqual({"result": "unavailable", "reason": "Claude shade row unavailable"},
                             evidence.report["dismissalEvidence"])
            self.assertIn("04-postswipe: swipe unexercised: Claude shade row unavailable",
                          evidence.report["failures"])
            self.assertEqual("08-unpinfull", evidence.report["scenarios"][-1]["name"])


if __name__ == "__main__":
    unittest.main()
