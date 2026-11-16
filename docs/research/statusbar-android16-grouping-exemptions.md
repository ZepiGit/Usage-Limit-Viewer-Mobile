# Android 16 forced-grouping exemptions

Branch under inspection: `refs/heads/android16-release` of `platform/frameworks/base`, not `main`. Line numbers are that branch. AOSP aconfig flags default to disabled unless a release overlay says otherwise; this tree does not contain that overlay, so which flags an API 36 emulator actually has on is build-dependent. The emulator observation is used only for the one flag it identifies. Other flag states on that image stay unknown.

Primary files:

- [`GroupHelper.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/notification/GroupHelper.java)
- [`NotificationManagerService.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/notification/NotificationManagerService.java)
- [`NotificationRecord.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/notification/NotificationRecord.java)
- [`Notification.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/app/Notification.java)
- [`StatusBarNotification.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/service/notification/StatusBarNotification.java)
- [`core/java/android/service/notification/flags.aconfig`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/service/notification/flags.aconfig)
- [`services/core/java/com/android/server/notification/flags.aconfig`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/notification/flags.aconfig)
- [`core/java/android/app/notification.aconfig`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/app/notification.aconfig)
- [`config.xml`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/res/res/values/config.xml) (`config_autoGroupAtCount` = 2, lines 5219–5220)
- [`GroupHelperTest.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/tests/uiservicestests/src/com/android/server/notification/GroupHelperTest.java)

## What the emulator already proved

`Aggregate_SilentSection` and `Aggregate_AlertingSection` are names built only when `notificationForceGrouping()` is true: `AGGREGATE_GROUP_KEY` is `"Aggregate_"` and the section suffix is appended (`GroupHelper.java:87`, `:1935–1937`, `:1946–1948`). The section predicates put `importance >= IMPORTANCE_DEFAULT` (3) in `AlertingSection` and `importance < IMPORTANCE_DEFAULT` in `SilentSection` (`GroupHelper.java:196–200`; `NotificationManager.java:593–626`).

