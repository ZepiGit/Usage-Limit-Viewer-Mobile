# Building and releasing

## Local Android builds

Use JDK 21 for tests (Robolectric API 36), or JDK 17 for packaging alone, and an
Android SDK with platform 36 and Build Tools 35 or later. Set
`ANDROID_HOME` or `sdk.dir` in the untracked `local.properties`. The Gradle wrapper
and dependency versions are committed; no global Gradle installation is needed.
The current build uses AGP 8.10.1 and Robolectric 4.16.1, with Java 17 app
bytecode. Use JDK 21 when running the build and tests together.

The wrapper pins Gradle 8.14.6 and its official distribution checksum. This
includes the fix for [repository fallback after SSL failures](https://github.com/gradle/gradle/security/advisories/GHSA-j5m7-59rp-24f5).
CI records the resolved release runtime dependency graph alongside the build
reports, so dependency review can distinguish requested and effective versions.

From the repository root in PowerShell:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:assembleDebug :app:assembleRelease
```

On Linux or macOS, use `./gradlew` with the same arguments. The debug APK is
`app/build/outputs/apk/debug/app-debug.apk`. Its application ID ends in `.debug`,
so it can be installed beside the release variant.

Tests include plain JVM tests, local HTTP and socket tests, and Robolectric tests
for Android storage and Compose screens. They do not require an emulator. The
HTML report is `app/build/reports/tests/testDebugUnitTest/index.html`; current
results and gaps are recorded in [verification.md](verification.md).

## Android signing

`app/build.gradle.kts` already reads the signing settings below. No source edit is
needed to sign a release.

| Environment variable | Gradle property or alternative environment variable |
|---|---|
| `ANDROID_KEYSTORE_PATH` | `USAGE_LIMITS_STORE_FILE` |
| `ANDROID_KEYSTORE_PASSWORD` | `USAGE_LIMITS_STORE_PASSWORD` |
| `ANDROID_KEY_ALIAS` | `USAGE_LIMITS_KEY_ALIAS` |
| `ANDROID_KEY_PASSWORD` | `USAGE_LIMITS_KEY_PASSWORD` |

For each setting, a nonblank `ANDROID_*` environment value takes precedence over
its `USAGE_LIMITS_*` Gradle property, followed by the `USAGE_LIMITS_*` environment
value. Local properties belong in the user's `.gradle/gradle.properties`, outside
the repository. Use an absolute keystore path.

- With all four settings absent, the release variant uses the debug key. This
  keeps local minified builds installable; it is for verification only.
- With any setting supplied, all four are required and the keystore must exist.
  An incomplete configuration fails instead of falling back to debug signing.
- With a complete configuration, Gradle uses that keystore. The build still has
  to verify that the password and alias are valid.

The release variant uses `com.usagelimits`, including when debug-signed. It cannot
update an installation signed with a different key. Keep signing-key creation,
backup, access and distribution credentials under the release owner's control.
Never commit release credentials or the release keystore, and do not enable
Gradle's configuration cache for a signed build.

## Updating an installed build without losing data

Android replaces an installed app in place only when the new APK carries the
same application ID, the same signing key and a version code that is not lower.
A mismatched signature or lower version code prevents that update. Obtain a
compatible artifact instead of uninstalling: uninstalling deletes the Room
database, encrypted credentials and widget configuration. Three things support
upgrade continuity:

- **One debug key everywhere.** `app/debug.keystore` is committed and is the
  debug signing config for every machine and every CI run. It uses Android's
  default debug credentials (`android` / `androiddebugkey`) and signs nothing
  distributed. Before this, Gradle used the key in `~/.android`, which each
  GitHub runner minted afresh, so every downloaded verification APK was signed
  differently from the one before it.
- **A rising version code.** The version code is the commit count
  (`git rev-list --count HEAD`) unless `ANDROID_VERSION_CODE` overrides it, in
  the Android workflow, in the release workflow and locally alike. The two
  workflows check out the full history so the count is real.
- **Migrations for every shipped schema.** `UsageLimitsDatabase` carries a
  migration for each version since 1 and never falls back to a destructive one.
  `DatabaseUpgradeTest` builds every exported schema, fills it with an account,
  a snapshot and a widget, opens it with the current app and checks all three
  survive; a version bump without its migration and schema export fails there.

What still cannot be carried over: the debug variant installs as
`com.usagelimits.debug`, a separate app with its own data, and a build signed
with the release key cannot update one signed with the debug key or vice versa.
Keep the phone whose data matters on the same signing line. A successful build
does not prove signature continuity; compare the new APK's signer with the
previously distributed APK before publishing.

With the release owner's settings supplied:

```powershell
.\gradlew.bat --no-configuration-cache :app:bundleRelease :app:assembleRelease
```

Outputs are `app/build/outputs/bundle/release/app-release.aab` and
`app/build/outputs/apk/release/app-release.apk`. Supply `ANDROID_VERSION_CODE` and
`ANDROID_VERSION_NAME` for a release; local defaults are the reachable commit
count and the version name in `app/build.gradle.kts`. Check the version code
against the last distributed build before uploading. A full-history descendant
has a larger commit count, but a shallow checkout or divergent branch does not
provide that guarantee.

## CI and release artifacts

The Android workflow runs the API 36 unit suite and uploads verification builds
and reports. Without signing settings, its minified release build uses the debug
key. Check the workflow run for the exact commit being evaluated; historical
test counts and results do not establish the current build's behavior.

The separate release workflow runs on `v*` tags or manual dispatch and builds
artifacts only. It never creates, publishes or overwrites a GitHub release. It
accepts a stable `major.minor.patch` version, with an optional leading `v`, and
pins every build job to the same resolved commit. Its Android job uses JDK 21
and requires unit tests and lint to pass before decoding the signing key.
Signing requires `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD` from the repository secret store.
It decodes the key into the runner's temporary directory, builds with
`--no-configuration-cache`, and removes the key in an `always()` cleanup step.
It does not use the Gradle caching action. The uploaded artifacts include the
signed AAB, APK and matching R8 `mapping.txt`.

Before packaging, the release workflow tests that same `android-release` APK on
an AOSP API 36 emulator, installing it over the latest published APK. The build
job's SHA-256 binds the tested file to its artifact; the runtime checkout,
workflow SHA and release metadata commit must match. The shared smoke script
checks upgrade continuity, the preserved setting, navigation and app crash
diagnostics. `release-android-runtime-evidence-<version>` retains its result,
environment, APK hashes, screenshots, and both APKs' `aapt` metadata and
`apksigner` verification reports. Packaging requires this runtime job to pass;
a branch workflow's APK result does not substitute for this release-artifact
check. Review the evidence's stated scope for device, widget and live-provider
coverage.

The workflow uses the full Git commit count as the Android version code and the
validated manual input or tag name as the version name. The iOS archive receives
the same marketing version and build number. The final
`release-assets-<version>` artifact contains versioned APK, AAB, R8 mapping and
iOS archive files, plus `RELEASE-METADATA.txt` and `SHA256SUMS`. The metadata
records the source commit and both version values.

The iOS release job generates the Xcode project from
`ios/UsageLimits/project.yml`, runs the shared Swift package tests and creates
an **unsigned** release archive on macOS. Distribution signing, provisioning and
store submission remain separate release-owner steps. Neither workflow
publishes to a store.

## Publish through the release owner's account

Use the existing authenticated **ZepiGit** account to publish. Do not use the
workflow's `GITHUB_TOKEN`, impersonate an author, create replacement signing
keys or change repository protections. If this authenticated publishing path
is unavailable, retain the finished artifacts and report that blocker.

First merge the reviewed changes and verify the merged commit's Android and iOS
checks. Choose a new version from the actual release history and update the
source version names before merging. Preserve existing tags and published assets;
publish fixes under a new version. These Bash commands illustrate a new `0.2.1` release; select
the appropriate unused version and verified commit:

```bash
RELEASE_REPO=ZepiGit/Usage-Limit-Viewer-Mobile
RELEASE_VERSION=0.2.1
RELEASE_TAG="v$RELEASE_VERSION"
test "$(gh api user --jq .login)" = ZepiGit
git fetch origin main --tags
RELEASE_COMMIT="$(git rev-parse origin/main)"
git tag "$RELEASE_TAG" "$RELEASE_COMMIT"
git push origin "$RELEASE_TAG"
gh run list --repo "$RELEASE_REPO" --workflow release.yml --branch "$RELEASE_TAG"
```

Select the run for that tag, wait for every job, and download its final artifact
into a new directory. Replace the example run ID with the actual run ID:

```bash
RELEASE_RUN=123456789
gh run watch "$RELEASE_RUN" --repo "$RELEASE_REPO" --exit-status
gh run download "$RELEASE_RUN" --repo "$RELEASE_REPO" \
  --name "release-assets-$RELEASE_VERSION" --dir release-candidate
(cd release-candidate && sha256sum --check SHA256SUMS)
cat release-candidate/RELEASE-METADATA.txt
test "$(sed -n 's/^commit=//p' release-candidate/RELEASE-METADATA.txt)" = "$RELEASE_COMMIT"
test "$(sed -n 's/^version=//p' release-candidate/RELEASE-METADATA.txt)" = "$RELEASE_VERSION"
```

Use the installed Android SDK's `apksigner` and `aapt` to inspect the candidate
and the previous published APK. Confirm `com.usagelimits`, the selected version
name, a strictly larger version code and identical signer SHA-256 digests.
Verify that the signer is the release signer, not `app/debug.keystore`. Run the
upgrade and widget smoke checks on this candidate where a suitable test device
is available. Preserve the same checksummed files through publication.

```bash
apksigner verify --verbose --print-certs "release-candidate/UsageLimits-$RELEASE_VERSION.apk"
aapt dump badging "release-candidate/UsageLimits-$RELEASE_VERSION.apk"
apksigner verify --verbose --print-certs previous-release.apk
aapt dump badging previous-release.apk
```

Write release notes with the fixes, verified checks and remaining device or
provider limitations. Create a draft with all assets attached using the
authenticated owner. GitHub immutable releases lock the tag and assets when
published, so inspect the draft before publishing:

```bash
test "$(gh api user --jq .login)" = ZepiGit
gh release create "$RELEASE_TAG" --repo "$RELEASE_REPO" --verify-tag \
  --draft --title "Usage Limits $RELEASE_VERSION" \
  --notes-file release-notes.md release-candidate/*
gh release view "$RELEASE_TAG" --repo "$RELEASE_REPO" \
  --json author,isDraft,tagName,assets,url
test "$(gh release view "$RELEASE_TAG" --repo "$RELEASE_REPO" --json author --jq .author.login)" = ZepiGit
gh release edit "$RELEASE_TAG" --repo "$RELEASE_REPO" --draft=false --latest
gh release view "$RELEASE_TAG" --repo "$RELEASE_REPO" \
  --json author,isDraft,tagName,assets,url
gh release download "$RELEASE_TAG" --repo "$RELEASE_REPO" --dir release-published
(cd release-published && sha256sum --check SHA256SUMS)
apksigner verify --verbose --print-certs "release-published/UsageLimits-$RELEASE_VERSION.apk"
aapt dump badging "release-published/UsageLimits-$RELEASE_VERSION.apk"
```

Confirm the published release is authored by ZepiGit, the tag still resolves to
the verified commit, and every expected asset is available. Record the public
APK URL and SHA-256. Where possible, repeat the smoke test using this downloaded
published APK. A running workflow or an unpublished draft is not a completed
release.

## Minified-build smoke test

The Android release variant enables R8 minification and resource shrinking.
`app/proguard-rules.pro` contains rules for generated serialization code and
readable crash locations; dependencies also contribute consumer rules. Keep
`mapping.txt` with the exact artifact it describes.

Build success does not establish that the minified app works on a device. Install
the local verification APK on an emulator or test phone:

```powershell
adb install -r app/build/outputs/apk/release/app-release.apk
```

Check launch, all four tabs and all four widget types first. Then have the account holder
run the login and usage checks in [verification.md](verification.md). Record the
artifact, device, OS version and result. Cold-start time and memory use require
runtime measurements; APK size is not a substitute.

## Before public distribution

The nine integrations are Codex, Claude, Antigravity, Grok, Kimi Code (kimi.com and kimi.ai),
Devin, Meta Muse, Z.ai GLM Coding Plan and OpenCode Go. Google Vertex is deliberately excluded:
CliProxyAPI exposes it through service-account authentication, and its quotas are per Google Cloud
project rather than per person (see docs/provider-auth-research.md). Vendor permission and terms review remain open, including the use of first-party OAuth
registrations and usage endpoints. A working response does not resolve that
review. Kimi also needs confirmation that the `UsageLimits` identity is allowed
on its coding API; the pasted-key route does not establish that approval.

Both platforms offer six app icons in Settings. Android includes transparent adaptive icons
and monochrome variants for themed launchers; iOS keeps the primary app icon opaque.
The primary iOS master is `AppIcon.appiconset/icon-1024.png`;
`tools/generate_app_icons.py` derives its other sizes with Pillow, a development tool
that is not included in either app.

Release-key custody, live account approval, legal and store copy, and publication
require owner review. See
[provider-auth-research.md](provider-auth-research.md) and the per-provider notes
for the implementation background. Treat builds as private verification
artifacts until those decisions and the device runbook are complete.
