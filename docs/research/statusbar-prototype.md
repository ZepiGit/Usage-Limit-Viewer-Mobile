# Throwaway status bar prototype

Branch: `prototype/statusbar-icons`. Never merge this branch into main. There are no new behavioral tests: the Robolectric runner is only a native-graphics PNG generator.

## Questions

1. Which alpha-mask layout survives a 15 dp status-bar drawing slot?
2. How do ungrouped notifications, separate groups, a shared summary, silent DEFAULT importance, competing notifications, and lock/doze affect the icons on real Android SystemUI?
3. Does updating the same ID replace its bitmap silently, and does swiping an ongoing notification deliver its delete intent?

## Regenerate contact sheets

From this worktree run `bash tools/prototype_statusbar_contacts.sh`. It acquires `/tmp/ulv-gradle.lock`, runs only the prototype renderer with native Robolectric graphics, writes the sheets to `app/build/reports/statusbar-prototype/`, and copies the exact A/C source masks into debug-only assets. The checked-in masks are the actual masks used by the emulator; no second renderer guesses their appearance. `docs/images/statusbar-prototype/` preserves the generated sheets on this throwaway branch as primary-source pixels, separate from ephemeral build output.

Four intentionally different structures: A digits only; B glyph over digits; C corner provider glyph and digits; D ring around provider glyph. Claude uses the Code mascot source, Codex an intentionally simplified six-loop OpenAI knot, xAI the local LobeHub path. White source RGB has no meaning: only alpha survives SystemUI tinting. Normal single digits are zero-padded (`05`). Stale is `42` plus a separated corner dot, exhausted is a crossed circle, error is `!`. The prototype does not establish a production rule for 100; the design verdict must do so.

`density-*.png` is authoritative for true pixel size and exact nearest-neighbour ×8. `source-authored-192px.png` shows the full 192px masks. Overview sheet previews are capped and explicitly labelled as such. The xxhdpi strip uses 45px drawings centered in 66px/22dp slots, with a clock and system-side placeholders.

## Real emulator pixels

The branch push triggers `.github/workflows/prototype-statusbar.yml` for AOSP API 36 and API 30. Bootstrap uses the established 720×1560, 280 dpi Pixel 7 Pro viewport, four cores, 4096 MB RAM and SwiftShader. Local Gradle invocations use the shared flock and offline mode. GitHub-hosted runners resolve dependencies online using the existing Android workflow convention (explicitly approved by the orchestrator); CI has no shared host lock. The debug-only activity labels the active scenario; an exported debug-only receiver posts real notifications using the generated bitmap assets.

The driver captures full screenshots, exact top-strip crops, expanded shades, `dumpsys notification --noredact`, SystemUI/statusbar dumps, uiautomator hierarchies, event logs, update keys and timestamps, swipe coordinates and delete-intent logs. Scenario e invokes the actual `NotificationPublisher` through synthetic local account data and also posts two shell-package notifications. Update and dismissal are performed in b for both candidate variants. API 30 predates Android 14's unlocked ongoing dismissal behavior, so lack of deletion there is an observation, not a scripted workaround.

Always-on is best effort: a black sleeping screenshot does not prove AOD works; the corresponding power/dream dumps must show a real doze/AOD state. No screenshot is synthesized into emulator evidence. LOW visibility is observed with the emulator's current settings, not guaranteed against the user's hide-silent-icons preference. Cross-app order/overflow is observed, not controllable by sortKey.

First emulator run `43972180341` exposed a real API 36 gotcha: a fixed 2023 `setWhen` timestamp caused NotificationService to reject the posts as `Ignored enqueue for old ... notification`, even with showWhen disabled. API 30 accepted them. The corrected entrypoint uses current time for new posts and preserves each original notification time on updates; the driver refuses to capture a baseline without all three actual posted records.

Local generation and `:app:assembleDebug` completed successfully. Deprecation warnings in the throwaway activity concern legacy bar-color/tint setters retained for API 30 comparison; target-36 edge-to-edge behavior is checked from the screenshots rather than assumed.