Three ongoing notifications, each with its own group key and no summary, landing in `Aggregate_SilentSection` (and DEFAULT importance landing in `Aggregate_AlertingSection`) means that image had `notification_force_grouping` enabled. Without it the aggregate key is `ranker_group`, not `Aggregate_*` ([`GroupHelper.java` L75](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/notification/GroupHelper.java#75), [L87](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/notification/GroupHelper.java#87), [L251–L265](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/notification/GroupHelper.java#251)).

`notification_force_group_singletons` is unknown on that image. The posts had no summary, so `isGroupChildWithoutSummary` aggregates them once two share a section ([`GroupHelper.java` L825–L835](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/notification/GroupHelper.java#825), [L1606–L1623](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/notification/GroupHelper.java#1606)). The singleton flag only gates sparse groups that already have a summary ([`GroupHelper.java` L852–L856](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/notification/GroupHelper.java#852); test `testAddAggregateSummary_singletonGroup_disableFlag`, [`GroupHelperTest.java` L3844–L3864](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/tests/uiservicestests/src/com/android/server/notification/GroupHelperTest.java#3844)). It is not required to explain this observation.

`FLAG_ONGOING_EVENT` is not an exemption. It is copied onto the aggregate summary when any child has it (`GroupHelper.java:84–85`, `:224–237`).

## Two different mechanisms

`GroupHelper.onNotificationPosted` branches on `notificationForceGrouping()` (`GroupHelper.java:251–265`).

Legacy autogrouping, flag off:

- Only notifications that are not an app group. `isAppGroup()` is true when `getGroup() != null` or `getSortKey() != null` (`StatusBarNotification.java:225–230`). A distinct `setGroup` key is enough to stay out.
- Ungrouped children of one user and package are counted together. At `mAutoGroupAtCount` (resource `config_autoGroupAtCount`, shipped as 2) or if a summary already exists, they move under `ranker_group` (`GroupHelper.java:75`, `:307–362`; `NotificationManagerService.java:3135–3139`; `config.xml:5219–5220`).
- No sections, no delay, no sparse-group pass. Summary flags are `FLAG_AUTOGROUP_SUMMARY | FLAG_GROUP_SUMMARY | FLAG_LOCAL_ONLY`, plus `FLAG_AUTO_CANCEL` only if every child has it, plus `FLAG_ONGOING_EVENT | FLAG_NO_CLEAR` if any child has them (`GroupHelper.java:79–85`).

Forced grouping, flag on:

- Immediate path `maybeGroupWithSections` still only sees non-app-grouped notifications (`GroupHelper.java:255–257`, `:586`). Same count of 2, but the bucket is `userId|pkg|g:Aggregate_<section>` (`GroupHelper.java:599–600`, `:628`).
- App-grouped notifications are not aggregated on post. NMS waits `DELAY_FORCE_REGROUP_TIME` = 3000 ms ([`NotificationManagerService.java` L541](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/notification/NotificationManagerService.java#541)), then calls `onNotificationPostedWithDelay` ([L9857–L9870](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/notification/NotificationManagerService.java#9857); the non-`checkAutogroupBeforePost` twin is [L9913–L9924](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/notification/NotificationManagerService.java#9913)). That delayed pass is what collapsed the prototype's distinct group keys. The delayed cases are missing summary, summary with no children, and, behind the singleton flag, sparse groups ([`GroupHelper.java` L773–L860](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/notification/GroupHelper.java#773)).

`check_autogroup_before_post` (`notification.aconfig:121–124`) only changes whether the immediate autogroup key is applied before the post sort. Both branches still schedule the 3 s delayed pass when force grouping is on.

## Every skip inside forced grouping

`getSection` walks `NOTIFICATION_SHADE_SECTIONS` and returns null when `isNotificationGroupable` is false ([`GroupHelper.java` L1926–L1931](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/notification/GroupHelper.java#1926), type gate [L2006–L2030](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/notification/GroupHelper.java#2006)). A null section skips immediate grouping, the delayed pass, and sparse grouping ([L589–L595](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/notification/GroupHelper.java#589), [L807–L812](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/notification/GroupHelper.java#807)). That is the only style/type exemption.

`isNotificationGroupable` returns false for:

1. Conversations, but only while `notification_force_group_conversations` is off (`GroupHelper.java:2011–2015`). The flag's own description is "enables forced auto-grouping conversations" (`services/.../flags.aconfig:147–150`). When the flag is on, conversations are groupable and land in `PeopleSection(priority)`, then either one `PeopleSection` (`sort_section_by_time` on) or `PeopleSection(alerting)` / `PeopleSection(silent)` (`GroupHelper.java:175–193`). Tests pin this: `testNonGroupableNotifications` expects a null section with the flag off (`GroupHelperTest.java:4155–4161`); `testConversationGroupSections` expects `PeopleSection` with both conversation and `sort_section_by_time` flags on (`GroupHelperTest.java:4381–4404`).
2. Colorized foreground-service notifications: `isForegroundService() && isColorized() && importance > IMPORTANCE_MIN` (`GroupHelper.java:2017–2020`). `isColorized()` is the requested colorized extra AND (`FLAG_CAN_COLORIZE` or FGS/user-initiated-job or promoted-ongoing) (`Notification.java:8082–8093`). A plain FGS, a colorized notification that is not an FGS, and importance `MIN` (1) do not match. The test name says "foreground services" but the stub sets both `isForegroundService` and `isColorized` (`GroupHelperTest.java:4175–4187`).
3. CallStyle, but only when `importance > IMPORTANCE_MIN` (`GroupHelper.java:2020–2024`). Importance `MIN` stays groupable. NMS separately strips a CallStyle that is not a real call or FGS (`NotificationManagerService.java:9116–9122`); that is a post rejection, not a grouping exemption.
4. Media notifications (`GroupHelper.java:2026–2028`). `isMediaNotification()` requires `MediaStyle` or `DecoratedMediaCustomViewStyle` AND a `EXTRA_MEDIA_SESSION` token (`Notification.java:8123–8134`). Style without a session is still groupable.

Also skipped, not by style:

- User-test-harness exemption: `ActivityManager.isRunningInUserTestHarness() && isExempted`, and only on the delayed pass (`GroupHelper.java:214–216`, `:803–805`). Not available to a normal app.
- Already carrying the aggregate override key (`GroupHelper.java:612–614`, `:819–822`).
- Canceled records, on the delayed pass only (`GroupHelper.java:800–802`).
- Critical notifications: NMS does not call `GroupHelper` when `record.getCriticality() < CriticalNotificationExtractor.NORMAL` (`NotificationManagerService.java:10154–10157`, `:9832`, `:9902`). Criticality is a ranking field, not an app API (`NotificationRecord.java:1018–1025`).

`FLAG_USER_INITIATED_JOB` is not read by `GroupHelper`. NMS strips the flag unless the job really is user-initiated (`NotificationManagerService.java:8667–8668`). It only protects children from being canceled when a summary goes away (`NotificationManagerService.java:10231–10236`, `:3210–3213`). A forged UIJ flag is not a grouping exemption.

`FLAG_PROMOTED_ONGOING` cannot be set by an app (`NotificationManagerService.java:8748`). It only blocks classification bundling (`NotificationManagerService.java:7388–7391`).

## Valid app group: summary plus children

Delayed pass, scenario 1 and 2 (`GroupHelper.java:825–835`):

- Child without a summary: `isAppGroup()`, not itself a summary, and `summaryByGroupKey` has no entry for `record.getGroupKey()` (`GroupHelper.java:1606–1623`). `getGroupKey()` for an app group with no override is `userId|pkg|g:<group>` (`StatusBarNotification.java:200–222`, `:436–438`).
- Summary without children: `isAppGroup()`, `isGroupSummary()`, and zero non-summary records whose `getGroup()` equals the summary's group name (`GroupHelper.java:1593–1603`, `:1572–1587`).

A group is left alone when it has a summary and at least one child. Tests: one summary plus one child produces no callback (`GroupHelperTest.java:1836–1880`); two children with no summary, or two summaries with no children, are aggregated once the count reaches the helper's threshold (`GroupHelperTest.java:1883–1929`).

That threshold is per section, not per group key. `addToUngroupedAndMaybeAggregate` stores the offending record under the section aggregate and fires when that map's size is at least `mAutoGroupAtCount` (2) or an aggregate summary already exists (`GroupHelper.java:865–893`). Two summary-less groups in the same section are enough. Three are more than enough. One summary-less child stays ungrouped until a second arrives or an aggregate summary already exists.

Posting the real summary later calls `onGroupSummaryAdded`, which removes matching children from the not-yet-aggregated pool (`GroupHelper.java:1108–1146`). NMS invokes it from `handleGroupedNotificationLocked` when the new record is a summary (`NotificationManagerService.java:10219–10229`). It does not pull children back out once `addAutoGroup` has already written the override. The 3 s delay is the window to post the summary first (`GroupHelper.java:773–777`).

`setGroup(null)` plus `setSortKey(...)` is still `isAppGroup()` (`StatusBarNotification.java:225–230`), so it enters this delayed pass rather than the immediate ungrouped path. Its group key becomes `userId|pkg|c:<channelId>` (`StatusBarNotification.java:207–221`). It does not create a valid summary/child pair.

## Summaries alone

A summary with no children is an offending record, same as a child with no summary (`GroupHelper.java:827–828`). Two such summaries in one section are aggregated. A summary is not a shield by itself.

When sparse grouping does fire, NMS removes the app summary (`REASON_GROUP_OPTIMIZATION`) and caches it so a later cancel of that summary still cancels the reparented children (`GroupHelper.java:1720–1726`, `:1902–1923`; `NotificationManagerService.java:7534–7550`).

## Sparse / singleton groups

Only if `notification_force_group_singletons` is on (`GroupHelper.java:852–856`; flag description at `services/.../flags.aconfig:140–143`).

`getSparseGroups` counts summaries that are in the triggering record's section, same package (ignore case) and user, an app group, and not already the aggregate. A group is sparse when `getNumChildrenForGroupWithSection` returns `> 0` and `< MIN_CHILD_COUNT_TO_AVOID_FORCE_GROUPING` (3) ([`GroupHelper.java` L89–L91](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/notification/GroupHelper.java#89), [L1752–L1766](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/notification/GroupHelper.java#1752)). So 1 or 2 children plus a summary is sparse. 0 children is not sparse (that is the summary-only path). 3 or more children in the same section is not sparse.

If any child has a null section or a different sectioner instance, the count is `-1` and that group is not sparse (`GroupHelper.java:1774–1796`). The comment names conversations as the example: summary in `AlertingSection`, child in a people section.

Aggregation happens only when the number of such sparse summaries in that section is at least `mAutogroupSparseGroupsAtCount`. NMS passes `AUTOGROUP_SPARSE_GROUPS_AT_COUNT` = 6 ([`NotificationManagerService.java` L640–L641](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/notification/NotificationManagerService.java#640), constructor pass [L3135–L3139](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/notification/NotificationManagerService.java#3135)). The unit test injects 2, so do not read the test's "2" as the device threshold ([`GroupHelperTest.java` L124–L144](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/tests/uiservicestests/src/com/android/server/notification/GroupHelperTest.java#124)).

[INFERENCE] Three groups of (summary + 1 child) in one section stay valid on a build using the constant 6. They are not valid on a build that lowered the constant. The constant is `private static final` in NMS, not a resource.

The prototype's three summary-less groups never reach this counter. They die earlier, in `isGroupChildWithoutSummary`, at count 2.

## Channels, sections, users

Section buckets are independent. `testAddAggregateSummary_multipleSections` posts DEFAULT and LOW records and expects one `Aggregate_AlertingSection` and one `Aggregate_SilentSection` (`GroupHelperTest.java:1933–1960`). Splitting three notifications across importance bands splits the count. It does not exempt a band that still reaches 2.

Classification sections exist only when `notification_classification` is on (`GroupHelper.java:156–173`; flag at `core/java/android/service/notification/flags.aconfig:41–45`). They match reserved channel ids `PROMOTIONS_ID`, `SOCIAL_MEDIA_ID`, `NEWS_ID`, `RECS_ID`, and only when `importance < IMPORTANCE_DEFAULT`. An app cannot create those reserved channels. `notificationClassificationUi` is a separate UI flag (`notification.aconfig:320–323`). With classification off, those channel ids fall through to Alerting/Silent (`GroupHelperTest.java:4200–4241`).

If `notification_regroup_on_classification` and `notification_classification` are both on, a child that classification moved into a bundle section while its summary stayed outside is moved into the aggregate even when the original group was valid (`GroupHelper.java:837–849`; flag at `flags.aconfig:71–74`). That is assistant behavior, not an app lever. NMS refuses to bundle a promoted-ongoing notification (`NotificationManagerService.java:7388–7391`).

Channel updates recompute the section and move the child when the section changes (`GroupHelper.java:1154–1188`, `:1303–1335`). Raising LOW to DEFAULT moves `SilentSection` to `AlertingSection`. If the destination aggregate already exists, one notification is enough to be pulled in (`GroupHelper.java:1488–1497`).

Users are part of the key (`userId|pkg|g:...`, `GroupHelper.java:1946–1948`). A different user id is a different bucket (`GroupHelperTest.java:1817–1833`). A normal app posts as its own user.

## `setGroupAlertBehavior`

Not an exemption. `GROUP_ALERT_ALL` (0), `GROUP_ALERT_SUMMARY` (1), and `GROUP_ALERT_CHILDREN` (2) only choose who makes sound (`Notification.java:1801–1848`, `:4396–4417`). The aggregate summary's behavior is `GROUP_ALERT_SUMMARY` only when every child is `GROUP_ALERT_SUMMARY`; any other child forces `GROUP_ALERT_CHILDREN` (`GroupHelper.java:497–508`). NMS then sets `FLAG_SILENT` on forced-grouped children and on the aggregate summary (`NotificationManagerService.java:7464–7468`, `:1079`). `fixSilentGroup()` rewrites the legacy silent group key `GROUP_KEY_SILENT` into `FLAG_SILENT` and clears the group (`Notification.java:4470–4477`), which makes the notification ungrouped and therefore eligible for immediate section aggregation. `notification_silent_flag` gates that rewrite (`flags.aconfig:56–59`).

## Timeouts

The 3 s delay is the only grouping timeout (`NotificationManagerService.java:541`). Removing a child of an app-provided group schedules the same delay on the summary via `onGroupedNotificationRemovedWithDelay` (`NotificationManagerService.java:10883–10909`), so a group that falls from 3 children to 2 can become sparse after 3 s if the singleton flag is on and the package already has 6 sparse groups in that section.

`Flags.allNotifsNeedTtl()` assigns a 3-day timeout when the app set none (`NotificationManagerService.java:612`, `:8806–8809`; flag at `services/.../flags.aconfig:85–88`). That cancels the notification later. It does not change grouping. `notification_minimalism` is a lockscreen flag (`flags.aconfig:133–136`), not a grouping flag.

## What is honest for a normal pinned-quota notification with no FGS

The prototype posts three ongoing, non-FGS notifications. Ongoing does not help. Forcing `FLAG_FOREGROUND_SERVICE` or `FLAG_USER_INITIATED_JOB` is stripped unless the service or job is real (`NotificationManagerService.java:8664–8668`), and even a real FGS is exempt only when it is also colorized and above min importance.

Candidates that the source actually exempts:

1. One valid group per band that must stay separate: a real summary plus at least one child, posted inside the 3 s window, and not sparse. Three separate icons need three such groups. They stay unaggregated only while `notification_force_group_singletons` is off, or while the package has fewer than 6 sparse groups in that section. A 1-child or 2-child group is sparse. A 3-child group is not (`MIN_CHILD_COUNT_TO_AVOID_FORCE_GROUPING` = 3). Three groups of three children is nine notifications, not three icons. [INFERENCE] One summary plus three children is one shade group and, if SystemUI still collapses a valid group to its summary icon, one status-bar icon. That does not satisfy "three icons". It is the only grouping shape the forced-grouping pass treats as valid without depending on a flag being off.

2. Stay under the per-section count of 2 for the summary-less path. One notification per section is not aggregated. The sections an ordinary app can actually hit are `AlertingSection` (`importance >= DEFAULT`) and `SilentSection` (`importance < DEFAULT`). That is two icons, not three. Classification and people sections are not app-selectable unless the corresponding flags are on and the record really is a conversation or a system-classified channel. Importance `MIN` still enters `SilentSection`. It does not create a third section.

3. Non-groupable types, if they are real for this app:
   - CallStyle with importance above `MIN`. A quota row is not a call. NMS also requires a real call/FGS association for CallStyle (`NotificationManagerService.java:9116–9122`).
   - MediaStyle or `DecoratedMediaCustomViewStyle` with a real media-session token (`Notification.java:8123–8134`). A token without playback is a lie to SystemUI; the exemption itself is only the boolean `isMediaNotification()`. Whether SystemUI then replaces the small icon with a media icon is outside `GroupHelper`.
   - A conversation, only on builds where `notification_force_group_conversations` is off. `isConversation()` requires `MessagingStyle`, a non-bot shortcut, and no user/NAS demotion (`NotificationRecord.java:1681–1714`). On builds where the flag is on, conversations are grouped into `PeopleSection*` instead, and three of them in the same people section still aggregate at 2 if they have no valid summary. The emulator already aggregated ordinary notifications, so this flag's state on that image is unknown. Do not treat "mark it a conversation" as a stable exemption.

Not candidates:

- Distinct group keys without summaries. That is the path that produced `Aggregate_*`.
- Sort key only. Still an app group, still delayed-forced.
- `setGroupAlertBehavior`, `FLAG_ONGOING_EVENT`, `FLAG_NO_CLEAR`, color without an FGS, a fake FGS/UIJ flag, `setTimeoutAfter`, or a different channel id inside the same importance band.
- Waiting out the 3 s delay. The delay is how long the platform waits before aggregating, not a way to opt out.
- Another user id. Not available to the app's own posts.

Practical experiment order for three icons, given this source: first post three valid groups of summary plus at least three children only if the goal is "not aggregated", accepting one icon per group; that is the honest non-sparse shape. For three icons, the source leaves no flag-independent hole that fits a normal non-FGS, non-call, non-media notification. The holes that remain are build-dependent: singleton flag off (then summary plus one child is enough, and the prototype says this image has the missing-summary path on even if the singleton flag were off), or fewer than 6 sparse groups with the singleton flag on. Splitting importance only buys a second section, so at most two ungrouped-or-summary-less icons before each section hits 2.
