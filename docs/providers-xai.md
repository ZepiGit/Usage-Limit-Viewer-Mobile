# Grok / xAI

Add Grok in Accounts, open the supplied browser page and approve the displayed device code. Return to the app while it waits for approval. Denial and expiry end the attempt; a slow-down response increases subsequent polling intervals.

Authentication endpoints come from `https://auth.x.ai/.well-known/openid-configuration`. Device and token URLs must use HTTPS on `x.ai` or a subdomain. The saved token URL is checked again before renewal. Identity comes from the ID token, falling back to `GET https://api.x.ai/v1/me`.

## Billing endpoints

The app reads both `https://cli-chat-proxy.grok.com/v1/billing?format=credits` and `/v1/billing`. Calls use bearer authorization plus `x-xai-token-auth: xai-grok-cli` and `x-grok-client-version`, following the reference client's compatibility headers.

`creditUsagePercent` is consumed quota. The app shows its complement as remaining, with the period ending at `currentPeriod.end`. A measured credit percentage supersedes the deprecated monthly included-spend row. If credits are unknown, the monthly allowance can supply a fallback.

Legacy money fields use integer cents, sometimes wrapped in `{"val":...}`. The quota view displays ratios rather than currency. A present empty amount object means zero; absent, null or malformed values stay unknown. Missing credit percentage never means zero use.

Included and on-demand allowances are separate pools. On-demand exhaustion can restrict that facility without exhausting another available pool. If one endpoint fails, returned measurements remain visible with a partial-update warning. If both fail, the account retains its previous readings and age.

This is separate from the xAI prepaid balance, which requires a management key in [Credits](credits.md). No reset-credit action is offered.
