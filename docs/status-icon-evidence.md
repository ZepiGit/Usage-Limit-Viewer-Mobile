# Android status-icon device evidence

The debug APK exposes `com.usagelimits.evidence.StatusIconEvidenceReceiver` for the CI capture runner. The receiver is declared only in `app/src/debug/AndroidManifest.xml`; release-manifest absence must be checked from the merged release manifest or APK, not inferred from this document.

## Re-running the matrix

Push status-icon, debug-driver or evidence-tool changes, or dispatch **Status icon device evidence** on the desired branch. The dedicated workflow runs API 30, 36 and 37 independently, without release signing or secrets. Every run uploads `status-icons-api<api>-<sha>` with HOME/shade screenshots, original and ×4 status-bar crops, per-step notification/ranking/event dumps, `capture-manifest.json`, and `summary.md`. The glyph renderer remains production code: rerun this same job after a glyph-only change.

Download a run and make one chronological contact sheet per API (JDK 21, no image-library install):

```sh
gh run download RUN_ID --dir /absolute/evidence/RUN_ID
$HOME/.jdks/jdk-21.0.13+9/bin/java -Djava.awt.headless=true tools/StatusIconContacts.java /absolute/evidence/RUN_ID
```

The command writes both chronological status-bar sheets and shade/lock-screen sheets per API. The sheets magnify real status-bar crops with nearest-neighbour scaling; source HOME, shade and lock-screen frames remain beside them. Review both sheets, the early frames and the raw dumps; sheet generation alone is not visual acceptance. The runner retries only a positively identified pre-install SystemUI ANR, at most twice, preserving each rejected boot separately. No retry occurs after installation and no ANR dialog is dismissed. Other boot/scenario failures fail the job.

## Clean run and completion

Use a wiped API 30, 36 or 37 AVD and a fresh debug install (or `adb shell pm clear com.usagelimits.debug` before granting notification permission again). Keep the application process alive for the scenario. Do not force-stop it, open the app, place a widget or request a refresh during the run. Starting a new process can reschedule background work, and opening the activity requests a launch refresh. Re-running after a process restart requires clearing app data first. Use synthetic-only installs, never a personal account database. If the device ran an earlier build of this feature, Android keeps its LOW-importance channel; uninstall that build or clear its app data before comparing.

Send explicit broadcasts, with a new token for every command:

```sh
adb shell am broadcast -n com.usagelimits.debug/com.usagelimits.evidence.StatusIconEvidenceReceiver \
  --es command seed --es token run-001-seed
adb shell am broadcast -n com.usagelimits.debug/com.usagelimits.evidence.StatusIconEvidenceReceiver \
  --es command pin --es token run-001-pin-claude --es account evidence-claude
adb shell run-as com.usagelimits.debug cat files/status-icon-evidence.json
```

