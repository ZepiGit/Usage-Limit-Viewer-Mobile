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

## iOS setup

Add Usage Limits from the Home Screen widget gallery. On iOS 17 and later, configurable Usage Bars, Account Rings and Mini Rings support account selection, provider selection and saved custom layouts. Edit a custom layout's accounts and order in app Settings; widgets using that layout share those choices. Content, transparency and text-tone choices belong to each widget.

On iOS 16, Usage Limits tiles, Usage Limits (Clear), and Usage Ring use the app's ranking. Standard tiles have small, medium, large and rectangular Lock Screen forms; the ring has small and circular Lock Screen forms. These widgets use fixed layouts rather than scrolling lists.

## Refresh and reset times

App refreshes, sign-in, removal and reset-credit actions request widget updates. Android's refresh button queues a shared sync with a network constraint. Periodic sync runs at the chosen interval, with a 15-minute minimum; the widget framework also requests updates around every 30 minutes. Android controls actual background delivery.

On iOS, refresh links open the app to fetch new readings. WidgetKit controls timeline delivery, so saving a configuration or requesting a reload has no fixed display deadline.

Reset times are absolute. An elapsed reset shows “Reset due” until a fetch supplies the next window. Failed refreshes retain the original measurement age; unknown percentages show a neutral state rather than an empty allowance. Partial results carry a separate warning.

The Credits widget shows balances worst first, with room for one, three or six accounts depending on size. It follows the same cached-data and system-scheduling rules. See [Credits](credits.md) for setup.
