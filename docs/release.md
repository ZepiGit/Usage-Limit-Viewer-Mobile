# Building and releasing

## Build Android

Use JDK 21, Android SDK platform 36 and Build Tools 35 or later. Set `ANDROID_HOME` or `sdk.dir` in untracked `local.properties`. The wrapper pins Gradle 8.14.6 and its distribution checksum; the app uses AGP 8.10.1 and Java 17 bytecode. Packaging alone also supports JDK 17.

From the repository root:

```sh
./gradlew :app:assembleDebug :app:assembleRelease
```

On Windows, use `gradlew.bat`. The debug APK is `app/build/outputs/apk/debug/app-debug.apk`; its application ID is `com.usagelimits.debug`, so it installs separately from release builds. Release output is `app/build/outputs/apk/release/app-release.apk`.

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

Use the candidate artifact and repeat the relevant steps on Android and iOS with the account holder:

1. Open Overview, Accounts, Resets and Settings. Check rotation, split view, large text, screen readers, keyboard navigation and reduced motion. Place widgets, resize them, change appearance, save and cancel configuration, then reopen after process loss and reboot.
2. Complete browser and device-code sign-in where offered, including cancellation and retry. Add two accounts on one provider and reconnect an existing account. Confirm account identity, quota direction and reset times against the provider's UI.
3. Compare populated widgets with the app. Refresh offline, restore connectivity, and remove an account during refresh. Check retained data age, recovery, notifications and credential renewal after expiry.
4. Install over the prior release without uninstalling. Confirm accounts, settings and widget selections remain. Redeem a Codex reset credit only with the holder's explicit approval: it spends a real credit.

## Owner responsibilities

Before public distribution, obtain provider permission and review terms for first-party OAuth registrations, internal usage endpoints and compatibility headers. Resolve Kimi's permission to use the `UsageLimits` identity. Use supported integration routes; TLS fingerprint imitation, bot-challenge bypasses and borrowed application identities are outside project policy. Vertex quota belongs to cloud projects and service-account/IAM access rather than personal subscription windows.

The owner supplies privacy policy, legal and store copy, signing-key custody and publication approval. Use the existing authenticated **ZepiGit** account for GitHub publication, preserving repository protections and existing signing keys.

Choose an unused version, merge the reviewed changes, update source version names and tag the selected commit. Download its release artifacts, complete the checklist and create a draft:

Prepare local `release-notes.md` and a `release-candidate/` directory containing the release workflow's versioned assets.

```sh
RELEASE_VERSION=0.3.1
RELEASE_TAG="v$RELEASE_VERSION"
gh api user --jq .login
gh release create "$RELEASE_TAG" --verify-tag --draft \
  --title "Usage Limits $RELEASE_VERSION" \
  --notes-file release-notes.md release-candidate/*
gh release view "$RELEASE_TAG" --json author,isDraft,tagName,assets,url
```

Confirm the account and draft author are ZepiGit, the tag names the intended commit, and every asset is present. Publish with `gh release edit "$RELEASE_TAG" --draft=false`. Download the public assets again, compare checksums and signer, and retain the APK URL and SHA-256. Immutable releases lock tags and assets when published.
