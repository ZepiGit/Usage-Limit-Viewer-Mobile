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

**Display mode**:
The app-wide choice of which side of a usage window the percentages count: **Left** (what remains) or **Used** (what has been spent). Every usage widget follows it.
_Avoid_: Percent mode, toggle, view mode
