# Silent status-bar icons: Android 17 default vs the API 36 image

**Key answer.** `hideSilentStatusBar=true` is the AOSP framework default on `refs/heads/android17-release`, not an emulator-image overlay and not an Android 17-only invention. Do not treat `refs/heads/android16-release` as the same default: re-read 2026-11-17, that branch head is still `false` (`PreferencesHelper.java:182`, attribute `hide_gentle` at line 169). The `true` default first appears on tag `android-16.0.0_r4` (build `BP4A.251205.006`, security patch 2025-12-05, page label Android16) and is what `refs/heads/android17-release:177` and tag `android-17.0.0_r1:177` keep. Tags `android-16.0.0_r1` through `android-16.0.0_r3` are `false`. The API 36 artifact's `hideSilentStatusBar=false` matches the android16-release head and r1–r3, not r4. There is no `Settings.Secure` key: `Settings.java` on android17-release (898487 bytes) has no `hide_silent`, `hide_gentle`, `silent_status`, or `silent_icons` symbol, and neither `config.xml` nor SettingsProvider `defaults.xml` defines one. A fresh `/data/system/notification_policy.xml` omits the element, so the field stays at the constant. The r4 rename from `hide_gentle` to `hide_silent` has no migration, so an old saved value is not read back. Public Pixel device repos checked have no android16/17 release branch; a private Pixel override is UNKNOWN, not disproved. The flip commit hash is unknown: Gitiles `+log`/`+blame` returned 401/403. `refs/heads/main` is still `false`.

## Capture facts

| Source actually read | `DEFAULT_HIDE_SILENT_STATUS_BAR_ICONS` | XML attribute |
| --- | --- | --- |
| `refs/heads/android16-release` | `false` line 182 | `hide_gentle` line 169 |
| `refs/tags/android-16.0.0_r3` | `false` line 186 | `hide_gentle` line 173 |
| `refs/tags/android-16.0.0_r4` | `true` line 185 | `hide_silent` line 172 |
| `refs/heads/android17-release` | `true` line 177 | `hide_silent` line 164 |
| API 36 image `BE2A.250530.026.D1` | observed `hideSilentStatusBar=false` | not an AOSP tag; see device facts |
| API 37 image `CE2A.260420.051.A3` | observed `true`, per assignment | not an AOSP tag; see device facts |

`dumpsys notification` prints `mPreferencesHelper.shouldHideSilentStatusIcons()` (`NotificationManagerService.java:8706-8707` on android17-release, `:7887` on android16-release). That method returns the field initialized from the constant. No Secure/System key, framework config bool, SettingsProvider default, or notification/SystemUI aconfig flag supplies another default. The Settings UI key is the preference string `silent_icons`, and only a system caller persists it. Detail and the Android 11–15 table follow. All four branch/tag rows above were fetched again after the branch-versus-tag warning. `refs/heads/android16-release` is not `true`.

Checked 2026-11-17 against public Gitiles blobs. Gitiles `+log` and `+blame` returned 401/403, so the commit that flipped the constant is not identified. `refs/heads/main` still has `false` and the old attribute name; that head is not the release that shipped the flip.

## Observed device facts, separate from source

These are properties read from the running emulator images. They are not AOSP manifest tags and do not identify a Gitiles commit.

| Image | Fingerprint, as read from the device | Security patch, as read | Dump |
| --- | --- | --- | --- |
| API 36 `sdk_phone64_x86_64` / `emu64x` | `Android/sdk_phone64_x86_64/emu64x:16/BE2A.250530.026.D1/13818094:userdebug/test-keys` | 2025-07-05 | `hideSilentStatusBar=false` in `03-enabled-default-notification.txt` |
| API 37 `sdk_phone64_x86_64` / `emu64x` | `Android/sdk_phone64_x86_64/emu64x:17/CE2A.260420.051.A3/16571990:userdebug/test-keys` | 2026-08-05 | `hideSilentStatusBar=true`, stated from the API 37 capture |

Established: both strings are `userdebug` `test-keys` emulator builds (`ro.product.name` form `sdk_phone64_x86_64`, device `emu64x`). The API 36 build id is `BE2A.250530.026.D1`, incremental `13818094`, release letter 16. The API 37 build id is `CE2A.260420.051.A3`, incremental `16571990`, release letter 17.

