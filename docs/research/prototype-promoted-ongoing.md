# Promoted ongoing prototype facts

Sources fetched 2026-11-17. No device was exercised. OEM policy is explicitly extra and not covered here.

Official docs:

- Live Updates eligibility: https://developer.android.com/develop/ui/views/notifications/live-update
- Progress-centric notifications: https://developer.android.com/about/versions/16/features/progress-centric-notifications
- `Notification.Builder`: https://developer.android.com/reference/android/app/Notification.Builder
- `Notification`: https://developer.android.com/reference/android/app/Notification
- `NotificationManager`: https://developer.android.com/reference/android/app/NotificationManager
- `Manifest.permission`: https://developer.android.com/reference/android/Manifest.permission
- `Settings`: https://developer.android.com/reference/android/provider/Settings
- `NotificationChannel`: https://developer.android.com/reference/android/app/NotificationChannel

AOSP files, fetched as `?format=TEXT` from these branch URLs:

- Android 17 `Notification.java`: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/core/java/android/app/Notification.java
- Android 17 `NotificationManagerService.java`: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/services/core/java/com/android/server/notification/NotificationManagerService.java
- Android 17 `NotificationManager.java`: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/core/java/android/app/NotificationManager.java
- Android 16 `Notification.java`: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/app/Notification.java
- Android 16 `NotificationManagerService.java`: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/notification/NotificationManagerService.java
- Android 17 SystemUI chips: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/packages/SystemUI/src/com/android/systemui/statusbar/chips/

Line numbers below are from those fetched files on 2026-11-17, plus one tag check. A later commit on the same branch can move them. The symbol names are the stable anchors.

The public reference says `setRequestPromotedOngoing` was "Added in version 36.1". That label dates the method's documentation. It is not proof that the non-colorized eligibility predicate changed in 36.1. The fetched `android16-release` branch file has the older colorized predicate and does not declare the request method. The fetched `android17-release` branch file has the request method and the non-colorized predicate. Those are two branch snapshots, not a 36.1 rule. Tag `android-16.0.0_r4` was checked once: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r4/core/java/android/app/Notification.java. It declares both and selects at runtime with `Flags.uiRichOngoing()` (`Notification.java:3428-3450` on that tag). This note does not map that tag to API 36.1 and does not claim either branch is the current API 36 or API 37 device image. Capture the posted device's current API. Do not apply the Android 17 predicate to an Android 16 device, or the reverse, without reading that device's flag result.

Installed `androidx.core:core:1.19.1` was inspected locally with `javap` from the Gradle module cache (`~/.gradle/caches/modules-2/files-2.1/androidx.core/core/1.19.1/.../core-1.19.1.aar`). There is no external URL for that bytecode. `NotificationCompat.Builder.setRequestPromotedOngoing` writes `android.requestPromotedOngoing` with no `SDK_INT` guard. `setShortCriticalText` writes `android.shortCriticalText` only when `SDK_INT < 36`. The version pin is `gradle/libs.versions.toml` (`core = "1.19.1"`). The first AndroidX release that added the method was not established. "36.1" is only the platform reference's version label.

## Interface the publisher can call

Android 17 request, from `Notification.java` on `android17-release`:

```java
new Notification.Builder(context, channelId)
    .setContentTitle(title)          // required
    .setSmallIcon(icon)
    .setOngoing(true)                 // FLAG_ONGOING_EVENT
    .setRequestPromotedOngoing(true)  // extra only
    .setShortCriticalText("42%")      // chip text; suggested max 7
    .setStyle(new Notification.ProgressStyle() /* optional */)
    .setColorized(false)              // true makes hasPromotableCharacteristics false
    .setOnlyAlertOnce(true)
    .build();
```

