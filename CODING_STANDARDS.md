# Coding standards

A review checks every change against these five rules. Each rule gives its reason and a place in this repository that follows it.

## 1. Tests of order, concurrency and time

**Force the order in every test of ordering, concurrency or time.**

A test that waits and hopes passes on an idle machine and fails on a loaded one, and its green run does not show that it would catch the bug. Drive the interleaving through a seam (a gate, a test dispatcher, an injected clock), and show that the test fails when the guarded code is broken, for example by reverting the fix. No sleeps, no time windows, and no reliance on the order in which test classes run. A fix for a timing-dependent failure is proven the same way, or by two full CI runs, never by one green run.

Example: `interleaved` in `app/src/test/kotlin/com/usagelimits/statusicon/StatusIconPublicationTest.kt` holds an older publication inside its account read on a gate, lets a newer one run on the test scheduler as far as the publisher allows, and only then lets the older one go. On iOS, `ios/UsageLimitsKit/Tests/UsageLimitsKitTests/RefreshCoalescerTests.swift` holds a usage request open until the test releases it, and `ios/UsageLimitsKit/Tests/UsageLimitsKitTests/SerialScheduling.swift` runs every task on one serial queue, so "not finished yet" has one answer.

## 2. Background work started by a test

**End all background work a test starts before the test ends.**

WorkManager and coroutine work that outlives a test breaks the next test that runs in the same JVM, and the failure shows up in that test's class, not in the one that left the work behind. Tests that touch WorkManager or the publication module reset it through the shared helper `resetTestWorkManager()` after each test.

Example: `drain()` in `app/src/test/kotlin/com/usagelimits/statusicon/StatusIconLifecycleTest.kt` cancels the work the test enqueued, runs the main looper until it is idle, then calls the helper. The helper is in `app/src/test/kotlin/com/usagelimits/core/sync/WorkManagerTestSupport.kt`, and `app/src/test/resources/robolectric.properties` describes what leftover work does to the next sandbox.

## 3. One way to do a thing

**Use the existing way to do a thing instead of adding a second one.**

Two ways to do the same thing drift apart, and every reader has to learn both. A second test framework, a second convention or a parallel path beside an existing module is a finding. The Kit's tests use XCTest, not Swift Testing; Android publishes out-of-app state through the one publication module, `AppContainer.publication` (see `docs/architecture.md`); each platform has one `WindowPresentation` and one account headline.

Example: `docs/adr/0001-one-presentation-per-usage-window.md` records how one presentation decision had spread to seven or eight places per platform and had already drifted before it became one module. `docs/adr/0002-one-account-headline-per-platform.md` does the same for the headline.

## 4. Behaviour both platforms share

**Pin behaviour both platforms share with one vector file that both test suites read.**

Android and iOS share behaviour, not code, so two implementations of one rule drift unless one table holds both to the same answers. The files live in `shared/test-vectors/` and both suites read them from the repository, not from copies. Expected values are worked out by hand from the rule, not produced by running either implementation, which would only record what the code does today. Where the platforms differ, the difference is a decision written down, not an accident.

Example: `shared/test-vectors/account-headline.json` states the rule in its description and is read by `HeadlineVectorsTest` on Android and `HeadlineVectorsTests` in the Kit. The section on the quota model in `docs/architecture.md` lists the presentation differences that are kept on purpose.

## 5. What docs and reports claim

**Write what the app does and what was observed, and say how it was observed.**

An emulator run, a view rendered in a test, a unit test and a phone are different kinds of evidence, and a reader acts on the sentence, not on the evidence behind it. A sentence must not promise more than its evidence carries: "the app requests silence and the emulator recorded no sound" does not support "the icons never make a sound". Cite the CI run of the last commit that changed code.

Example: the section "What has been checked, and where" in `docs/status-icons.md` names the CI run, the emulators and the synthetic accounts, and lists what was not checked on a phone. The device checklist in `docs/release.md` holds those remaining checks.

These are the rules that kept coming back in review, not a style guide.

Formatting and naming follow the surrounding code.

A rule is added here when the same finding has come back several times.
