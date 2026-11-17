---
status: accepted
---

# One presentation decision per usage window, shared by every surface

Every surface that draws a usage window (app rows, lead strip, resets, widget previews, all Android
and iOS widgets) used to decide on its own how a figure, its fill and its exhausted
state follow from the display mode. The same decision existed in seven or eight places per platform,
and it had already drifted once: an iOS ring spoke one figure while drawing another. Each platform
now has one presentation module, `WindowPresentation`, that takes a usage window, the display mode
and the reading's validity (stale reading, reset due, error) and returns a structured value: the
figure token, the side (Left or Used), the fill fraction or no fill, the fill state (data, stale,
none) and whether the window is exhausted. It returns no localized words or
sentences. Words, the percent sign and spoken sentences stay with each surface, so localization
only swaps text. Notifications are not a consumer: they evaluate thresholds on the remaining
percentage and choose their own wording by display mode. Colour also stays with each
surface, because Glance widgets carry their own palette. On iOS the module lives in
`UsageLimitsKit`, not the app target, so `swift test` on Linux covers it.

## Considered options

- Build it into the localization work. Rejected: one review would have covered two unrelated
  changes at once.
- Do it after localization. Rejected: localization would have touched the scattered call sites
  once, and the follow-up would have touched them again.

## Consequences

- The module landed before localization, as its own reviewed change.
- An iOS fill that could leave its range was fixed as part of it, because that is exactly the fill
  decision the module took over. Two caption changes on iOS widgets landed alongside and stay with
  the surface: the caption's reserved height now scales with the text size, and its wording comes
  from one place instead of an inline English literal.
- Exhausted is read from the window's raw, unaged severity (the provider's verdict or a reading of
  zero remaining), never from a stale or failed state that replaced it (see `GLOSSARY.md`).
- Android and iOS check their module against the same table of vectors (`WindowPresentationTest`
  and `WindowPresentationTests`).
