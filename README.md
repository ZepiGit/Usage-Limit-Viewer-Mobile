<p align="center"><img src="docs/media/brand/app-icon.png" width="76" alt="Usage Limits app icon"></p>

<h1 align="center">Usage Limits Mobile</h1>

<p align="center">Usage readings and reset times for supported AI subscriptions, plus prepaid API balances, in the app and on home-screen widgets.</p>

<p align="center">
  <a href="https://rookieenough.github.io/Orion-Data/app/?id=usage-limit-viewer-mobile"><img src="https://raw.githubusercontent.com/RookieEnough/Orion-Store/refs/heads/main/assets/orion-badge.png" height="44" alt="Get it on Orion Store"></a>
  &nbsp;
  <a href="https://raw.githack.com/ZepiGit/Usage-Limit-Viewer-Mobile/readme-preview/docs/demo/index.html"><img src="docs/demo/assets/try-the-demo.svg" height="44" alt="Try the demo"></a>
  &nbsp;
  <a href="https://github.com/ZepiGit/Usage-Limit-Viewer-Mobile/releases/tag/v0.5.1"><img src="https://img.shields.io/github/v/release/ZepiGit/Usage-Limit-Viewer-Mobile?label=GitHub%20Releases&style=for-the-badge" height="44" alt="Download v0.5.1 from GitHub Releases"></a>
  &nbsp;
  <a href="LICENSE"><img src="https://img.shields.io/github/license/ZepiGit/Usage-Limit-Viewer-Mobile?style=for-the-badge" height="44" alt="MIT license"></a>
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/images/readme/01-hero-dark.png">
    <img src="docs/images/readme/01-hero-light.png" width="880" alt="Every AI limit. One glance. Three phones: usage as a list, usage as rings, and prepaid API credit balances.">
  </picture>
</p>

<p align="center"><a href="#features">Features</a> · <a href="#widgets">Widgets</a> · <a href="#services">Services</a> · <a href="#appearance">Appearance</a> · <a href="#your-data">Privacy</a> · <a href="#install">Install</a></p>

<h2 id="features">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/images/readme/headings/features-dark.png">
    <img src="docs/images/readme/headings/features-light.png" alt="Features" height="48">
  </picture>
</h2>


Connect a supported subscription to read its usage windows and reset times, as a list or as dials. Overview sorts lowest headline first. Resets are grouped by day, with a live countdown.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/readme/03-resets-dark.png">
  <img src="docs/images/readme/03-resets-light.png" width="880" alt="Resets screen: upcoming 5-hour and weekly resets grouped by day, with 24-hour times and live countdowns.">
</picture>

<h2 id="widgets">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/images/readme/headings/widgets-dark.png">
    <img src="docs/images/readme/headings/widgets-light.png" alt="Widgets" height="48">
  </picture>
</h2>


Account Rings and Mini Rings can show all accounts, one service, one account, or a Custom selection. Pin up to three usage accounts and see each glyph and headline as a status-bar notification on Android, or as the Pinned accounts Lock Screen widget on iPhone. Off until you turn it on. Credit balances cannot be pinned.

Every widget style and size, including Usage Bars, Usage Summary and Credits, is in [Widgets](docs/widgets.md). Alerts and pinning are covered in [Notifications](docs/notifications.md).

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/readme/08-widgets-rings-dark.png">
  <img src="docs/images/readme/08-widgets-rings-light.png" width="880" alt="Account Rings in 4×3, 4×2 and 2×2, and Mini Rings in 2×2, 1×2 and 4×1, from the real widget renders.">
</picture>

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/readme/06-notifications-dark.png">
  <img src="docs/images/readme/06-notifications-light.png" width="880" alt="Status bar icon settings and the notification shade, with pinned usage accounts next to the clock.">
</picture>

<h2 id="services">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/images/readme/headings/services-dark.png">
    <img src="docs/images/readme/headings/services-light.png" alt="Services" height="48">
  </picture>
</h2>


Eleven subscriptions and ten prepaid credit APIs, on Android and iOS.

**Subscriptions:** OpenAI Codex, Claude, Antigravity, Grok, Kimi Code, Devin, Meta Muse, Z.ai GLM Coding Plan, OpenCode Go, Ollama Cloud, MiniMax Token Plan.

**Credit APIs:** OpenRouter, DeepSeek, Moonshot (Kimi API), xAI, fal.ai, Novita, Vercel AI Gateway, DeepInfra, TokenRouter, Ollama Cloud.

The same Ollama key can be a usage account and a credit account. Anthropic and OpenAI API balances cannot be shown. [See all supported services and details →](docs/PROVIDERS.md)

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/readme/10-providers-dark.png">
  <img src="docs/images/readme/10-providers-light.png" width="880" alt="Icons and names of the eleven supported subscriptions with what the app reads, and the ten prepaid credit APIs, including Ollama Cloud, MiniMax Token Plan, Vercel AI Gateway, DeepInfra and TokenRouter.">
</picture>

<h2 id="appearance">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/images/readme/headings/appearance-dark.png">
    <img src="docs/images/readme/headings/appearance-light.png" alt="Appearance" height="48">
  </picture>
</h2>


Four themes — Warm dark, Pure black, Cool graphite and Light paper — plus bundled fonts. The Claude Code figure follows the account’s headline. English and German.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/readme/11-themes-dark.png">
  <img src="docs/images/readme/11-themes-light.png" width="880" alt="Overview in the Warm dark, Pure black, Cool graphite and Light paper themes.">
</picture>

<h2 id="your-data">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/images/readme/headings/your-data-dark.png">
    <img src="docs/images/readme/headings/your-data-light.png" alt="Your data" height="48">
  </picture>
</h2>


The app talks straight to each service. There is no server, no proxy and no tracking; keys stay in the Android Keystore or the iOS Keychain. Widgets only see cached numbers. See [Security](docs/security.md). Phones, foldables, tablets and widget sizes are in [Devices](docs/DEVICES.md).

<h2 id="install">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/images/readme/headings/install-dark.png">
    <img src="docs/images/readme/headings/install-light.png" alt="Install" height="48">
  </picture>
</h2>


Get the signed Android APK of [0.5.1](https://github.com/ZepiGit/Usage-Limit-Viewer-Mobile/releases/tag/v0.5.1) from GitHub Releases or from the Orion Store:&nbsp;<a href="https://rookieenough.github.io/Orion-Data/app/?id=usage-limit-viewer-mobile"><img src="https://raw.githubusercontent.com/RookieEnough/Orion-Store/refs/heads/main/assets/orion-badge.png" height="44" alt="Get it on Orion Store" valign="middle"></a>

**Android** (SDK Platform 37, JDK 21), from the repository root:

```bash
./gradlew :app:assembleDebug        # build the app
./gradlew :app:testDebugUnitTest    # run the tests
```

**iOS** (macOS with Xcode and [XcodeGen](https://github.com/yonaskolb/XcodeGen)):

```bash
swift test --package-path ios/UsageLimitsKit   # run the shared logic tests
cd ios/UsageLimits && xcodegen generate         # create the Xcode project
```

Open the generated project in Xcode to sign and run it. 0.5.1 also ships an unsigned iOS archive; this repository has no App Store listing.

A [click-through demo](https://raw.githack.com/ZepiGit/Usage-Limit-Viewer-Mobile/readme-preview/docs/demo/index.html) shows the screens in a browser. Build notes and the rest of the reference are in [Details](docs/DETAILS.md). Found a problem? Open an [issue](https://github.com/ZepiGit/Usage-Limit-Viewer-Mobile/issues/new/choose).

[MIT](LICENSE), copyright (c) 2026 ZepiGit.
