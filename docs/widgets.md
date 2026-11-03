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

The configuration screen also has Show percentages as, with App setting, Left and Used, for Usage Summary, Usage Bars, Account Rings and Mini Rings. The preview follows the choice. See [Percentages: left or used](#percentages-left-or-used).

## iOS setup

Add Usage Limits from the Home Screen widget gallery. On iOS 17 and later, configurable Usage Bars, Account Rings and Mini Rings support account selection, provider selection and saved custom layouts. Edit a custom layout's accounts and order in app Settings; widgets using that layout share those choices. Content, transparency and text-tone choices belong to each widget.

On iOS 16, Usage Limits tiles, Usage Limits (Clear), and Usage Ring use the app's ranking. Standard tiles have small, medium, large and rectangular Lock Screen forms; the ring has small and circular Lock Screen forms. These widgets use fixed layouts rather than scrolling lists. They are static widgets on every iOS version, so they always follow the app's percentage setting.

## Percentages: left or used

Show percentages as is in Settings → Overview on Android and Settings → Display on iOS, with Left (the default) and Used. It applies to every percentage of a usage window: the Overview, Accounts and Resets screens, the widget configuration preview, notification texts and all usage widgets. Credits are money, not percentages, and are unchanged.

- **Switching.** Tap the large figure in the Overview's lead strip to flip the same setting. Android exposes a button; iOS exposes a named accessibility action, such as “Show percentage used”, through the actions rotor. The figure crossfades, except when reduced motion is on.
- **Every figure says which it is.** Rows read “73% left” or “27% used”. Where there is no room for a word next to each figure, such as Mini Rings, Account Rings and the small Lock Screen forms, a caption names the mode and the spoken description states it. Settings notification titles follow the mode, for example “Below 20% left” and “Above 80% used”; the thresholds themselves do not change.
- **Fill follows the figure; meaning does not.** The displayed value determines the bar length or ring arc, so Used fills as the window is consumed. Severity, account order, “most critical” ranking and notification thresholds remain decided by what is left. Severity-colored surfaces keep a nearly exhausted usage window red in both modes. Android widget ring arcs retain their account accent, and the iOS circular Lock Screen ring retains its primary color rather than following severity.
- **Exhausted, unknown and stale.** An exhausted usage window reads 100% used; a current, valid reading fills its bar completely in Used mode. An unknown figure shows a dash and no fill in both modes. Stale and reset-due readings retain their figures and use grey bar fill; failed readings draw no bar fill. The Android widget ring arcs and iOS circular Lock Screen arc retain their existing ink and draw the known fraction even for aged readings.
- **The breakdown is always used.** A window's segment breakdown, such as “Opus usage: 20%”, counts what was used in both modes and is labeled that way.
- **German.** On a device set to German the words become “übrig” and “verbraucht” and the percent sign is separated by a space (“73 %”). Window labels that a provider reports, and model names, are shown as reported.

### Per-widget override

Each configurable widget can keep its own choice: App setting (the default, which follows the app), Left or Used. Widgets that existed before this option follow the app setting.

| Widget | Own choice |
| --- | --- |
| Android Usage Summary, Usage Bars, Account Rings, Mini Rings | Yes, in the widget configuration |
| iOS 17 Usage Bars, Account Rings, Mini Rings | Yes, the Show percentages as option |
| iOS Usage Limits, Usage Limits (Clear), Usage Ring | No; they always follow the app |
| Credits (both platforms) | Not applicable |

On Android a change in Settings repaints the widgets that follow the app. On iOS the app writes its choice into the widget snapshot and asks WidgetKit to reload, so delivery follows the timeline rules below.

## Refresh and reset times

App refreshes, sign-in, removal and reset-credit actions request widget updates. Android's refresh button queues a shared sync with a network constraint. Periodic sync runs at the chosen interval, with a 15-minute minimum; the widget framework also requests updates around every 30 minutes. Android controls actual background delivery.

On iOS, refresh links open the app to fetch new readings. WidgetKit controls timeline delivery, so saving a configuration or requesting a reload has no fixed display deadline.

Reset times are absolute. An elapsed reset shows “Reset due” until a fetch supplies the next window. Failed refreshes retain the original measurement age; unknown percentages show a neutral state rather than an empty allowance. Partial results carry a separate warning.

The Credits widget shows balances worst first, with room for one, three or six accounts depending on size. It follows the same cached-data and system-scheduling rules. See [Credits](credits.md) for setup.
