"""Device-evidence parser/assertion seam; no emulator or APK needed."""
import json
import tempfile
import unittest
import xml.etree.ElementTree as ET
from contextlib import contextmanager
from pathlib import Path
from unittest.mock import patch

from android_runtime_smoke import png_bytes
from check_status_icon_release import verify
from status_icon_evidence import (
    Evidence, SystemDialog, SystemUIBootANR,
    ACCOUNTS, assert_order_split, assert_snapshot, parse_notifications, posted_order, swipe_accounts, systemui_anr,
)


# Shape of NotificationRecord.dump on Android 11/16, including extras and ranking.
PAIR = """    NotificationRecord(0x123: pkg=com.usagelimits.debug user=UserHandle{0} id=2001 tag=status-icon:uuid-claude importance=3 key=0|com.usagelimits.debug|2001|status-icon:uuid-claude|10123: Notification(channel=status_icons shortcut=null contentView=null vibrate=null sound=null defaults=0x0 flags=0x30a color=0x00000000 group=status-icon:uuid-claude vis=PRIVATE))
      uid=10123 userId=0
      key=0|com.usagelimits.debug|2001|status-icon:uuid-claude|10123
      groupKey=0|com.usagelimits.debug|g:status-icon:uuid-claude
      overrideGroupKey=null
      mImportance=3
      mSound= null
      mVibration= null
      mLight= null
      effectiveNotificationChannel=NotificationChannel{mId='status_icons', mImportance=3, mSound=null, mLights=false, mVibrationEnabled=false, mShowBadge=false}
      android.title=String (Claude)
      mRankingTimeMs=1794880000002
      when=1794880000001
      globalSortKey=intrsv=0:grnk=0x0000:gsmry=0:gsk=nshd:rnk=0x0000
    NotificationRecord(0x124: pkg=com.usagelimits.debug user=UserHandle{0} id=2002 tag=status-icon:uuid-claude importance=3 key=0|com.usagelimits.debug|2002|status-icon:uuid-claude|10123: Notification(channel=status_icons shortcut=null contentView=null vibrate=null sound=null defaults=0x0 flags=0x10a color=0x00000000 group=status-icon:uuid-claude vis=PRIVATE))
      uid=10123 userId=0
      key=0|com.usagelimits.debug|2002|status-icon:uuid-claude|10123
      groupKey=0|com.usagelimits.debug|g:status-icon:uuid-claude
      overrideGroupKey=null
      mImportance=3
      mSound= null
      mVibration= null
      mLight= null
      effectiveNotificationChannel=NotificationChannel{mId='status_icons', mImportance=3, mSound=null, mLights=false, mVibrationEnabled=false, mShowBadge=false}
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
            "11-17 12:00:00.000 1000 1000 I notification_alert: [0|com.usagelimits.debug|2001|status-icon:uuid-claude|10123,1,0,0]",
            "11-17 12:00:00.000 1000 1000 I notification_alert: [0|com.usagelimits.debug|2002|status-icon:uuid-claude|10123,0,1,0,0,0]",
        ):
            with self.subTest(events=events), self.assertRaisesRegex(AssertionError, "notification_alert"):
                assert_snapshot(updated, ["uuid-claude"], baseline=baseline, events=events)
        with self.assertRaisesRegex(AssertionError, "keys"):
            assert_snapshot(parse_notifications(dump(PAIR.replace("|10123", "|10124"))), ["uuid-claude"], baseline=baseline)

    def test_rank_change_records_dump_order_without_asserting_it(self):
        codex = PAIR.replace("uuid-claude", "uuid-codex").replace("Claude", "Codex")
        before = parse_notifications(dump(PAIR + codex))
        after = parse_notifications(dump(codex + PAIR))
        report = assert_snapshot(after, ["uuid-claude", "uuid-codex"], baseline=before)
        self.assertEqual(["uuid-codex", "uuid-claude"], report["accountDumpOrder"])

    def _pins(self, stamps):
        """Three pinned pairs whose summary and child carry the given `when` per alias."""
        body = ""
        for alias, stamp in stamps.items():
            pair = PAIR.replace("uuid-claude", "uuid-" + alias.removeprefix("evidence-"))
            body += pair.replace("when=1794880000001", f"when={stamp}").replace("when=1794880000002", f"when={stamp}")
        return parse_notifications(dump(body))

    def _split_state(self, overview, all_accounts=None, pin_order=None):
        return {"accounts": ACCOUNTS, "pins": [ACCOUNTS[1], ACCOUNTS[2], ACCOUNTS[0]],
                "pinOrder": ACCOUNTS[:3] if pin_order is None else pin_order,
                "overview": overview, "allAccounts": overview if all_accounts is None else all_accounts,
                "localIds": {alias: "uuid-" + alias.removeprefix("evidence-") for alias in ACCOUNTS}}

    def test_order_split_reads_the_posted_order_from_the_apps_times(self):
        records = self._pins({ACCOUNTS[2]: 100, ACCOUNTS[0]: 300, ACCOUNTS[1]: 200})
        self.assertEqual(ACCOUNTS[:3], posted_order(records, self._split_state(ACCOUNTS)["localIds"]))
        with self.assertRaisesRegex(AssertionError, "same time"):
            posted_order(self._pins({ACCOUNTS[0]: 300, ACCOUNTS[1]: 300, ACCOUNTS[2]: 100}), self._split_state(ACCOUNTS)["localIds"])

    def test_order_split_accepts_a_rank_that_differs_from_stored_pins(self):
        ranked = [ACCOUNTS[3], ACCOUNTS[2], ACCOUNTS[1], ACCOUNTS[4], ACCOUNTS[0], ACCOUNTS[5]]
        records = self._pins({ACCOUNTS[0]: 300, ACCOUNTS[1]: 200, ACCOUNTS[2]: 100})
        result = assert_order_split(self._split_state(ranked), records, pins_in=ACCOUNTS[:3])
        self.assertEqual(ACCOUNTS[:3], result["posted"])
        self.assertEqual(ranked, result["overview"])

    def test_order_split_rejects_each_broken_contract(self):
        ranked = [ACCOUNTS[3], ACCOUNTS[2], ACCOUNTS[1], ACCOUNTS[4], ACCOUNTS[0], ACCOUNTS[5]]
        good = self._pins({ACCOUNTS[0]: 300, ACCOUNTS[1]: 200, ACCOUNTS[2]: 100})
        cases = (
            ("All accounts", self._split_state(ranked, all_accounts=ACCOUNTS), good),
            ("cannot tell them apart", self._split_state(ACCOUNTS), good),
            ("Pins left the stored order", self._split_state(ranked, pin_order=[ACCOUNTS[2], ACCOUNTS[0], ACCOUNTS[1]]), good),
            ("Posted icon order", self._split_state(ranked), self._pins({ACCOUNTS[2]: 300, ACCOUNTS[0]: 200, ACCOUNTS[1]: 100})),
            ("Stored order moved", {**self._split_state(ranked), "accounts": list(reversed(ACCOUNTS))}, good),
            ("missing overview", {key: value for key, value in self._split_state(ranked).items() if key != "overview"}, good),
        )
        for message, state, records in cases:
            with self.subTest(message=message), self.assertRaisesRegex(AssertionError, message):
                assert_order_split(state, records, pins_in=ACCOUNTS[:3])

    def test_unknown_dump_shape_fails_instead_of_passing_empty_state(self):
        with self.assertRaisesRegex(AssertionError, "Notification List"):
            parse_notifications("adb transport closed")

    def test_api30_attempt_keeps_ongoing_group_but_api36_and37_dismiss_it(self):
        self.assertEqual(["evidence-claude", "evidence-codex", "evidence-gemini"], swipe_accounts(30))
        for api in (36, 37):
            self.assertEqual(["evidence-codex", "evidence-gemini"], swipe_accounts(api))


class DefaultEvidenceParserTest(unittest.TestCase):
    def test_default_records_reject_each_alert_or_privacy_regression(self):
        assert_snapshot(parse_notifications(dump()), ["uuid-claude"])
        assert_snapshot(parse_notifications(dump(PAIR.replace("mImportance=3\n", "mImportance=DEFAULT\n"))), ["uuid-claude"])
        for before, after in (
            ("importance=3", "importance=2"),
            ("mImportance=3\n", "mImportance=2\n"),
            ("mImportance=3\n", "missingImportance=3\n"),
            ("mImportance=3\n", "mImportance=HIGH\n"),
            ("mSound= null", "mSound= content://sound"),
            ("mVibration= null", "mVibration= []"),
            ("mLight= null", "mLight= Light{color=0}"),
            ("mSound=null", "mSound=content://sound"),
            ("effectiveNotificationChannel=", "unknownChannel="),
            ("mShowBadge=false", "unknownBadge=false"),
            ("mLight= null", "missingLight= null"),
            ("mVibrationEnabled=false", "mVibrationEnabled=true"),
            ("mLights=false", "mLights=true"), ("mShowBadge=false", "mShowBadge=true"),
            ("mImportance=3,", "mImportance=2,"),
            ("vis=PRIVATE", "vis=SECRET"), ("vis=PRIVATE", "vis=PUBLIC"),
            ("flags=0x30a", "flags=0x308"),
            ("flags=0x30a", "flags=0x302"), ("flags=0x30a", "flags=0x20a"),
        ):
            with self.subTest(field=before), self.assertRaises(AssertionError):
                assert_snapshot(parse_notifications(dump(PAIR.replace(before, after))), ["uuid-claude"])

    def test_aosp_keyed_zero_evaluation_events_are_allowed_but_noise_fails(self):
        records = parse_notifications(dump())
        key = records[0]["key"]
        for tail in ("0,0,0", "0,0,0,0,4"):
            assert_snapshot(records, ["uuid-claude"], events="I notification_alert: [" + key + "," + tail + "]")
        for tail in ("1,0,0", "0,1,0", "0,0,1", "0,1,0,2,0", "0,0", "0,0,0,unknown", "0,0,0,1,2,3"):
            with self.subTest(tail=tail), self.assertRaisesRegex(AssertionError, "notification_alert"):
                assert_snapshot(records, ["uuid-claude"], events="I notification_alert: [" + key + "," + tail + "]")
        assert_snapshot(records, ["uuid-claude"], events="I notification_alert: [0|other.app|1|null|10001,1,1,1]")

    def test_heads_up_checks_active_section_not_global_key_mentions(self):
        import status_icon_evidence as subject
        records = parse_notifications(dump())
        key = records[0]["key"]
        sibling = "    StatusBarTouchableRegionManager state:"
        empty = "    HeadsUpManagerPhone state:\n      mTouchAcceptanceDelay=700\n      mSnoozeLengthMs=60000\n      now=123\n      mUser=0\n      snoozed packages: 0\n      mBarState=0\n      mTouchableRegion=SkRegion()\n" + sibling + "\n      key=" + key
        subject.assert_no_heads_up(empty, records)
        for active in ("      HeadsUpEntry\n        mEntry=" + key + "\n", "      mAlertEntries={" + key + "=entry}\n"):
            with self.subTest(active=active), self.assertRaisesRegex(AssertionError, "heads.up|Heads.up"):
                subject.assert_no_heads_up(empty.replace(sibling, active + sibling), records)
        with self.assertRaisesRegex(AssertionError, "Missing dump section boundary"):
            subject.assert_no_heads_up(empty.replace("HeadsUpManagerPhone state:", "HeadsUpManager state:"), records)

    def test_heads_up_uses_shade_touchable_region_manager_bound_despite_outdents(self):
        import status_icon_evidence as subject
        records = parse_notifications(dump())
        key = records[1]["key"]
        flags = "      mHeadsUpPinnedMode=false\n"
        manager = """HeadsUpManager state:
      mTouchAcceptanceDelay=700
      mSnoozeLengthMs=60000
      now=139930
      mUser=0
      snoozed packages: 0
      mBarState=1
      mTouchableRegion=SkRegion()
