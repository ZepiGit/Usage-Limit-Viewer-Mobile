# MiniMax Token Plan

Add MiniMax Token Plan in Accounts, choose the region that issued your key and paste a plan key. International is the default and uses `minimax.io`; China uses `minimaxi.com`. The app checks the key against the quota route before saving it in Android's encrypted credential store or iOS Keychain. A plan key is bound to its region, and there is no refresh grant.

Usage is `GET https://www.minimax.io/v1/token_plan/remains` or `https://www.minimaxi.com/v1/token_plan/remains` with `Authorization: Bearer <plan key>` and `Accept: application/json`, and nothing else.

MiniMax publishes no API reference for this route. The route, the header and the reading rules come from MiniMax's own CLI ([MiniMax-AI/cli](https://github.com/MiniMax-AI/cli), commit `ae29aae57b05c2140c7ea6aba0a852763ffe7859`, `src/client/endpoints.ts`, `src/commands/quota/show.ts`, `src/utils/quota.ts`); the CLI's `User-Agent: mmx-cli/<version>` is deliberately not copied. The CLI's own hosts are `api.minimax.io` and `api.minimaxi.com`, but `api.minimax.io` answers 404 for this path while `www.minimax.io` answers 200 with the auth error, so the app uses the `www` hosts.

## Reading quota

`model_remains[]` holds one entry per model, and each entry supplies a five-hour and a weekly window, labelled `<model> · 5-hour` and `<model> · Weekly`. Those labels carry the model name and stay in English in both app languages, as Claude's per-model windows do.

Two readings are reproduced from the CLI rather than approximated:

- `current_interval_usage_count` and `current_weekly_usage_count` are read as counts that REMAIN unless the matching `*_remaining_percent` is present and agrees better with the consumed reading, in which case the count is inverted. A percentage that agrees with neither reading within one point, a zero total, a negative count or a count above the total leaves that window unknown instead of guessing.
- `status` 1 or absent lets the counts decide, 2 is exhausted at 100% whatever the counts say, and 3 is unlimited (no bar). A weekly status of 3 omits the weekly window, and a weekly total of 0 means the plan has no weekly allowance, so that window is omitted rather than drawn at 0%.

`end_time` and `weekly_end_time` are the windows' resets, read as epoch milliseconds with seconds tolerated. A missing model name leaves its entry unread, because a window id is filed under the name.

A business error arrives as HTTP 200 with `base_resp.status_code` other than 0: code 1004 is a rejected key, and any other code — or a body without `model_remains` — keeps the reading unknown rather than showing a plan without windows.

The response names no user, so a one-way key digest identifies the account, scoped by region: the same key added on both services files two accounts and neither overwrites the other. Removal deletes the local key; revoke it in the platform console at `platform.minimax.io` or `platform.minimaxi.com`.

Evidence: this is the route MiniMax's own CLI uses, not a route in MiniMax's API reference, and the hosts differ from the CLI's for the reason above. No live MiniMax account was checked.
