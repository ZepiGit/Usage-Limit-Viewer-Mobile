# Details

Reference notes for [Usage Limits Mobile](../README.md) 0.5.1. Both native apps have three destinations: **Overview**, **Resets** and **Settings**. Account management starts in Overview; there is no separate Accounts tab.

## Overview, Resets and Settings

| Destination | What it shows |
| :--- | :--- |
| Overview | Usage accounts as a list or as dials, plus the optional Credits block |
| Resets | Recorded upcoming resets, grouped by day with a live countdown |
| Settings | Appearance, display mode, Overview layout, icons, sync and notifications |

Choose **Settings → Overview → Layout** for **List** or **Dials**. List shows every usage window and emphasises the account's **headline**; Dials shows one headline ring per account. Both open the same detail screen. Overview shows expired sign-ins first, then the lowest headline. In List you can filter **All**, **Needs attention** (expired sign-ins only) or **Resets today**. The Left/Used choice is explained in [Widgets](widgets.md#percentages-left-or-used); the headline rules are in [ADR 0002](adr/0002-one-account-headline-per-platform.md).

The app supports several accounts per service and keeps the last readings after a failed refresh (a passed reset is marked **Reset due**, not a fresh allowance). It never sends a prompt; it only asks each service for your usage. English and German.

Widgets: [Widgets](widgets.md). Pinned usage and alerts: [Notifications](notifications.md). Services and keys: [Providers](PROVIDERS.md) and [Credit balances](credits.md). Platforms and sizes: [Devices](DEVICES.md).

## Claude Code figure

Under **Settings → Provider icons → Claude**, the **Claude Code** choices use the account's headline for their figure. The mood uses the raw percentage left, never the rounded or Used figure. In Resets the figure always uses Hooray.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="images/readme/12-claude-moods-dark.png">
  <img src="images/readme/12-claude-moods-light.png" width="880" alt="Six Claude Code figure moods: Asleep, Tired, Working, Idle, Dancing and Hooray, with the % left (or, for Hooray, the reset) that triggers each.">
</picture>

| Raw headline percentage left | Steady mood |
| :--- | :--- |
| 0, or provider-declared exhaustion even with a positive or missing figure | Asleep |
| Above 0 through 20 | Tired |
| Above 20 through 50 | Working |
| Above 50 through 90 | Idle |
| Above 90 | Dancing |
| No known figure, without declared exhaustion | Idle |

On a German device, use **Einstellungen → Übersicht → Layout → Liste/Ringe**, **Anzeige → Prozente anzeigen als**, or **Erscheinungsbild** for **Textschrift**, **Ziffernschrift** and **Schriftlizenzen**. The List filters are **Alle**, **Braucht Aufmerksamkeit** and **Resets heute**.

The four themes and fonts affect the app, not placed widgets ([ADR 0005](adr/0005-app-and-widget-appearance.md)). Font licences are listed under **Font licences** and in the [canonical font manifest](../third_party/fonts/manifest.json).

## Your data

- **Keys and tokens stay locked away**, in the Android Keystore or the iOS Keychain. They are never written to the usage database and never logged.
- **Widgets only see numbers.** They read the cached usage, never your credentials.

Several services don't officially publish the usage pages the app reads, so an update on their side can break a card until the app catches up. Details: [security model](security.md).

## Build notes

The README has the build commands. [0.5.1](https://github.com/ZepiGit/Usage-Limit-Viewer-Mobile/releases/tag/v0.5.1) carries the signed Android APK and AAB, the R8 mapping and an unsigned iOS archive. A debug APK from an Android run under **Actions** is a separate build; match it to its commit.

The images in this repository are Robolectric captures and renders of the native views in device frames. The full-size 1080×1920 store screenshots are in [store-previews](store-previews/). The [click-through demo](https://raw.githack.com/ZepiGit/Usage-Limit-Viewer-Mobile/e63f204ab5e8e7b4ea86983734903620715d57ed/docs/demo/index.html) shows the screens in a browser.

## Documentation

| If you want to… | Read |
| :--- | :--- |
| check whether your phone is supported | [Devices](DEVICES.md) |
| see every widget | [Widgets](widgets.md) |
| see every supported service | [Providers](PROVIDERS.md) |
| keep accounts in the status bar or on the Lock Screen | [Notifications](notifications.md), [Status icons](status-icons.md) |
| know how your data is protected | [Security](security.md) |
| set up a specific service | [Codex](providers/codex.md), [Kimi](providers/kimi.md), [Z.ai](providers/zai.md), [Ollama](providers/ollama.md), [MiniMax](providers/minimax.md) and the other [provider guides](providers/) |
| use the Credits section | [Credits](credits.md) |

For development, read the [Architecture](architecture.md) and [Release guide](release.md). Reviews check changes against the [Coding standards](../CODING_STANDARDS.md).

Found a problem or have a feature request? Please open an [issue](https://github.com/ZepiGit/Usage-Limit-Viewer-Mobile/issues/new/choose). For vulnerabilities, follow the [security policy](../SECURITY.md) to report privately.

[MIT](../LICENSE), copyright (c) 2026 ZepiGit. Third-party artwork notices and trademark rights remain as documented in [Provider artwork](providers/assets.md).
