<p align="center"><img src="docs/01-terracotta/app-icon.png" width="76" alt="Usage Limits app icon"></p>

<h1 align="center">Usage Limits Mobile</h1>

<p align="center"><strong>Check your usage limits and reset times.</strong><br>Track Codex, Claude, Antigravity, Grok, Kimi, Devin, Meta Muse, Z.ai and OpenCode Go, plus prepaid API credits, on your phone and in home-screen widgets.</p>

<p align="center">
<a href="#build"><img src="docs/01-terracotta/android-badge.png" height="25" alt="Android 8.0+"></a> <a href="#build"><img src="docs/01-terracotta/ios-badge.png" height="25" alt="iOS 16.0+"></a> <a href="#build"><img src="docs/01-terracotta/source-badge.png" height="25" alt="Build from source"></a> 
</p>

<p align="center"><a href="#features">Features</a> · <a href="#home-screen-widgets">Widgets</a> · <a href="#multiple-accounts-per-provider">Multiple accounts</a> · <a href="#app-tour">App tour</a> · <a href="#providers">Providers</a> · <a href="#build">Build</a> · <a href="#documentation">Documentation</a></p>

<p align="center"><img src="docs/01-terracotta/devices.png" width="1000" alt="Usage Limits on Android, iPhone and an unfolded foldable"></p>