`setRequestPromotedOngoing` writes `extras["android.requestPromotedOngoing"]` (`Notification.java:1838`, `:6044-6046` on `android17-release`). It is annotated `@FlaggedApi(Flags.FLAG_OPT_IN_RICH_ONGOING)`. The [Builder reference](https://developer.android.com/reference/android/app/Notification.Builder#setRequestPromotedOngoing(boolean)) says "Added in version 36.1". `setShortCriticalText` stores `extras["android.shortCriticalText"]` (`:1474`, `:5738-5740`) and the same reference says API 36.

The local 1.19.1 bytecode check is described above. It is not an external source. A search of the fetched `android16-release` `Notification.java` finds neither `EXTRA_REQUEST_PROMOTED_ONGOING` nor `setRequestPromotedOngoing`. That is true of that branch file only. Tag `android-16.0.0_r4` does declare both (`:1752`, `:5652-5654`). Its `hasPromotableCharacteristics` (`:3428-3450`) uses the request, non-colorized predicate only when `Flags.uiRichOngoing()` is true. Otherwise it uses the older predicate: ongoing, not a group summary, no custom views, a title, and then either ongoing `CallStyle` or colorized plus `hasPromotableStyle`. The 36.1 documentation label does not say which of those two branches a device runs. The Android 17 branch method that reads the extra is `isRequestPromotedOngoing` (`android17-release` `Notification.java:8997-8998`). Whether the fetched `android16-release` system reads an extra it does not declare is unknown. Do not describe the r4 runtime switch as the behavior of either moving branch.

Read back, do not infer:

- `Notification.isRequestPromotedOngoing()` reads the extra, default false (`android17-release` `Notification.java:8997-8998`).
- `Notification.hasPromotableCharacteristics()` is structural only (`:3738-3746`). True does not mean the system promoted it. False means it will not. The method comment says this explicitly (`:3733-3737`).
- `Notification.FLAG_PROMOTED_ONGOING` is `0x00040000` (`:790`). Apps cannot set it (`:782-788`). Read it from `NotificationManager.getActiveNotifications()`.
- `NotificationManager.canPostPromotedNotifications()` is the user/device gate. The [reference](https://developer.android.com/reference/android/app/NotificationManager#canPostPromotedNotifications()) says API 36. The Android 17 implementation delegates to `canBePromoted` (`NotificationManager.java:1258-1266`) and does not inspect one notification. The Android 16 implementation returns false unless `Flags.apiRichOngoing()` (`NotificationManagerService.java:4587-4591`).

Manifest, in addition to `POST_NOTIFICATIONS`. The [permission reference](https://developer.android.com/reference/android/Manifest.permission#POST_PROMOTED_NOTIFICATIONS) says "Added in version 36.1", protection level `normal|appop`, and that it is required in addition to `POST_NOTIFICATIONS`:

```xml
<uses-permission android:name="android.permission.POST_PROMOTED_NOTIFICATIONS" />
```

No source found says the default result of `canPostPromotedNotifications()` is true. On `android17-release`, the manifest declares `POST_PROMOTED_NOTIFICATIONS` as `normal|appop` with `android:featureFlag="android.app.api_rich_ongoing_permission"` (`core/res/AndroidManifest.xml:9113-9117`). `AppOpsManager` registers `OP_POST_PROMOTED_NOTIFICATIONS` (`"android:post_promoted_notifications"`) without `setDefaultMode`, so `AppOpInfo.Builder` keeps `mDefaultMode = MODE_DEFAULT` (`AppOpsManager.java:3623-3626`; `AppOpInfo.java:162`, `:175`). The fourth constructor argument there is `pccMode`, and the value passed is `MODE_ERRORED` (`AppOpInfo.java:175-183`). That is not a grant default. `PermissionChecker.PERMISSION_HARD_DENIED` is documented for the case where the app op is `MODE_DEFAULT` and the permission is denied (`core/java/android/content/PermissionChecker.java:91-99`). It does not say a `normal|appop` permission is granted while the op remains `MODE_DEFAULT`. Capture `canPostPromotedNotifications()` from the device. Do not treat the manifest protection level as a default grant. If that log is false, opening `ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS` is an optional supplement. Do not change the app-op mode from the app, and leave the existing silent channel settings untouched.

Settings intent is `Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS` (`android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS`), extra `Settings.EXTRA_APP_PACKAGE`. The [Settings reference](https://developer.android.com/reference/android/provider/Settings#ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS) says API 36 and warns that a matching activity may not exist. `canPostPromotedNotifications` points at this action. The Live Updates guide instead names `Settings.ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS`. That second constant was not present in the fetched `Settings` reference. Use the constant the manager reference names.

## Request versus promoted flag

`android17-release` `NotificationManagerService` clears `FLAG_PROMOTED_ONGOING` on every incoming notification (`:9743`). Later `fixNotificationWithChannel` may set it again (`:9885-9889`). `android16-release` clears it at `NotificationManagerService.java:8749` and sets it at `:8491-8497` only when `apiRichOngoing()` is true. The enclosing method names were not re-read. The flag lines were. `android17-release` `Notification.java` has no `apiRichOngoing()` or `uiRichOngoing()` call. `android16-release` `Notification.java:789` annotates the flag `@FlaggedApi(Flags.FLAG_API_RICH_ONGOING)`, and `:8115` defines `isPromotedOngoing()` as `Flags.uiRichOngoing() && (flags & FLAG_PROMOTED_ONGOING) != 0`. Android 17 `isPromotedOngoing()` is only the bit test (`Notification.java:8986-8987`). Android 17's request methods use `@FlaggedApi(Flags.FLAG_OPT_IN_RICH_ONGOING)` (`:1837`, `:6043`). `canBePromoted` on Android 16 returns false when `apiRichOngoing()` is false (`NotificationManagerService.java:4587-4591`). Those two flag names are the Android 16 rollout flags to record. Their on/off values are device state, not constants in these files.

Do not share one eligibility assertion across the two branches, and do not treat the r4 tag as the owner of either branch's rule. The fetched `android16-release` `hasPromotableCharacteristics` (`Notification.java:3297-3307`) does not read a request extra. The r4 tag's method does when `uiRichOngoing()` is true, and does not when it is false (`android-16.0.0_r4` `Notification.java:3428-3450`). The fetched `android17-release` method always uses the request predicate (`:3738-3746`). None of these files is labeled API 36.1. The public "Added in version 36.1" text was not matched to one of them. On the fetched `android16-release` file, `hasPromotableCharacteristics` returns false unless ongoing, not a group summary, no custom views, and a title. Ongoing `CallStyle` then returns true. Everything else must be colorized and pass `hasPromotableStyle` (`:3284-3290`: null style, `BigTextStyle`, `CallStyle`, or `ProgressStyle`). `MetricStyle` is absent. `setShortCriticalText` is present (`:5110-5112`), guarded by `@FlaggedApi(Flags.FLAG_API_RICH_ONGOING)`.

The server predicate `isPromotable` (`android17-release` `NotificationManagerService.java:9855-9867`) requires all of:

1. `hasPromotableCharacteristics()`
2. channel importance strictly greater than `IMPORTANCE_MIN`
3. `channel.isBundleChannel()` false

Only then it calls `checkPermissionForDataDelivery(POST_PROMOTED_NOTIFICATIONS)` (`:9885-9887`). Grant sets the flag (`:9889`). Denial leaves it clear. This note does not have a separate source saying every such call records an AppOp usage. Android 16's equivalent assignment also requires `hasPromotableCharacteristics()` and importance greater than `IMPORTANCE_MIN`, but it calls `mPreferencesHelper.canBePromoted` rather than `checkPermissionForDataDelivery` (`NotificationManagerService.java:8491-8497`).

`hasPromotableCharacteristics()` on `android17-release` (`Notification.java:3738-3746`) is:

```text
isRequestPromotedOngoing()
&& isOngoingEvent()
&& hasTitle()
&& hasPromotableStyle()
&& !isGroupSummary()
&& !containsCustomViews()
&& !isColorizedRequested()
```

`hasPromotableStyle()` (`:3721-3729`) accepts a null style, `BigTextStyle`, `CallStyle`, `MetricStyle`, or `ProgressStyle`. `ProgressStyle` is allowed, not required. A notification with no `setStyle` has a null template class and passes this check. `Builder.setProgress` (`:5748-5752`) only writes `EXTRA_PROGRESS`, `EXTRA_PROGRESS_MAX`, and `EXTRA_PROGRESS_INDETERMINATE`. It does not set a style. `InboxStyle`, `BigPictureStyle`, `MessagingStyle`, and `MediaStyle` are not in the accepted list, so they fail it.

`isGroupSummary()` is `mGroupKey != null && FLAG_GROUP_SUMMARY` (`:4958-4959`). A grouped non-summary child is not excluded by this method. `containsCustomViews()` is any content, big-content, heads-up, or public-version `RemoteViews` (`:3677-3684`). `setColorized(true)` fails the check. The [Live Updates guide](https://developer.android.com/develop/ui/views/notifications/live-update) says the same: not colorized, not a group summary, no custom content view, and not `IMPORTANCE_MIN`.

No foreground service, user-initiated job, category, or `setWhen` value appears in this predicate. The Live Updates guide's user-initiated and time-sensitive rules are usage criteria, not terms in `hasPromotableCharacteristics()`.

## Group experiment

Current publisher posts, per account, one `setGroupSummary(true)` notification and one child, both `setGroup(tag)`, `setOngoing(true)`, and `setSilent(true)` (`StatusIconPublisher.kt` in this worktree).

- Requesting promotion on the summary is the negative control. `hasPromotableCharacteristics()` is false because `isGroupSummary()` is true (`Notification.java:3743`), so `isPromotable` never sets the flag.
- The child is structurally eligible if the other Android 17 conditions hold. Nothing in `hasPromotableCharacteristics` or `isPromotable` excludes a grouped non-summary. That is the source fact. It does not establish that the child will be promoted or displayed as a Live Update.
- An ungrouped notification is a supplemental control, not a requirement established by these sources. Use it only with an explicit label when the experiment needs a posting that cannot be affected by group-summary handling. One such posting is enough. Three postings test display limits, not eligibility. The source only excludes the summary.

`setSilent(true)` is not grouping-neutral on `android17-release` (`Notification.java:5527-5549`). When `notificationSilentFlag()` is off and `mGroupKey` is empty, it calls `setGroup("silent")` (`GROUP_KEY_SILENT`, `:2011`). A summary sets `GROUP_ALERT_CHILDREN`. A non-summary sets `GROUP_ALERT_SUMMARY`. `fixSilentGroup()` (`:5035-5041`) clears a group key equal to `"silent"` and sets hidden `FLAG_SILENT` only when `notificationSilentFlag()` is on. If an ungrouped supplemental control calls `setSilent(true)`, read the posted `getGroup()`. `"silent"` means the platform grouped it. `setOnlyAlertOnce(true)` does not change the group. Its [reference text](https://developer.android.com/reference/android/app/Notification.Builder#setOnlyAlertOnce(boolean)) only covers sound, vibration, ticker, and stopping an ongoing alert. The current publisher already calls `setSilent(true)` on both grouped notifications. That is a fact about the existing path. It is not evidence that a silent grouped child fails `hasPromotableCharacteristics()`.

## Chips and records

NMS sets `FLAG_PROMOTED_ONGOING` independently on every notification that passes `isPromotable` (`NotificationManagerService.java:9880-9889`). No per-app numeric cap is in that method. One app can hold more than one promoted record.

Display, from `android17-release` SystemUI:

- `StatusBarNotificationChipsInteractor.kt` builds one chip flow per promoted notification (`:96-150`). Its comparator comment says older chips are hidden when there is not enough room (`:177-188`). File: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/packages/SystemUI/src/com/android/systemui/statusbar/chips/notification/domain/interactor/StatusBarNotificationChipsInteractor.kt
- `NotifChipsViewModel.filterByPackage()` keeps `chips[0]` after `groupBy { it.packageName }` (`:135-137`). The comment says this is so multiple chips are not shown for the same app (`:132-134`). File: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/packages/SystemUI/src/com/android/systemui/statusbar/chips/notification/ui/viewmodel/NotifChipsViewModel.kt
- `OngoingActivityChipsViewModel` has `MAX_VISIBLE_CHIPS = 3` (`:364`). The class comment still says one chip "for now" (`:52-53`). `rankChips` orders screen record, share-to-app, cast, call, then notification chips, and puts active chips into overflow once the three slots are filled (`:326-359`). A chip with `isHidden` does not decrement the slot count (`:347-348`). File: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/packages/SystemUI/src/com/android/systemui/statusbar/chips/ui/viewmodel/OngoingActivityChipsViewModel.kt
- `NotifChipsViewModel` hides a notification chip while its app is visible (`:375`).

Three promoted records can therefore exist while this app contributes at most one chip, and that chip shares three device-wide slots with screen record, share, cast, call, and other apps. Collect both `flags & FLAG_PROMOTED_ONGOING` and the visible chip count. A missing chip does not mean the flag was denied.

`setShortCriticalText` is chip content only (`Notification.java:5719-5736` and the [Builder reference](https://developer.android.com/reference/android/app/Notification.Builder#setShortCriticalText(java.lang.String))). Empty string forces no text. Null falls through to a critical metric, then `when`. The [Live Updates guide](https://developer.android.com/develop/ui/views/notifications/live-update) says a chip is at most 96dp: under 7 characters shows whole, and under half visible shows icon only. `setWhen` is not an eligibility input. `setShowWhen(false)` suppresses the when fallback. The guide also says the when text is hidden when `when` is in the past, and a countdown needs at least two minutes in the future.

## Channel importance

The [createNotificationChannel reference](https://developer.android.com/reference/android/app/NotificationManager#createNotificationChannel(android.app.NotificationChannel)) says an existing channel's importance changes only if the new importance is lower and the user has not altered settings on that channel. It cannot be raised through the same id. Name, description, and group may update. All other fields are ignored. [deleteNotificationChannel](https://developer.android.com/reference/android/app/NotificationManager#deleteNotificationChannel(java.lang.String)) says recreating the same id restores the deleted channel's previous settings. A new id is required to leave `status_icons`.

`status_icons` is created `IMPORTANCE_LOW` with sound null and vibration disabled (`StatusIconPublisher.kt`). The [importance constants](https://developer.android.com/reference/android/app/NotificationManager#IMPORTANCE_LOW) say `IMPORTANCE_LOW` shows in the shade and potentially in the status bar, without audible intrusion. `IMPORTANCE_DEFAULT` shows everywhere and makes noise, without visual intrusion. `IMPORTANCE_HIGH` makes noise and peeks. `IMPORTANCE_MIN` is shade-only, below the fold, and is the importance `isPromotable` rejects (`NotificationManagerService.java:9861-9862`). [shouldHideSilentStatusBarIcons](https://developer.android.com/reference/android/app/NotificationManager#shouldHideSilentStatusBarIcons()) applies to low-importance icons but is available only to a notification listener, so this app cannot read it.

[setOnlyAlertOnce](https://developer.android.com/reference/android/app/Notification.Builder#setOnlyAlertOnce(boolean)) limits sound, vibration, and ticker to the first posting and stops an ongoing alert. It does not mention importance, lock-screen visibility, or promotion. Channel sound, vibration, and lights are documented as channel fields. Whether a `DEFAULT` channel with sound null, vibration false, and lights false still peeks, shows on the lock screen, or makes a sound is not stated by these pages and was not run. [NotificationChannel.setImportance](https://developer.android.com/reference/android/app/NotificationChannel#setImportance(int)) says importance is modifiable only before the channel is submitted. That agrees with the create-channel rule above. It does not describe lock-screen behavior.

## Semantic eligibility

The [Live Updates guide](https://developer.android.com/develop/ui/views/notifications/live-update) says to use Live Updates for activities that are ongoing, user-initiated, and time-sensitive, with a distinct start and end. It names active navigation, phone calls, rideshare, and food delivery as appropriate, and ads, promotions, chat, alerts, upcoming calendar events, and quick access as inappropriate. Ambient information about the user's environment, interests, or upcoming events is excluded. A standing AI-quota percentage is ambient status under that wording. That is a reading of the guide, not a named prohibition of quota tracking.

`ProgressStyle` is one accepted style (`Notification.java:3728`), not a requirement. A `ProgressStyle` whose text says "usage 42%" can exercise the builder and flag path. It does not meet the guide's usage criteria. The experiment label should keep those results separate: structural `hasPromotableCharacteristics` versus semantic Live Update use. No FGS check appears in the predicate, and these sources give no eligibility benefit for adding one. Meeting the Java API 36.1 structural requirements without an FGS does not make the notification semantically eligible.

The same guide's Dismissal section says: "Don't repost Live Updates that the user dismissed. Use `setDeleteIntent` to detect dismissed updates." https://developer.android.com/develop/ui/views/notifications/live-update Restoring or reposting a dismissed Live Update from a cached quota snapshot conflicts with that instruction. A structural flag test can post once. It should not restore a user-dismissed promoted notification from cache and present that restore as Live Update behavior. The guide does not define an exception for cached content.

## Flags to record

Per posting: SDK, `canPostPromotedNotifications()`, channel id and `getImportance()`, `isRequestPromotedOngoing()`, `hasPromotableCharacteristics()`, `flags & FLAG_PROMOTED_ONGOING`, `getGroup()`, `FLAG_GROUP_SUMMARY`, and visible chip count.

On a device running the Android 16 branch, also record whether `apiRichOngoing` and `uiRichOngoing` are enabled. Their runtime values are not constants in `Notification.java`.

## Unknowns

- No runtime result on this host for flag assignment, chip count, or channel alerting.
- Whether a grouped non-summary child is promoted or visually demoted after passing `hasPromotableCharacteristics()` is not decided by the predicate. The predicate allows it. An ungrouped posting is a supplemental observation, not a documented requirement.
- `notificationSilentFlag()` state on a given device, which selects the `setSilent` group rewrite (`Notification.java:5543-5548`).
- OEM restrictions beyond AOSP. The Live Updates guide says OEMs can add criteria.
- Which platform file corresponds to the public "Added in version 36.1" label. The fetched `android16-release` file lacks the request method and uses the colorized predicate. The fetched `android17-release` file has the request method and the non-colorized predicate. Tag `android-16.0.0_r4` contains both predicates behind `Flags.uiRichOngoing()`. The 36.1 predicate is therefore not known from the version label alone.
- Which AndroidX release first added `NotificationCompat.Builder.setRequestPromotedOngoing`. Local `javap` of cached `androidx.core:core:1.19.1` shows the method. No external source for that bytecode was used. The platform reference dates the framework method to version 36.1. That does not date this artifact. The `android16-release` file fetched here does not declare the platform method. The `android17-release` file does. Tag `android-16.0.0_r4` does too.
