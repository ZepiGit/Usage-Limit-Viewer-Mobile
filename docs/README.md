# Documentation

Every document in this directory, and what it answers.

## Using the app

| Document | Covers |
| --- | --- |
| [Widgets](widgets.md) | The home-screen and Lock Screen widgets, their sizes and their settings. |
| [Status icons](status-icons.md) | Pinned accounts in the Android status bar and on the iOS Lock Screen. |
| [Credits](credits.md) | Prepaid API balances, their providers and the Credits widget. |
| [Device compatibility](compatibility.md) | Minimum Android and iOS versions. |
| [Security](security.md) | Where credentials live and what leaves the device. |

## Connecting a service

One guide per provider, with the sign-in flow, the payload shape and the limits it reports.

[Antigravity](providers/antigravity.md) ·
[Claude](providers/claude.md) ·
[Codex](providers/codex.md) ·
[Devin](providers/devin.md) ·
[Kimi](providers/kimi.md) ·
[Meta Muse](providers/meta.md) ·
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

`media/` holds the images the README and the release guide display: app icon and store badges under `brand/`, the Android captures under `captures/`, and the status icon evidence under `status-icons/`. Nothing else belongs there.
