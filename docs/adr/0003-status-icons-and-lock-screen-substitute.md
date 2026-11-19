---
status: accepted
---

# Status icons: one notification group per pinned account on Android, a Lock Screen widget on iOS

A person can pin up to three usage accounts and see each one as a status icon: its provider glyph
and the figure of its headline ([ADR 0002](0002-one-account-headline-per-platform.md)) in the
app's display mode (issue 60). How it looks and behaves is described in
[Status icons](../status-icons.md); the platform facts and their sources are in
[Status bar icons: platform facts](../research/statusbar-icons-platform-facts.md). The evidence
below comes from CI emulators and AOSP source, not from a phone.

## Android

Each pinned account gets a notification group of its own, with one summary and one child, both
carrying the drawn status icon as their small icon. The status bar tints the icon's alpha mask in
one colour, so the glyph's shape, not brand colour, tells providers apart ([platform
facts](../research/statusbar-icons-platform-facts.md#1-small-icon-rules)). On Android 16,
summary-less notifications with their own group keys were regrouped into one generated stack, and
one shared group showed one icon for all accounts; of the shapes tested, only complete groups kept
three icons (prototype runs
[43974065784](https://github.com/ZepiGit/Usage-Limit-Viewer-Mobile/actions/runs/43974065784) and
[43977478191](https://github.com/ZepiGit/Usage-Limit-Viewer-Mobile/actions/runs/43977478191)).

The channel has default importance with no sound, vibration, lights or badge. A soundless channel
alone does not prevent a doze pulse, so both records also request group-alert suppression
([platform facts](../research/statusbar-icons-platform-facts.md#4-channel-importance)). The owner
chose this over low importance: from `android-16.0.0_r4` on, AOSP hides the status-bar icons of
silent low-importance notifications by default, and on
the API 37 image a low-importance channel showed no icons while this channel showed three (run
[44156767429](https://github.com/ZepiGit/Usage-Limit-Viewer-Mobile/actions/runs/44156767429)). He
accepted that the rows sit in the alerting part of the shade.

The owner also chose the row: the reading as the title, window and reset below, the provider
artwork as the large icon, and the provider's name in the header, with the account name added
only when two pinned accounts share a provider. And he chose Android's normal lock-screen policy
over hiding the notifications there.

The icon is a 3:2 bitmap with the glyph left of the figure, the owner's choice against the
stacked layout (glyph above figure) recommended after the prototype. Android fits the bitmap into a
square slot, so it is drawn smaller: about 24 × 14 px against 23 × 26 px for "Codex 42" on an API
36 emulator at 280 dpi (run
[43989808585](https://github.com/ZepiGit/Usage-Limit-Viewer-Mobile/actions/runs/43989808585)).

A sort key does not order separate groups, so the publisher attempts the order through ranking
time: it posts the accounts in reverse order, each with a real clock reading taken before the
notification is built and later than the previous account's. If it detects that the clock jumped
back, or the clock stands still for longer than a short wait, it posts the remaining groups without
the order. It never invents a time; an API 36 emulator ignored
one set years in the past (run
[43972180341](https://github.com/ZepiGit/Usage-Limit-Viewer-Mobile/actions/runs/43972180341)).

## iOS

Apps cannot add items to the iOS status bar. The Pinned accounts Lock Screen widget stands in,
with the same glyphs, pins and content rules. The inline form allows one row of text and at most
one image, so it supplies only the first pinned account's glyph and figure with its side, without
names (the owner's choice). Whether a real Lock Screen keeps that glyph is a release gate.

## Considered options

- A Live Activity. Rejected: it ends after at most eight hours, and keeping it current without the
  app running needs a push server, which this app does not have.
- An Android 16 Live Update chip. Rejected: Google limits Live Updates to activities a person
  starts and that end, and asks apps not to repost one the person dismissed. On the API 37
  emulator three promoted records gave one visible chip, matching AOSP's one chip per package
  ([platform facts](../research/statusbar-icons-platform-facts.md#6-android-16-live-updates)).
- One composite icon for all accounts. Rejected: at status-bar size it cannot show three figures
  with their state marks.
- Low importance plus a hint to change the system setting. Rejected: under the AOSP default from
  `android-16.0.0_r4` on, the icons would show nothing until the person changed a setting that
  affects every app.

## Consequences

- Nothing about visibility is guaranteed: status-bar width, other apps' icons, the person's
  settings and manufacturer versions of Android can hide icons. Settings says so.
- Quiet updates are requested, not proven. The CI records carried no sound, vibration or light and
  no heads-up showed in the sampled frames; silence on a phone and a dozing screen are release
  checks.
- Order was observed, not guaranteed. On API 30, 36 and 37 emulators the icons followed the
  app's order on first post and after a reorder (historical runs
  [44196948542](https://github.com/ZepiGit/Usage-Limit-Viewer-Mobile/actions/runs/44196948542) and
  [44216665612](https://github.com/ZepiGit/Usage-Limit-Viewer-Mobile/actions/runs/44216665612),
  from before the app stopped offering a reorder);
  on API 30 a newly pinned account came first until the next sync. The owner accepted that
  exception as a known limit.
- The "app's order" the icons follow is the stored account order, not Overview's automatic order;
  [ADR 0004](0004-automatic-overview-order-and-stored-order.md) splits the two and keeps the
  icons on the stored one, so a changed reading does not move them.
- A swiped-away icon normally returns with the next sync, or at once when the app's Settings
  screen, which showed notifications or the status icon category blocked, sees them allowed again
  (that counts like turning the feature on); a block it did not see waits for the next sync. Best
  effort, as
  [Status icons](../status-icons.md#swiping-an-icon-away) describes.
- On iOS the widget redraws on WidgetKit's schedule, so the Lock Screen can lag the app.
