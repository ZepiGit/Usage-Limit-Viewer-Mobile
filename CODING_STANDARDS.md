# Coding standards

Apply these five rules to new and changed code and tests. Each rule gives its reason and a place in this repository that shows the pattern. The examples show patterns to use, not that every existing test follows them.

## 1. Tests of order, concurrency and time

**Force the order in every test of ordering, concurrency or time.**

A test that waits and hopes passes on an idle machine and fails on a loaded one, and its green run does not show that it would catch the bug. Control the clock or the order of the operations under test through a seam (a gate, a test dispatcher, an injected clock). Do not use elapsed wall time to make an operation happen or to decide that it did not happen, and do not rely on the order in which test classes run. A timeout may fail a hung test; it must not be what makes an assertion pass. For a new or changed test of order, concurrency or time, temporarily break the behaviour it guards and report which assertion fails. For a timing-dependent fix, report which assertion fails with the fix reverted, or link two passing runs of the complete affected CI workflow on the same fixed commit. One green run is not enough.

Example: `interleaved` in `app/src/test/kotlin/com/usagelimits/statusicon/StatusIconPublicationTest.kt` holds an older publication inside its account read on a gate, lets a newer one run on the test scheduler as far as the publisher allows, and only then lets the older one go. In `ios/UsageLimitsKit/Tests/UsageLimitsKitTests/RefreshCoalescerTests.swift`, a gate holds the usage request. `ios/UsageLimitsKit/Tests/UsageLimitsKitTests/SerialScheduling.swift` routes immediate global jobs to the main actor while the test yields before checking which callers are still waiting.

## 2. Background work started by a test

**End all background work a test starts before the test ends.**

Leftover work can interfere with the next Robolectric sandbox, and the failure then shows up in another test's class, not in the one that left the work behind. In teardown, await completion or cancel and join the jobs the test starts. If the fixture starts real WorkManager, wait for cancellation, drain the main looper and then call `resetTestWorkManager()`. A publication substitute that never starts WorkManager needs no WorkManager reset.

Example: `drain()` in `app/src/test/kotlin/com/usagelimits/statusicon/StatusIconLifecycleTest.kt` cancels the work the test enqueued, runs the main looper until it is idle, then calls the helper. The helper is in `app/src/test/kotlin/com/usagelimits/core/sync/WorkManagerTestSupport.kt`, and `app/src/test/resources/robolectric.properties` describes what leftover work does to the next sandbox.

## 3. One way to do a thing

**Reuse the existing implementation or convention for the same job.**

Two ways to do the same job drift apart, and every reader has to learn both. A second test framework, a second convention or a second implementation of a decision that already has a home is a finding. The Kit's tests use XCTest, not Swift Testing. On Android, app-driven changes to widgets, status icons and usage alerts go through `AppContainer.publication` (see `docs/architecture.md`); Android's own widget lifecycle callbacks are separate. Each platform has one `WindowPresentation` and one account headline.

Example: `docs/adr/0001-one-presentation-per-usage-window.md` records how one presentation decision had spread to seven or eight places per platform and had already drifted before it became one module. `docs/adr/0002-one-account-headline-per-platform.md` does the same for the headline.

## 4. Behaviour both platforms share

**When you add or change a rule that must return the same result on Android and iOS, add or update one JSON vector file in `shared/test-vectors/` and have both suites read it directly.**

Android and iOS share behaviour, not code, so two implementations of one rule drift unless one table holds both to the same answers. Both suites read the file from the repository, not from copies. Expected values are worked out by hand from the rule, not produced by running either implementation, which would only record what the code does today. Where the platforms differ, the difference is a decision written down, not an accident.

Example: `shared/test-vectors/account-headline.json` states the rule in its description. It is read by `app/src/test/kotlin/com/usagelimits/core/model/HeadlineVectorsTest.kt` and by `ios/UsageLimitsKit/Tests/UsageLimitsKitTests/HeadlineVectorsTests.swift`. The section on the quota model in `docs/architecture.md` lists the presentation differences that are kept on purpose.

## 5. What docs and reports claim

**Write what the app does and what was observed, and say how it was observed.**

An emulator run, a view rendered in a test, a unit test and a phone are different kinds of evidence, and a reader acts on the sentence, not on the evidence behind it. A sentence must not promise more than its evidence carries: "the app requests silence, and the emulator's notification records showed no sound configured" does not establish what a phone will do, and does not support "the icons never make a sound". For a CI observation, link the run and name the commit it tested. For a local or device check, record the build or commit, the environment and what you did. To show that the current code behaves a certain way, link the relevant workflow on the latest commit that changed that behaviour; label older results as historical.

Example: the section "What has been checked, and where" in `docs/status-icons.md` shows how to keep observations apart from behaviour nobody checked: it names the CI run, the emulators and the synthetic accounts, and lists what was not checked on a phone. It is not the complete citation format; its runs are historical. The device checklist in `docs/release.md` holds the remaining checks.

These are the rules that kept coming back in review, not a style guide.

Formatting and naming follow the surrounding code.

A rule is added here when the same finding has come back several times.
