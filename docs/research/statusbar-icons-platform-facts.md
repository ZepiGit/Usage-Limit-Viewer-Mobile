# Status bar icons: platform facts

Research for a planned glanceable "quota left" surface: at most three pinned accounts, each a simplified provider glyph plus a two-digit percent, refreshed after every usage sync. This app has no server.

App targets, read from the repo on 16 November 2026:

- Android `minSdk = 26`, `targetSdk = 36`, `compileSdk = 36` (`app/build.gradle.kts`).
- iOS deployment target `16.0` (`ios/UsageLimits/project.yml`).

Sources are developer.android.com, AOSP (`android.googlesource.com`, `main` as fetched on 16 November 2026), and developer.apple.com. Inferences are marked `[INFERENCE]`.

## 1. Small icon rules

The status bar shows the notification's small icon, set with `setSmallIcon()`. It is required. The large icon is a separate, optional shade element and is not the status bar glyph. ([About notifications](https://developer.android.com/develop/ui/compose/notifications))

The design guide says the app icon "appears in monochrome in the status bar," and that an app with several notification types should replace the app icon with a symbol that distinguishes them. Starting in Android 12 (API 31), the system derives the icon colour from `setColor()`; if the app sets none, it uses the system theme colour. Previously the colour was grey. The same page says that, for most styles, this colour is applied only when the notification is a foreground-service notification; `MediaStyle` and `DecoratedMediaCustomViewStyle` with a media session are the stated exceptions. ([Notifications design guide](https://developer.android.com/design/ui/mobile/guides/home-screen/notifications))

Current AOSP SystemUI does not draw the bitmap's own colours. `StatusBarIconView.updateTintMatrix` builds a colour matrix that replaces RGB with one colour and keeps only the source alpha, then applies it as a `ColorMatrixColorFilter`. The comment on `setStaticDrawableColor` calls that colour "the static color that should be used for the drawable." Conversation icons are the exception and get no filter. ([StatusBarIconView.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/src/com/android/systemui/statusbar/StatusBarIconView.java), `main`, 16 November 2026)

So a coloured glyph-plus-digits bitmap will not survive: only its alpha mask is drawn, in one system-chosen colour. [INFERENCE] Provider brand colours cannot distinguish the three accounts in the status bar; shape has to.

Sizes, from AOSP `main` on the same date, not from a developer-guide table:

| Resource | Value | Where |
|---|---|---|
| `status_bar_icon_drawing_size` | 15 dp | SystemUI `res/values/dimens.xml` |
| `status_bar_icon_size` | 22 dp | framework `core/res/res/values/dimens.xml` |
| `status_bar_icon_drawing_alpha` | 90% | SystemUI `res/values/dimens.xml` |
| `notification_small_icon_size` | 48 dp | framework `core/res/res/values/dimens.xml` |

`StatusBarIconView.getIcon` downscales any loaded drawable to `notification_small_icon_size` (48 dp square) "to protect against applications using too much memory," and says drawables already at that size are not touched. The drawn status-bar slot is the 15 dp drawing size. ([dimens.xml](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/res/values/dimens.xml), [core dimens.xml](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/res/values/dimens.xml), [StatusBarIconView.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/src/com/android/systemui/statusbar/StatusBarIconView.java))

[INFERENCE] 15 dp is the size a two-digit percent has to stay legible at. 48 dp is only the pre-scale cap. Two digits plus a glyph inside 15 dp will not be readable; the bitmap should be the digits alone, or a single distinctive shape.

Pixel sizes for a bitmap follow `px = dp * (dpi / 160)`. The documented buckets are mdpi ~160 dpi (1.0), hdpi ~240 (1.5), xhdpi ~320 (2.0), xxhdpi ~480 (3.0), xxxhdpi ~640 (4.0). ([Support different pixel densities](https://developer.android.com/training/multiscreen/screendensities))

| Bucket | Factor | 15 dp drawing size | 48 dp downscale cap |
|---|---|---|---|
| mdpi | 1.0 | 15 px | 48 px |
| hdpi | 1.5 | 23 px | 72 px |
| xhdpi | 2.0 | 30 px | 96 px |
| xxhdpi | 3.0 | 45 px | 144 px |
| xxxhdpi | 4.0 | 60 px | 192 px |

The 15 dp column is rounded to the nearest pixel. [INFERENCE] Author the bitmap at the 48 dp cap (or larger and let SystemUI downscale) so the 15 dp draw still has enough pixels; a 15 px mdpi source scaled up will blur.

`Icon.createWithBitmap(Bitmap)` (API 23) "create[s] an Icon pointing to a bitmap in memory." The reference requires a valid `Bitmap` and does not state a size, a config, or an alpha-only rule. `IconCompat.createWithBitmap` says the same. ([Icon](https://developer.android.com/reference/android/graphics/drawable/Icon#createWithBitmap(android.graphics.Bitmap)), [IconCompat](https://developer.android.com/reference/androidx/core/graphics/drawable/IconCompat#createWithBitmap(android.graphics.Bitmap)))

`setSmallIcon(Icon)` (the overload that takes an `Icon`) "set[s] the small icon, which will be used to represent the notification in the status bar." ([Notification.Builder](https://developer.android.com/reference/android/app/Notification.Builder#setSmallIcon(android.graphics.drawable.Icon)))

Image Asset Studio generates notification icons per density and, since Android 3, a white icon; its padding slider runs from -10% to 50% after an optional trim. It does not publish a safe-area inset. ([Create app icons](https://developer.android.com/studio/write/create-app-icons))

No developer.android.com page documents SystemUI crashes from bitmap small icons, OEM crashes, status-bar icon caching, or a RemoteViews byte limit that applies to the small icon. `RemoteViews` is the shade content view, not the status-bar glyph, and a promoted ongoing notification must not set a custom content view at all (see section 6). The only size guard found in source is the 48 dp downscale above. Unverifiable from the primary sources: crash reports and OEM deviations.

## 2. One icon per notification, and grouping

A notification "appears as an icon in the status bar." The wording is per notification, not per app. Current SystemUI agrees: `NotificationIconsInteractor.filteredNotifSet` filters the set of active notifications, and each surviving notification contributes its own icon. `StatusBarNotificationIconsInteractor` hides dismissed and replied-message notifications, and hides low-priority ones unless the user setting `showSilentStatusIcons` is on. ([About notifications](https://developer.android.com/develop/ui/compose/notifications), [NotificationIconsInteractor.kt](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/src/com/android/systemui/statusbar/notification/icon/domain/interactor/NotificationIconsInteractor.kt))

Grouping changes the shade, and the docs describe the shade rather than the status bar:

- Android 7.0 (API 24) and higher can show a group. Below 7.0 only the summary is shown. ([Create a group of notifications](https://developer.android.com/develop/ui/views/notifications/group))
- "If your app sends four or more notifications and doesn't specify a group, the system automatically groups them" on Android 7.0 and higher. A second page says "four or more" as well. ([Group](https://developer.android.com/develop/ui/views/notifications/group), [About notifications](https://developer.android.com/develop/ui/compose/notifications))
- A group needs a summary (`setGroupSummary(true)`). On API 24 and higher the system shows the summary as the nested group and does not display the summary's own text. Children can still be expanded. ([Group](https://developer.android.com/develop/ui/views/notifications/group))

AOSP `main` does not match the "four" in that guide. `NotificationManagerService` reads `config_autoGroupAtCount` and passes it to `GroupHelper`. The shipped value is 2: `GroupHelper.maybeGroup` autogroups ungrouped children when `children.size() >= mAutoGroupAtCount`. The summary is posted under the key `ranker_group` with `FLAG_AUTOGROUP_SUMMARY | FLAG_GROUP_SUMMARY | FLAG_LOCAL_ONLY`. ([config.xml](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/res/values/config.xml), [GroupHelper.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/notification/GroupHelper.java), [NotificationManagerService.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/notification/NotificationManagerService.java))

[INFERENCE] Three ungrouped notifications from this app meet that threshold, so current AOSP will autogroup them. The guide's "four" is stale relative to this tree. Whether the status bar then keeps one icon per child or collapses to the summary is not stated in the docs or in the icon interactor, which only sees "top level representative" notifications. Treat "three separate status-bar icons while autogrouped" as unverified.

Putting all three in one explicit group with a summary collapses the shade to one row. The docs do not say this also collapses the status-bar icons. [INFERENCE] It is the wrong direction if the goal is three icons.

No AOSP path found here shows that distinct group keys disable autogrouping. `maybeGroup` only counts children that are not already grouped, so children that already carry their own group key are not in the ungrouped set. [INFERENCE] One group key per account, and no summary, is the configuration most likely to keep three shade rows and three icons. That still needs a device check: the guide says a summary is required "to enable grouped notifications," and it does not say what a group without a summary does.

## 3. Status bar overflow

There is no documented fixed count. Current SystemUI decides per frame:

- `NotificationIconContainer` reads `max_notif_static_icons` = 4 ("maximum number of icons in the status bar when also showing overflow dot"), `max_notif_icons_on_aod` = 3, and `max_notif_icons_on_lockscreen` = 3. ([integers.xml](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/res/values/integers.xml))
- `isOverflowing` returns true when the next icon does not fit the container's width. For any icon that is not the last child it reserves two icon widths, one for the icon and one for the dot. Overflowing icons render as one dot and the rest are hidden. The binder comment says icons past the max "will render as an overflow dot." ([NotificationIconContainer.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/src/com/android/systemui/statusbar/phone/NotificationIconContainer.java), [NotificationIconContainerViewBinder.kt](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/src/com/android/systemui/statusbar/notification/icon/ui/viewbinder/NotificationIconContainerViewBinder.kt))

[INFERENCE] On a narrow phone or with a camera cutout, three icons can already fall past the width check even though 4 is the configured cap. The cap is not a promise of three visible icons.

Ordering that an app can influence:

- `setSortKey` "orders this notification among other notifications from the same package." It does not order against other apps. ([Notification.Builder.setSortKey](https://developer.android.com/reference/android/app/Notification.Builder#setSortKey(java.lang.String)))
- The group guide says notifications are sorted by post time by default, and `setSortKey` changes that order. ([Group](https://developer.android.com/develop/ui/views/notifications/group))
- `setWhen` sets the timestamp shown for the notification. It is not documented as a status-bar sort key. The same timestamp feeds a Live Update chip countdown (section 6). ([Notification.Builder.setWhen](https://developer.android.com/reference/android/app/Notification.Builder#setWhen(long)))

No public API documents control over status-bar icon order against other apps' icons. Priority and channel importance decide interruption, not that order (section 4).

## 4. Channel importance

On Android 8.0 (API 26) and higher, channel importance decides behaviour. This app's `minSdk` is 26, so the pre-8.0 `setPriority` path does not apply. The user-visible levels: ([Create and manage notification channels](https://developer.android.com/develop/ui/compose/notifications/channels))

| Constant | User-visible level | Documented behaviour |
|---|---|---|
| `IMPORTANCE_HIGH` | Urgent | Sound and heads-up |
| `IMPORTANCE_DEFAULT` | High | Sound |
| `IMPORTANCE_LOW` | Medium | No sound |
| `IMPORTANCE_MIN` | Low | No sound, and "doesn't appear in the status bar" |
| `IMPORTANCE_NONE` | None | No sound, not in the status bar or the shade |

`IMPORTANCE_LOW` is the one the two sources describe differently. The channel guide's table puts "doesn't appear in the status bar" on `IMPORTANCE_MIN` only. The `NotificationManager` reference says `IMPORTANCE_LOW` "shows in the shade, and potentially in the status bar (see `shouldHideSilentStatusBarIcons()`), but is not audibly intrusive," and that `IMPORTANCE_MIN` "only shows in the shade, below the fold." `shouldHideSilentStatusBarIcons()` "returns whether the user wants silent notifications (`IMPORTANCE_LOW`) to appear in the status bar." ([NotificationManager](https://developer.android.com/reference/android/app/NotificationManager#IMPORTANCE_LOW), [shouldHideSilentStatusBarIcons()](https://developer.android.com/reference/android/app/NotificationManager#shouldHideSilentStatusBarIcons()))

AOSP matches the reference: `StatusBarNotificationIconsInteractor` includes low-priority icons only when `showSilentStatusIcons` is true. ([NotificationIconsInteractor.kt](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/src/com/android/systemui/statusbar/notification/icon/domain/interactor/NotificationIconsInteractor.kt))

[INFERENCE] `IMPORTANCE_LOW` is the right channel for a silent, ongoing quota icon, with the caveat that a user (or OEM default) who hides silent icons removes it. `IMPORTANCE_MIN` never shows a status-bar icon. `IMPORTANCE_DEFAULT` would make a sound on every new post.

After the channel is created, the app cannot change its importance. The user can change the channel at any time. Recreating a channel with a lower importance only takes effect when the user has not already changed it. ([Channels](https://developer.android.com/develop/ui/compose/notifications/channels), [NotificationManager.createNotificationChannel](https://developer.android.com/reference/android/app/NotificationManager#createNotificationChannel(android.app.NotificationChannel)))

`setShowBadge(false)` stops that channel's notifications from appearing as launcher icon badges. The badge guide recommends this for ongoing notifications. It does not affect the status-bar icon. Badges exist on Android 8.0 and higher. ([Notification badges](https://developer.android.com/develop/ui/views/notifications/badges), [NotificationChannel.setShowBadge](https://developer.android.com/reference/android/app/NotificationChannel#setShowBadge(boolean)))

## 5. Ongoing notifications on Android 14+, and POST_NOTIFICATIONS

`setOngoing(true)` sets `FLAG_ONGOING_EVENT`. The builder summary says ongoing notifications "cannot be dismissed by the user on locked devices, or by notification listeners," and that call, device-management, and media notifications cannot be dismissed on unlocked devices either. ([Notification.Builder.setOngoing](https://developer.android.com/reference/android/app/Notification.Builder#setOngoing(boolean)))

Android 14 changed that for every app, independent of `targetSdk`. `FLAG_ONGOING_EVENT` notifications became dismissible, except: the phone is locked; the user taps Clear all; `CallStyle`; device-policy controller and its supporting packages; media notifications; the default Search Selector package. ([Behavior changes: all apps, Android 14](https://developer.android.com/about/versions/14/behavior-changes-all))

The design guide still says a foreground service "must" show a notification the user cannot dismiss. That sentence disagrees with the Android 14 behaviour change for ordinary ongoing notifications. ([Notifications design guide](https://developer.android.com/design/ui/mobile/guides/home-screen/notifications)) [INFERENCE] A quota notification is not in the exception list, so on Android 14 and higher the user can swipe it away while unlocked.

`setDeleteIntent` "supplies a PendingIntent to send when the notification is cleared explicitly by the user." ([Notification.Builder.setDeleteIntent](https://developer.android.com/reference/android/app/Notification.Builder#setDeleteIntent(android.app.PendingIntent)))

The Live Update guide is the only page that says what to do next: "Don't repost Live Updates that the user dismissed. Use `setDeleteIntent` to detect dismissed updates." ([Live updates](https://developer.android.com/develop/ui/compose/notifications/live-update)) No page states a general rule for reposting an ordinary ongoing notification. [INFERENCE] Reposting a dismissed quota notification on the next sync is allowed by the API and is what keeps the icon alive, but the Live Update guidance treats reposting as something that gets the app's permission revoked. Stay off that surface (section 6) and repost the plain notification.

`POST_NOTIFICATIONS` is a runtime permission on Android 13 (API 33) and higher. This app targets 36, so:

- New installs on Android 13 and higher have notifications off until the user grants the permission. The app chooses when the dialog appears.
- A target of 12L or lower would have the system show the dialog on the first activity start after the first channel is created. Not this app.
- Upgrades from 12L or lower are pre-granted if the app already had a channel and the user had not turned notifications off. That denial survives the upgrade.
- Denying once does not by itself suppress the dialog. The permission guide says that if the user taps Deny more than once for a permission during the install lifetime, the system dialog is not shown again. One denial flags `USER_SET`; a second denial flags `USER_FIXED`. The notification page adds that, for an app targeting 12L or lower, a single Don't allow is already permanent. That special case does not apply at target 36.
- Swiping the dialog away leaves the permission unchanged.
- With the permission denied, foreground-service notices still appear in Task Manager but not in the notification drawer. Media sessions and self-managed phone calls are exempt. A quota notification is not. ([Notification runtime permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission), [Request runtime permissions](https://developer.android.com/training/permissions/requesting))

## 6. Android 16 Live Updates

Android 16 adds progress-centric notifications (`Notification.ProgressStyle`) "to help users track user-initiated, start-to-end journeys." The stated use cases are rideshare, delivery, and navigation. ([Progress-centric notifications](https://developer.android.com/about/versions/16/features/progress-centric-notifications))

A promoted ongoing notification (the "Live Update") must meet every rule: standard style, `BigTextStyle`, `CallStyle`, `ProgressStyle`, or `MetricStyle`; the manifest permission `POST_PROMOTED_NOTIFICATIONS`; `EXTRA_REQUEST_PROMOTED_ONGOING` or `setRequestPromotedOngoing(true)`; `setOngoing(true)`; a content title; no custom content view; not a group summary; not colourized; channel importance other than `IMPORTANCE_MIN`. `canPostPromotedNotifications()` reports the user's setting. OEMs may add criteria. ([Live updates](https://developer.android.com/develop/ui/compose/notifications/live-update))

The same page limits the feature to activities that are ongoing, user-initiated, and time-sensitive, with a distinct start and end. "Don't use Live Updates to offer accelerated access to app functionality. If you want to do this, use an app widget or a custom Quick Settings tile." Inappropriate uses listed include "alerts" and "quick access to app features." Package tracking is called out as not appropriate because the user does not need to watch it constantly. "Don't repost Live Updates that the user dismissed."

The status chip always includes the small icon and optionally text from `setShortCriticalText` or `setWhen`. Its maximum width is 96 dp. Text under 7 characters is shown whole; otherwise the chip drops to the icon, or shows the longest prefix that still covers more than half the string. ([Live updates](https://developer.android.com/develop/ui/compose/notifications/live-update), [setShortCriticalText](https://developer.android.com/reference/android/app/Notification.Builder#setShortCriticalText(java.lang.String)))

How many chips can be on screen at once is not stated. Unverifiable from the docs.

[INFERENCE] A standing "42% of the weekly quota left" notification fails the criteria: it has no end, the user did not start a journey, and it is exactly the "quick access" case the page redirects to a widget. `ProgressStyle` is a journey bar with segments and points, not a percent badge. Live Updates are a worse fit than a plain small icon, and they add a second permission the user can revoke for good.

## 7. Silent updates and rate limits

`notify(tag, id, notification)` with the same tag and id replaces the existing notification. The reference's own example is replacing the status-bar icon. ([NotificationManager](https://developer.android.com/reference/android/app/NotificationManager))

`setOnlyAlertOnce(true)` "set[s] this flag if you would only like the sound, vibrate and ticker to be played if the notification is not already showing." ([Notification.Builder.setOnlyAlertOnce](https://developer.android.com/reference/android/app/Notification.Builder#setOnlyAlertOnce(boolean)))

No developer.android.com page states a rate limit on `notify()` updates. Unverifiable from the docs. The shade guide's only related advice is to update an existing notification rather than post a new one. ([About notifications](https://developer.android.com/develop/ui/compose/notifications))

[INFERENCE] One id per account, `setOnlyAlertOnce(true)`, and an update after each sync is the documented mechanism. A 15-minute WorkManager period is far slower than anything a rate limit would plausibly police, but that comparison is not backed by a cited limit.

## 8. Lock screen and always-on

Since Android 5.0, notifications can appear on the lock screen. The app sets the detail level with `setVisibility`; the user can hide lock-screen notifications globally, and since Android 8.0 per channel. ([About notifications](https://developer.android.com/develop/ui/compose/notifications))

The lock screen and the always-on display do not reuse the status-bar drawing size. SystemUI caps notification icons at 3 on the lockscreen and 3 on the always-on display (`max_notif_icons_on_lockscreen`, `max_notif_icons_on_aod`), against 4 in the status bar. ([integers.xml](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/res/values/integers.xml))

[INFERENCE] Those are the same small icons as the status bar, so the alpha-only and 15 dp constraints apply there too. Three accounts already equal the lockscreen and always-on caps, before any other app's icons. Digits that are marginal at 15 dp are worse on an always-on display, which SystemUI draws with a doze amount that blends the icon colour toward white (`StatusBarIconView.updateIconColor`). ([StatusBarIconView.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/src/com/android/systemui/statusbar/StatusBarIconView.java))

`setPublicVersion` supplies a separate notification "whose contents should be shown in insecure contexts." ([Notification.Builder.setPublicVersion](https://developer.android.com/reference/android/app/Notification.Builder#setPublicVersion(android.app.Notification))) [INFERENCE] A quota percent on the lock screen is visible to anyone who can see the phone. Ship a redacted public version if the percent counts as private.

## 9. Third-party apps cannot add status bar items

The Human Interface Guidelines page for status bars describes one system-drawn bar: time, cellular carrier, Wi-Fi, battery. The best-practice actions offered to an app are obscuring content beneath it, hiding it during full-screen media, and not hiding it permanently. Nothing on the page is an API for adding an item. The platform note says there are "no additional considerations for iOS or iPadOS." ([Status bars](https://developer.apple.com/design/human-interface-guidelines/status-bars))

The only developer symbol that page links is `UIStatusBarStyle` and `preferredStatusBarStyle`, which set the bar's appearance, not its contents. ([Status bars](https://developer.apple.com/design/human-interface-guidelines/status-bars))

[INFERENCE] There is no supported way for this app to place a glyph in the iOS status bar. The HIG does not spell that out as a prohibition; it simply defines the bar as system content and publishes no insertion API. Live Activities, Lock Screen widgets, and controls are the documented glanceable surfaces.

## 10. Live Activities

A Live Activity "can be active for up to eight hours unless its app or a person ends it before this limit. After the eight-hour limit, the system automatically ends the Live Activity, and immediately removes it from the Dynamic Island. However, the Live Activity remains on the Lock Screen until a person removes it or for up to four additional hours before the system removes it, whichever comes first. As a result, a Live Activity remains on the Lock Screen for a maximum of 12 hours." The HIG repeats the guidance: use them for tasks with a defined beginning and end that do not exceed eight hours, and end them when the event ends. ([Displaying live data with Live Activities](https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities), [Live Activities HIG](https://developer.apple.com/design/human-interface-guidelines/live-activities))

Presentations:

- One ongoing Live Activity uses the compact leading and trailing presentation.
- "When multiple Live Activities are active, the system uses the minimal presentation to display up to three of them in the Dynamic Island. One appears attached to the Dynamic Island, while a second (and third, when supported) appear detached." The API article's current wording is device-specific: "iPhone 18 Pro displays two simultaneous Live Activities in the Dynamic Island" in a reduced-width compact presentation, and "on other devices, both activities appear in the minimal presentation." ([Live Activities HIG](https://developer.apple.com/design/human-interface-guidelines/live-activities), [Displaying live data](https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities))
- The app must supply compact, minimal, expanded, and Lock Screen presentations. Lock Screen height past 160 points may be truncated. ([Displaying live data](https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities))
- Several Live Activities from one app: "you can tell the system which one it should display by setting a relevance score." `relevanceScore` "determines which of your Live Activities appears in the Dynamic Island and the order of your Live Activities on the Lock Screen." ([Displaying live data](https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities))

No page states a numeric cap on how many Live Activities one app may have started at once. Unverifiable. The HIG's advice cuts the other way: "let people track multiple events efficiently with a single Live Activity" instead of separate ones the person has to move between. ([Live Activities HIG](https://developer.apple.com/design/human-interface-guidelines/live-activities))

Updates without a server:

- "To update the dynamic data of an active Live Activity, use ActivityKit in your app or allow your Live Activities to receive ActivityKit push notifications." A Live Activity cannot access the network itself. Combined static and dynamic data, including push payloads, cannot exceed 4 KB. ([Displaying live data](https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities))
- `Activity.update` is the in-app path. The push article says remote start, update, and end all go through a server and APNs. ([Starting and updating Live Activities with ActivityKit push notifications](https://developer.apple.com/documentation/activitykit/starting-and-updating-live-activities-with-activitykit-push-notifications))
- On iOS and iPadOS 17.1 and earlier, an app cannot start a Live Activity with a push. Push-to-start uses `pushToStartTokenUpdates` and an APNs payload with `event` set to `start`. That path requires a push server. ([Push notifications](https://developer.apple.com/documentation/activitykit/starting-and-updating-live-activities-with-activitykit-push-notifications))

[INFERENCE] With no server, the only update path is `Activity.update` while the app is running, including time the system has already given it. BGAppRefresh is not named by ActivityKit as a Live Activity update mechanism. Whether a BGAppRefresh launch may call `Activity.update` is not stated. Starting from the background is the push-to-start feature and needs APNs, so a refresh cannot start one.

`staleDate` "tells the system when the Live Activity content becomes outdated." The push article says an update "optionally set[s] the `stale-date`" "to mark a Live Activity as outdated." The sample reads `context.isStale` and branches its view on it. ([Displaying live data](https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities), [Push notifications](https://developer.apple.com/documentation/activitykit/starting-and-updating-live-activities-with-activitykit-push-notifications)) What the system changes visually at that date, beyond the app's own `isStale` branch, is not specified in the pages fetched.

Apple Watch: the HIG says a Live Activity started on iPhone "automatically appears in system locations across a person's devices," and lists Apple Watch Smart Stack as one of those locations. The compact presentation "appears in the Dynamic Island on iPhone and [is] combine[d] into a single view for Apple Watch." iOS 18 is not named as the version that introduced the mirroring. ([Live Activities HIG](https://developer.apple.com/design/human-interface-guidelines/live-activities))

## 11. Lock Screen accessories and controls

`accessoryCircular`, `accessoryRectangular`, and `accessoryInline` exist on iOS 16.0 and later, which matches this app's deployment target. `accessoryCircular` "can appear as a complication in watchOS, or on the Lock Screen in iOS and iPadOS." iPad Lock Screen widgets need iPadOS 17 or later. ([accessoryCircular](https://developer.apple.com/documentation/widgetkit/widgetfamily/accessorycircular))

`WidgetRenderingMode.vibrant` "desaturates the widget, making a monochrome version that it uses to create an adaptive, vibrant effect," and "the system displays vibrant widgets on the Lock Screen on iPhone." It is available on iOS 16.0 and later. ([vibrant](https://developer.apple.com/documentation/widgetkit/widgetrenderingmode/vibrant))

[INFERENCE] A provider glyph drawn in brand colour is flattened on the Lock Screen. The percent has to be readable in monochrome, same as the Android status bar.

Reload budget, from the WidgetKit guide:

- A widget's budget covers a 24-hour window aligned to the user's usage, not midnight. A widget the user views often typically gets 40 to 70 refreshes a day, roughly every 15 to 60 minutes.
- Timeline entries should be at least about 5 minutes apart. WidgetKit imposes a minimum interval and may coalesce reloads.
- Reloads are not counted while the containing app is in the foreground, during an active audio or navigation session, while it is the Now Playing app, when the widget runs an app intent or an animation, or on locale, Dynamic Type, or accessibility changes. The system also refreshes a rarely viewed widget when it becomes visible.
- `WidgetCenter.reloadTimelines(ofKind:)` is how the app "tell[s] WidgetKit to request a new timeline" when data changes. The guide does not single out BGAppRefresh, and it does not say a background launch is budget-free. The foreground exemption is the one it names. ([Keeping a widget up to date](https://developer.apple.com/documentation/widgetkit/keeping-a-widget-up-to-date), [reloadTimelines(ofKind:)](https://developer.apple.com/documentation/widgetkit/widgetcenter/reloadtimelines(ofkind:)))

[INFERENCE] A BGAppRefresh that calls `reloadTimelines` still spends budget. A 15-minute refresh cadence is inside the "at least 5 minutes" floor but far above the 40 to 70 daily budget, so most of those reloads will be deferred. The percent on the Lock Screen can lag the sync.

How many accessory widgets a user can place, and how many slots sit below or above the clock, is not stated on the WidgetKit pages fetched. Unverifiable from those docs.

Always-On: the Live Activities HIG says to check contrast "on a device with an Always-On display with reduced luminance because the system adapts colors as needed," and that the system does not run Live Activity animations on an Always-On display with reduced luminance. It does not give a separate rule for accessory widgets. ([Live Activities HIG](https://developer.apple.com/design/human-interface-guidelines/live-activities))

Controls: the WidgetKit control APIs are an iOS 18 surface, so they are not available at this app's iOS 16 deployment target. The symbol page for `ControlWidget` did not resolve during this research, so the control-specific claims are not cited here. Unverifiable in this pass.

## 12. Other facts that change the design

- Android 13 and higher: notifications are off by default on a new install. The icon cannot appear until the user grants `POST_NOTIFICATIONS`. Ask in context, not at first launch. ([Notification runtime permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission))
- The design guide lists "operations that don't require user involvement, such as syncing information" under "when not to use a notification." A permanent quota notification is close to that case. The page does not define an exception for a user-pinned status icon. ([Notifications design guide](https://developer.android.com/design/ui/mobile/guides/home-screen/notifications)) [INFERENCE] Pinning has to be an explicit user action, and the notification copy has to say what it is, or this reads as the sync notification the guide forbids.
- Live Activities also land on the paired Mac menu bar, CarPlay dashboard, and Apple Watch Smart Stack without extra work. CarPlay deactivates buttons and toggles. ([Live Activities HIG](https://developer.apple.com/design/human-interface-guidelines/live-activities))
- The HIG forbids using a Live Activity for ads, and tells apps not to display sensitive information on the Lock Screen or Always-On display. A quota percent is visible to anyone looking at the phone. ([Live Activities HIG](https://developer.apple.com/design/human-interface-guidelines/live-activities))
- Android routes every notification to a paired Wear OS device unless `setLocalOnly(true)` is set. ([About notifications](https://developer.android.com/develop/ui/compose/notifications))

## Design implications

- Android can show one status-bar icon per notification. Three ungrouped notifications already hit AOSP's autogroup threshold of 2, so grouping has to be defeated deliberately and then checked on a device.
- The status bar draws the small icon as a 15 dp alpha mask in one colour. A coloured glyph plus two digits will not survive; use a distinct silhouette per provider, and put the percent in the shade text.
- Author the bitmap at the 48 dp cap. `Icon.createWithBitmap` adds no size or format rule of its own.
- Use one `IMPORTANCE_LOW` channel with `setShowBadge(false)`. `IMPORTANCE_MIN` never shows a status-bar icon, and `IMPORTANCE_LOW` can still be hidden by the user's silent-icon setting.
- On Android 14 and higher the user can dismiss the ongoing notification while the phone is unlocked. Detect that with `setDeleteIntent`. Repost on the next sync for a plain notification; do not repost if you ever use Live Updates.
- Do not use Android 16 Live Updates or `ProgressStyle`. A standing quota is not a user-initiated, start-to-end journey, and the guide points that case at a widget.
- Update with the same notification id and `setOnlyAlertOnce(true)`. No published rate limit was found.
- The lockscreen and always-on display cap notification icons at 3, so three accounts fill that row by themselves.
- iOS has no status-bar item API. A Live Activity lasts at most 8 hours and cannot be started or reliably updated from BGAppRefresh without a push server. It is a poor fit for an all-day quota.
- The durable iOS surface is the Lock Screen accessory widgets the app already has. They render vibrant (monochrome) on iPhone, and `reloadTimelines` from a background refresh spends a budget of roughly 40 to 70 reloads a day.
- Ask for `POST_NOTIFICATIONS` in context. A second denial on Android 13 and higher suppresses the system dialog for the rest of the install.
- Treat the percent as visible to bystanders on the lock screen, always-on display, Dynamic Island, and a paired watch. Offer a redacted `setPublicVersion` and an in-app way to turn the surface off.

## Open / could not verify

- No developer.android.com page documents SystemUI crashes, OEM crashes, or status-bar icon caching caused by `Icon.createWithBitmap`. Only the 48 dp downscale in `StatusBarIconView` was found.
- Whether autogrouping (or an explicit group plus summary) collapses several status-bar icons to one. The docs describe the shade. The icon interactor consumes "top level representative" notifications, which is suggestive and not proof.
- A fixed, public number for how many status-bar icons show before the overflow dot. AOSP uses width plus a default cap of 4, and OEMs can override the integer.
- A rate limit on `NotificationManager.notify` updates.
- How many Live Update chips Android 16 shows at once.
- A numeric cap on concurrent Live Activities per app. The HIG says "up to three" minimal presentations in the Dynamic Island and separately recommends one Live Activity that covers several events.
- Whether a BGAppRefresh launch may call `Activity.update`. ActivityKit only documents in-app updates and APNs updates.
- The exact visual change when `staleDate` passes, beyond exposing `isStale` to the view.
- How many `accessoryInline`, `accessoryCircular`, and `accessoryRectangular` slots a user can place, and their position relative to the clock.
- `ControlWidget` behaviour: the symbol page did not resolve, and the API is iOS 18 while this app deploys to iOS 16.
- The developer guide says automatic grouping starts at four notifications; AOSP `main` on 16 November 2026 sets `config_autoGroupAtCount` to 2. Which value ships on a given OEM build was not verified.
- The HIG never states "third-party apps cannot add status bar items" as a sentence. Section 9 is the absence of any insertion API, plus a status bar defined as system content.
