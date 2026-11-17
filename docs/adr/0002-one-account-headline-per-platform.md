---
status: accepted
---

# One account headline, defined once per platform and proven by shared vectors

An account usually has several usage windows, but a widget row, the lead strip or a status icon
has room for one. Which window speaks for the account used to be answered in several places:
Android's `UsageSnapshot.mostCritical` (which took no clock), an inline expression in the widget
data, a fallback inside `UsageViewModel.leadAt`, and on iOS `GlanceAccount.representative(at:)`
next to an unused `mostCritical` in the Kit. The concept now has one name, the headline (see
`GLOSSARY.md`), and one definition per platform in the model layer: `UsageSnapshot.headlineAt` in
`core/model/Usage.kt` and `headline(fetchedAt:at:)` in the Kit's `Model/Headline.swift`, which the
widget export's `representative(at:)` calls. Every caller uses it, so the app, the widgets and the
status icons cannot pick different windows for the same account at the same moment.

The rule is the one the widgets already followed on both platforms. Windows whose reset is due
drop out, unless that would leave none. If any remaining window is global, only global windows
compete. An exhausted window wins, then the lowest remaining percentage; an unknown percentage
loses to any known one, and a tie goes to the first window in provider order. No windows, no
headline. The rule takes the time as an input, because whether a reset is due depends on it.

Android and iOS share behaviour, not code. Both test suites read one hand-written file,
`shared/test-vectors/account-headline.json` (`HeadlineVectorsTest` and `HeadlineVectorsTests`),
from the repository rather than from copies. Both readers fail on an unknown key or an empty
file, so a renamed field cannot quietly skip vectors. The expected values were written from the
rule, not generated from either implementation. Built in pull request 36 (issue 29).

## Considered options

- Keep `mostCritical` and its siblings, each next to its caller. Rejected: only some of them took
  the time into account, so only some dropped a window whose reset had passed.
- Generate the expected values from one platform's output. Rejected: the vectors would then only
  repeat what that platform already does, mistakes included.

## Consequences

- Where the platforms had disagreed, the vectors now decide. Without a fetch time, any passed
  reset counts as due on both platforms, as iOS already did; and exhausted means the provider said
  so or nothing is left.
- Two presentation differences outside the headline remain, and `docs/architecture.md` lists them:
  how app and widget rows mark a reset due when the measurement time is missing, and the
  cross-account lead fallback.
- New surfaces that need one window per account, such as the status icons
  ([ADR 0003](0003-status-icons-and-lock-screen-substitute.md)), take the headline instead of a
  rule of their own.
