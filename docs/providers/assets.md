# Provider artwork

Provider marks are bundled locally; displaying them makes no network request. They remain trademarks of their respective owners.

Sources live in `assets/providers/lobehub/`, with Devin and Meta Muse artwork beside them. `assets/providers/catalog.json` records the choices. Devin comes from [LobeHub icons](https://lobehub.com/icons); Meta uses the open Meta glyph from [Simple Icons](https://simpleicons.org/).

Z.ai, ChatGLM, OpenCode, Ollama, MiniMax and nine of the ten credit marks — OpenRouter, DeepSeek, Moonshot, xAI, fal.ai, Novita, Vercel, DeepInfra and Ollama, every mark but TokenRouter's — come from [LobeHub icons v1.96.0](https://www.npmjs.com/package/@lobehub/icons-static-svg/v/1.96.0), licensed under [MIT](https://github.com/lobehub/lobe-icons/blob/master/LICENSE), copyright © 2023 LobeHub. The SVG sources include the license notice; Android PNGs retain it in `Copyright` and `License` metadata. Keep those notices with redistributed artwork.

In Settings → Provider icons, choose a mark for each service. The selection applies throughout the app and its widgets on that device. Unknown choice IDs fall back to the default. Claude and Claude Code share an account identity, as do Antigravity and Gemini; changing artwork does not change authentication or quota.

Credit providers each have one fixed mark: OpenRouter, DeepSeek, Moonshot, xAI, fal.ai, Novita, Vercel, DeepInfra, Ollama and TokenRouter. Packaged artwork uses transparent PNGs. Android app artwork takes the palette's ink where the mark needs a one-color tint; color artwork retains its colors. Placed widgets use their own ink rules, not the app palette. Vector sources remain available alongside the exported platform assets. TokenRouter's mark, a TR monogram in `assets/providers/tokenrouter.svg`, was drawn for this app and has no third-party source.

## Android Claude Code figure

The **Claude Code · Color** (`claudecode-color`) and **Claude Code · Monochrome** (`claudecode`) choices use native figure art derived from LobeHub's Claude Code mark. The LobeHub MIT attribution above still applies. Mood follows each account's raw headline percentage left, with declared exhaustion taking precedence; Used does not change it. See the [mood and Hooray guide](../../README.md#claude-code-mood-and-hooray) for the animation switch, reduced-motion behavior and one-shot reset rules.

The picker, Add account and widget configuration use static artwork. In the combined Android candidate, placed Usage Bars, Account Rings and Mini Rings use the same native figure renderer for the selected account's steady mood. Color retains its artwork; monochrome uses the widget's own text ink, with transparent eye cut-outs. Other icon choices stay static. Widgets never animate or play Hooray, and the status-icon glyph below remains a separate fixed silhouette. [Release evidence](../release.md#android-redesign-evidence) records the candidate's incomplete CI and device gates. iOS figure and appearance redesign work is paused, not documented here as released parity.

## Font notices

Fonts are not provider artwork. The [canonical font manifest](../../third_party/fonts/manifest.json) pins the six bundled SIL OFL 1.1 families, upstream versions, original notices and per-file checksums; [font notes](../../third_party/fonts/README.md) explain the bundle. On Android, open **Settings → Appearance → Font licences** (**Einstellungen → Erscheinungsbild → Schriftlizenzen**) for the complete offline notices. App text and figure choices do not apply to placed widgets.

## Status bar glyphs

The status icons on Android (`app/src/main/kotlin/com/usagelimits/statusicon/StatusIconRenderer.kt`) and the iOS Pinned accounts Lock Screen widget do not reuse the artwork above. Both draw the path data from one file, `shared/glyphs/status-icon-glyphs.json`; changing a glyph means changing that file only, and both platforms' tests read it. The status bar draws the whole icon about 10 px high and keeps only its alpha, so a logo's thin strokes and small holes disappear. Each provider instead has one bold monochrome silhouette, drawn as path data on a 24-unit square. These paths were drawn for this app after the mark each row names; none is a copy of a source path. Some stay close to the mark (Claude, Antigravity, Kimi, OpenCode, Ollama Cloud), the others are freer abstractions of it (Codex, Grok, Devin, Meta Muse, Z.ai, MiniMax Token Plan). The provider marks remain their owners' trademarks.

| Provider | Glyph | Drawn after | Licence of that source |
|---|---|---|---|
| Claude | The Claude Code mascot with thicker legs and the eyes cut out | `assets/providers/lobehub/claudecode.svg` | LobeHub icons, MIT, © 2023 LobeHub |
| OpenAI Codex | A hexagon with a hexagonal hole, an abstraction of the OpenAI knot's six-fold shape | `assets/providers/lobehub/openai.svg` | LobeHub icons, MIT, © 2023 LobeHub |
| Antigravity | A solid arch with flared feet | `assets/providers/lobehub/antigravity.svg` | LobeHub icons, MIT, © 2023 LobeHub |
| Grok (xAI) | A bold symmetric X | `assets/providers/lobehub/xai.svg` | LobeHub icons, MIT, © 2023 LobeHub |
| Kimi | A K with a dot at its upper right | `assets/providers/lobehub/kimi.svg` | LobeHub icons, MIT, © 2023 LobeHub |
| Devin | Four solid hexagons in a diamond | `assets/providers/lobehub/devin.svg` | LobeHub icons, MIT, © 2023 LobeHub |
| Meta Muse | Two rings side by side, standing for the infinity loop | `assets/providers/lobehub/meta-mono.svg` | Simple Icons, CC0 1.0 |
| Z.ai GLM | A bold Z | `assets/providers/lobehub/zai.svg` | LobeHub icons, MIT, © 2023 LobeHub |
| OpenCode Go | An upright frame around a hollow, the mark's frame redrawn with its edges half a unit further in | `assets/providers/lobehub/opencode.svg` | LobeHub icons, MIT, © 2023 LobeHub |
| Ollama Cloud | A llama head with two upright oval ears and the eyes cut out | `assets/providers/lobehub/ollama.svg` | LobeHub icons, MIT, © 2023 LobeHub |
| MiniMax Token Plan | The mark's wave as one bold M, its middle dipping lowest | `assets/providers/lobehub/minimax.svg` | LobeHub icons, MIT, © 2023 LobeHub |
| Unknown provider | A plain disc | Drawn for this app | No third-party source |

### Glyph path grammar

The file must be strict UTF-8 JSON of the shape below, and CI rejects anything else before it can ship: `tools/check_glyph_file.py` runs in the Android unit-test job, in the iOS Linux job, and as the first step of the release workflow. The apps' loaders (Android `StatusIconGlyphs.kt`, iOS `StatusIconGlyphs.swift`) read it with their platform's JSON reader and trust the shipped file beyond their own schema check, which covers the shape and this subset of SVG path data:

- Commands, absolute only: `M` (2 numbers), `L` (2), `H` (1), `V` (1), `C` (6), `Z` (none). Lower-case relative commands, `Q`, `S`, `T`, `A` and any other letter are rejected.
- A path starts with `M`. A command repeats while numbers follow it; pairs after `M` continue as `L`. Numbers after `Z` without a new command are rejected.
- Numbers: an optional `-`, then ASCII digits with at most one decimal point (`12`, `1.5`, `.5`, `-0.4`). No `+`, no exponent.
- Separators: spaces and commas.
- The file is one object with exactly three keys. `grid` is the JSON number 24 (`"24"` in quotes is rejected). `glyphs` is an object naming exactly the providers this build knows, no more and no fewer. Every path, in `glyphs` and in `fallback` (the disc), is a JSON string; a number, `null` or an array is rejected.

`shared/test-vectors/glyph-path-grammar.json` lists the provider ids and accepted and rejected paths and whole files; both platforms' tests and the checker read it, and each platform's test checks the ids against its own provider list. `shared/test-vectors/glyph-file-raw.json` holds raw file texts for the checker's own tests. The fallback disc is drawn from four cubic segments on both platforms, so it is no longer pixel-identical to Android's earlier `addCircle` disc. A malformed file fails Android's status-icon step, which the publication logs and skips, and it makes the iOS widget draw the disc for every account.

The glyphs are judged at the size the status bar draws them, not at the size of the source. `StatusIconRenderingTest` writes a contact sheet and a true-size sheet per density to `app/build/reports/status-icons/`. In the true-size sheet each icon is scaled down to its 15 × 10 dp slot and enlarged ×8 without smoothing. The same test fails if two glyphs differ in fewer than 12 pixels at 280 dpi.
