# Building and releasing

## Build Android

Use JDK 21 for development and all CI jobs, Android SDK platform 37.0 and Build Tools 36.0.0 or later. Set `ANDROID_HOME` or `sdk.dir` in untracked `local.properties`. Install the platform and, for a phone emulator, the system image with:

```sh
sdkmanager 'platforms;android-37.0' 'system-images;android-37.0;default;x86_64'
```

Use command-line tools build `15859902` (22.0) for AVD creation. Older tools can misparse the dotted `37.0` image version and generate an `android-0` target. CI pins that tools build without changing the SDK setup action's major, records the selected tools and AVD metadata, and requires `target=android-37.0` before launch.

The app has minSdk 26 and compiles and targets API 37. The wrapper pins Gradle 9.7.0 and its distribution checksum; the toolchain uses AGP 9.3.1, Kotlin 2.4.21, KSP 2.3.14 and Compose compiler 2.4.21. AGP's built-in Kotlin and new DSL stay enabled, without the `org.jetbrains.kotlin.android` plugin. AGP and Gradle require at least JDK 17, but the project uses JDK 21, including for Robolectric 4.18's API 37 support. Explicit older regression SDKs remain in tests. Java 17 bytecode is preserved.

The versions are coordinated against the [AGP 9.3 compatibility table](https://developer.android.com/build/releases/agp-9-3-0-release-notes), [Kotlin's Gradle/AGP compatibility table](https://kotlinlang.org/docs/gradle-configure-project.html) and [KSP requirements](https://kotlinlang.org/docs/ksp-overview.html). The merged CI action upgrade uses `gradle/actions@v6` only with `cache-provider: basic`; do not enable its default enhanced cache.

From the repository root:

```sh
./gradlew :app:assembleDebug :app:assembleRelease
```

On Windows, use `gradlew.bat`. The debug APK is `app/build/outputs/apk/debug/app-debug.apk`; its application ID is `com.usagelimits.debug`, so it installs separately from release builds. Release output is `app/build/outputs/apk/release/app-release.apk`.

CI runs `:app:testDebugUnitTest :app:lintDebug`. Lint aborts on errors and writes HTML and XML reports under `app/build/reports/`. Existing warnings remain warnings, with no baseline hiding them.

Branch protection on `main` requires one check, `Merge gate` (`.github/workflows/gate.yml`). It runs on every pull request, waits for every other workflow run on the head commit, whatever started it, and passes only if none failed; a cancelled run counts only when the same workflow also succeeded on that commit. It decides once and does not notice later re-runs: after re-running a failed job in another workflow and seeing it pass, open the pull request's Merge gate run and choose "Re-run all jobs" (or push a new commit).

## Dependency updates

The Android toolchain moves together, outside Dependabot: AGP, Gradle, Kotlin, KSP and the Compose compiler must remain compatible. The coordinated API 37 upgrade removes the first-wave AndroidX, Coroutines, Serialization and OkHttp version bounds now that their current stable versions pass the consumer tests. Library and security updates may advance normally; the coupled toolchain remains excluded. Room, WorkManager, Lifecycle, Glance and Compose updates stay grouped separately so unrelated libraries do not block each other.

After upgrading CI actions, run the full Android and iOS workflows on a maintainer branch. Dependabot runs skip the signed API 37 release-upgrade smoke. The Android workflow uses `gradle/actions` v6 with `cache-provider: basic` to retain MIT-licensed caching without the separate Enhanced Caching terms. Basic caching does not provide Enhanced Caching's cleanup or deduplication. The release workflow remains uncached, and artifact uploads retain zipped, explicitly named artifacts for the existing download steps.

## Signing

Supply all four values for a distribution build:

| Environment variable | Alternative Gradle property or environment variable |
| --- | --- |
| `ANDROID_KEYSTORE_PATH` | `USAGE_LIMITS_STORE_FILE` |
| `ANDROID_KEYSTORE_PASSWORD` | `USAGE_LIMITS_STORE_PASSWORD` |
| `ANDROID_KEY_ALIAS` | `USAGE_LIMITS_KEY_ALIAS` |
| `ANDROID_KEY_PASSWORD` | `USAGE_LIMITS_KEY_PASSWORD` |

A nonblank `ANDROID_*` environment value takes precedence, followed by the `USAGE_LIMITS_*` Gradle property, then its environment value. Keep local properties in your user `.gradle/gradle.properties` and use an absolute keystore path.

With every signing value absent, release builds use the debug key for local installation. Partial settings fail instead of falling back. Distribution requires the existing release key. Keep that key, passwords and provisioning credentials outside the repository and under owner control. Disable Gradle's configuration cache for signed builds.

```sh
./gradlew --no-configuration-cache :app:bundleRelease :app:assembleRelease
```

The bundle is `app/build/outputs/bundle/release/app-release.aab`. Retain the matching R8 `mapping.txt` with the minified APK and AAB. Set `ANDROID_VERSION_NAME` and `ANDROID_VERSION_CODE` for distribution; local defaults are the source version name and reachable commit count.

## Preserving data on upgrade

An in-place Android update requires the same application ID, the same signing key and a version code that is not lower. Publish with a strictly larger code. Compare the candidate with the previous distributed APK using `aapt dump badging` and `apksigner verify --verbose --print-certs`; confirm `com.usagelimits` and the release signer's SHA-256 digest.

Use `adb install -r` to update a compatible installation. If Android rejects it, obtain the correctly signed build instead of uninstalling. Uninstalling deletes accounts, encrypted credentials, cached readings and widget configuration. Debug and release application IDs have separate storage, and debug and release signers cannot replace each other.

The committed `app/debug.keystore` gives local and CI builds a consistent debug identity. Production keeps its existing release signer. Room uses additive migrations from shipped schemas rather than destructive fallback. Full history is required when deriving version codes from commit count; shallow checkouts and divergent branches need an explicit code above the previous release.

## Release artifacts

The release workflow accepts a stable `major.minor.patch` version through a `v*` tag or manual dispatch. It pins jobs to one commit and builds artifacts without publishing a GitHub release or submitting to stores.

Android signing uses repository secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`. The decoded key is temporary and removed during cleanup. The iOS job generates the project from `ios/UsageLimits/project.yml` and produces an unsigned archive. The owner supplies distribution signing and provisioning separately.

Download `release-assets-<version>` from the matching run. It contains versioned APK, AAB, R8 mapping and iOS archive files, `RELEASE-METADATA.txt` and `SHA256SUMS`. Check the metadata's commit, version name and build number against the selected tag, then run `sha256sum --check SHA256SUMS` in that directory. Preserve those same files through publication. A subsequent release uses a new version rather than replacing published tags or assets.

## Device checklist

Use the candidate artifact and repeat the relevant steps on Android and iOS with the account holder. Android 17 physical-device validation is still needed. For loopback OAuth, keep the browser in the same user or work profile as the app: Android 17 blocks cross-profile localhost access regardless of target SDK. Certificate Transparency is enabled by default; do not disable it.

1. Open Overview, Accounts, Resets and Settings. Check rotation, split view, large text, screen readers, keyboard navigation and reduced motion. Place widgets, resize them, change appearance, save and cancel configuration, then reopen after process loss and reboot.
2. Complete browser and device-code sign-in where offered, including cancellation and retry. Add two accounts on one provider and reconnect an existing account. Confirm account identity, quota direction and reset times against the provider's UI.
3. Compare populated widgets with the app. Refresh offline, restore connectivity, and remove an account during refresh. Check retained data age, recovery, notifications and credential renewal after expiry.
4. Install over the prior release without uninstalling. Confirm accounts, settings and widget selections remain. On iOS 17+, first place Usage Bars, Account Rings and Mini Rings with a display-mode override opposite to the app setting and nondefault selection and appearance. After upgrading, confirm they still render, retain those other choices, no longer offer a display-mode control and follow changes to the app setting after WidgetKit reloads. Record the OS and both builds: Linux checks cannot verify WidgetKit's decoding of a stored intent whose parameter was removed. Redeem a Codex reset credit only with the holder's explicit approval: it spends a real credit.
5. On an Android 17 device, in this order:
   1. Long-press a placed widget, confirm the resize frame opens, then drag its handles by touch. After release, confirm the new size holds and the widget keeps its content and configuration. CI checks only keyboard move and resize, not this touch path.
   2. Add enough accounts to fill Account Rings. On a small or low-resolution display, place it in several sizes and in both orientations. Android 17 caps widget bitmap memory at about 6 × screen width × screen height bytes. A widget over the cap should show Glance's text-only error layout; a blank, stale or partly drawn widget is a failure. You do not need to cause an out-of-memory condition or turn off the cap.
   3. On the Add credit balance screen, type an API key with a hardware keyboard. Confirm every character stays masked as you type.
   4. Sign in to and refresh each provider. Each connection must succeed with Certificate Transparency on, and with Encrypted Client Hello where the device and server negotiate it. Do not turn off TLS checks, and use only accounts whose holders have agreed.
   5. Put the device under memory pressure, for example by opening several large apps, then return to the app and its widgets. Confirm both recover normally and keep their configuration.

6. On an iPhone, add the Pinned accounts widget in all three Lock Screen forms with three accounts pinned, in English and German. **Release gate:** the inline form shows the provider glyph on a real Lock Screen, also after a timeline reload; if it does not, the release waits for the owner's choice between a bundled template image and a self-identifying text. **Release gate, inline words:** on a real Lock Screen, in English and in German, at the default text size and at the largest accessibility size (AX5), and in the narrowest inline slot the supported devices offer, the inline row shows the complete figure with its side and the stale suffix ("58% used · Stale" / "58 % verbraucht · Veraltet"), and one state without a number shows its whole word (for example "Exhausted" / "Aufgebraucht"). If the system truncates any of them, the release waits for the owner's choice in the same way as for the glyph. Then check the rest, which CI renders only as views: the glyphs and the crossed circle are tinted by the system against a real wallpaper; the circular form shows its system background; the cells keep every mark whole at the largest text size (Settings → Accessibility → Larger Text); on a device restarted after the app has written widget data (open the app and refresh first, so the widget has shown figures), before the first unlock, the widget shows "Locked" (the circular form a lock symbol) and fills in after unlocking once WidgetKit reloads it. With VoiceOver on, each cell reads its account's title, the full figure with its side or the state, and "Stale" where outdated.
7. On Android phones, at least one on Android 16 or later and, where available, one with a manufacturer's own version of Android (Samsung, Xiaomi or similar), with sound and vibration on and the app's alerts set so they do not fire. CI covers this only on emulators ([Status icons](status-icons.md#what-has-been-checked-and-where)):
   1. Turn on Status bar icons and pin three accounts. The three icons show next to the clock with "Hide silent notifications in status bar" left as the phone ships it.
   2. First post, re-post and update: at the moment the icons first appear, after one is swiped away and returns with a refresh, and when a refresh changes a figure, the phone makes no sound, does not vibrate or blink, and no notification drops in from the top.
   3. Screen off, with the phone's ambient or "wake screen for notifications" display on. First a re-post: swipe one row away while unlocked, turn the screen off and wait for a background refresh; when the row returns, the screen shows no notification pulse and does not wake. Then an update: let a background refresh change a figure; again no pulse and no wake. Repeat with always-on display enabled, where present: the always-on display may stay on, but shows no pulse for the change.
   4. Lock the phone with a PIN, with lock-screen notifications allowed. The status notifications appear there in the phone's own form (rows, cards or icons only). Then hide them: turn off lock-screen display for the Status bar icons category where the phone offers it, otherwise the system-wide option. Hiding notification content is a different setting and does not count.
   5. With TalkBack on, move through the three rows: note what it reads for each, and whether the provider can be told apart. Tap a row: it opens that account's detail screen.
   6. Swipe one row away. It stays away after a settings change and returns after the next refresh; unpinning removes it.
   7. Switch the "Status bar icons" category off in Android: the icons disappear, alerts still arrive, and the app's Status bar icons section names the category and opens its settings. Open **Open category settings** from the app, switch it on there and go back to the app's Settings: all pinned icons come back at once, a swiped-away one too. Switched off and on again without the app's Settings screen seeing it, they come back with the next sync instead. Repeat with the app's notifications switched off on an Android 12 or older phone if one is available.

## Owner responsibilities

Before public distribution, obtain provider permission and review terms for first-party OAuth registrations, internal usage endpoints and compatibility headers. Resolve Kimi's permission to use the `UsageLimits` identity. Use supported integration routes; TLS fingerprint imitation, bot-challenge bypasses and borrowed application identities are outside project policy. Vertex quota belongs to cloud projects and service-account/IAM access rather than personal subscription windows.

The owner supplies privacy policy, legal and store copy, signing-key custody and publication approval. Use the existing authenticated **ZepiGit** account for GitHub publication, preserving repository protections and existing signing keys.

Choose an unused version, merge the reviewed changes, update source version names and tag the selected commit. Download its release artifacts, complete the checklist and create a draft:

Prepare local `release-notes.md` and a `release-candidate/` directory containing the release workflow's versioned assets.

```sh
RELEASE_VERSION=0.4.1
RELEASE_TAG="v$RELEASE_VERSION"
gh api user --jq .login
gh release create "$RELEASE_TAG" --verify-tag --draft \
  --title "Usage Limits $RELEASE_VERSION" \
  --notes-file release-notes.md release-candidate/*
gh release view "$RELEASE_TAG" --json author,isDraft,tagName,assets,url
```

Confirm the account and draft author are ZepiGit, the tag names the intended commit, and every asset is present. Publish with `gh release edit "$RELEASE_TAG" --draft=false`. Download the public assets again, compare checksums and signer, and retain the APK URL and SHA-256. Immutable releases lock tags and assets when published.
