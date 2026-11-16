# Throwaway status bar prototype

Branch: `prototype/statusbar-icons`. Never merge this branch into main. There are no new behavioral tests: the Robolectric runner is only a native-graphics PNG generator.

## Extended experiments

The owner corrected the design goal: provider glyph plus digits 0–99, and glyph alone at exactly 100. The renderer compares B's 5 vs 05 and usual-position vs centered 100, plus noncolliding stale dot/underline/hollow digits. Ring-around-digits E is local comparison only, not an emulator target. Composite F tests bars and concentric arcs for up to three ordered accounts, because one summary icon may be the honest fallback.

API 34/35 add plain a/b capture lanes. API 36 also captures one channel per account; a valid shared summary carrying F bars/rings; DEFAULT/LOW/MIN section split; plain colorized (expected nonexempt); three summaries with one, two or three real quota-detail children each. The 1/2-child groups are sparse but below android16-release NMS's six-sparse-group threshold; 3 children avoid the sparse definition. No fake call, media, conversation, foreground service or UIJ is posted. Source exemptions and flag qualifications are in `statusbar-android16-grouping-exemptions.md`.

## Questions

1. Which alpha-mask layout survives a 15 dp status-bar drawing slot?
2. How do ungrouped notifications, separate groups, a shared summary, silent DEFAULT importance, competing notifications, and lock/doze affect the icons on real Android SystemUI?
3. Does updating the same ID replace its bitmap silently, and does swiping an ongoing notification deliver its delete intent?

## Regenerate contact sheets

From this worktree run `bash tools/prototype_statusbar_contacts.sh`. It acquires `/tmp/ulv-gradle.lock`, runs only the prototype renderer with native Robolectric graphics, writes the sheets to `app/build/reports/statusbar-prototype/`, and copies the exact A/C/E/F source masks into debug-only assets. The checked-in masks are the actual masks used by the emulator; no second renderer guesses their appearance. `docs/images/statusbar-prototype/` preserves the generated sheets on this throwaway branch as primary-source pixels, separate from ephemeral build output.

Primary structures are A digits only, B glyph over digits, C corner provider glyph and digits; D ring around provider glyph and E ring around digits remain comparison-only alternatives. F compares composite bars and concentric rings. Claude uses the Code mascot source, Codex an intentionally simplified six-loop OpenAI knot, xAI the local LobeHub path. White source RGB has no meaning: only alpha survives SystemUI tinting. Normal single digits are zero-padded (`05`). Stale is `42` plus a separated corner dot, exhausted is a crossed circle, error is `!`. The owner's rule for 100 is provider glyph only, no numeric token; B comparison sheets preserve both centered and usual-position glyph options.

`density-*.png` is authoritative for true pixel size and exact nearest-neighbour ×8. `source-authored-192px.png` shows the full 192px masks. Overview sheet previews are capped and explicitly labelled as such. The xxhdpi strip uses 45px drawings centered in 66px/22dp slots, with a clock and system-side placeholders.

## Real emulator pixels

The branch push triggers `.github/workflows/prototype-statusbar.yml` for AOSP API 36 and API 30. Bootstrap uses the established 720×1560, 280 dpi Pixel 7 Pro viewport, four cores, 4096 MB RAM and SwiftShader. Local Gradle invocations use the shared flock and offline mode. GitHub-hosted runners resolve dependencies online using the existing Android workflow convention (explicitly approved by the orchestrator); CI has no shared host lock. The debug-only activity labels the active scenario; an exported debug-only receiver posts real notifications using the generated bitmap assets.

The driver captures full screenshots, exact top-strip crops, expanded shades, `dumpsys notification --noredact`, SystemUI/statusbar dumps, uiautomator hierarchies, event logs, update keys and timestamps, swipe coordinates and delete-intent logs. Scenario e invokes the actual `NotificationPublisher` through synthetic local account data and also posts two shell-package notifications. Update and dismissal are performed in b for both candidate variants. API 30 predates Android 14's unlocked ongoing dismissal behavior, so lack of deletion there is an observation, not a scripted workaround.

Always-on is best effort: a black sleeping screenshot does not prove AOD works; the corresponding power/dream dumps must show a real doze/AOD state. No screenshot is synthesized into emulator evidence. LOW visibility is observed with the emulator's current settings, not guaranteed against the user's hide-silent-icons preference. Cross-app order/overflow is observed, not controllable by sortKey.

A source-review correction removed `setSortKey` from case a: Android counts sortKey alone as an app group, so the earlier a captures were summary-less channel groups, not a genuinely ungrouped baseline. Case b keeps sortKey and explicit per-account group keys. The final four-release matrix distinguishes these correctly.

First emulator run `43972180341` exposed a real API 36 gotcha: a fixed 2023 `setWhen` timestamp caused NotificationService to reject the posts as `Ignored enqueue for old ... notification`, even with showWhen disabled. API 30 accepted them. The corrected entrypoint uses current time for new posts and preserves each original notification time on updates; the driver refuses to capture a baseline without all three actual posted records.

Corrected run `43974065784` posted all three records on both APIs. API 36 forcibly aggregates the distinct singleton groups into `Aggregate_SilentSection` (or `Aggregate_AlertingSection` for DEFAULT), replacing the three account icons with an autogenerated stack-summary icon. API 30 keeps the distinct singleton groups. Swiping the collapsed API 36 summary dismissed all three and delivered all delete intents, so the final driver explicitly expands that group first and requires only Codex to be removed.

Extended run [43977478191](https://github.com/ZepiGit/Usage-Limit-Viewer-Mobile/actions/runs/43977478191) passed AOSP API 30/34/35/36. API 36 summary+one-child per account preserved all three bitmap status icons and distinct effective group keys; two/three-child groups did too. Per-account channels and plain colorized still forced aggregation. A shared valid three-child summary showed the supplied F composite only; its child icons did not appear in the status bar. DEFAULT/LOW/MIN split retained the DEFAULT icon but aggregated the LOW/MIN pair, not a third section. Expanding the forced group before a child swipe removed only Codex7101, retained7100/7102, and logged DELETE_INTENT7101 for both layouts.

Local generation and `:app:assembleDebug` completed successfully. Deprecation warnings in the throwaway activity concern legacy bar-color/tint setters retained for API 30 comparison; target-36 edge-to-edge behavior is checked from the screenshots rather than assumed.
