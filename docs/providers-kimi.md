# Kimi Code

Choose Kimi Code in Accounts, select the issuing region, then use device-code sign-in or paste a key from your Kimi Code console. These credentials are separate from Moonshot Open Platform keys used for [credit balances](credits.md).

China is the default region: authorization uses `https://auth.kimi.com`, usage comes from `https://api.kimi.com/coding/v1/usages`, and the console is `https://www.kimi.com/code/console`.

International accounts use `https://auth.kimi.ai`, `https://api.kimi.ai/coding/v1/usages` and `https://www.kimi.ai/code/console`, respectively.

## Sign in

Device authorization uses `/api/oauth/device_authorization`; polling and renewal use `/api/oauth/token` on the selected host. Approve the displayed code in the browser, then return to the app. Polling respects the provider's interval, slow-down, denial and expiry responses.

Every request identifies the app as `X-Msh-Platform: UsageLimits` with a `UsageLimits/<version>` user agent. OAuth access depends on Moonshot permitting that identity. An access rejection is shown in the app; the key option uses a key from your own console and checks its usage access before saving.

Tokens and keys stay bound to their region. Pasted keys have no refresh grant; a revoked key needs replacing. Region-specific identities keep China and international accounts separate. A provider-supplied user ID is preferred; otherwise a one-way key digest identifies the account. With that fallback, rotating a key creates a new account.

## Reading quota

The weekly `usage` block reports `limit` and `used`; rolling `limits[].detail` commonly reports `limit` and `remaining`. Reported consumption takes precedence over a value derived from remaining quota. Zero or absent capacity drops the window rather than producing a percentage.

Window duration follows `window.duration` and `window.timeUnit`, independent of array position. `resetTime` accepts ISO timestamps or epoch seconds/milliseconds. Removing an account deletes its local credential; revoke it separately in Kimi's console.
