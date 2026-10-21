# Image sources

The original `app-icon.png`, platform badges, `devices.png`, `widgets.png` and individual device assets come unchanged from the first Terracotta package. They are source-based illustrations. The three device shells keep their original identities, including the iPad.

## Widget illustrations

The added SVG/PNG illustrations use `demo-accounts.json`: four separate Codex and Claude Personal/Work identities with consistent values. They depict source-defined layouts, colors and provider artwork. Captions sit outside the widget boundaries; Mini Rings contain logos rather than invented account labels. Transparent backdrops are illustrations, not captured wallpaper.

The source basis is commit [`bfb9a14fe73d37b262d1007fd6d20c3f44513cb2`](https://github.com/ZepiGit/Usage-Limit-Viewer/tree/bfb9a14fe73d37b262d1007fd6d20c3f44513cb2), inspected on 14 September 2026: Android `UsageWidgets.kt`, `RingWidgets.kt`, `WidgetStyle.kt`, `WidgetLayout.kt`; iOS `ConfiguredWidgets.swift`, `UsageWidget.swift`; and `assets/providers/lobehub/openai.svg` and `claudecode-color.svg`. These are illustrations rather than native widget captures. No image-generation model was used.

## Native screenshots

The five PNGs in `screenshots/` come unchanged from `ios-marketing-screenshots`, [run 34712814242](https://github.com/ZepiGit/Usage-Limit-Viewer/actions/runs/34712814242), artifact `10303876932`. Capture commit: `abc43759b19bcc59894b9ab055fec358916e1065`, 12 September 2026. Included `CAPTURE.txt` records the capture metadata.

The screenshots use synthetic accounts. The tour adds a generic phone frame without retouching them; its tab navigation does not contact providers. Artwork attribution and trademark details are in [Provider artwork](../provider-assets.md).
