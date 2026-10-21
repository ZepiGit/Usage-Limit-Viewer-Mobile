# Device and OS compatibility

## Android toolchain

| Setting | Current value |
|---|---|
| Minimum Android version | API 26 (Android 8.0) |
| Compile and target SDK | API 36 (Android 16) |
| Android Gradle Plugin / wrapper | 8.10.1 / 8.14.6 |
| Robolectric | 4.16.1 |
| JVM for API 36 tests | JDK 21 |
| App bytecode target | Java 17 |
| Packaging JVM | JDK 17 in Android CI; JDK 21 in the release workflow |

Use [verification.md](verification.md#quota-and-widget-regression-coverage) for checks and
execution-evidence requirements; emulator results do not verify every physical device.

The API 26 floor provides native `java.time` and notification channels. Notification
permission is requested on API 33 and later; declining it does not block quota
viewing.

## Window sizes and navigation

Android chooses navigation from the current window width, including split-screen
and resizable windows:

| Window width | Navigation |
|---|---|
| Below 600 dp | Bottom bar |
| 600–839 dp | Side rail |
| 840 dp and wider | Side rail |

Content is centered and capped at 720 dp. The activities allow resizing and do
not lock orientation. The provider picker scrolls in short landscape windows;
its regression test checks reachability.

Both Android activities use light system-bar icons over the dark app. Widget
configuration consumes safe drawing insets. The manifest has no edge-to-edge opt-out;
navigation uses AndroidX rather than a custom legacy back-key handler. Physical foldable
transitions require device checks.

iOS content fills narrow windows and is capped at 760 pt on wider ones. The app
supports iPhone and iPad, including landscape and split view. CI includes iPhone/iPad
simulator runs and landscape/large-text provider-picker checks.

## Widgets, icons and accessibility

Android's four usage widgets use Glance exact sizes and support resizing; Credits uses
responsive sizes. Populated accounts, launcher grids and physical foldable resizing require
runtime checks. iOS uses WidgetKit and the shared App Group snapshot.

Both platforms use the same quota-cell and return-arrow icon. Android supplies
adaptive and monochrome variants. The iOS AppIcon catalog is wired into the
XcodeGen build and contains 18 opaque slots.

Status includes text and bar length as well as color. Android exposes grouped
usage descriptions to screen readers and enables RTL support. Sync-interval choices wrap
in narrow windows with large text. Some bottom-tab labels can break mid-word at font scale
2.0; touch targets remain accessible. Keyboard, pointer and screen-reader behavior need
device checks.

Android observes changes to the system animator scale while the app is open.
Scale zero removes value interpolation, item movement and sign-in transitions.
iOS reads `accessibilityReduceMotion`. Test large text, screen readers, RTL,
keyboard navigation and reduced motion on populated screens before distribution.

See [verification.md](verification.md) for the device runbook and
[release.md](release.md) for distribution requirements.
