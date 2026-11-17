# Android status-icon device evidence

The debug APK exposes `com.usagelimits.evidence.StatusIconEvidenceReceiver` for the CI capture runner. The receiver is declared only in `app/src/debug/AndroidManifest.xml`; release-manifest absence must be checked from the merged release manifest or APK, not inferred from this document.

## Re-running the matrix

Push status-icon, debug-driver or evidence-tool changes, or dispatch **Status icon device evidence** on the desired branch. The dedicated workflow runs API 30, 36 and 37 independently, without release signing or secrets. Every run uploads `status-icons-api<api>-<sha>` with HOME/shade screenshots, original and ×4 status-bar crops, per-step notification/ranking/event dumps, `capture-manifest.json`, and `summary.md`. The glyph renderer remains production code: rerun this same job after a glyph-only change.

Download a run and make one chronological contact sheet per API (JDK 21, no image-library install):

```sh
gh run download RUN_ID --dir /absolute/evidence/RUN_ID
$HOME/.jdks/jdk-21.0.13+9/bin/java -Djava.awt.headless=true tools/StatusIconContacts.java /absolute/evidence/RUN_ID
```

The sheets magnify real status-bar crops with nearest-neighbour scaling; source HOME and shade frames remain beside them. The runner retries only a positively identified pre-install SystemUI ANR, at most twice, preserving each rejected boot separately. No retry occurs after installation and no ANR dialog is dismissed. Other boot/scenario failures fail the job.

## Clean run and completion

Use a wiped API 30, 36 or 37 AVD and a fresh debug install (or `adb shell pm clear com.usagelimits.debug` before granting notification permission again). Keep the application process alive for the scenario. Do not force-stop, relaunch or manually refresh the app after seeding: normal application startup schedules a provider sync, and this driver does not change production scheduling. Re-running after a process restart requires clearing app data first. Use synthetic-only installs, never a personal account database.

Send explicit broadcasts, with a new token for every command:

```sh
adb shell am broadcast -n com.usagelimits.debug/com.usagelimits.evidence.StatusIconEvidenceReceiver \
  --es command seed --es token run-001-seed
adb shell am broadcast -n com.usagelimits.debug/com.usagelimits.evidence.StatusIconEvidenceReceiver \
  --es command pin --es token run-001-pin-claude --es account evidence-claude
adb shell run-as com.usagelimits.debug cat files/status-icon-evidence.json
```

Broadcast delivery is not command completion. Poll the JSON until its `token` equals the token just sent, require `success: true`, and fail the scenario on timeout or `success: false`. The receiver uses `goAsync`; the atomically replaced file is written after awaited repository/settings/publication effects. Its fields are `token`, `command`, `success`, optional `error`, `accounts` (protocol aliases in repository order), `pins` (protocol aliases in stored pin order), `enabled`, and `localIds` (alias to actual repository-created local ID). Tokens are mandatory; unknown commands/accounts and refused fourth pins report failure. No credentials are created and the driver makes no provider requests.

Before first seeding, the driver waits for the empty installation's startup one-shot and first periodic sync to finish naturally, then cancels synthetic-run sync scheduling through WorkManager. The debug-only periodic barrier reads WorkManager 2.12's restricted `WorkSpec.periodCount` after the request returns to `ENQUEUED`; recheck that barrier when upgrading WorkManager. Startup has a 45-second deadline and reports failure on timeout. The first seed in each process refuses any existing account database, including prior fixtures: clear app data after a process restart. An initial sync that cannot complete fails the command rather than seeding into racing work. An in-process `seed` reset retains local IDs and clears enablement and pins through the same status-icon change API as the app.

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

## Silent-icon visibility is a device precondition

The API 37 AOSP image defaults to hiding silent status-bar icons. Run 44133402554 retained six valid account records and three shade rows but showed **zero account icons** on HOME: Notification Manager reported `hideSilentStatusBar=true`, SystemUI `showSilentStatusIcons=false`. API 30/36 images in that run showed three icons. This is a user/system setting, not evidence that publication failed.

Keep a capture and dump of the initial device setting before changing it. For the visible-icon scenario, use Settings → Notifications → turn **Hide silent notifications in status bar** off, then verify `hideSilentStatusBar=false` and `showSilentStatusIcons=true`. There is no `cmd notification` verb or secure-settings key for this policy; the harness uses the actual Settings switch. Do not alter production channel importance or bypass the setting.

Sources: [API 37 default policy](https://android.googlesource.com/platform/frameworks/base/+/android-17.0.0_r1/services/core/java/com/android/server/notification/PreferencesHelper.java), [Settings switch](https://android.googlesource.com/platform/packages/apps/Settings/+/android-17.0.0_r1/src/com/android/settings/notification/SilentStatusBarPreferenceController.java), [shell-command surface](https://android.googlesource.com/platform/frameworks/base/+/android-17.0.0_r1/services/core/java/com/android/server/notification/NotificationShellCmd.java). The same default changed on Android 16 between release tags r3 and r4; do not generalize one emulator image to every device.

## What counts as evidence

Capture the status bar, expanded shade and magnified status-bar crop after each scenario step. Save notification ranking dumps alongside captures, sampled at least three seconds after publication. Three selected accounts require six status-channel records in three groups, each one summary and one child, with no `Aggregate_*` or `ranker_group` grouping. Count alert and system records separately. An update or reorder must not add alert records; unpinning one account from a full state leaves four status records.

Dismiss only with the runner's real swipe. On API 30 attempt it and record that ongoing notifications remain; never emulate a dismissal by cancellation. On API 36 and 37 require the dismissed account to stay absent through a presentation event and pinning another account, then return on `cache`. A failed swipe is a failed device scenario, not permission to manufacture evidence.

Repository and Robolectric notification tests cannot prove device pixels, platform ranking or successful swipes. Inspect the downloaded CI images and ranking dumps on every matrix level; record whether icons and shade rows follow account order before and after reorder. API 37 follows API 36 expectations unless actual captures differ; report differences to the owner rather than hiding them. Device overflow, silent-icon settings, lock-screen policy and OEM behavior still limit visibility. The debug driver does not exercise provider networking, real credentials, process-death restoration or production background-sync timing.
