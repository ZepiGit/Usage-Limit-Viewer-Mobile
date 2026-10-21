# Device compatibility

Android requires Android 8.0 (API 26) or later. The app compiles and targets API 36 with AGP 8.10.1, Gradle 8.14.6 and Java 17 bytecode. Use JDK 21 and Android SDK 36 for development. Notification permission is requested on Android 13 and later; quota viewing works without it.

Android uses bottom navigation below 600 dp and a side rail in wider windows. Content is capped at 720 dp. Activities support resizing and rotation, including split-screen. Usage widgets resize to the launcher's available space.

Android launcher icons use transparent artwork; the launcher controls any additional frame or background. These icons do not supply monochrome themed variants.

iOS requires iOS 16 or later and supports iPhone and iPad, including landscape and split view. Content is capped at 760 pt. Configurable usage widgets require iOS 17; the standard tiles and Credits widget support iOS 16.

Status uses text and bar length as well as color. Both platforms respect reduced motion. See [Widgets](widgets.md) for setup and the [release checklist](release.md#device-checklist) for accessibility and device checks.