Not established, and not claimed: either fingerprint equals `android-16.0.0_r1`, `r2`, `r3`, `r4`, `android-17.0.0_r1`, or any other Gitiles tag. The [build-numbers table](https://source.android.com/docs/setup/reference/build-numbers) fetched 2026-11-17 lists `BP2A.250605.031.A2` as `android-16.0.0_r1`, `BP2A.250605.031.A3` as `r2`, `BP3A.250905.014` as `r3`, `BP4A.251205.006` as `r4`, and `CP2A.260605.016` as `android-17.0.0_r1`. It does not list `BE2A.250530.026.D1` or `CE2A.260420.051.A3`. The observed API 36 flag `false` is consistent with the pre-r4 constant, and the observed API 37 flag `true` is consistent with the android17-release constant. The API 36 patch date 2025-07-05 is older than the r4 patch date 2025-12-05. Consistency and an older patch date are not manifest provenance.

## What the dump line is

`dumpsys notification` prints the live field, not a settings row:

```text
pw.println("  hideSilentStatusBar="
        + mPreferencesHelper.shouldHideSilentStatusIcons());
```

- android17-release: `NotificationManagerService.java:8706-8707`
- android16-release: `NotificationManagerService.java:7887`
- android-16.0.0_r4: `NotificationManagerService.java:8244`

`shouldHideSilentStatusIcons()` returns `mHideSilentStatusBarIcons`, which is initialized from `DEFAULT_HIDE_SILENT_STATUS_BAR_ICONS`.

Observed contrast, not provenance: the API 36 artifact `03-enabled-default-notification.txt` contains `hideSilentStatusBar=false`. The assignment states the API 37 capture is `true`. Those observed flags are consistent with the constants on the refs above (`false` before r4, `true` on r4 and android17-release). They do not identify the tree either image was built from.

## Where the default is set

`frameworks/base` `PreferencesHelper.java`, symbol `DEFAULT_HIDE_SILENT_STATUS_BAR_ICONS`.

| Ref | Line | Default | XML attribute `ATT_HIDE_SILENT` | Element |
| --- | --- | --- | --- | --- |
| `refs/heads/android11-release` | 129 | `false` | `"hide_gentle"` (line 120) | `silent_status_icons` (line 106) |
| `refs/tags/android-11.0.0_r1` | 128 | `false` | `"hide_gentle"` | same tag |
| `refs/tags/android-11.0.0_r48` | 130 | `false` | `"hide_gentle"` | same tag |
| `refs/heads/android12-release` | 136 | `false` | `"hide_gentle"` (line 127) | line 113 |
| `refs/tags/android-12.0.0_r1` | 136 | `false` | `"hide_gentle"` | same |
| `refs/tags/android-12.1.0_r27` | 136 | `false` | `"hide_gentle"` | same |
| `refs/heads/android13-release` | 147 | `false` | `"hide_gentle"` (line 138) | line 124 |
| `refs/tags/android-13.0.0_r1` and `android-13.0.0_r84` | 147 | `false` | `"hide_gentle"` | same |
| `refs/heads/android14-release` | 150 | `false` | `"hide_gentle"` (line 141) | line 128 |
| `refs/tags/android-14.0.0_r1` | 150 | `false` | `"hide_gentle"` | same |
| `refs/tags/android-14.0.0_r75` | 156 | `false` | `"hide_gentle"` (line 146) | line 133 |
| `refs/heads/android15-release` | 165 | `false` | `"hide_gentle"` (line 153) | line 138 |
| `refs/tags/android-15.0.0_r1` | 165 | `false` | `"hide_gentle"` | same |
| `refs/tags/android-15.0.0_r36` | 182 | `false` | `"hide_gentle"` (line 169) | line 154 |
| `refs/heads/android16-release` | 182 | `false` | `"hide_gentle"` (line 169) | line 153 |
| `refs/tags/android-16.0.0_r1` | 182 | `false` | `"hide_gentle"` (line 169) | line 153 |
| `refs/tags/android-16.0.0_r2` | 182 | `false` | `"hide_gentle"` (line 169) | not re-read; tag name unchanged on the surrounding refs |
| `refs/tags/android-16.0.0_r3` | 186 | `false` | `"hide_gentle"` (line 173) | not re-read |
| `refs/tags/android-16.0.0_r4` | 185 | `true` | `"hide_silent"` (line 172) | line 156 |
| `refs/heads/android17-release` | 177 | `true` | `"hide_silent"` (line 164) | line 148 |
| `refs/tags/android-17.0.0_r1` | 177 | `true` | `"hide_silent"` (line 164) | line 148 |
| `refs/heads/main` | 183 | `false` | `"hide_gentle"` (line 170) | line 155 |

Blob URL pattern, android17 example:

`https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/services/core/java/com/android/server/notification/PreferencesHelper.java`

The constant is annotated `@VisibleForTesting`. The field is:

```text
private boolean mHideSilentStatusBarIcons = DEFAULT_HIDE_SILENT_STATUS_BAR_ICONS;
```

android17-release line 235; android16-release line 239.

This table is those refs only. It is not a claim about every `android-1N.0.0_r*` tag. Intermediate Android 12–15 tags were not all fetched. The first and latest published platform tag of each family, plus every `android-16.0.0_r1`–`r4` tag and the release heads, are what was read. `frameworks/base` `+refs` on 2026-11-17 listed 47 `android-11.0.0_*` tags, 57 Android 12 tags, 82 Android 13, 71 Android 14, 34 Android 15, exactly four `android-16.0.0_r*`, and one `android-17.0.0_r1`.

## Official tag dates

From [Codenames, tags, and build numbers](https://source.android.com/docs/setup/reference/build-numbers), fetched 2026-11-17:

| Build ID | Tag | Version on that page | Security patch |
| --- | --- | --- | --- |
| `BP2A.250605.031.A2` | `android-16.0.0_r1` | Android16 | 2025-06-05 |
| `BP2A.250605.031.A3` | `android-16.0.0_r2` | Android16 | 2025-06-05 |
| `BP3A.250905.014` | `android-16.0.0_r3` | Android16 | 2025-09-05 |
| `BP4A.251205.006` | `android-16.0.0_r4` | Android16 | 2025-12-05 |
| `CP2A.260605.016` | `android-17.0.0_r1` | Android17 | 2026-06-05 |

The same page maps Android 16 to API 36 (25Q2), Android 16 QPR2 to API 36.1 (25Q4), and Android 17 to API 37 (26Q2). `Build.VERSION_CODES` on android17-release names `BAKLAVA = 36` (line 1312) and `CINNAMON_BUN = 37` (line 1318). `VERSION_CODES_FULL.BAKLAVA_1` is the 36.1 minor (line 1539). [INFERENCE] `android-16.0.0_r4` is the 25Q4 / API 36.1 source drop: its build id starts `BP4A.251205` and its patch date is 2025-12-05, which is the QPR2 row's quarter. The page does not print "36.1" on the `r4` row itself, so the API minor of that tag is that inference, not a quoted cell.

The flip is therefore inside the Android 16 tag series, between r3 and r4. It is already present in the first published Android 17 tag. `refs/heads/android16-release` still has `false`. A tree synced to that branch head does not have the hidden default; a tree synced to `android-16.0.0_r4` or `android17-release` does. That is a statement about those Gitiles refs, not about either emulator image.

`frameworks/base` tag pages resolved these commits (page HTML, not an annotated tagger date): `android-16.0.0_r3` `ac202657a80a1e6f7d9c3a8d7a713fe1e27fc377`, `android-16.0.0_r4` `dab2e60df41eec5479d4cb934235d6e133433664`, `android-17.0.0_r1` `23149ba144e850fe494a91ffa13f8019455c0804`. Those are the branch tips the tags point at, not the commit that edited the constant.

## What it is not

No `Settings.Secure` or `Settings.System` key stores this. `Settings.java` on android16-release and android17-release has no `hide_silent`, `hide_gentle`, or silent-status-bar constant. `packages/SettingsProvider/res/values/defaults.xml` on android17-release has lock-screen and bubble defaults, not this flag. `core/res/res/values/config.xml` on both release heads has no matching resource. `services/core/java/com/android/server/notification/flags.aconfig` on android16-release, android-16.0.0_r4, and android17-release has no silent-status-bar flag (the only nearby name is `autogroup_summary_icon_update`). `packages/SystemUI/aconfig/systemui.aconfig` on android17-release and `SystemUiDeviceConfigFlags.java` likewise have no match.

The user-facing control is a Settings preference key, not a settings provider key. On android17-release, `packages/apps/Settings` `res/xml/configure_notification_settings.xml:197-200` declares `android:key="silent_icons"` with title `@string/silent_notifications_status_bar` and controller `SilentStatusBarPreferenceController`. The string is "Hide silent notifications in status bar" (`res/values/strings.xml:9649`). The controller's `KEY` is `"silent_icons"`; `isChecked()` calls `NotificationBackend.shouldHideSilentStatusBarIcons`, and `setChecked` calls `setHideSilentStatusIcons` (`SilentStatusBarPreferenceController.java:29-50`). The backend forwards to `INotificationManager` (`NotificationBackend.java:437-448`). Checked means hidden.

`NotificationManagerService` enforces the write: `setHideSilentStatusIcons` calls `checkCallerIsSystem()` then `mPreferencesHelper.setHideSilentStatusIcons(hide)` and `handleSavePolicyFile()` (android17-release lines 4783-4787). The read, `shouldHideSilentStatusIcons(String callingPkg)`, allows the system/phone caller or a registered listener package and otherwise throws `SecurityException` (lines 4771-4778). An ordinary app cannot set or read it. SystemUI is the listener: on connect it calls `onSilentStatusBarIconsVisibilityChanged(mNotificationManager.shouldHideSilentStatusBarIcons())` (`NotificationListener.java:134-135`), and the callback does `mStatusIconInteractor.setHideSilentStatusIcons(...)` (lines 256-257).

## Persistence: source rules, not an upgrade test

No userdata image was upgraded for this note. The bullets below are what these two parsers do with a file. They are not a claim that every device upgrade loses a setting. The API 36 and API 37 captures were separate fresh images, not one image upgraded in place.

File, both heads: `new File(systemDir, "notification_policy.xml")` where `systemDir` is `new File(Environment.getDataDirectory(), "system")`. android17-release `NotificationManagerService.java:3364` and `:3394`; android16-release `:2995` and `:3026`. Loaded by `loadPolicyFile()` into `readPolicyXml` (android17-release around line 1420).

Write, only when the field differs from the constant (android17-release `PreferencesHelper.java:719-723`; the same shape is on android16-release at 691-694):

```text
if (mHideSilentStatusBarIcons != DEFAULT_HIDE_SILENT_STATUS_BAR_ICONS) {
    out.startTag(null, TAG_STATUS_ICONS);
    out.attributeBoolean(null, ATT_HIDE_SILENT, mHideSilentStatusBarIcons);
    out.endTag(null, TAG_STATUS_ICONS);
}
```

Read (android17-release lines 309-314):

```text
if (TAG_STATUS_ICONS.equals(tag)) {
    if (forRestore && userId != USER_SYSTEM) {
        continue;
    }
    mHideSilentStatusBarIcons = parser.getAttributeBoolean(null,
            ATT_HIDE_SILENT, DEFAULT_HIDE_SILENT_STATUS_BAR_ICONS);
}
```

`XML_VERSION` is 4 on both release heads (android17-release line 262). The version migrations in `readXml` are bubbles, notification permission, and the review-permissions notification (`XML_VERSION_BUBBLES_UPGRADE`, `XML_VERSION_NOTIF_PERMISSION`, `XML_VERSION_REVIEW_PERMISSIONS_NOTIFICATION`). None mention silent status icons. There is no second read of `hide_gentle`.

What that source does, if a file is later read by the r4 or android17-release parser:

- While the constant is `false`, both an untouched value and an explicit "show" are `false`. The writer emits no element in either case. Those two states are not distinguishable in the file.
- A file with no `silent_status_icons` element, read by the r4 or android17-release parser, stays at that parser's constant `true`. Untouched and explicit-show therefore both become hide after the constant flip. This is source behavior of the reader, not a migrated image.
- The only element the old writer emits is the non-default hide, `hide_gentle="true"`. The new reader does not look up `hide_gentle`. A missing `hide_silent` attribute falls back to `true`, so that old hide still reads as hide. The fallback is `true`, not proof that a `hide_gentle="false"` file existed. The old writer does not produce that false file.

This was not executed against a migrated userdata image. Neither capture included `notification_policy.xml`. The two dumps are separate images. The observed flags are consistent with those constants. They are not provenance for either image's manifest.

A later save rewrites only a non-default value, under the new name `hide_silent`. Restore of the element is skipped unless `userId == USER_SYSTEM` (lines 310-311).

## Pixel and emulator overlays

Public `device/google/*` and `device/generic/goldfish` were checked for a product override. No published overlay found sets this default.

- `device/google/` lists device repos (`bluejay`, `pantah`, `caimito`, `akita`, `gs101`, `cuttlefish`, and others). `+refs` on `bluejay`, `pantah`, `caimito`, `akita`, and `gs101` contained `main` but not `android16-release`, `android17-release`, `android-16.0.0_r3`, `android-16.0.0_r4`, or `android-17.0.0_r1`. Those release branches are not in the public Pixel device repos checked. Whether a private vendor tree overrides the constant is **UNKNOWN**, not "no override."
- `device/google/cuttlefish` and `device/generic/goldfish` do have `android16-release`, `android17-release`, `android-16.0.0_r3`, `android-16.0.0_r4`, and `android-17.0.0_r1`.
- Cuttlefish android17-release `shared/overlays/SettingsProvider/res/values/defaults.xml` (918 bytes) and `shared/overlays/core/res/values/config.xml` (3901 bytes) contain none of `silent`, `hide_`, or `status_bar`. The same SettingsProvider file on android16-release is also 918 bytes with no hits. `shared/phone/overlays/core/res/values/config.xml` (3500 bytes) and `shared/phone/overlay/packages/apps/Settings/res/values/arrays.xml` have no hits.
- Goldfish android17-release `overlay/frameworks/base/packages/overlays` contains only `pixel_fold2`. The guessed framework `config.xml` paths 404. This is not a full recursive grep of every goldfish file.

`platform/vendor/google/` is not a public Gitiles project (404). Pixel factory images and driver binaries are linked from the build-numbers page (`developers.google.com/android/images` and `/drivers`) but were not unpacked here. No public Pixel document found in this pass states a different default for "Hide silent notifications in status bar." Absence of that document is **UNKNOWN** for shipped Pixel builds.

## Established vs inference

Established from the blobs and the build-numbers page:

- The dump field is `PreferencesHelper.shouldHideSilentStatusIcons()`.
- The default constant flips from `false` to `true` between `android-16.0.0_r3` and `android-16.0.0_r4`, and Android 17's release head and `android-17.0.0_r1` keep `true`.
- The XML attribute flips from `hide_gentle` to `hide_silent` in the same interval. The element name stays `silent_status_icons`.
- No Secure key, framework `config.xml` bool, SettingsProvider default, or notification/SystemUI aconfig flag was found on the files read.
- The Settings toggle is preference key `silent_icons` and only a system caller can persist the change, into `notification_policy.xml`, and only when the value differs from the constant.
- The API 36 artifact's `hideSilentStatusBar=false` matches every Android 11–16 ref read except `android-16.0.0_r4`. The artifact's build id is observed, not mapped to one of those refs.

[INFERENCE]

- The API 36 image's observed patch 2025-07-05 is an earlier date than the `android-16.0.0_r4` patch date 2025-12-05 on the build-numbers page. Its id date `250530` is an earlier date than that tag's `251205`. The observed dump flag `false` is consistent with the constant on `android16-release` and r3. The observed API 37 flag `true` is consistent with the constant on `android17-release` and r4. Neither date nor flag is a manifest tag. The build-numbers page does not list `BE2A.250530.026.D1` or `CE2A.260420.051.A3`.
- While the constant is `false`, explicit show and untouched are the same value, so the writer emits no element for either. After the constant is `true`, a file with no element reads as hide. The old non-default element is `hide_gentle="true"`; the new reader ignores that attribute name and its missing-attribute fallback is `true`. No policy file from either image was read.
- `android-16.0.0_r4` corresponds to Android 16 QPR2 / API 36.1. The official table labels that tag "Android16" only.
- `refs/heads/main` not yet containing the flip means release branches diverged. It does not date the cherry-pick.

Not established: the git commit hash and message of the flip; any manifest tag or source tree for either emulator fingerprint; behavior of any Pixel factory image; contents of unpublished `vendor/google` overlays. Observed dump flags and patch dates are not that provenance.

## Appendix: direct file URLs

Every URL below returned the blob that this note cites. Append nothing. Line numbers are the decoded file, not the Gitiles HTML view. `+log` and `+blame` are login-walled and are not sources.

Framework default and persistence, `platform/frameworks/base`, `services/core/java/com/android/server/notification/PreferencesHelper.java`:

- android17-release, `true` / `hide_silent`: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/services/core/java/com/android/server/notification/PreferencesHelper.java
- android16-release, `false` / `hide_gentle`: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/notification/PreferencesHelper.java
- android-16.0.0_r3, `false` / `hide_gentle`: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r3/services/core/java/com/android/server/notification/PreferencesHelper.java
- android-16.0.0_r4, `true` / `hide_silent`: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r4/services/core/java/com/android/server/notification/PreferencesHelper.java
- android-17.0.0_r1, `true` / `hide_silent`: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-17.0.0_r1/services/core/java/com/android/server/notification/PreferencesHelper.java
- android-16.0.0_r1: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/services/core/java/com/android/server/notification/PreferencesHelper.java
- android-16.0.0_r2: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r2/services/core/java/com/android/server/notification/PreferencesHelper.java
- android11-release: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/services/core/java/com/android/server/notification/PreferencesHelper.java
- android-11.0.0_r1: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/services/core/java/com/android/server/notification/PreferencesHelper.java
- android-11.0.0_r48: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r48/services/core/java/com/android/server/notification/PreferencesHelper.java
- android12-release: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/notification/PreferencesHelper.java
- android-12.0.0_r1: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.0.0_r1/services/core/java/com/android/server/notification/PreferencesHelper.java
- android-12.1.0_r27: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.1.0_r27/services/core/java/com/android/server/notification/PreferencesHelper.java
- android13-release: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/services/core/java/com/android/server/notification/PreferencesHelper.java
- android-13.0.0_r1: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/services/core/java/com/android/server/notification/PreferencesHelper.java
- android-13.0.0_r84: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r84/services/core/java/com/android/server/notification/PreferencesHelper.java
- android14-release: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/notification/PreferencesHelper.java
- android-14.0.0_r1: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/services/core/java/com/android/server/notification/PreferencesHelper.java
- android-14.0.0_r75: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r75/services/core/java/com/android/server/notification/PreferencesHelper.java
- android15-release: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/services/core/java/com/android/server/notification/PreferencesHelper.java
- android-15.0.0_r1: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/core/java/com/android/server/notification/PreferencesHelper.java
- android-15.0.0_r36: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r36/services/core/java/com/android/server/notification/PreferencesHelper.java
- main, still `false` / `hide_gentle`: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/notification/PreferencesHelper.java

Dump printer and policy path, `NotificationManagerService.java`:

- android17-release, print at 8706, policy file at 3364 and 3394, setter at 4783: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/services/core/java/com/android/server/notification/NotificationManagerService.java
- android16-release, print at 7887, policy file at 2995 and 3026: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/notification/NotificationManagerService.java
- android-16.0.0_r4, print at 8244: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r4/services/core/java/com/android/server/notification/NotificationManagerService.java

Absence checks. A search of the decoded blob found no silent-status key:

- `Settings.java`, android17-release, 898487 bytes, no `hide_silent`, `hide_gentle`, `silent_status`, or `silent_icons`: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/core/java/android/provider/Settings.java
- `Settings.java`, android16-release, same negative search: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/provider/Settings.java
- `core/res/res/values/config.xml`, android17-release: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/core/res/res/values/config.xml
- `core/res/res/values/config.xml`, android16-release: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/res/res/values/config.xml
- SettingsProvider `defaults.xml`, android17-release: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/packages/SettingsProvider/res/values/defaults.xml
- notification `flags.aconfig`, android17-release: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/services/core/java/com/android/server/notification/flags.aconfig
- notification `flags.aconfig`, android16-release: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/notification/flags.aconfig
- notification `flags.aconfig`, android-16.0.0_r4: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r4/services/core/java/com/android/server/notification/flags.aconfig
- SystemUI `systemui.aconfig`, android17-release: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/packages/SystemUI/aconfig/systemui.aconfig
- `SystemUiDeviceConfigFlags.java`, android17-release: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/core/java/com/android/internal/config/sysui/SystemUiDeviceConfigFlags.java
- `Build.java` version codes, android17-release (`BAKLAVA = 36` at 1312, `CINNAMON_BUN = 37` at 1318): https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/core/java/android/os/Build.java

Settings UI, `platform/packages/apps/Settings`, android17-release:

- preference XML, key `silent_icons` at 197: https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/android17-release/res/xml/configure_notification_settings.xml
- controller: https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/android17-release/src/com/android/settings/notification/SilentStatusBarPreferenceController.java
- backend forwarder: https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/android17-release/src/com/android/settings/notification/NotificationBackend.java
- string at 9649: https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/android17-release/res/values/strings.xml

SystemUI listener, android17-release, lines 134 and 256: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/packages/SystemUI/src/com/android/systemui/statusbar/NotificationListener.java

Emulator overlays read, none containing the silent-status strings:

- cuttlefish SettingsProvider defaults, android17-release: https://android.googlesource.com/device/google/cuttlefish/+/refs/heads/android17-release/shared/overlays/SettingsProvider/res/values/defaults.xml
- same file, android16-release: https://android.googlesource.com/device/google/cuttlefish/+/refs/heads/android16-release/shared/overlays/SettingsProvider/res/values/defaults.xml
- cuttlefish core config, android17-release: https://android.googlesource.com/device/google/cuttlefish/+/refs/heads/android17-release/shared/overlays/core/res/values/config.xml
- cuttlefish phone core config: https://android.googlesource.com/device/google/cuttlefish/+/refs/heads/android17-release/shared/phone/overlays/core/res/values/config.xml
- cuttlefish Settings arrays overlay: https://android.googlesource.com/device/google/cuttlefish/+/refs/heads/android17-release/shared/phone/overlay/packages/apps/Settings/res/values/arrays.xml
- goldfish overlay directory listing, only `pixel_fold2` under `packages/overlays`: https://android.googlesource.com/device/generic/goldfish/+/refs/heads/android17-release/overlay/frameworks/base/packages/overlays/

Ref indexes used to see which branches exist. These pages list names; they are not file blobs:

- frameworks/base refs: https://android.googlesource.com/platform/frameworks/base/+refs
- device/google index: https://android.googlesource.com/device/google/
- cuttlefish refs, includes both release heads and r3/r4/17 tags: https://android.googlesource.com/device/google/cuttlefish/+refs
- goldfish refs: https://android.googlesource.com/device/generic/goldfish/+refs
- bluejay refs, `main` only among the names checked: https://android.googlesource.com/device/google/bluejay/+refs
- pantah refs: https://android.googlesource.com/device/google/pantah/+refs
- caimito refs: https://android.googlesource.com/device/google/caimito/+refs
- akita refs: https://android.googlesource.com/device/google/akita/+refs
- gs101 refs: https://android.googlesource.com/device/google/gs101/+refs
- `platform/vendor/google/` returned 404: https://android.googlesource.com/platform/vendor/google/

Tag and API table: https://source.android.com/docs/setup/reference/build-numbers

Local observed dump, API 36, line `hideSilentStatusBar=false`: `.omp-work/screenshots/status-icons/ticket04/44140344139/status-icons-api36-61f54ea3e4508ca0edcb9d2e036964085be3e207/03-enabled-default-notification.txt` in this checkout. The API 37 `true` value is the assignment's report of that capture, not a file this note re-opened.