> **Development preview.** See [Build](#build) to run the app from source.

<p align="center"><a href="https://rookieenough.github.io/Orion-Data/redirect.html?id=firefin"><img src="https://raw.githubusercontent.com/RookieEnough/Orion-Store/refs/heads/main/assets/orion-badge.png" width="206" alt="Get it on Orion Store"></a></p>

## Features

| Accounts | Reset times | Home-screen widgets |
| :--- | :--- | :--- |
| Check usage across all your connected accounts. | See which limit resets next and when. | Check your usage without opening the app. |

<img src="docs/01-terracotta/widgets.png" width="1000" alt="Usage Summary home-screen widget">

## Home-screen widgets

Choose a compact summary, detailed usage bars or rings to keep your usage visible on
the home screen.

<p align="center"><a href="docs/01-terracotta/widgets-gallery.png"><img src="docs/01-terracotta/widgets-gallery.png" width="1000" alt="Usage Bars, Usage Summary, Account Rings and Mini Rings widgets"></a></p>


### Usage Summary

Shows the five-hour and longer-term allowances, the next reset and the account's quota
status in a compact row. The layout adjusts to the widget's width.

To track more than one account, add a widget for each and select the account in its settings.

<p align="center"><a href="docs/01-terracotta/summary-scopes.png"><img src="docs/01-terracotta/summary-scopes.png" width="1000" alt="Usage Summary widgets for personal and work Codex accounts"></a></p>

### Usage Bars

Shows each account's provider, remaining allowances and reset times as a set of usage bars.
Filter by provider or choose a custom set of accounts and arrange them in any order.

### Account Rings

Shows a usage ring around each provider logo, with the account name and reset time beside
it. Larger widgets can display multiple columns.

### Mini Rings

Compact rings with a provider logo for each account. Choose which accounts appear and
their order in the widget settings.

<p align="center"><a href="docs/01-terracotta/rings-multiaccount.png"><img src="docs/01-terracotta/rings-multiaccount.png" width="1000" alt="Account Rings and Mini Rings with personal and work accounts"></a></p>

<details>
<summary><strong>iPhone and iPad widgets</strong></summary>

<p align="center"><a href="docs/01-terracotta/ios-widget-gallery.png"><img src="docs/01-terracotta/ios-widget-gallery.png" width="1000" alt="Usage Bars, Account Rings, Mini Rings, Usage Limits, Usage Limits Clear and Usage Ring on iOS"></a></p>

| Widget | Availability | Layout and options |
| :--- | :--- | :--- |
| **Usage Bars** | iOS 17+ | Usage bars in small, medium and large sizes. Choose an account, a provider or a custom selection. |
| **Account Rings** | iOS 17+ | A grid of rings with account names and reset times. |
| **Mini Rings** | iOS 17+ | Compact rings with provider logos. |
| **Usage Limits** | iOS 16+ | Usage tiles in small, medium and large sizes; also available on the Lock Screen as a rectangular widget. |
| **Usage Limits (Clear)** | iOS 16+ | Usage tiles with a transparent background. |
| **Usage Ring** | iOS 16+ | A single-account ring; also a circular Lock Screen accessory. |

The iOS 16 widgets select accounts using the app's ranking. On iOS 17+, use Usage Bars,
Account Rings or Mini Rings to choose an account, filter by provider or use a saved layout.

</details>

### Widget settings

| Choose the content | Arrange the accounts | Set the appearance |
| :--- | :--- | :--- |
| Show all accounts, one provider, one account or a custom selection. | Use the app's account order or set your own. | Android: background colour, opacity and text colour. Configurable iOS widgets: transparent background and text colour. |

Widgets display locally cached usage. Refreshing a widget requests a sync on Android and
opens the app on iOS. See [Widgets](docs/widgets.md) for configuration and technical details.

## Multiple accounts per provider

Add multiple accounts from the same provider, such as personal and work subscriptions.
Each account has its own usage limits and reset times.

<p align="center"><a href="docs/01-terracotta/accounts-by-provider.png"><img src="docs/01-terracotta/accounts-by-provider.png" width="1000" alt="Usage Bars widgets for personal and work accounts in Codex and Claude"></a></p>

Add each account in **Accounts**. In the widget settings, select **One provider** to group
its accounts, **One account** to show a single account, or **Custom** to combine accounts
from different providers. On iOS 17+, custom layouts can be saved in the app's settings.

## App tour

<p align="center"><a href="docs/01-terracotta/DEMO.md"><img src="docs/01-terracotta/tour-card.png" width="1000" alt="App tour: Overview, Accounts, Resets and Settings"></a></p>

Explore Overview, Accounts, Resets and Settings in the **[app tour](docs/01-terracotta/DEMO.md)**,
which also includes a widget gallery. The linked page explains how to open the tour in your browser.

## Providers

| Provider | Usage windows | Connect with |
| :--- | :--- | :--- |
| **OpenAI Codex** | Five-hour, weekly, plan-dependent limits and reset credits | OAuth + PKCE; device-code fallback |
| **Claude** | Five-hour and reported weekly / per-model windows | OAuth + PKCE |
| **Antigravity** | Per-model quota buckets | Google OAuth + PKCE |
| **Grok** | Weekly credits and monthly billing window | Device-code flow |
| **Kimi Code** | Coding quota and reset windows (kimi.com and kimi.ai) | Device code or console key |
| **Devin** | Daily and weekly seat quota | OAuth + PKCE loopback |
| **Meta Muse** | Rolling subscription window and weekly quota | OAuth device code |
| **Z.ai GLM Coding Plan** | Five-hour, weekly and monthly limits (z.ai and bigmodel.cn) | API key |
| **OpenCode Go** | Five-hour, weekly and monthly limits | API key |

Prepaid API balances for OpenRouter, DeepSeek, Moonshot, xAI, fal.ai and Novita appear in a
separate Credits section and widget; see [Credits](docs/credits.md). Anthropic and OpenAI offer no
balance endpoint for API keys.

Android and iOS include integrations for all nine providers. Sign-in has not yet been
verified for every provider, and Kimi OAuth usage access may require vendor approval.


## Build

### Android

Use Android SDK platform 36 and JDK 21 for the tests.

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

### iOS

You'll need macOS, Xcode and [XcodeGen](https://github.com/yonaskolb/XcodeGen).

```bash
swift test --package-path ios/UsageLimitsKit
cd ios/UsageLimits && xcodegen generate
```

Open the generated project in Xcode to configure signing and run the app.


## Privacy

The app connects directly to each provider using your account. It reads usage limits without
making model requests and has no backend, proxy or telemetry. Credentials are kept in
Android Keystore-backed storage or iOS Keychain; widgets only access cached usage data.

Some integrations use undocumented provider APIs and may need updates when those APIs change.
See the [security model](docs/security.md) and [release readiness](docs/release-readiness.md) for details.


## Documentation

| Start here | For contributors |
| :--- | :--- |
| [Device compatibility](docs/compatibility.md) | [Architecture](docs/architecture.md) |
| [Widgets](docs/widgets.md) | [Verification](docs/verification.md) |
| [Security](docs/security.md) | [Release guide](docs/release.md) |

Provider setup and limitations are covered in the [Codex](docs/providers-codex.md), [Devin](docs/providers-devin.md),
[Meta Muse](docs/providers-meta.md) and other `docs/providers-*` guides. Report bugs in
[Issues](https://github.com/ZepiGit/Usage-Limit-Viewer/issues).
