# Credit balances

Credits shows API and prepaid balances separately from subscription quota. In **Add Credit Balance**, choose a provider, paste the required key and select a region or team where applicable. The key is checked before saving. You can rename the account and set its low-balance threshold.

## Keys and endpoints

OpenRouter uses a management key for the account balance, or a normal API key for its own limit. It reads `GET https://openrouter.ai/api/v1/credits`, falling back to `/api/v1/key` on HTTP 403.

DeepSeek uses an API key with `GET https://api.deepseek.com/user/balance`.

Moonshot uses an Open Platform API key, separate from Kimi Code, with `GET https://api.moonshot.ai/v1/users/me/balance` for international USD or `api.moonshot.cn` for China CNY.

xAI uses a management key from console Settings → Management Keys, not an inference key, with `GET https://management-api.x.ai/v1/billing/teams/{team_id}/prepaid/balance`.

fal.ai requires a BILLING-preset key with `billing:usage:read` for `GET https://api.fal.ai/v1/account/billing?expand=credits`.

Novita uses an API key with `GET https://api.novita.ai/openapi/v1/billing/balance/detail`.

Moonshot keys belong to their issuing region. xAI resolves the team through `/auth/management-keys/validation`; a team ID entered by the user takes precedence. Anthropic and OpenAI balance entries are disabled because their API keys provide no supported remaining-balance endpoint.

## Reading the balance

OpenRouter subtracts usage from purchased credits. Its key endpoint uses the effective budget when supplied, otherwise the key's limit. An uncapped key shows monthly spending and “No limit.” HTTP 401 rejects the key without fallback.

DeepSeek can return several currencies. Moonshot uses available balance rather than cash balance. xAI's ledger total is signed cents, converted as `-val / 100`. Novita amounts are divided by 10,000. Missing required xAI or fal.ai balance fields produce an error rather than a zero balance.

Amounts use decimal arithmetic and locale currency formatting. Positive amounts below one cent display as less than one cent; negative balances remain visible.

With a known total, severity follows the percentage remaining. Otherwise the default low threshold is 5 currency units, or 35 CNY; below twice that is moderate. Zero or negative is exhausted. An account takes its worst balance's status.

## Refresh and removal

Balances refresh alongside usage. Failed reads retain previous values and their age; one failed account does not block others. Credits widgets show up to one, three or six accounts, worst first, depending on size.

Keys stay in Android's encrypted credential store or iOS Keychain, separate from cached balances. Removing an account deletes its local key. Revoke the key in the provider's console to invalidate it elsewhere. See [Security](security.md).
