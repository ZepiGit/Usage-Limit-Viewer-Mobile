---
status: accepted
---

# Overview and All accounts sort by headline; pins and other selections keep the stored order

Overview used to show accounts in the order the person last dragged them into, and the All
accounts widget used the same stored order. The redesign drops dragging (issue 88, owner decision
F3 of 18 November 2026): Overview should put the accounts that need attention first without being
arranged by hand. Status icons, on the other hand, should not move whenever a reading changes.
So there are now two orders, and each surface uses exactly one of them.

**Automatic order**, used by Overview and the All accounts widget, and by nothing else.
Accounts whose sign-in expired come first. Within each group the account headline
([ADR 0002](0002-one-account-headline-per-platform.md)) at the moment of presentation ranks the
account by its unrounded percentage left, lowest first. A headline the provider declares exhausted
counts as 0, even if its number is missing or positive. A headline without a figure, an account
whose reading has no windows, and an account with no reading at all come after every known figure
in their group. Ties go to the account added first, then to the account ID compared by Unicode
scalar values. The display mode never enters the order. The rank is computed when the surface is
drawn, before a filter or a widget's size trims the list, so a reset that falls due can reorder
the list without a new reading. Android does this in `inOverviewOrder` (`core/model/OverviewOrder.kt`).

**Stored order**, used by the pinned status icons and the Pinned accounts widget, the Provider
scope, the App Group export on iOS, and the stored-order ties in Closest Resets and Most Critical.
This is the order in the account database: the arrangement a build with dragging left behind, or
the order the accounts were added in. New accounts go last, and reconnecting keeps an account's
position. No UI can reorder existing accounts; changing which accounts are pinned leaves the
stored ordering rule intact. The repository writer (`UsageRepository.reorderAccounts`
on Android, `AccountRepository.reorder(ids:)` on iOS) and its tests remain so that saved
arrangements and test fixtures keep working. The legacy "manually ordered" preference stays in
storage but no view reads it. **Custom** keeps its own saved list of IDs, in its saved order.

Both platforms read `shared/test-vectors/overview-order.json`: the expected automatic, stored and
Custom orders for the same inputs, worked out by hand from the rule above.

## Considered options

- Sort the status icons by the same rank. Rejected by the owner: an icon that changes place with
  every reading cannot be found by position, and Android ranks separate notification groups on its
  own terms anyway ([ADR 0003](0003-status-icons-and-lock-screen-substitute.md)).
- Keep dragging as an override of the automatic order. Rejected: two sources of order on one
  screen, and a dragged order that a new reading silently overrides or that hides a tight account.
- Rank by the account's worst window instead of its headline. Rejected: a model-scoped pool
  running dry does not block the account (ADR 0002), and the mood, the dial and the row already
  speak for the headline.
- Compare IDs with each platform's default string ordering. Rejected: Kotlin compares UTF-16 code
  units and Swift compares canonical equivalence, so the two would disagree on IDs outside the
  ASCII range. IDs are ASCII UUIDs today; the vectors hold both cases anyway.

## Consequences

- Overview and All accounts lead with the same account at the same moment. Any other widget
  selection or the pinned icons may lead with a different one; this is intended.
- The order the app asks Android for, through each pinned account's notification `when`, is the
  stored order; pinning or unpinning changes which accounts are posted, not their order. What
  SystemUI draws is Android's decision: on API 30 the shade has listed an account whose reading
  had just changed first, against the app's `when` order (frame `05-reranked`, run
  [44586152463](https://github.com/ZepiGit/Usage-Limit-Viewer-Mobile/actions/runs/44586152463)
  on `343bd88`), and listed the stored order again in the next frames.
- The status-icon device evidence no longer has a reorder step. It seeds a pre-upgrade stored
  order with the repository writer as a fixture and changes one reading so that the driver's
  Overview and All accounts projections move. It then asserts that the stored order and the
  notification `when` order the app posts stay as they were, and that the changed account's records
  carry the new reading ([Status icon device evidence](../status-icon-evidence.md)).
- On iOS, an export written before `createdAt` existed cannot rank ties by added time; the widget
  uses the export's order until the app writes the next export.
