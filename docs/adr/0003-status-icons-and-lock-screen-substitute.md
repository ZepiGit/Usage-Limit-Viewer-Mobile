---
status: accepted
---

# Status icons: one notification group per pinned account on Android, a Lock Screen widget on iOS

A person can pin up to three usage accounts and see each one as a status icon: the provider glyph
and the figure of the account's headline ([ADR 0002](0002-one-account-headline-per-platform.md)),
in the app's display mode. The feature is off until the person turns it on (issue 60). The
platform facts behind this decision, with their sources, are collected in
[Status bar icons: platform facts](../research/statusbar-icons-platform-facts.md). Where this
record relies on something observed rather than documented, it says whether the evidence is an
emulator in CI or AOSP source; none of it was checked on a physical phone.

## Android

Each pinned account gets a notification group of its own, holding one summary and one child, both
with the status icon as their small icon. Three pinned accounts are six notification records.
Simpler shapes lose icons on Android 16: single notifications with their own group keys but no
summary are regrouped into one generated stack about three seconds after posting, and one shared
group shows a single icon for all accounts. Only complete groups kept three separate icons. This was
seen on API 36 emulators and matches the delayed regrouping pass in
[`GroupHelper`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/notification/GroupHelper.java)
on `android16-release`.

The small icon is a bitmap drawn after every publication. The status bar keeps only its alpha and
tints it with one system colour, so the glyph's shape has to tell providers apart; colour cannot.

The channel has default importance but is silent: no sound, no vibration, no lights, no badge, and
every notification is ongoing and alerts only once. From `android-16.0.0_r4` on, AOSP's default
for "hide silent notifications in the status bar" is on
([`PreferencesHelper`](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r4/services/core/java/com/android/server/notification/PreferencesHelper.java)),
so a low-importance channel shows no status-bar icon unless the person changes a system setting.
On the API 37 emulator a low-importance channel showed no account icons, and the silent
default-importance channel showed all three with that setting untouched. Whether Pixel or other
manufacturers ship the same default was not established. The research notes recommended low
importance; they predate this finding.

The icon is a 3:2 bitmap with the glyph to the left of the figure. The owner chose this after
seeing measured emulator pixels, against the recommendation that followed the prototype: the
stacked layout, glyph above figure. The status bar fits any small icon into a fixed square slot and does not widen
it, so a wider bitmap is drawn smaller: on an API 36 emulator at 280 dpi the painted part of a
"Codex 42" icon was about 24 × 14 px in 3:2 against 23 × 26 px stacked. The owner accepted the
smaller drawing to have the glyph beside the figure rather than above it.

Icons are ordered by ranking time, because a sort key does not order separate groups. Each
publication posts the accounts in reverse account order, each with a real clock reading later than
the one before, so the first account is the newest and leads. The times are never made up: in the
prototype, an API 36 emulator ignored a notification posted with a fixed time years in the past.

## iOS

Third-party apps cannot add items to the iOS status bar; Apple publishes no API for it. The
"Pinned accounts" Lock Screen widget stands in for the status icons, with the same glyphs, pins
and content rules. There is no Live Activity.

## Considered options

- A Live Activity on iOS. Rejected: it ends after at most eight hours, and keeping it current
  without the app running needs a push server, which this app does not have.
- An Android 16 Live Update chip. Rejected: Android shows one chip per app, not one per account
  (seen on the API 37 emulator with three promoted notifications, and in AOSP's
  `NotifChipsViewModel`), and Google limits Live Updates to activities a person starts and that
  end. Google also says not to repost a Live Update the person dismissed, while status icons
  return with the next sync.
- One composite icon for all pinned accounts (bars or rings in one bitmap, under one shared
  summary). Rejected: at status-bar size it cannot show three exact figures with their state
  marks. The prototype keeps it as a fallback idea.
- A low-importance channel plus a hint to turn off "hide silent notifications" in system settings.
  Rejected: on current AOSP the feature would show nothing until the person changed a setting
  that affects every app.
- The stacked layout. Rejected by the owner, as described above.

## Consequences

- The status rows sit in the alerting section of the shade, not under "Silent", although the
  channel has no sound, vibration or lights. In the visibility experiment on API 36 and 37
  emulators the records carried none of the three and no heads-up appeared in the captured
  frames. The emulator has no audio output, and nobody has listened on a phone.
- The notifications appear on the lock screen as Android shows normal notifications there. The
  app does not hide them with secret visibility.
- Visibility is not guaranteed. Status-bar width, other apps' icons, the person's settings and
  manufacturer skins can still hide icons. A note in the app's Settings says so; the app does not
  work around it.
- Order is best effort. On API 30, 36 and 37 emulators the icons followed a reorder in the app
  (captured while the channel still had low importance, with silent icons allowed on API 37). On
  the API 30 emulator a newly pinned account came first until the next sync restored the app's
  order.
- On Android 14 and later a person can swipe an ongoing notification away. The icon returns with
  the next sync; unpinning is how to remove it for good.
- On iOS the widget follows WidgetKit's reload budget, so the Lock Screen can lag a sync.
