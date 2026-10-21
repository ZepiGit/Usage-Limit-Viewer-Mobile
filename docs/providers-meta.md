# Meta Muse

Add Meta Muse in Accounts, open Meta's browser page and approve the displayed device code. Authorization starts at `https://auth.meta.com/oidc/device/authorization/`; the app polls `/oidc/device/token/` at the provider's interval, respecting pending and slow-down responses.

The returned DCA token is stored separately from the Muse API key minted through `POST https://api.meta.ai/muse-code/key`. Subscription quota reads call that same route with the DCA bearer token, `x-api-version: 1.0.0` and an empty JSON body. Neither token nor key goes into account metadata or widget snapshots. There is no refresh-token grant, but the DCA token can mint a replacement API key. Reconnect when authorization expires.

## Reading quota

`subs_usage.window.used_percent` and `subs_usage.weekly.used_percent` report consumed quota; the app displays remaining percentages. The rolling window uses `window_duration_mins`. Reset values accept epoch seconds, milliseconds or ISO timestamps.

`subs_tier_name`, falling back to `subs_usage.tier`, supplies the plan label. Missing usage blocks remain unknown. Removing the account deletes local credentials; manage provider authorization separately with Meta.
