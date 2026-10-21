# Verification

## Quota and widget regression coverage

Synthetic regressions cover scoped quota availability, malformed and absent percentages,
Claude window identity, current xAI credits versus deprecated monthly totals, partial
refresh diagnostics, credential races and measurement age. Workflow check runs and their
downloadable artifacts record execution results for each source revision.

Android CI runs the unit suite and lint, then builds debug and minified release
APKs. `android-verification-evidence` contains test XML, lint output, generated
Room schemas and widget PNGs. Schema 10 adds a nullable attempt timestamp;
upgrade tests preserve accounts, snapshots and widget configuration from every
retained schema. The RemoteViews test uses an actual `AppWidgetHostView`, verifies
saved colors across day/night and resize, and requires materialized account and
quota text in collection widgets. A background-only PNG is not sufficient proof.
These are synthetic Robolectric renders, not physical launcher lifecycle tests.

[WorkManager 2.10.5](https://developer.android.com/jetpack/androidx/releases/work#2.10.5)
and [DataStore 1.1.7](https://developer.android.com/jetpack/androidx/releases/datastore#1.1.7)
include upstream fixes for network constraints, startup storage races, PreferencesDataStore
corruption and R8/ProGuard rules. Their presence does not establish the cause of a widget
color change or a performance improvement. CI records the resolved dependency graph and
runs scheduling, persistence and minified-APK upgrade checks.

The APK reports record package/version metadata, SHA-256 and signer certificates.
When the existing release key is configured, CI compares the previous published
APK's identity, version code and signer with the new artifact and fails on missing
or inconsistent values. Signature continuity alone does not prove an on-device upgrade.
The API 36 runtime job starts a fresh AOSP emulator and waits for a focused
launcher window and its observed workspace before sending test input. Boot logs
are retained; a system error dialog fails the run. Both Android and release CI
keep Xvfb alive around the runtime wrapper with `EGL_PLATFORM=x11`. The emulator
uses explicit `-gpu swiftshader` and verifies SwiftShader Vulkan/GLES backends
before APK installation. This matches an earlier healthy boot; LLVMpipe boots
also produced a SystemUI startup ANR while waiting on SurfaceFlinger under CPU
pressure. The wrapper rejects missing `DISPLAY` and retains all system-dialog
and ANR checks. This setup does not establish physical-device performance.
The fresh API 36 AVD renders at 720x1560 pixels and 280 dpi, preserving the
previous 1440x3120 at 560 dpi logical viewport (about 411x891 dp) while reducing
CPU-bound software-rendering work. The wrapper records and checks both physical
display values and the preserved logical dimensions before installing an APK.
It installs the latest published release, changes and reloads
a setting, then installs the same run's signed minified APK with `adb install -r`.
It verifies the installed APK hash, preserved setting and package identity, four
tabs, relaunch and crash/ANR diagnostics. It also places all four empty widget
types through the launcher and checks their IDs, visible content and saved
appearance selections after upgrading, changing day/night mode, ending the
observed background app process with `am kill`, restarting the launcher and
rebooting the emulator, then going offline and back online. Network checks record
the actual default network and connected Internet-capable links, rather than
treating the airplane-mode switch alone as proof. Original radio settings are
restored even on failure; no provider requests are made. Recorded process and boot IDs verify those transitions;
crash diagnostics are checked and preserved before reboot. Reconfiguration checks cancel
without saving, so they cannot repair lost settings. These checks are required;
missing launcher controls fail with screenshots and UI XML for diagnosis.
Before the lifecycle rounds, normal launcher keyboard actions move Mini Rings
to a different observed position on the same Home page and resize it horizontally
and vertically, growing and shrinking each dimension. The checks require changed
host bounds, preserved dimensions on the other axis, all four original widget IDs
and saved Mini Rings appearance read without saving. The geometry report records
the actual bounds, selected launcher targets and elapsed verification time.
Its artifact records the commit and device environment. A passing run establishes
empty-account upgrade and the stated launcher checks; it does not establish
populated-account behavior, a fresh provider repaint or physical-device
lifecycle behavior. The earlier force-stop/relaunch checks remain distinct from
the background-process-loss check.
That check first observes every original app PID's Android 16 `setAdj` at or
above `SERVICE_ADJ` (500), with a bounded wait and saved process/service/job
diagnostics. Going Home alone is insufficient while Glance's widget jobs retain
service bindings. It then issues one `am kill` and still requires disappearance
of every original PID before checking retained widget content and settings.
iOS CI runs the shared package on Linux and macOS and builds/runs the app with the
real SDK on iPhone and iPad simulators.

The runtime script also records one excluded warmup and three launches after
force-stop per APK with four widgets placed, then saves process memory reports
after five idle seconds on Overview. Timings are grouped by Android's reported
COLD, WARM or HOT state. The script records PIDs before force-stop and before
launch, requiring termination of the prior PIDs. A background job can create a
new process before the post-stop PID check or activity launch; it must not be
mistaken for a surviving old process or counted as a guaranteed cold start.
These observations use the same emulator, but do not control
shared host load, ART profiles or file caches. They do not establish general speed,
scrolling, battery or background-work improvements.

Widget colour changes are not reproduced on a device. The 25-day Claude reset fixture has
not been verified against live source data.
Physical-device lifecycle, populated-account behavior and installation of the
published artifact remain separate checks. Release publication and verification
follow [release.md](release.md); a successful CI artifact build is not publication.

Usage Limits reads quota for the providers listed in the [README](../README.md). Google Vertex is deliberately
excluded because CliProxyAPI exposes it through API-key/service-account authentication rather
than OAuth. The main correctness risk is a
plausible but wrong percentage or reset time. Tests need to check what a number
means, which account it belongs to, and how old it is.

## Parser and credential regression contracts

Codex parsers retain declared unknown durations as OTHER / "Limit", preserving
percentages, resets and exhaustion. Known and legacy window assignments retain priority;
remaining unknowns use available long then short slots. A known 60% remaining stays HEALTHY
even when its duration maps to OTHER. Device-poll `invalid_client` maps to Unexpected;
xAI's mapped double-failure outcome is noData.

Swift cancellation checks prevent publishing failures and starting reactive renewal for
abandoned work. An exchange already in flight still completes through credential persistence.
Sink assertions check account identities and outcome kinds, not only call counts. Both
platforms recognize the evaluator's current reconnect message and the persisted legacy wording.

Linux framework shims catch missing declarations and selected type/concurrency errors;
real-SDK compilation and simulator runs provide separate evidence for SwiftUI behavior.

## Login and presentation regression checks

Synthetic HTTP and callback tests cover device-grant denial, expiry and persistent
`slow_down`; unrelated Codex callbacks; credential removal while renewal waits for its
lock; and outgoing sign-in clicks. Swift tests also cover browser-dismissal versus
loopback-bind errors, cancellation, and long device-poll error bodies.
`HttpClientRetryTest` covers temporary connection failures, retry limits, certificate
rejection and the prohibition on replaying grants after ambiguous delivery. The silent-peer
case of `LoopbackServerNoiseTest` can flake with `ConnectException`; its cause is not established.

Widget rendering tests cover Glance composition and host views. Builder, style, receiver
and scheduling tests cover selections, stale/reset presentation and update requests;
these separate checks do not prove the complete WorkManager-to-launcher repaint chain.
Use synthetic accounts for boundary and multi-instance checks. Compare each widget before,
at and after reset/stale boundaries without publishing new data; test save and cancel,
separate custom layouts and widgets sharing one layout, then repeat after process loss
and reboot. Validate system appearance, transparency and ink independently per widget.

For Android background-update diagnosis, capture scheduler, widget and standby state:

```sh
adb shell dumpsys jobscheduler
adb shell dumpsys appwidget
adb shell dumpsys deviceidle whitelist
adb shell am get-standby-bucket com.usagelimits
adb logcat -s UsageLimitsApp WidgetUpdater GlanceAppWidget SessionWorker WM-WorkerWrapper
```

Keep captures local and redact personal metadata before sharing. For iOS configuration
latency, record edit completion, provider callback, returned timeline and first matching
frame separately; a reload request does not guarantee a display deadline. Record OS,
launcher or Home Screen appearance, widget kind, selection and layout bounds so a dynamic
lead-account change can be distinguished from a changed configuration.

## What the tests establish

**Synthetic fixtures** record intended behavior and reproduce known failures.
They are useful regression tests, but a parser and fixture written from the same
assumption can agree and both be wrong.

**Production-shaped and upstream-derived fixtures** preserve field names, types,
nulls and nesting while using synthetic values. They cover cases such as Codex
weekly windows in the primary slot, numeric reset timestamps, integer
percentages, Claude's scoped windows and xAI's nested billing fields. Their
provenance matters: an upstream type and a captured account response are
different evidence. Neither re-confirms the provider's current live response.

**Integration and regression tests** exercise account isolation, token storage,
refresh and removal races, notifications, widget snapshots, OAuth parsing and
polling. Android includes real local socket tests for loopback cancellation and
timeouts, plus Robolectric storage and Compose screen tests. Those checks can
catch a regression without contacting a provider; they do not prove the browser
round trip on a phone.

Code review complements those tests by tracing the whole displayed result. A
correctly parsed value can still be paired with another account's reset, lose its
stale marker, or disagree with a widget. Findings should have a source trace and,
where practical, a demonstrating test before being treated as confirmed.

## Device runbook

Run the account-free steps first on both a debug and a minified Android build.
Record the commit, artifact, device, OS version and whether each check passed.
Repeat the relevant checks on an iOS build made with the real SDK.

### Before signing in

1. **Install and open.** A fresh installation reaches Overview with no accounts
   and no crash. Record cold-start time and memory use on the test device if
   reporting performance; include the measurement method.
2. **Open every tab.** Overview, Accounts, Resets and Settings render their empty
   states. Check large text, system appearance changes and reduced-motion
   settings. The iOS app currently requests dark appearance. Add both widget
   sizes and confirm their empty state too.

### Sign-in, with the account holder

Live sign-in and credential checks require the account holder's participation.
Use a dedicated test account with its own credentials: a provider may rotate its refresh
token, and a failed persistence step can leave a shared credential unusable. Kimi accounts
connected by a pasted key have no refresh grant.

3. **Codex:** the default is browser authorization with PKCE and a loopback
   callback on port 1455. The device-code flow is the fallback when the listener
   cannot bind. Verify the normal flow and fallback separately. Approval that
   succeeds in the browser but leaves the app waiting is a failure.
4. **Claude and Antigravity:** browser authorization returns to loopback ports
   54545 and 51121 respectively. Verify completion, browser cancellation and
   retry. A closed sheet without an account, an indefinite wait, or a port left
   occupied after cancellation is a failure.
5. **Grok and Kimi:** the app shows a device code and polls after browser
   approval. Check cancellation, expiry and denial as well as success. Kimi also
   offers a pasted key from the user's console; test it separately. Kimi OAuth
   access may remain blocked until the vendor permits the app's `UsageLimits`
   identity. Record that outcome without substituting another client's identity.
6. **Add two accounts on one provider.** They must remain separate, with the
   correct account label and windows. Reconnecting an existing account must not
   create a duplicate or overwrite a different account.

### Quota, resets and widgets

7. **Compare against the provider's own UI.** Check each account and each
   reported window, including the distinction between used and remaining quota.
   An unsupported or missing value must not become a confident zero.
8. **Check reset times now and later.** Compare with the wall clock and the
   provider UI. Return after a reset or after an hour: a frozen countdown or a
   past reset still shown as upcoming is a failure.
9. **Compare both widgets with the app.** Confirm account, percentage, severity,
   reset time and freshness agree. Refresh, background the app and reopen it.
   Remove an account during refresh and check that it stays removed everywhere.
10. **Check failures.** Disable the network and refresh. Previously fetched data
    should retain its age and show the failure; one failed account must not stop
    others. Restore the network and verify recovery. Check that notifications
    are not duplicated on every refresh or removed just because nothing changed.

### Credential renewal and credit redemption

11. **Observe OAuth renewal.** Leave the app installed until a token has expired,
    then refresh and reopen the app. Do not assume every provider expires or
    rotates tokens at the same interval. An account that works only until app
    restart suggests the replacement credential was not persisted. Resolve that
    before proceeding with any credit operation.
12. **Reset-credit redemption is optional and spends a real credit.** The account
    holder must deliberately approve it on a Codex account with an applicable
    credit. First confirm that the action is unavailable when
    `applicable_available_count` is zero. If redeemed, verify the result against
    the provider and confirm a single action did not produce repeated requests.

### If a check fails

Record the expected value, actual value, timestamps, account label without a
private identifier, and build version. When a payload is needed, retain a
sanitized copy of its shape and relevant values. Remove tokens, keys, cookies and
personal identifiers before sharing or committing evidence. Diagnose the cause before
repeating live authentication attempts.

## Remaining release decisions

Synthetic fixtures do not establish live-provider compatibility. Physical-device checks,
populated-account behavior and controlled performance comparisons require separate evidence.
Use workflow artifacts for the source revision being evaluated; historical results do not
verify the current release artifact.
Both platforms have the shared app-icon assets configured; their presence does
not establish store acceptance.

### Owner actions

- Complete provider permission and terms review, including Kimi's `UsageLimits` allowlist entry.
- Keep signing keys and provisioning credentials outside the repository.
- Supply the privacy policy and store/legal copy.
- Decide on store publication; a successful CI artifact build is not publication.

Ordinary HTTPS rejection is a compatibility finding to resolve with the provider; the runbook
does not include TLS fingerprint mimicry or another client's identity as a
workaround. Passing automated checks does not settle any of these release
decisions.
