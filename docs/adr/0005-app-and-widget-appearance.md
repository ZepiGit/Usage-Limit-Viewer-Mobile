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
This is appearance protection, not the widget redesign planned for ticket 07.

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
configuration activity with a Codex Account Rings fixture, checks the existing warm scene colours
and a represented figure, and compares its pixels across saved themes with another font pair.
It drives the shared display mode to Used and back to Left before comparison so the editor has
visibly consumed settings emissions. Passing `-PcaptureDir=<directory>` exports the baseline and
variant images for visual inspection. These are Robolectric rendering checks, not launcher or device evidence;
their execution and image observations belong in the ticket report.
