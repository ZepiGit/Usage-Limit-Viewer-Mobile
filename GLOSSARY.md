# Usage Limit Viewer

An app and home-screen widgets that show how much of each AI provider allowance a person has used or has left, and when it resets.

## Language

### Accounts

**Account**:
One identity at one provider whose usage windows or credit balance the app reads.
_Avoid_: Login, profile, subscription, connection

**Headline**:
The one usage window that speaks for an account at a given moment: the most critical one, with windows that are not reset due preferred when there are any and global windows ahead of model-specific ones. An account without usage windows has no headline.
_Avoid_: Most critical window, representative, lead window

**Sync**:
An attempt to read an account's current state from its provider, started by a person or by the app in the background. The interface calls it "refresh"; a failed sync keeps the previous readings.
_Avoid_: Fetch, poll, update, reload

**Reading**:
What one sync learned about one usage window, together with the moment it was learned.
_Avoid_: Sample, data point, value

### Allowances

**Usage window**:
An allowance a provider reports for one account, measured as a percentage and starting over on its own schedule.
_Avoid_: Limit, quota, bucket (a "limit" appears only inside a window's label, e.g. "5h limit"; providers' own words for it are data, not our vocabulary)

### Reading states

**Expired sign-in**:
An account whose provider requires signing in again. A failed refresh, stale reading or exhausted usage window does not by itself mean the sign-in expired.
_Avoid_: Expired account, broken account

**Exhausted**:
Nothing is left in a usage window: the provider says so, or its reading shows zero remaining. It is never inferred from an aged or replaced state.
_Avoid_: Empty, out, maxed

**Stale reading**:
A reading of a usage window too old to be trusted as current.
_Avoid_: Old data, outdated, expired

**Reset due**:
A usage window whose reset time has passed since it was read, so the reading describes the window that already ended.
_Avoid_: Reset pending, expired window

### Presentation

**Overview layout**:
The choice between **List**, which shows every usage window, and **Dials**, which shows one headline ring per account. This is separate from the display mode.
_Avoid_: Display mode for List/Dials

**Needs attention**:
The Overview List filter for expired sign-ins only. Low or exhausted allowance does not qualify.
_Avoid_: Critical accounts, low accounts

**Resets today**:
The Overview List filter for accounts with a recorded reset strictly after now and strictly before the next local day starts. A past reset or a reset exactly at the next day start does not qualify.
_Avoid_: Resets in 24 hours

**Display mode**:
The app-wide choice of which side of a usage window the percentages count: **Left** (what remains) or **Used** (what has been spent). Every usage widget follows it.
_Avoid_: Percent mode, toggle, view mode

**Pinned account**:
A usage account the person selected for a status icon. At most three can be selected; credit accounts cannot be.
_Avoid_: Favourite, starred account

**Status icon**:
The small mark for one pinned account: its provider glyph with a short form of its headline's figure, a sign that stands in for the figure, or the glyph alone. Underlined when its reading is stale or reset due (`outdated` in code and in the shared vectors); never for an error or an exhausted window.
_Avoid_: Notification icon, status bar item

**Provider glyph**:
The one-colour silhouette that stands for a provider in a status icon; not the provider artwork chosen under Provider icons.
_Avoid_: Logo, provider icon
