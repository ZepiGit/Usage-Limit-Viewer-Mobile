---
status: accepted
---

# One presentation decision per usage window, shared by every surface

Every surface that shows a usage window (app rows, lead strip, resets, widget previews, all Android
and iOS widgets, notifications) used to decide on its own how a figure, its fill and its exhausted
state follow from the display mode. The same decision existed in seven or eight places per platform,
and it had already drifted once: an iOS ring spoke one figure while drawing another. Each platform
now has one presentation module, `WindowPresentation`, that takes a usage window, the display mode
and the reading's validity (stale reading, reset due, error) and returns a structured value: the
figure token, the side (Left or Used), the fill fraction or no fill, the fill state (data, stale,
none) and whether the window is exhausted. It returns no strings. Words, the percent sign and spoken
sentences stay with each surface, so localization only swaps text. Colour also stays with each
surface, because Glance widgets carry their own palette. On iOS the module lives in
`UsageLimitsKit`, not the app target, so `swift test` on Linux covers it.

## Considered options

- Build it into the localization work. Rejected: one review would have covered two unrelated
  changes at once.
- Do it after localization. Rejected: localization would have touched the scattered call sites
  once, and the follow-up would have touched them again.

## Consequences

- The module landed before localization, as its own reviewed change.
- Two iOS defects were fixed as part of it, because they were exactly the fill and caption
  decisions the module took over: a fill that could leave its range, and a caption that reserved
  space for a hard-coded English literal.
- Exhausted is read from the provider's raw verdict only, never from an aged state (see
  `GLOSSARY.md`).
- Android and iOS run the same vector table against their module (`WindowPresentationTest` and
  `WindowPresentationTests`).
