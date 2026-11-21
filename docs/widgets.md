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

At placement, choose closest resets, most critical, all accounts, one account, one provider, or a custom account list and order. The first three include every account with different ordering: all accounts uses Overview's order (sign-in expired first, then the least room left in each account's headline; [ADR 0004](adr/0004-automatic-overview-order-and-stored-order.md)), while one provider keeps the stored account order and a custom list keeps its own. Closest resets is the default. Use the launcher's reconfigure control to change an existing widget. Saving applies the selection; canceling keeps its current configuration.

Each usage widget has its own background color, opacity and text tone. Automatic text tone follows the selected panel color, including at zero opacity; it does not sample wallpaper. Choose light or dark text explicitly when needed on a transparent panel. Provider artwork follows the app's saved icon choices. Android app palettes and text/figure fonts do not restyle placed widgets or their configuration editor; the editor keeps its Warm dark scene and widget-styled preview ([ADR 0005](adr/0005-app-and-widget-appearance.md)).

Account Rings and Mini Rings omit the visible Left/Used caption, while their accessibility descriptions retain the side, headline window, reset and applicable reading state. Summary labels and Bars figures still name the side. A widget tap naming an account opens its detail through Overview; a whole-widget tap opens Overview. Cold/warm delivery through a real launcher remains a [device check](release.md#device-checklist).

Placed widgets stay still and never play Hooray. At the documentation base `e0767a98e90469497944132833dd0df6e3b92a92`, their Claude Code artwork is still the catalogue image; integration and evidence for the account's steady-mood still are pending, not complete.

Android placed Usage Bars, Account Rings and Mini Rings show a steady mood still when Claude Code · Color or Claude Code · Monochrome is selected. Each account uses its own headline's raw percentage left; provider-declared exhaustion shows Asleep even without a number. Used mode, reading age and the app's animation switch do not change the pose or create a reset celebration. Existing reading publications and scheduled presentation repaints rebuild the headline and still. The monochrome body uses the widget's text ink, and the eyes are transparent cut-outs. Configuration keeps fixed catalog artwork; other icon choices remain static. Usage Summary and Credits have no provider logo.

The configuration preview and every usage widget follow the app's display mode. See [Percentages: left or used](#percentages-left-or-used).

All accounts ordering is not pin ordering. Android status icons retain the stored account order, independent of automatic Overview ranks; Custom keeps its saved ID order. Status icons are off by default and require notifications and their category to be allowed. A swipe normally hides an icon until the next sync; display or appearance changes do not restore it. Settings observing notifications allowed again can restore it immediately. These permission, dismissal and best-effort SystemUI rules remain in [Status icons](status-icons.md) and the [ADR 0003 clarification](adr/0003-status-icons-and-lock-screen-substitute.md#consequences).

## iOS setup

The following describes existing iOS widget support, not completed redesign parity. iOS redesign work is paused, including its remaining widget and appearance slices; no new iPhone/iPad capture or physical-device approval is claimed.

Add Usage Limits from the Home Screen widget gallery. On iOS 17 and later, configurable Usage Bars, Account Rings and Mini Rings support account selection, provider selection and saved custom layouts. Edit a custom layout's accounts and order in app Settings; widgets using that layout share those choices. Content, transparency and text-tone choices belong to each widget.

The configured **All accounts** choice ranks accounts like Overview: expired sign-ins first, then lowest headline percentage left, with ties by account-added time and ID. The widget evaluates the headline at each timeline entry. Provider keeps register order; Closest Resets and Most Critical keep it on ties. Older widget snapshots keep register order for All accounts until the app publishes again.

On iOS 16, Usage Limits tiles, Usage Limits (Clear), and Usage Ring use the Most Critical ranking. Standard tiles have small, medium, large and rectangular Lock Screen forms; the ring has small and circular Lock Screen forms. These widgets use fixed layouts rather than scrolling lists. They are static widgets on every iOS version, so they always follow the app's percentage setting.

The Pinned accounts widget is the iOS form of the [status icons](status-icons.md). It is off by default. Turn on pinned accounts in app Settings, then pin up to three usage accounts; credit accounts cannot be pinned. Add Pinned accounts to the Lock Screen on iOS 16 or later, in one of three forms. All three follow the app's display mode and keep the account register order, independently of Overview.

- **Rectangular:** one cell per pin, side by side: the provider glyph and the figure without a leading zero. A non-exhausted window at 100 shows only its glyph, larger; an exhausted window shows a crossed circle, a failed sync or expired sign-in "!", and a missing percentage a dash. An underline marks a stale or reset-due figure.
- **Circular:** the first pinned account, drawn like one rectangular cell.
- **Inline:** one line above the clock, showing the first pinned account only, like the circular form: its provider glyph as the line's one image, then its figure with the side, "42% left" / "42 % übrig", or a word for a state without a number (Exhausted, Error, Sign-in expired, Unknown). The system draws this form in its own font and drops other styling, so an outdated figure cannot be underlined and says so instead: "42% left · Stale" / "42 % übrig · Veraltet". The line names no account; the glyph identifies the provider. Without the glyph the line would read "42% left" alone, so whether a real Lock Screen shows the glyph, also after a timeline reload, is a release gate (`docs/release.md`, device checklist). More in [Status icons](status-icons.md#ios-lock-screen-forms).

The glyphs are the same silhouettes as Android's status icons, drawn from one shared file, `shared/glyphs/status-icon-glyphs.json`. Each cell's accessibility label is the account's title (provider, plus plan where known), the full figure and its side ("Claude Max, 42% remaining"), including "<1%", ">99%" and 100%, or the state, and "Stale" for an outdated reading; the inline form labels the first account the same way. Two accounts with the same provider and plan get the same title.

The widget schedules entries in advance for the moments a reading turns stale or a reset falls due, so these changes do not need the app to be running; when WidgetKit actually displays an entry still depends on the system's update budget. With the feature off or no pins, the rectangular form shows "Pin an account in Settings", the circular form a pin with "Pin in Settings" and the inline form "Pin in Settings". If the widget cannot read the app's data before the first unlock after a restart, it shows "Locked" (the circular form a lock symbol) and asks iOS to reload in 15 minutes; with corrupt snapshot data the rectangular form asks you to open the app to rebuild it, the inline form shows "Can't read usage" and the circular form a warning symbol with that label.

## Percentages: left or used

On Android, **Show percentages as** is in **Settings → Display**, with **Left** (the default) and **Used**. On a German device: **Einstellungen → Anzeige → Prozente anzeigen als**, **Übrig/Verbraucht**. iOS also has one app-wide choice; this Android section path does not imply its remaining Settings redesign is complete. The display mode applies to Overview, account detail and Resets, the widget configuration preview, notification texts and all usage widgets. Credits are money, not percentages, and are unchanged.

- **Switching on Android.** Tap the percentage chip in the Overview header to flip the same setting. Its accessibility label names the other mode. Figures fade through on a switch, except when reduced motion is on.
- **Left or Used.** Android Usage Summary labels and Usage Bars figures name the side. Android Account Rings and Mini Rings leave out the visible caption to make room for the rings; their accessibility descriptions name the side, headline window and reset. The iOS widgets retain their own caption and spoken treatments. Settings notification titles also follow the mode, for example “Below 20% left” and “Above 80% used”; the thresholds themselves do not change.
- **Fill follows the figure; meaning does not.** The displayed value determines the bar length or ring arc, so Used fills as the window is consumed. Severity, account order, “most critical” ranking and notification thresholds remain decided by what is left. Severity-colored surfaces keep a nearly exhausted usage window red in both modes. Android widget ring arcs use the widget data color, and grey for stale, failed, reauthentication-needed or reset-due readings, rather than a severity color; the iOS circular Lock Screen ring keeps its primary color in every state.
- **Exhausted, unknown and stale.** An exhausted usage window reads 100% used; a current, valid reading fills its bar completely in Used mode. An unknown figure shows a dash and no fill in both modes. Stale and reset-due readings retain their figures and use grey bar fill; failed readings draw no bar fill. Android widget ring arcs turn grey for stale, failed, reauthentication-needed and reset-due readings and otherwise use the widget data color; the iOS circular Lock Screen arc keeps its primary color in every state. Both draw the fraction the presentation gives, even for aged readings. On Account Rings a full allowance (100% left) leaves the ring's centre empty where the ring draws its arc, and reads “100” without the percent sign where it draws none (on iOS, a failed reading or a provider-exhausted window); an exhausted window reads “100” without the percent sign in Used mode; the spoken description still says 100%.
- **The breakdown is always used.** A window's segment breakdown, such as “Opus usage: 20%”, counts what was used in both modes and is labeled that way.
- **German.** On a device set to German the words become “übrig” and “verbraucht” and the percent sign is separated by a space (“73 %”). Window labels that a provider reports, and model names, are shown as reported.

Every usage widget follows the app setting, including widgets that previously had their own Left or Used choice. There is no separate widget display-mode setting. Credits show money and are unchanged.

On Android a change in Settings repaints every usage widget. On iOS the app writes its choice into the widget snapshot and asks WidgetKit to reload, so delivery follows the timeline rules below.

## Refresh and reset times

App refreshes, sign-in, removal and reset-credit actions request widget updates. Android's refresh button queues a shared sync with a network constraint. Background sync runs at the interval chosen in Settings: 5, 15, 30 minutes, 1 hour or 3 hours, with 30 minutes as the default. At 15 minutes or more it is periodic work. Android's periodic work cannot run more often than every 15 minutes, so with 5 minutes each sync schedules the next one as one-time work. Doze and battery saver can delay either kind. The widget framework also requests updates around every 30 minutes. Android controls actual background delivery, so the chosen interval is not an exact cadence.

On iOS, refresh links open the app to fetch new readings. The chosen interval is only the earliest time the app asks to refresh in the background; the system decides how often it actually runs, often less often than asked. WidgetKit controls timeline delivery, so saving a configuration or requesting a reload has no fixed display deadline.

Reset times are absolute. An elapsed reset shows “Reset due” until a fetch supplies the next window. Failed refreshes retain the original measurement age; unknown percentages show a neutral state rather than an empty allowance. Partial results carry a separate warning.

The Android Credits widget shows balances worst first. Its smallest tile places the account name at the top and centers the balance and freshness or status below. The larger sizes list up to three or six accounts as space allows and indicate when more are available. It follows the same cached-data and system-scheduling rules. See [Credits](credits.md) for setup.