Broadcast delivery is not command completion. Poll the JSON until its `token` equals the token just sent, require `success: true`, and fail the scenario on timeout or `success: false`. The receiver uses `goAsync`; the atomically replaced file is written after awaited repository/settings/publication effects. Its fields are `token`, `command`, `success`, optional `error`, `completedElapsedRealtimeMs` (device monotonic time after the command's effects), `accounts` (protocol aliases in repository order), `pins` (protocol aliases in stored pin order), `enabled`, and `localIds` (alias to actual repository-created local ID). Tokens are mandatory; unknown commands/accounts and refused fourth pins report failure. No credentials are created and the driver makes no provider requests.

Before first seeding, the driver waits for startup scheduling, not for a launch sync. It gives due requests up to five seconds to start and lets any already-attempted sync finish before cancelling pending work by the `SyncWorker` class tag. This covers periodic, short-interval and one-shot requests, including queued short-interval successors. An unattempted request still waiting for its network constraint does not hold up seeding beyond the grace period. It then checks that no such work is unfinished and rejects a cancelled request with a nonzero attempt count: cancellation alone does not prove that the worker has finished its non-cancellable publication. WorkManager 2.12 preserves the attempt count on cancellation and resets it after a completed periodic iteration; recheck that behavior when upgrading WorkManager. The barrier fails closed on inconsistent work state or after 45 seconds. The first seed in each process refuses any existing account database, including prior fixtures: clear app data after a process restart. An in-process `seed` reset retains local IDs and clears enablement and pins through the same status-icon change API as the app. A presentation-boundary worker can request a sync about an hour after seeding at the default interval (45 minutes at intervals of 15 minutes or less), well beyond the roughly nine-minute evidence run.

## Fixtures and commands

Protocol aliases are compatibility names, **not provider identities**. Account titles name the actual supported provider:

| Alias | Actual provider | Initial reading |
| --- | --- | --- |
| `evidence-claude` | Claude (`CLAUDE`) | 42% left / 58% used |
| `evidence-codex` | OpenAI Codex (`CODEX`) | 5% left / 95% used |
| `evidence-gemini` | Antigravity (`ANTIGRAVITY`) | 100% left / 0% used |
| `evidence-xai` | Grok / xAI (`XAI`) | exhausted |
| `evidence-copilot` | Kimi (`KIMI`) | stale, 5% left |
| `evidence-cursor` | Devin (`DEVIN`) | synthetic sync error |

`seed` establishes the table's account order, Left display mode, future resets, disabled status icons and no pins (zero status records). Alert settings are silenced through `SettingsStore`, not by bypassing publication. `enable`, `pin` and `unpin` use production `StatusIconChanges`; the latter two take `--es account ALIAS` and check acceptance. The CI runner pins Codex, then Antigravity, then Claude while the feature is off, then enables it; pin order differs from account order.

`update` changes Claude to 87% left and publishes `cacheMoved`. `reorder` stores Antigravity, Claude, Codex, xAI, Kimi, Devin and publishes `presentationChanged`. `states` makes the first three fixtures Claude exhausted, Codex stale (5 underlined), and Antigravity an error, then publishes `cacheMoved`. `reset-readings` restores initial readings without changing settings, pins or order. `presentation` and `cache` invoke only the corresponding production publication event. No command posts, cancels or dismisses a notification directly.

## Notification header evidence

Owner decisions 008 and 009 use Android's standard notification template for both summary and child: the title is the figure and side (or state in words), text is the window and reset, and the large icon is the provider artwork selected in Provider icons. Standard header sub-text always starts with the canonical `ProviderId.displayName`. Only when another pinned account has the same provider does it add ` · <account label>`. Custom account labels never replace the provider name; there are no hidden descriptions or custom views. White marks are inset on a fixed dark backing and rendered to the 48 dp slot at the device's density. Colour marks keep their original artwork and use resource icons; the catalog selects a backed resource for variants that fall below 3:1 on the light shade. The backing does not follow night mode, so a theme switch does not require the app to repost the notifications.

At `03-enabled` and `05-reordered`, all six posted Notification Manager Service (NMS) records must use exactly **Claude**, **OpenAI Codex** or **Antigravity** for the matching account's sub-text. The runner checks recognized shade XML headers associated with the reading title and retains actual text and content descriptions. Contracted rows may expose the title without the header; the report marks that separately, and the original shade XML and PNG remain available for review. Neither header checks nor ranking dumps assert visible order. An accessibility hierarchy shows text exposed to accessibility services, not what TalkBack actually says.

The final `same-provider` command adds a seventh synthetic account without changing the original six local IDs: `evidence-claude-work`, named **Claude work (synthetic)**, with 87% left. It restores **Claude (synthetic)** to 42% left, enables exactly those two pins and selects `claude-color` through production settings. Completion includes all seven `localIds` and `duplicateLocalIds` with `personal`/`work` UUIDs. The initial `seed` contract remains the original six accounts.

`14-same-provider` captures HOME/shade with exactly four records in two valid groups; `14-same-provider-lockscreen` separately captures the same pair under the restored non-secure emulator keyguard procedure. NMS titles must be **42% left**/**87% left**, with exact sub-text **Claude · Claude (synthetic)**/**Claude · Claude work (synthetic)** and a non-null large icon in both records per account. Both account names must occur in actual shade XML. Lock-screen name visibility is reported without asserting what Android's privacy policy exposes. Human review must still confirm the chosen provider artwork and the standard-template appearance.

## DEFAULT channel, unchanged platform policy

Owner decision 007 uses the existing `status_icons` channel at **default importance**, without sound, vibration, lights or badges, on API 30, 36 and 37. The feature is unreleased: there is no replacement channel or migration. Notifications remain ongoing, only-alert-once, local-only and normally `PRIVATE`; the app does not force secret lock-screen visibility. The harness exercises production publication only, with no experimental channel, recipe, promotion request or promotion permission.

The harness never opens Settings or changes notification/system preferences. It reads `hideSilentStatusBar` and `showSilentStatusIcons` before and after the scenario and requires unchanged supported fields. API 30 lacks these policy fields and records that limitation. The clean API 37 image must retain `hideSilentStatusBar=true` and `showSilentStatusIcons=false`: `03-enabled-default` captures DEFAULT account icons under that policy, while `03-enabled` remains the normal initial capture filename. The model assertion is separate from human confirmation of the pixels. There is no allowed-silent scenario or silent-icon toggle.

Notification permission is granted to the synthetic debug installation where required. The only temporary device-policy exception is the explicitly requested **non-secure emulator swipe keyguard** for `03-enabled-lockscreen`: read and retain its original disabled state, require SystemUI `mSecure: false`, enable it only if originally disabled, sleep/wake without dismissing, verify WINDOW `KeyguardServiceDelegate` reports `showing=true`, capture, then restore and verify the original disabled state. Lock-screen PNG/XML, window, window-policy, trust, SystemUI and notification dumps remain alongside the original/restored state files. Three pinned account groups must still be active; normal Android privacy rules decide their content. This is not evidence for a secure phone or always-on display.

Capture commands use bounded screenshots and `KEYCODE_WAKEUP` before HOME rather than changing stay-awake, animation, screen-timeout or other system settings. The issue-66 pre-install HOME/ANR gate and exact retry rule are unchanged.

## What counts as evidence

Capture the status bar, expanded shade and magnified status-bar crop after each scenario step. Ranking dumps are sampled after the five-second publication settle period. Three selected accounts require six status-channel records in three groups, each one summary and one child, without `Aggregate_*`, `ranker_group` or non-null group overrides. Alert and system records are counted separately. Initial enablement, update and reorder preserve all six stable record keys; unpinning one account from a full state leaves four records.

Every active status record must report header importance 3, effective importance 3/DEFAULT, and an effective `status_icons` channel with importance 3, null sound, vibration/lights disabled and badge false. Effective record `mSound`, `mVibration` and `mLight` must each be strictly `null`. Ongoing, only-alert-once, local-only and normal `PRIVATE` visibility are required. AOSP `notification_alert` rows are parsed as notification key, buzz, beep, blink, optionally politeness and mute reason. Zero-valued evaluations are allowed; any nonzero buzz/beep/blink or unknown app-event shape fails. Absence of an evaluation event is not required or treated as proof of silence heard.

SystemUI's bounded `HeadsUpManager[Phone] state` section must match the supported empty-active-entry format for every snapshot, not just pinned-mode false. Any status key in that section, active presentation flag, unknown line or unknown section shape fails. On API 36/37 the literal `ShadeTouchableRegionManager state:` heading marks the boundary even if Android outdents the manager heading or a field. Keys outside the section are valid ranking/shade references, not heads-up entries. Before arming an early observer, the runner exports the idle UI hierarchy and confirms the launcher workspace so the baseline frame does not capture the shade closing. The observer retains that pre-command HOME frame and targets 0.25, 0.5, 1 and 2 seconds around enable/update/reorder; actual frame times and early window/SystemUI/log/event dumps are retained. The runner waits one second after reading the matched receiver completion, then timestamps the early SystemUI dump on the device immediately before launching it. The summary reports how long after receiver completion it started. A start outside the first 4.0 seconds, or an unverifiable time, fails as `heads-up window not observed`. The comparison allows for the device uptime stamp's 10 ms resolution without accepting a potentially late dump. On first enable, the early dump must also list the three account child keys in SystemUI's notification collection; otherwise the observation may have happened before delivery. Update and reorder reuse these keys, so their presence cannot confirm delivery of the newer version. These finite samples cannot rule out transient peeks between observations.

API 36/37 require every account child key in the actual `ShadeListBuilder shade notifications` list to have section `Alerting`; summaries need not be separately listed. API 30's generic section cannot establish the same name, so actual shade XML must place every account title below **Notifications** and before **Silent** when present. The report labels this precisely as “title positions under Notifications (not Silent)”.

Where the image exposes `NotificationIconContainerStatusBarViewModel`/`StatusBarIconViewModel` `visibleIcons`, its app keys must exactly match the account child keys (three in the full state), without missing, duplicate or unexpected keys. This asserts the status-bar visibility model, not pixels or order. Images exposing only shelf/AOD containers explicitly report unsupported metadata and require human pixel inspection; six NMS records are never substituted for three visible icons. Unknown reported status-bar metadata fails rather than becoming unsupported.

Dismiss only with the runner's real swipe. On API 30 attempt it and record that ongoing notifications remain; never emulate a dismissal by cancellation. On API 36 and 37 require the dismissed account to stay absent through a presentation event and pinning another account, then return on `cache`. A failed swipe is a failed device scenario, not permission to manufacture evidence.

Repository and Robolectric notification tests cannot prove device pixels, platform ranking or successful swipes. In particular, the group-alert metadata used to suppress interruptions is not evidence from a dozing display; the emulator does not exercise ambient pulses. Inspect the downloaded CI images and ranking dumps on every matrix level; record whether icons and shade rows follow account order before and after reorder. API 37 follows API 36 expectations unless actual captures differ; report differences to the owner rather than hiding them. Device overflow, silent-icon settings, lock-screen policy and OEM behavior still limit visibility. The debug driver does not exercise provider networking, real credentials, process-death restoration or production background-sync timing.