"""
        later = (
            "    ShadeTouchableRegionManager state:\n"
            "      mTouchableRegion=SkRegion((0,0,720,21)(0,21,798,144)(653,144,798,165))\n"
            "    CentralSurfacesImpl:\n"
            "            NotificationIconContainer\n"
            f"              StatusBarIconView(notification=StatusBarNotification(key={key}))\n"
        )
        outdented_field = "    " + manager.replace("      snoozed packages: 0", "  snoozed packages: 0")
        for api, dump_section in ((36, manager), (37, outdented_field)):
            with self.subTest(api=api):
                report = subject.assert_no_heads_up(flags + dump_section + later, records)
                self.assertEqual([], report["activeStatusKeys"])
                self.assertEqual(["false"], report["presentationFlags"])

        for entry, error in (
            (f"  mAlertEntries={{{key}=entry}}\n", "Status record in active heads-up manager entry"),
            ("  unknownActiveRepresentation=empty\n", "Unrecognized heads-up active section"),
            ("    OtherManager state:\n", "Unrecognized heads-up active section"),
            ("  ShadeTouchableRegionManager state: unknown\n", "Unrecognized heads-up active section"),
        ):
            with self.subTest(entry=entry), self.assertRaisesRegex(AssertionError, error):
                subject.assert_no_heads_up(flags + outdented_field + entry + later, records)
        with self.assertRaisesRegex(AssertionError, "Active heads-up presentation flag"):
            subject.assert_no_heads_up(flags.replace("false", "true") + manager + later, records)

    def test_shade_requires_every_account_child_in_alerting(self):
        import status_icon_evidence as subject
        records = parse_notifications(dump())
        key = records[1]["key"]
        shade = "ShadeListBuilder shade notifications:\n  [0] " + key + " (parent=<root>) rank=1 section=6:5:Alerting\n"
        self.assertEqual("Alerting", subject.assert_alerting_section(shade, records, 37)["assertion"])
        for text in (shade.replace(":Alerting", ":Silent"), shade.replace(key, key.replace("uuid-claude", "wrong"))):
            with self.subTest(text=text), self.assertRaises(AssertionError):
                subject.assert_alerting_section(text, records, 37)

    def test_api30_uses_observed_title_bounds_under_notifications_not_silent(self):
        import status_icon_evidence as subject
        records = parse_notifications(dump())
        xml = '<hierarchy><node text="Notifications" bounds="[0,100][720,130]"/><node package="com.android.systemui" resource-id="android:id/title" text="Claude" bounds="[0,200][720,240]"/><node text="Silent" bounds="[0,300][720,340]"/></hierarchy>'
        result = subject.assert_alerting_section("DefaultSection", records, 30, ET.fromstring(xml))
        self.assertEqual("title positions under Notifications (not Silent)", result["assertion"])
        with self.assertRaises(AssertionError):
            subject.assert_alerting_section("DefaultSection", records, 30, ET.fromstring(xml.replace("[0,200][720,240]", "[0,400][720,440]")))

    def test_api30_repeated_reading_titles_need_distinct_observed_rows(self):
        import status_icon_evidence as subject
        records = parse_notifications(dump(PAIR + PAIR.replace("uuid-claude", "uuid-codex") + PAIR.replace("uuid-claude", "uuid-gemini")))
        header = '<node text="Notifications" bounds="[0,100][720,130]"/>'
        row = '<node package="com.android.systemui" resource-id="android:id/title" text="Claude" bounds="[0,200][720,240]"/>'
        for nodes in (row, row * 3):
            with self.subTest(nodes=nodes), self.assertRaises(AssertionError):
                subject.assert_alerting_section("DefaultSection", records, 30, ET.fromstring('<hierarchy>' + header + nodes + '</hierarchy>'))
        rows = row + row.replace("[0,200][720,240]", "[0,300][720,340]") + row.replace("[0,200][720,240]", "[0,400][720,440]")
        subject.assert_alerting_section("DefaultSection", records, 30, ET.fromstring('<hierarchy>' + header + rows + '</hierarchy>'))

    def test_statusbar_metadata_is_not_inferred_from_shelf_or_record_count(self):
        import status_icon_evidence as subject
        records = parse_notifications(dump())
        key = records[1]["key"]
        block = "    [27f5e08] NotificationIconContainerStatusBarViewModel:\n      icons=NotificationIconsViewData(visibleIcons=[NotificationIconInfo(sourceIcon=Icon(typ=BITMAP size=84x56), notifKey=" + key + ", groupKey=group)], limit=4)\n    CRITICAL dump took 0ms -- viewmodel\n"
        self.assertEqual("verified", subject.assert_visible_icons(block, records)["status"])
        for text in (block.replace(key, key.replace("uuid-claude", "wrong")), block.replace("NotificationIconInfo(sourceIcon=Icon(typ=BITMAP size=84x56), notifKey=" + key + ", groupKey=group)", "")):
            with self.subTest(text=text), self.assertRaises(AssertionError):
                subject.assert_visible_icons(text, records)
        for field in ("visibleState=DOT", "iconState=HIDDEN", "isHidden=true"):
            with self.subTest(field=field), self.assertRaises(AssertionError):
                subject.assert_visible_icons(block.replace("groupKey=group", field + ", groupKey=group"), records)
        result = subject.assert_visible_icons(block.replace("NotificationIconContainerStatusBarViewModel", "NotificationIconContainerShelfViewModel"), records)
        self.assertEqual("unsupported", result["status"])
        self.assertIn("pixel", result["reason"])
        three = parse_notifications(dump(PAIR + PAIR.replace("uuid-claude", "uuid-codex") + PAIR.replace("uuid-claude", "uuid-gemini")))
        two = block.replace("], limit=4)", ", NotificationIconInfo(notifKey=" + key.replace("uuid-claude", "uuid-codex") + ", groupKey=group)], limit=4)")
        with self.assertRaises(AssertionError):
            subject.assert_visible_icons(two, three)


    def test_initial_and_reranked_captures_require_provider_headers_without_assuming_visible_order(self):
        expected = ["evidence-claude", "evidence-codex", "evidence-gemini"]
        accounts = [("claude", "42% left", "Claude"), ("codex", "5% left", "OpenAI Codex"),
                    ("gemini", "100% left", "Antigravity")]
        posted = "".join(PAIR.replace("uuid-claude", "uuid-" + account)
                         .replace("android.title=String (Claude)", f"android.title=String ({title})\n      android.subText=String ({provider})")
                         for account, title, provider in accounts)
        children = [record["key"] for record in parse_notifications(dump(posted)) if not record["summary"]]
        systemui = ("HeadsUpManager state:\n  mTouchAcceptanceDelay=700\n  mSnoozeLengthMs=60000\n  now=123\n  mUser=0\n"
                    "  snoozed packages: 0\n    ShadeTouchableRegionManager state:\nShadeListBuilder shade notifications:\n" +
                    "".join(f"  [{i}] {key} (parent=<root>) rank={i} section=Alerting\n" for i, key in enumerate(children)))
        shade = '''<hierarchy>
          <node package="com.android.systemui" resource-id="android:id/status_bar_latest_event_content">
            <node package="com.android.systemui" resource-id="android:id/notification_header">
              <node package="com.android.systemui" resource-id="android:id/app_name_text" text="Usage Limits"/>
              <node package="com.android.systemui" resource-id="android:id/header_text" text="Antigravity" content-desc=""/>
            </node>
            <node package="com.android.systemui" resource-id="android:id/title" text="100% left" bounds="[0,30][720,60]"/>
          </node>
          <node package="com.android.systemui" resource-id="android:id/status_bar_latest_event_content">
            <node package="com.android.systemui" resource-id="android:id/notification_header" content-desc="Usage Limits · Claude"/>
            <node package="com.android.systemui" resource-id="android:id/title" text="42% left" bounds="[0,90][720,120]"/>
          </node>
          <node package="com.android.systemui" resource-id="android:id/status_bar_latest_event_content">
            <node package="com.android.systemui" resource-id="android:id/notification_headerless_view_row">
              <node package="com.android.systemui" resource-id="android:id/title" text="5% left" bounds="[0,150][720,180]"/>
            </node>
          </node>
        </hierarchy>'''
        for name in ("03-enabled", "05-reranked"):
            for notification, xml, failure in (
                (posted.replace("android.subText=String (OpenAI Codex)", "android.subText=String (OpenAI Codex (synthetic))", 1), shade, "sub-text"),
                (posted.replace("android.subText=String (Claude)", "missingSubText=Claude", 1), shade, "sub-text"),
                (posted, shade.replace('text="Antigravity"', 'text="OpenAI Codex"'), "shade header"),
                (posted, shade, None),
            ):
                with self.subTest(name=name, failure=failure), tempfile.TemporaryDirectory() as directory:
                    evidence = Evidence(directory)
                    evidence.report["api"] = 36
                    evidence.state = {"localIds": {alias: "uuid-" + account for alias, (account, _, _) in zip(expected, accounts)}}
                    with patch.object(evidence, "adb", side_effect=lambda *args: {
                             ("shell", "dumpsys", "notification", "--noredact"): dump(notification),
                             ("shell", "dumpsys", "activity", "service", "com.android.systemui/.SystemUIService"): systemui,
                         }.get(args, "")), patch.object(evidence, "xml", return_value=ET.fromstring(xml)), \
                            patch.object(evidence, "screen"), patch.object(evidence, "home"), patch("status_icon_evidence.time.sleep"):
                        if failure:
                            with self.assertRaisesRegex(AssertionError, failure):
                                evidence.capture(name, "three pinned providers", expected)
                        else:
                            evidence.capture(name, "three pinned providers", expected)
                            headers = evidence.report["scenarios"][-1]["providerHeaders"]
                            self.assertEqual("verified", headers["evidence-claude"]["shadeStatus"])
                            self.assertEqual("Usage Limits · Claude", headers["evidence-claude"]["shadeNodes"][0]["content-desc"])
                            self.assertEqual("not_exposed", headers["evidence-codex"]["shadeStatus"])
                            self.assertEqual("Antigravity", headers["evidence-gemini"]["shadeNodes"][0]["text"])
                            self.assertIn("Shade order from XML: evidence-gemini, evidence-claude, evidence-codex",
                                          (evidence.directory / "summary.md").read_text())

    def test_same_provider_rows_require_reading_titles_and_distinct_names(self):
        import status_icon_evidence as subject
        personal = PAIR.replace("android.title=String (Claude)", "android.title=String (42% left)\n      android.text=String (5h limit · Resets 10:48 PM)\n      android.subText=String (Claude · Claude (synthetic))\n      android.largeIcon=Icon (typ=BITMAP size=96x96)")
        work = personal.replace("uuid-claude", "uuid-work").replace("42% left", "87% left").replace("Claude (synthetic)", "Claude work (synthetic)")
        records = parse_notifications(dump(personal + work))
        root = ET.fromstring('<hierarchy><node package="com.android.systemui" text="Claude (synthetic)" content-desc="personal account"/><node package="com.android.systemui" text="Claude work (synthetic)" content-desc="work account"/></hierarchy>')
        local_ids = {"evidence-claude": "uuid-claude", "evidence-claude-work": "uuid-work"}
        result = subject.assert_duplicate_rows(records, root, local_ids)
        self.assertEqual(["42% left", "87% left"], [row["title"] for row in result["accounts"]])
        self.assertEqual(["Claude · Claude (synthetic)", "Claude · Claude work (synthetic)"], [row["subText"] for row in result["accounts"]])
        for broken in (personal.replace("42% left", "Claude"), personal.replace("Claude · ", ""),
                       personal.replace("Claude (synthetic)", "work"), personal.replace("Icon (typ=BITMAP size=96x96)", "null")):
            with self.subTest(broken=broken), self.assertRaises(AssertionError):
                subject.assert_duplicate_rows(parse_notifications(dump(broken + work)), root, local_ids)
        with self.assertRaises(AssertionError):
            subject.assert_duplicate_rows(records, ET.fromstring('<hierarchy/>'), local_ids)
        self.assertFalse(subject.assert_duplicate_rows(records, ET.fromstring('<hierarchy/>'), local_ids, require_names=False)["namesExposed"])

    def test_same_provider_completion_preserves_current_stored_accounts(self):
        import json
        import status_icon_evidence as subject
        original = {alias: "uuid-" + alias for alias in subject.ACCOUNTS}
        reordered = [subject.ACCOUNTS[2], subject.ACCOUNTS[0], subject.ACCOUNTS[1], *subject.ACCOUNTS[3:]]
        for completed_order in ([*reordered, subject.DUPLICATE_ACCOUNT], [*subject.ACCOUNTS, subject.DUPLICATE_ACCOUNT]):
            with self.subTest(completed_order=completed_order), tempfile.TemporaryDirectory() as directory:
                evidence = Evidence(directory)
                evidence.state = {"accounts": reordered, "localIds": original}
                token = None
                def adb(*args, **kwargs):
                    nonlocal token
                    if args[:3] == ("shell", "am", "broadcast"):
                        token = args[-1]
                        return "Broadcast completed"
                    return json.dumps({"token": token, "command": "same-provider", "success": True,
                                       "accounts": completed_order, "enabled": True,
                                       "pins": [subject.ACCOUNTS[0], subject.DUPLICATE_ACCOUNT],
                                       "localIds": {**original, subject.DUPLICATE_ACCOUNT: "uuid-work"},
                                       "duplicateLocalIds": {"personal": original[subject.ACCOUNTS[0]], "work": "uuid-work"}})
                with patch.object(evidence, "adb", side_effect=adb):
                    if completed_order == [*reordered, subject.DUPLICATE_ACCOUNT]:
                        self.assertEqual(completed_order, evidence.command("same-provider")["accounts"])
                    else:
                        with self.assertRaisesRegex(AssertionError, "account order"):
                            evidence.command("same-provider")

    def test_platform_policy_reads_only_and_api37_hidden_default_stays_true(self):
        with tempfile.TemporaryDirectory() as directory:
            evidence = Evidence(directory)
            evidence.report["api"] = 37
            with patch.object(evidence, "adb", side_effect=["hideSilentStatusBar=true", "showSilentStatusIcons=false"]) as adb:
                observed = evidence.default_policy("defaults-before")
            self.assertEqual(["true"], observed["hideSilentStatusBar"])
            self.assertEqual(["false"], observed["showSilentStatusIcons"])
            self.assertEqual([("shell", "dumpsys", "notification", "--noredact"),
                              ("shell", "dumpsys", "activity", "service", "com.android.systemui/.SystemUIService")],
                             [call.args for call in adb.call_args_list])
            with patch.object(evidence, "adb", side_effect=["hideSilentStatusBar=false", "showSilentStatusIcons=true"]), self.assertRaises(AssertionError):
                evidence.default_policy("changed")

    def test_lockscreen_restores_original_swipe_state_after_capture_failure(self):
        for disabled in ("true", "false"):
            with self.subTest(disabled=disabled), tempfile.TemporaryDirectory() as directory:
                evidence = Evidence(directory)
                original = disabled
                state = disabled
                evidence.state = {"localIds": {alias: alias for alias in ("evidence-claude", "evidence-codex", "evidence-gemini")}}
                def adb(*args, **kwargs):
                    nonlocal state
                    if args == ("shell", "locksettings", "get-disabled"):
                        return state
                    if args[:3] == ("shell", "locksettings", "set-disabled"):
                        state = args[3]
                        return "Lock screen disabled set to " + state
                    if args == ("shell", "dumpsys", "activity", "service", "com.android.systemui/.SystemUIService"):
                        return "    KeyguardStateController:\n      mSecure: false\n"
                    if args == ("shell", "dumpsys", "window"):
                        return "    KeyguardServiceDelegate\n      showing=true\n      occluded=false\n      secure=false\n"
                    return ""
                with patch.object(evidence, "adb", side_effect=adb) as calls, patch.object(evidence, "screen", side_effect=RuntimeError("capture failed")), patch("status_icon_evidence.time.sleep"), self.assertRaisesRegex(RuntimeError, "capture failed"):
                    evidence.lockscreen([])
                self.assertEqual(original, state)
                mutations = [call.args for call in calls.call_args_list if call.args[:3] == ("shell", "locksettings", "set-disabled")]
                self.assertEqual([("shell", "locksettings", "set-disabled", "false"), ("shell", "locksettings", "set-disabled", "true")] if original == "true" else [], mutations)

    def test_secure_keyguard_is_never_temporarily_changed(self):
        with tempfile.TemporaryDirectory() as directory:
            evidence = Evidence(directory)
            def adb(*args, **kwargs):
                if args == ("shell", "locksettings", "get-disabled"):
                    return "true"
                return "    KeyguardStateController:\n      mSecure: true\n"
            with patch.object(evidence, "adb", side_effect=adb) as calls, self.assertRaisesRegex(AssertionError, "non-secure"):
                evidence.lockscreen([])
            self.assertFalse(any(call.args[:3] == ("shell", "locksettings", "set-disabled") for call in calls.call_args_list))


class SwipeEvidenceTest(unittest.TestCase):
    @contextmanager
    def shade_device(self, api, samples, events=("", "")):
        samples = iter(samples)
        events = iter(events)
        observed = []
        actions = []
        screenshot = png_bytes(720, 1560, 3, [bytes(720 * 3)] * 1560)
        with tempfile.TemporaryDirectory() as directory:
            evidence = Evidence(directory)
            evidence.report["api"] = api
            evidence.state = {"localIds": {"evidence-claude": "uuid-claude"}}

            def xml(name, timeout=12):
                root = next(samples)
                observed.append(root)
                evidence.save(name + ".xml", ET.tostring(root, encoding="unicode"))
                return root

            def screen(name):
                (evidence.directory / (name + ".png")).write_bytes(screenshot)

            def adb(*args, **kwargs):
                if args[:2] == ("shell", "input"):
                    actions.append((len(observed), args[2:]))
                if "logcat" in args and "events" in args:
                    actions.append((len(observed), ("events",)))
                    return next(events)
                return ""

            with patch.object(evidence, "adb", side_effect=adb), patch.object(evidence, "xml", side_effect=xml), \
                    patch.object(evidence, "screen", side_effect=screen), patch("status_icon_evidence.time.sleep"):
                yield evidence, actions, observed

    @staticmethod
    def modern_shade(outer, inner, title_bounds, above=""):
        return ET.fromstring(f'''<hierarchy>
          {above}
          <node package="com.android.systemui" resource-id="com.android.systemui:id/expandableNotificationRow" bounds="{outer}">
            <node package="com.android.systemui" resource-id="com.android.systemui:id/expandableNotificationRow" bounds="{inner}">
              <node package="com.android.systemui" resource-id="android:id/status_bar_latest_event_content" bounds="{inner}">
                <node package="com.android.systemui" resource-id="android:id/title" text="Claude" bounds="{title_bounds}"/>
              </node>
            </node>
          </node>
        </hierarchy>''')

    def test_api30_swipes_nearest_content_without_expandable_row_id(self):
        # API 30's observed shade has content bounds but no expandableNotificationRow.
        root = ET.fromstring('''<hierarchy><node package="com.android.systemui"
            resource-id="com.android.systemui:id/notification_stack_scroller" bounds="[0,0][720,1420]">
          <node package="com.android.systemui" resource-id="android:id/status_bar_latest_event_content" bounds="[7,718][713,979]">
            <node package="com.android.systemui" resource-id="android:id/status_bar_latest_event_content" bounds="[7,762][713,947]">
              <node package="com.android.systemui" resource-id="android:id/notification_main_column" bounds="[35,843][685,912]">
                <node package="com.android.systemui" resource-id="android:id/title" text="Claude (synthetic)" bounds="[35,843][685,877]"/>
              </node>
            </node>
          </node>
        </node></hierarchy>''')
        records = parse_notifications(dump(PAIR.replace("Claude", "Claude (synthetic)")))
        with self.shade_device(30, [root, root, ET.Element("hierarchy")]) as (evidence, actions, _):
            evidence.swipe_claude(records)
        self.assertEqual([(2, ("swipe", "183", "854", "718", "854", "500"))],
                         [action for action in actions if action[1][0] != "events"])

    def test_modern_swipe_waits_for_two_matching_outer_row_bounds(self):
        moving = self.modern_shade("[28,649][692,803]", "[28,663][692,797]", "[119,684][320,718]")
        first = self.modern_shade("[28,737][692,883]", "[28,751][692,877]", "[119,772][320,806]")
        second = self.modern_shade("[28,737][692,883]", "[28,755][692,877]", "[119,776][320,810]")
        for api in (36, 37):
            with self.subTest(api=api), self.shade_device(api, [moving, first, second, ET.Element("hierarchy")]) as (evidence, actions, _):
                evidence.swipe_claude(parse_notifications(dump()))
                self.assertEqual([(3, ("swipe", "194", "810", "718", "810", "500"))],
                                 [action for action in actions if action[1][0] != "events"])
                before = ET.parse(evidence.directory / "swipe-before-shade.xml").getroot()
                self.assertEqual(ET.tostring(second), ET.tostring(before))

    def test_swipe_collapses_observed_group_then_waits_for_new_geometry(self):
        # Failed API 36 capture: the Antigravity group above Claude exposed this button.
        expanded = '''<node package="com.android.systemui" resource-id="com.android.systemui:id/expandableNotificationRow" bounds="[28,438][692,645]">
          <node package="com.android.systemui" resource-id="android:id/status_bar_latest_event_content" bounds="[28,438][692,645]">
            <node package="com.android.systemui" resource-id="android:id/header_text" text="Antigravity" bounds="[263,472][363,501]"/>
            <node package="com.android.systemui" resource-id="android:id/expand_button" content-desc="Collapse" clickable="true" bounds="[594,438][692,536]"/>
          </node>
        </node>'''
        collapsed = '''<node package="com.android.systemui" resource-id="com.android.systemui:id/expandableNotificationRow" bounds="[28,438][692,584]">
          <node package="com.android.systemui" resource-id="android:id/expand_button" content-desc="Expand" clickable="true" bounds="[594,438][692,584]"/>
        </node>
        <node package="com.android.systemui" resource-id="android:id/expand_button" content-desc="Collapse" clickable="true" bounds="[20,20][80,80]"/>'''
        original = self.modern_shade("[28,649][692,803]", "[28,663][692,797]", "[119,684][320,718]", expanded)
        final = self.modern_shade("[28,588][692,742]", "[28,602][692,736]", "[119,623][320,657]", collapsed)
        samples = [original, original, final, final, ET.Element("hierarchy")]
        with self.shade_device(36, samples) as (evidence, actions, _):
            evidence.swipe_claude(parse_notifications(dump()))
        self.assertEqual([(2, ("tap", "643", "487")), (4, ("swipe", "194", "665", "718", "665", "500"))],
                         [action for action in actions if action[1][0] != "events"])

    def test_swipe_refuses_to_gesture_while_row_keeps_moving(self):
        first = self.modern_shade("[28,649][692,803]", "[28,663][692,797]", "[119,684][320,718]")
        second = self.modern_shade("[28,737][692,883]", "[28,751][692,877]", "[119,772][320,806]")
        with self.shade_device(36, [first, second] * 4) as (evidence, actions, observed):
            with self.assertRaisesRegex(AssertionError, "layout did not stabilize"):
                evidence.swipe_claude(parse_notifications(dump()))
        self.assertEqual(8, len(observed))
        self.assertEqual([], actions)

    def test_swipe_reports_only_new_target_cancellations(self):
        summary = "0|com.usagelimits.debug|2001|status-icon:uuid-claude|10123"
        child = "0|com.usagelimits.debug|2002|status-icon:uuid-claude|10123"
        historical = f"11-18 09:40:00.651   648   648 I notification_canceled: [{summary},2,42,5,0,2,6,NULL]"
        unrelated = "11-18 09:40:55.651   648   648 I notification_canceled: [0|com.usagelimits.debug|2003|status-icon:uuid-codex|10123,2,42,5,0,2,6,NULL]"
        summary_cancel = f"11-18 09:40:55.651   648   648 I notification_canceled: [{summary},2,98794,19699,0,2,6,NULL]"
        child_cancel = f"11-18 09:40:55.651   648   648 I notification_canceled: [{child},12,98793,19696,8210,-1,-1,NULL]"
        dismiss = "11-18 09:40:55.624  1036  1036 I sysui_multi_action: [757,803,799,notification_dismissed,802,1]"
        frame = "11-18 09:40:55.976  1036  1036 W FrameTracker: Missed SF frame:JANK_COMPOSER, 78337, 68276430, CUJ=J<NOTIFICATION_SHADE_ROW_SWIPE>"
        root = self.modern_shade("[28,649][692,803]", "[28,663][692,797]", "[119,684][320,718]")
        target_cancellations = [
            {"key": summary, "reason": 2, "raw": summary_cancel},
            {"key": child, "reason": 12, "raw": child_cancel},
        ]
        for outcome, post_swipe, cancellations in (
            ("row-retained-without-target-cancel", root, []),
            ("target-dismissal-observed", ET.Element("hierarchy"), target_cancellations),
        ):
            with self.subTest(outcome=outcome):
                after = "\n".join([historical, dismiss, unrelated, *(event["raw"] for event in cancellations), frame])
                with self.shade_device(36, [root, root, post_swipe], (historical, after)) as (evidence, actions, _):
                    evidence.swipe_claude(parse_notifications(dump()))
                    self.assertEqual([(2, ("events",)), (2, ("swipe", "194", "726", "718", "726", "500")),
                                      (2, ("events",))], actions)
                    report = evidence.report["swipe"]
                    self.assertEqual(outcome, report["outcome"])
                    self.assertEqual(cancellations, report["cancellations"])
                    self.assertEqual([dismiss], report["systemUiDismissEvents"])
                    self.assertEqual([frame], report["frameEvents"])
                    saved = json.loads((evidence.directory / "swipe.json").read_text())
                    self.assertEqual(json.loads(json.dumps(report)), saved)
                    self.assertEqual(historical + "\n", (evidence.directory / "swipe-before-events.txt").read_text())
                    self.assertEqual(after + "\n", (evidence.directory / "swipe-after-events.txt").read_text())


class TransitionEvidenceTest(unittest.TestCase):
    def test_first_enable_requires_early_collection_to_contain_eventual_children(self):
        home = ('<hierarchy><node package="example.launcher" enabled="true" '
                'resource-id="example.launcher:id/workspace" bounds="[0,0][720,1560]"/></hierarchy>')
        accounts = ["evidence-" + name for name in ("claude", "codex", "gemini", "xai", "copilot", "cursor")]
        local_ids = {alias: alias.replace("evidence-", "uuid-") for alias in accounts}
        posted = "".join(PAIR.replace("uuid-claude", local_ids[alias]) for alias in accounts[:3])
        keys = [f"0|com.usagelimits.debug|2002|status-icon:{local_ids[alias]}|10123" for alias in accounts[:3]]
        manager = ("HeadsUpManager state:\n  mTouchAcceptanceDelay=700\n  mSnoozeLengthMs=60000\n"
                   "  now=123\n  mUser=0\n  snoozed packages: 0\n    ShadeTouchableRegionManager state:\n")
        later = "    ShadeListBuilder shade notifications:\n" + "".join(
            f"      [{index}] {key} (parent=<root>) rank={index} section=6:5:Alerting\n"
            for index, key in enumerate(keys)
        )
        shade = ('<hierarchy><node text="Notifications" bounds="[0,100][720,130]"/>' + "".join(
            '<node package="com.android.systemui" resource-id="android:id/title" text="Claude" '
            f'bounds="[0,{y}][720,{y + 40}]"/>' for y in (200, 250, 300)
        ) + '</hierarchy>')
        stage = "03-enabled-default"
        for api, api_suffix in ((30, "sectionIndex=0 sectionName=DefaultSection"),
                                (36, "section=4:5:Alerting"), (37, "section=6:5:Alerting")):
            for arrived in ([], keys[:2], keys):
                with self.subTest(api=api, arrived=len(arrived)), tempfile.TemporaryDirectory() as directory:
                    evidence = Evidence(directory)
                    evidence.report.update(api=api, preflight={"component": "example.launcher/.Home"})
                    token, shade_expanded, early_dumps = None, False, 0
                    rows = ["-1|android|55|null|1000", *arrived]
                    count = "" if api == 30 else f" {len(rows)}"
                    gap = " " if api == 30 else "  "
                    detail = "    \t\t\tinteracted=no \n" if api == 30 else ""
                    collection = ("    NotifCollection:\n    ----------------------------------------------------------------------------\n"
                                  f"    \tNotifCollection unsorted/unfiltered notifications:{count}\n"
                                  + "".join(f"    \t\t[{index}]{gap}{key} {api_suffix}\n{detail}"
                                            for index, key in enumerate(rows)))

                    def adb(*args, **kwargs):
                        nonlocal token, shade_expanded, early_dumps
                        if args[:3] == ("shell", "am", "broadcast"):
                            token = args[args.index("token") + 1]
                        if args[:2] == ("exec-out", "run-as"):
                            return json.dumps({"token": token, "command": "enable", "success": True, "enabled": True,
                                               "accounts": accounts, "pins": accounts[:3], "localIds": local_ids,
                                               "completedElapsedRealtimeMs": 100000})
                        if args[:3] == ("shell", "sh", "-c") and "/proc/uptime" in args[3]:
                            return "systemuiDumpStartedUptime=101.25\n" + manager + collection + later
                        if args == ("shell", "dumpsys", "notification", "--noredact"):
                            return dump(posted)
                        if args[-1] == "com.android.systemui/.SystemUIService":
                            return manager + later
                        if args == ("shell", "cmd", "statusbar", "expand-notifications"):
                            self.shade_expanded = True
                        if args == ("exec-out", "cat", "/sdcard/status-icon-evidence.xml"):
                            return shade if self.shade_expanded else home
                        return ""

                    self.shade_expanded = False
                    with patch.object(evidence, "adb", side_effect=adb), patch.object(evidence, "screen"), \
                            patch("status_icon_evidence.time.sleep"):
                        evidence.transition("enable", stage)
                        if arrived == keys:
                            evidence.capture(stage, "first enable with three pins", accounts[:3])
                        else:
                            with self.assertRaisesRegex(AssertionError, "heads-up window not observed"):
                                evidence.capture(stage, "first enable with three pins", accounts[:3])
                            evidence.summary()
                            self.assertIn("heads-up window not observed", (evidence.directory / "summary.md").read_text())

    def transition_summary(self, completion, stamp, *, observed):
        home = ('<hierarchy><node package="example.launcher" enabled="true" '
                'resource-id="example.launcher:id/workspace" bounds="[0,0][720,1560]"/></hierarchy>')
        accounts = ["evidence-" + name for name in ("claude", "codex", "gemini", "xai", "copilot", "cursor")]
        with tempfile.TemporaryDirectory() as directory:
            evidence = Evidence(directory)
            evidence.report["preflight"] = {"component": "example.launcher/.Home"}
            token, receipt_read = None, False

            def adb(*args, **kwargs):
                nonlocal token, receipt_read
                if args == ("exec-out", "cat", "/sdcard/status-icon-evidence.xml"):
                    return home
                if args[:3] == ("shell", "am", "broadcast"):
                    token = args[args.index("token") + 1]
                if args[:2] == ("exec-out", "run-as"):
                    receipt_read = True
                    state = {"token": token, "command": "enable", "success": True, "enabled": True,
                             "accounts": accounts, "pins": accounts[:3], "localIds": dict(zip(accounts, accounts))}
                    if completion is not None:
                        state["completedElapsedRealtimeMs"] = completion
                    return json.dumps(state)
                if any("com.android.systemui/.SystemUIService" in arg for arg in args):
                    systemui = "  HeadsUpManagerPhone state:\n    mHasPinnedNotification=false"
                    if receipt_read and stamp is not None and args[:3] == ("shell", "sh", "-c") and "/proc/uptime" in args[3]:
                        return f"systemuiDumpStartedUptime={stamp}\n{systemui}"
                    return systemui
                return ""

            with patch.object(evidence, "adb", side_effect=adb), patch.object(evidence, "screen"), \
                    patch("status_icon_evidence.time.sleep"), patch("status_icon_evidence.time.monotonic", return_value=105.0):
                if observed:
                    evidence.transition("enable", "03-enabled-default")
                else:
                    with self.assertRaisesRegex(AssertionError, "heads-up window not observed"):
                        evidence.transition("enable", "03-enabled-default")

            evidence.summary()
            return (evidence.directory / "summary.md").read_text()

    def test_late_systemui_dump_fails_despite_fresh_completion_receipt(self):
        summary = self.transition_summary(100000, "104.25", observed=False)
        self.assertIn("4.250 s (maximum 4.0 s); heads-up window not observed", summary)

    def test_dump_must_be_provably_in_window_at_device_clock_precision(self):
        for stamp, observed, delay in (("103.99", True, "3.990 s"), ("104.00", False, "4.000 s"),
                                       ("99.99", False, "-0.010 s")):
            with self.subTest(stamp=stamp):
                summary = self.transition_summary(100000, stamp, observed=observed)
                self.assertIn(f"{delay} (maximum 4.0 s)", summary)

    def test_missing_or_malformed_device_timing_fails_closed(self):
        for completion, stamp in ((None, "101.25"), (100000, None), (100000, "101.2")):
            with self.subTest(completion=completion, stamp=stamp):
                summary = self.transition_summary(completion, stamp, observed=False)
                self.assertIn("delay unavailable (maximum 4.0 s); heads-up window not observed", summary)

    def test_shade_is_saved_but_does_not_arm_observer_or_post(self):
        shade = ('<hierarchy><node package="com.android.systemui" enabled="true" '
                 'resource-id="com.android.systemui:id/notification_stack_scroller" '
                 'bounds="[0,0][720,1560]"/></hierarchy>')
        with tempfile.TemporaryDirectory() as directory:
            evidence = Evidence(directory)
            evidence.report["preflight"] = {"component": "com.android.launcher3/.Launcher"}

            def adb(*args, **kwargs):
                return shade if args == ("exec-out", "cat", "/sdcard/status-icon-evidence.xml") else ""

            with patch.object(evidence, "adb", side_effect=adb), patch.object(evidence, "command") as command, \
                    patch.object(evidence, "screen", side_effect=AssertionError("Observer armed before HOME was verified")) as screen:
                with self.assertRaisesRegex(AssertionError, "Pre-command HOME must expose exactly one enabled launcher workspace with bounds"):
                    evidence.transition("enable", "03-enabled-default")
                screen.assert_not_called()
                command.assert_not_called()
            self.assertEqual(shade + "\n", (evidence.directory / "03-enabled-default-early-precommand-home.xml").read_text())

    def test_verified_home_hierarchy_precedes_observer_frame_and_post(self):
        home = ('<hierarchy><node package="example.launcher" enabled="true" '
                'resource-id="example.launcher:id/workspace" bounds="[0,0][720,1560]"/></hierarchy>')
        stage = "04-updated"
        observations = []
        with tempfile.TemporaryDirectory() as directory:
            evidence = Evidence(directory)
            evidence.report["preflight"] = {"component": "example.launcher/.Home"}

            def adb(*args, **kwargs):
                if args == ("shell", "input", "keyevent", "KEYCODE_HOME"):
                    observations.append("HOME key")
                if args == ("exec-out", "cat", "/sdcard/status-icon-evidence.xml"):
                    observations.append("HOME hierarchy")
                    return home
                if any("com.android.systemui/.SystemUIService" in arg for arg in args):
                    return "systemuiDumpStartedUptime=101.25\nSystemUI dump"
                return ""

            def command(name):
                observations.append(name)
                return {"completedElapsedRealtimeMs": 100000}

            def screen(name, *, crop):
                if name == stage + "-early-0000ms-home":
                    observations.append("pre-command frame")

            with patch.object(evidence, "adb", side_effect=adb), patch.object(evidence, "screen", side_effect=screen), \
                    patch.object(evidence, "command", side_effect=command), patch("status_icon_evidence.time.sleep"):
                evidence.transition("update", stage)

            self.assertEqual(["HOME key", "HOME hierarchy", "pre-command frame", "update"], observations)
            self.assertEqual(home + "\n", (evidence.directory / (stage + "-early-precommand-home.xml")).read_text())


class ReleaseIsolationTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.build = Path(directory.name) / "build"
        self.evidence = Path(directory.name) / "evidence"
        self.write_manifest("debug", "com.usagelimits.evidence.StatusIconEvidenceReceiver")

    def write_manifest(self, variant, component):
        manifest = self.build / "intermediates" / "merged_manifests" / variant / "AndroidManifest.xml"
        manifest.parent.mkdir(parents=True)
        manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
                            f'<application><receiver android:name="{component}"/></application></manifest>')

    def test_valid_debug_manifest_does_not_substitute_for_missing_release(self):
        with self.assertRaisesRegex(AssertionError, "No merged release manifest found"):
            verify(self.build, self.evidence)

    def test_valid_debug_manifest_does_not_excuse_leaking_release(self):
        self.write_manifest("release", "com.usagelimits.evidence.StatusIconEvidenceReceiver")
        with self.assertRaisesRegex(AssertionError, "Evidence component leaked into"):
            verify(self.build, self.evidence)


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
