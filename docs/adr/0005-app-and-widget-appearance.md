---
status: accepted
---

# App themes do not choose widget appearance

Android's app theme is one persisted `AppSettings.appTheme` value, written through
`SettingsStore.setAppTheme`: Warm dark, Pure black (the app default), Cool graphite or Light
paper. It chooses app colours and launch presentation, not a placed widget's palette.

A placed widget keeps its saved `WidgetConfigEntity` background, opacity and text tone.
`WidgetStyle` derives its ink, tracks and data colours from those inputs, independently of the
app theme. Existing widget defaults, artwork and colour choices remain unchanged. The display
mode, provider icon choice and sync interval remain shared inputs; this boundary does not freeze
a widget's figures, artwork selection or reading validity.

The widget configuration activity explicitly uses `UsageLimitsTheme(appTheme =
AppTheme.WARM_DARK)`. Its warm ground, cards, controls and provider artwork therefore retain the
pre-theme configuration scene, including when Light paper is saved for the app. The preview
still paints its panel, figures and bars or rings with the selected `WidgetStyle`; its mat
remains `#3A3936`. App text and figure preferences do not enter the editor. Represented
figures keep the editor's original system monospace style, not the app's `UsageType`.
The widget redesign (ticket 07) changes the ring preview content and adds display-mode guidance to
Text; neither change makes the editor follow the app's theme or fonts.

## iOS app and WidgetKit

The same boundary applies to iOS. `AppSettings.appearanceTheme`, `appearanceTextFont` and
`appearanceFigureFont` belong to the app's settings JSON, not its App Group snapshot.
`appAppearance(_:)` supplies a view-local `usageAppearance` value after the existing settings
load gate. The root stays on the neutral launch surface until settings have been installed;
then native light/dark appearance, app colours and fonts change together. A system launch screen
or cached scene cannot read that file and is not promised to match the saved choice.

The environment default is `UsageAppearance.widget`: the original Warm dark roles and native
system text/monospace. `UsageColors` remains that fixed palette for WidgetKit, and `WidgetInk`
continues to resolve its own saved panel. A widget-styled app preview explicitly installs
`.environment(\\.usageAppearance, .widget)`. No extension target receives `UIAppFonts`, the
canonical font folder or notices. Display mode and provider artwork remain shared as before.

The app references `third_party/fonts/android-res` directly as an Xcode folder resource. Its
nine font files and six complete notices are checked against the canonical manifest by
`ios/Tools/check_font_bundle.py`, including the registration list and extension isolation.
Apple's System text choice is unbundled; bundled variable faces request their actual `wght`
axis (450/560 for text, 400/500/700 for figures), with native/static Regular and Medium fallback.

## Considered options

- Let configuration inherit the app theme. Rejected: it changes the existing configuration scene
  and can apply light-theme provider-art tint before the widget redesign.
- Store a second copy of app settings for widgets. Rejected: appearance already has per-widget
  storage, and shared inputs already flow through `SettingsStore` and `WidgetUpdater`.

## Consequences and checks

`WidgetUpdater.observe` and `observeCredits` combine the full `settingsStore.settings` flow.
Changing `appTheme` can therefore rebuild their projections and reevaluate reading time, even
though neither projection reads it for style. No second observer or settings mechanism is added,
and this decision does not promise that app-theme writes cause no widget emissions.

`WidgetAppearanceRenderingTest` saves every app theme through the same settings store, applies
Compact widget RemoteViews to a host, and checks known default/white panel pixels and known light/
dark ink as well as unchanged text colours. `WidgetConfigActivitySaveTest` captures the real
configuration activity with a Codex Account Rings fixture, checks the existing warm scene colours,
and compares its pixels after changing the saved app theme and fonts. It drives the shared display
mode to Used and back to Left before comparison so the editor has visibly consumed settings
emissions. Passing `-PcaptureDir=<directory>` exports the baseline and variant images for visual
inspection. Separately, `WidgetConfigPreviewTest` renders `ContentPreview` with IBM Plex Mono
selected for app figures and checks that Account Rings and Usage Bars still request system
monospace for their figures. These are Robolectric checks, not launcher or device evidence. The
run results and image observations belong in the ticket report.
