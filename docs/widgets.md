# Widgets

Widgets show the app's cached readings. They do not hold credentials or contact providers directly. Add accounts in the app before choosing what to display.

## Android setup

Open your launcher's widget picker, choose Usage Limits and place a widget. Resize it using the launcher's handles. The four usage styles are:

| Style | Content |
| --- | --- |
| Usage Summary | One leading account's limits and next reset, with selection-wide status labeled separately |
| Usage Bars | Scrollable account cards with limit rows and reset times |
| Account Rings | Scrollable rings with provider icons, account labels and resets |
| Mini Rings | A compact grid of rings and icons; capacity depends on placement size |

At placement, choose closest resets, most critical, all accounts, one account, one provider, or a custom account list and order. The first three include every account with different ordering. Closest resets is the default. Use the launcher's reconfigure control to change an existing widget. Saving applies the selection; canceling keeps its current configuration.

Each usage widget has its own background color, opacity and text tone. Automatic text tone follows the selected panel color, including at zero opacity; it does not sample wallpaper. Choose light or dark text explicitly when needed on a transparent panel. Provider artwork follows the app's saved icon choices.

The configuration preview and every usage widget follow the app's display mode. See [Percentages: left or used](#percentages-left-or-used).

## iOS setup

Add Usage Limits from the Home Screen widget gallery. On iOS 17 and later, configurable Usage Bars, Account Rings and Mini Rings support account selection, provider selection and saved custom layouts. Edit a custom layout's accounts and order in app Settings; widgets using that layout share those choices. Content, transparency and text-tone choices belong to each widget.

On iOS 16, Usage Limits tiles, Usage Limits (Clear), and Usage Ring use the app's ranking. Standard tiles have small, medium, large and rectangular Lock Screen forms; the ring has small and circular Lock Screen forms. These widgets use fixed layouts rather than scrolling lists. They are static widgets on every iOS version, so they always follow the app's percentage setting.

The Pinned accounts widget is off by default. Turn on pinned accounts in app Settings, then pin up to three usage accounts; credit accounts cannot be pinned. Add Pinned accounts to the Lock Screen on iOS 16 or later, in one of three forms. All three follow the app's display mode and the app's account order.

- **Rectangular:** one cell per pin, side by side: the provider glyph and the figure without a leading zero. A non-exhausted window at 100 shows only its glyph, larger; an exhausted window shows a crossed circle, a failed sync or expired sign-in "!", and a missing percentage a dash. An underline marks a stale or reset-due figure.
- **Circular:** the first pinned account, drawn like one rectangular cell.
- **Inline:** one line above the clock, showing the first pinned account only, like the circular form: its provider glyph as the line's one image, then its figure with the side, "42% left" / "42 % übrig", or a word for a state without a number (Exhausted, Error, Sign-in expired, Unknown). The system draws this form in its own font and drops other styling, so an outdated figure cannot be underlined and says so instead: "42% left · Stale" / "42 % übrig · Veraltet". The line names no account; the glyph identifies it. Without the glyph the line would read "42% left" alone, so whether a real Lock Screen shows the glyph, also after a timeline reload, is a release gate (`docs/release.md`, device checklist).

The glyphs are the same silhouettes as Android's status icons, drawn from one shared file, `shared/glyphs/status-icon-glyphs.json`. VoiceOver reads each account's name, the full figure and its side ("Claude Max, 42% remaining"), including "<1%", ">99%" and 100%, and adds "Stale" for an outdated reading; the inline form reads the first account the same way.

The widget schedules entries in advance for the moments a reading turns stale or a reset falls due, so these changes do not need the app to be running; when WidgetKit actually displays an entry still depends on the system's update budget. With the feature off or no pins, the widget shows "Pin an account in Settings" ("Pin in Settings" inline). Before the first unlock after a restart it shows "Locked" and requests a retry after 15 minutes; corrupt snapshot data asks you to open the app to rebuild it.

## Percentages: left or used

Show percentages as is in Settings → Overview on Android and Settings → Display on iOS, with Left (the default) and Used. It applies to every percentage of a usage window: the Overview, Accounts and Resets screens, the widget configuration preview, notification texts and all usage widgets. Credits are money, not percentages, and are unchanged.

- **Switching.** Tap the large figure in the Overview's lead strip to flip the same setting. Android exposes a button; iOS exposes a named accessibility action, such as “Show percentage used”, through the actions rotor. The figure crossfades, except when reduced motion is on.
- **Every figure says which it is.** Rows read “73% left” or “27% used”. Where there is no room for a word next to each figure, such as Mini Rings, Account Rings and the small Lock Screen forms, a caption names the mode and the spoken description states it. Settings notification titles follow the mode, for example “Below 20% left” and “Above 80% used”; the thresholds themselves do not change.
- **Fill follows the figure; meaning does not.** The displayed value determines the bar length or ring arc, so Used fills as the window is consumed. Severity, account order, “most critical” ranking and notification thresholds remain decided by what is left. Severity-colored surfaces keep a nearly exhausted usage window red in both modes. Android widget ring arcs use the widget data color, and grey for stale, failed, reauthentication-needed or reset-due readings, rather than a severity color; the iOS circular Lock Screen ring keeps its primary color in every state.
- **Exhausted, unknown and stale.** An exhausted usage window reads 100% used; a current, valid reading fills its bar completely in Used mode. An unknown figure shows a dash and no fill in both modes. Stale and reset-due readings retain their figures and use grey bar fill; failed readings draw no bar fill. Android widget ring arcs turn grey for stale, failed, reauthentication-needed and reset-due readings and otherwise use the widget data color; the iOS circular Lock Screen arc keeps its primary color in every state. Both draw the fraction the presentation gives, even for aged readings. On Account Rings a full allowance (100% left) leaves the ring's centre empty where the ring draws its arc, and reads “100” without the percent sign where it draws none (on iOS, a failed reading or a provider-exhausted window); an exhausted window reads “100” without the percent sign in Used mode; the spoken description still says 100%.
- **The breakdown is always used.** A window's segment breakdown, such as “Opus usage: 20%”, counts what was used in both modes and is labeled that way.
- **German.** On a device set to German the words become “übrig” and “verbraucht” and the percent sign is separated by a space (“73 %”). Window labels that a provider reports, and model names, are shown as reported.

Every usage widget follows the app setting, including widgets that previously had their own Left or Used choice. There is no separate widget display-mode setting. Credits show money and are unchanged.

On Android a change in Settings repaints every usage widget. On iOS the app writes its choice into the widget snapshot and asks WidgetKit to reload, so delivery follows the timeline rules below.

## Refresh and reset times

App refreshes, sign-in, removal and reset-credit actions request widget updates. Android's refresh button queues a shared sync with a network constraint. Periodic sync runs at the chosen interval, with a 15-minute minimum; the widget framework also requests updates around every 30 minutes. Android controls actual background delivery.

On iOS, refresh links open the app to fetch new readings. WidgetKit controls timeline delivery, so saving a configuration or requesting a reload has no fixed display deadline.

Reset times are absolute. An elapsed reset shows “Reset due” until a fetch supplies the next window. Failed refreshes retain the original measurement age; unknown percentages show a neutral state rather than an empty allowance. Partial results carry a separate warning.

The Credits widget shows balances worst first, with room for one, three or six accounts depending on size. It follows the same cached-data and system-scheduling rules. See [Credits](credits.md) for setup.
