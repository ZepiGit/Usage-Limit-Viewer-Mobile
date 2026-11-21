# iOS

The iOS client has a SwiftUI app, a WidgetKit extension and a shared Swift package.
It follows the same quota model and provider behavior as Android, with separate
implementations. The per-provider documents in `docs/` describe payload shapes
and compatibility limits.

## Targets

- `UsageLimitsKit` contains models, parsers, HTTP clients, authentication and sync.
  Its portable code builds and tests on Linux; Keychain and Network-framework
  adapters are compiled when those Apple frameworks are available.
- `UsageLimits` contains the app, system-browser presentation and background work.
- `UsageLimitsWidget` reads snapshots through the shared App Group container.
  It is embedded in the app by `UsageLimits/project.yml`.

## Platform mapping

| Android | iOS |
|---|---|
| Android Keystore and AES-GCM | Keychain with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` |
| Custom Tabs and loopback listener | `ASWebAuthenticationSession` plus a Network-framework loopback listener |
| WorkManager | `BGAppRefreshTask`, subject to the system's scheduling decisions |
| Glance and local cache | WidgetKit and snapshots in the shared App Group |

Codex, Claude, Antigravity and Devin use their registered loopback callbacks. The browser
may intercept the callback, while the listener provides another way to receive
it. Codex also has a device-code fallback when the port cannot be used. Grok and
Kimi use device codes; Meta Muse uses the Meta device grant, and Kimi additionally accepts a key from the user's console.
These code paths are implemented, but live account sign-in has not been verified
by synthetic tests alone.

If a usage or balance read still returns HTTP 429 after its retry budget, iOS
keeps usable results and shows a notice that automatic reads for the account
are paused. The pause lasts at least 15 minutes, honors a longer `Retry-After`
up to six hours, and survives app restarts. An explicit refresh can retry
immediately; it renews the pause if a required read is still throttled and clears
it on an unthrottled success.

Codex's optional reset-credit read is tried once per pass. On a 429 the app
uses the credits embedded in the usage response, and usage refresh continues.

Settings installs persisted preferences before credential preparation and remains inert until that
installation finishes. The Settings Overview controls precede their live preview; Show credits
precedes the controls it reveals so the switch stays at its tap position.

Reset stamps use local calendar dates: time today, weekday and time over the next six dates,
then date and time. `GlanceText` already delegates widget reset and freshness lines to
`Countdown`, so those lines share this tier change. Pinned accounts' inline text remains its
figure and side, not a reset stamp. All three Pinned accounts families use the same full spoken
reading, adding the account label among same-provider pins and a stable account number when
labels collide. Formatter and Kit coverage do not establish actual Lock Screen or VoiceOver
behavior on a phone.

## Building and checking

For the portable package, from the repository root:

```sh
swift test --package-path ios/UsageLimitsKit
```

The app and widget require macOS with Xcode and XcodeGen:

```sh
cd ios/UsageLimits
xcodegen generate
xcodebuild build -scheme UsageLimits -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO
```

The iOS workflow is configured to test the package on Linux and macOS, build the
app with the real SDK, check the embedded widget and run simulator UI tests.
The real SDK job starts only after the Linux checks pass; macOS package tests run
independently to cover Darwin-specific code.
The Linux `ios/Tools/typecheck-app.sh` script provides an additional check with
framework shims; a pass there does not establish an Xcode build or UI behavior.

See the [release guide](../docs/release.md) for unsigned archives, distribution signing
and the [device checklist](../docs/release.md#device-checklist).

## Appearance

Settings starts with Appearance: Warm dark, Pure black (default), Cool graphite and Light paper,
then independent text and figure fonts. System uses Apple's native face. The other choices are
bundled offline: Roboto Flex, IBM Plex Sans, Figtree, Roboto Mono, JetBrains Mono and IBM Plex Mono.
Font licences opens each complete copyright and OFL notice without a network request.

The app references the canonical `third_party/fonts/android-res` folder directly. `UIAppFonts`
registers its nine TTF files in the app only. The iOS workflow runs
`python3 ios/Tools/check_font_bundle.py <UsageLimits.app>` against the built bundle, checking
manifest SHA-256 values, registrations, notices and the absence of font resources in extensions.
Variable text requests 450/560, static and unsupported system faces use Regular/Medium, and money
uses a real Bold face or 700 axis instance. Custom sizes scale with Dynamic Type.

Saved settings are installed before themed root content becomes visible. The static launch surface
is neutral: system-owned launch pixels and cached scenes cannot follow an app preference.
WidgetKit and widget-styled previews keep their own palette and fixed native fonts; app appearance
is not exported through the App Group. Native bar contrast, saved-choice cold frames and iOS 16
behavior still require a device check; loaded simulator screenshots are not first-frame evidence.

## Motion checks

`MotionUITests` launches the Debug app with synthetic accounts. `-motion-log` reports the
real modifiers' decisions; `-reduce-motion` overrides the preference, and
`-motion-delayed-demo` with `-marketing-demo` exercises the first-load decision. These hooks
are absent from Release. The tests drive the loading gate, filter selection, display-mode changes
and account deep links. They do not observe the applied animation or rendered frames.

Kit tests cover the selected source window ID and the unchanged widget export. No iOS motion
has been visually verified. Device checks include same-label window replacement (first frame
and VoiceOver), list and figure transitions, deep-link cuts, live Reduce Motion changes and the
real credit dialog's still busy mark (size and progress semantics). UIKit navigation, sheets,
alerts and refresh controls retain system motion.

On a physical iPhone with Reduce Motion off and the Claude Code figure visibly animating,
quickly tap each Settings switch ten times. Check that each tap changes its switch exactly
once, and watch the figure for a flash when it switches between still and live drawing.
Simulator tests check the controls and motion decisions, not these on-device observations.

## Confirmed-reset presentation

The account register commits a Claude reset mark with its new reading in one atomic JSON
replacement. A matching window must have measured figures on both sides of its advertised
reset, and the next reset must advance beyond the new reading. Amount increases alone do
not qualify. Several confirmations coalesce until the next opening.

The first active scene, or active after background, claims and clears the marks before the
on-open refresh. An inactive-only interruption does not claim again. The first ready layout
starts one four-second presentation for each visible real Claude Code slot; other slots for
that account share its elapsed time. Resets and sample previews are excluded. No eligible slot
spends the opening without replay on later navigation. Interruption stops playback, and
Reduce Motion or the animation switch keeps the same four-second Hooray as a still.
Widget snapshots contain neither the pending mark nor the opening playback.

`ResetConfirmationTests` reads the unchanged 24 shared reset vectors through the production
writer and reopened claim. `AppOpeningTests` holds real provider reads and opening claims
on gates. `HoorayRenderingTests` holds native layout readiness, injects monotonic time and
expiry release, and captures the real figure before and after expiry or interruption. These
test sources are not a claim that simulator or device checks have passed.

## Marketing screenshots

Run the manual **Marketing screenshots** workflow to capture the actual SwiftUI app on an
iPhone and an iPad simulator. The `ios-marketing-screenshots` artifact includes `CAPTURE.txt`
with the source commit and simulator models. The phone captures Overview, account detail,
empty Overview, Resets, Settings, and the custom layout before and after restoration; the iPad
captures Overview. `iphone-overview-order.png` uses a separate fixture: Codex has an expired
sign-in and appears ahead of Claude despite having more quota left.

The separate `UsageLimitsScreenshots` test scheme launches a Debug build with
`-marketing-demo`. This uses five synthetic accounts in memory, a fixed clock and a visible
**Demo data** label. It does not initialize shared storage, the keychain, provider clients,
background refresh or notification routing. Settings changes have no persistent store.
Release builds ignore the demo argument and contain no fixtures.

`iphone-claude-hooray.png` is the exception to the in-memory demo: `-ui-testing -fixture-hooray`
uses a temporary synthetic account register, commits two dated readings through the real writer,
and reaches Hooray through the normal initial scene claim. It never opens the keychain or the
App Group, and its transport rejects network requests. The screenshot uses Reduce Motion to
capture the still pose during the normal four-second window; the fixture does not extend it.

To reproduce on a Mac, generate the project with XcodeGen and choose a simulator destination:

```sh
xcodebuild test -scheme UsageLimitsScreenshots -configuration Debug \
  -destination 'platform=iOS Simulator,id=YOUR_SIMULATOR_UDID' CODE_SIGNING_ALLOWED=NO
```

The workflow shows the complete commands for exporting the XCTest screenshot attachments
as named PNGs. `ios/Tools/typecheck-app.sh -D DEBUG` also checks the fixture branch against
the Linux framework shims; the simulator workflow is the real SDK and rendering check.
