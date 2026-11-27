# Documentation

Every document in this directory, and what it answers.

[DETAILS.md](DETAILS.md) is the long-form reference the README links to: install, build and feature notes. Service tables live in [Providers](PROVIDERS.md).

## Using the app

| Document | Covers |
| --- | --- |
| [Providers](PROVIDERS.md) | The eleven subscriptions and ten credit APIs: what you see and how you connect. |
| [Widgets](widgets.md) | Every widget style and size, with light and dark images, plus setup. |
| [Notifications](notifications.md) | Pinned usage in the status bar / Lock Screen, and alerts. |
| [Status icons](status-icons.md) | The detailed pinned-account glyphs, forms and evidence. |
| [Credits](credits.md) | Prepaid API balances and the Credits widget. |
| [Devices](DEVICES.md) | Android and iOS versions, foldables, tablets and widget sizes (this page also holds the foldable layout). |
| [Device compatibility](compatibility.md) | Pointer to Devices. |
| [Security](security.md) | Where credentials live and what leaves the device. |

## Connecting a service

One guide per provider, with the sign-in flow, the payload shape and the limits it reports.

[Antigravity](providers/antigravity.md) ·
[Claude](providers/claude.md) ·
[Codex](providers/codex.md) ·
[Devin](providers/devin.md) ·
[Kimi](providers/kimi.md) ·
[Meta Muse](providers/meta.md) ·
[MiniMax Token Plan](providers/minimax.md) ·
[Ollama Cloud](providers/ollama.md) ·
[OpenCode Go](providers/opencode.md) ·
[xAI](providers/xai.md) ·
[Z.ai](providers/zai.md)

[Provider artwork](providers/assets.md) records where each mark comes from and under which licence.

## Working on the code

| Document | Covers |
| --- | --- |
| [Architecture](architecture.md) | The module layout, the shared vectors and the platform boundaries. |
| [Release](release.md) | Versioning, the CI gate, capture provenance and the device checklist. |
| [Decisions](adr/) | ADRs 0001–0005: the decisions that were expensive to rediscover. |
| [Research](research/statusbar-icons-platform-facts.md) | The platform facts behind ADR 0003. |
| [Status icon device evidence](status-icon-evidence.md) | How the status icon evidence was captured. |

## Media

`media/` holds the images the guides display: the app icon under `brand/`, the Android captures under `captures/`, and the status icon evidence under `status-icons/`. Nothing else belongs there.

`images/readme/` holds the README artwork and section-heading PNGs in light and dark variants, `images/widgets/` and `images/devices/` the widget catalog and foldable boards, `store-previews/` the full-size store screenshots, and `demo/` the click-through demo.
