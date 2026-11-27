# Credit balances

Credits shows API and prepaid balances separately from subscription quota. In **Add Credit Balance**, choose a provider, paste the required key and select a region or team where applicable. The key is checked before saving. You can rename the account and set its low-balance threshold.

## Keys and endpoints

OpenRouter uses a management key for the account balance, or a normal API key for its own limit. It reads `GET https://openrouter.ai/api/v1/credits`, falling back to `/api/v1/key` on HTTP 403.

DeepSeek uses an API key with `GET https://api.deepseek.com/user/balance`.

Moonshot uses an Open Platform API key, separate from Kimi Code, with `GET https://api.moonshot.ai/v1/users/me/balance` for international USD or `api.moonshot.cn` for China CNY.

xAI uses a management key from console Settings → Management Keys, not an inference key, with `GET https://management-api.x.ai/v1/billing/teams/{team_id}/prepaid/balance`.

fal.ai requires a BILLING-preset key with `billing:usage:read` for `GET https://api.fal.ai/v1/account/billing?expand=credits`.

Novita uses an API key with `GET https://api.novita.ai/openapi/v1/billing/balance/detail`.

Vercel AI Gateway uses an AI Gateway API key (`vck_…`) or a Vercel access token with `GET https://ai-gateway.vercel.sh/v1/credits`. Its team id is optional and entered when the account is added; a stored id is sent as `?teamId=`. Vercel documents `teamId` for its API-key management calls, not for this endpoint, so a key that covers several teams answers 403, which asks for the team id.

DeepInfra uses an API key with the `account:read` scope, which new keys have by default, and reads `GET https://api.deepinfra.com/v1/me?checklist=true`.

TokenRouter uses a management key, not an inference key, with `GET https://api.tokenrouter.com/api/management/self/wallet`.

Ollama Cloud uses an API key from `ollama.com/settings/keys` with `GET https://ollama.com/api/balance`. It is the same call the [Ollama Cloud usage account](providers/ollama.md) reads, so one key can be added as both a usage account and a credit account.

Moonshot keys belong to their issuing region. xAI resolves the team through `/auth/management-keys/validation`; a team ID entered by the user takes precedence. Anthropic and OpenAI balance entries are disabled because their API keys provide no supported remaining-balance endpoint.

## Reading the balance

OpenRouter subtracts usage from purchased credits. Its key endpoint uses the effective budget when supplied, otherwise the key's limit. An uncapped key shows monthly spending and “No limit.” HTTP 401 rejects the key without fallback. A capped key shows the limit's period — daily, weekly or monthly — and its next UTC boundary as the row's reset: 00:00 UTC, Monday 00:00 UTC, or the first of the month at 00:00 UTC. A null or unfamiliar `limit_reset` keeps the plain key limit with no reset, and the effective-budget row never carries one.

DeepSeek can return several currencies. Moonshot uses available balance rather than cash balance. xAI's ledger total is signed cents, converted as `-val / 100`. Novita amounts are divided by 10,000. Missing required xAI or fal.ai balance fields produce an error rather than a zero balance.

Vercel AI Gateway states every figure as a decimal string. The balance row is what is left and its label is lifetime spend; an active Token Pass adds a second row with its reset and no bar, a null pass or remainder adds no row, and a figure sent as a JSON number is a malformed answer. DeepInfra's `stripe_balance` is signed: negative is money left to spend, so the row negates it, and a positive figure is money owed that stays visible as a negative balance. Each live `scoped_credits` entry adds a row named after it, expired entries add none, and `limit` is a spending cap, never a balance. TokenRouter adds the topped-up balance and the valid vouchers, with the spent figures on its label; its management documentation states no cap, so the row draws no bar. Ollama Cloud's `purchased.balance_usd` is unexpired prepaid credit, and no cap is documented there either, so that row draws no bar.

These shapes come from the providers' own documentation, with two inferences recorded in the code: DeepInfra's cents unit (its OpenAPI types `stripe_balance` as a number and states no unit; only the scoped credits name cents) and TokenRouter's USD (its management documentation names no currency).

Amounts use decimal arithmetic and locale currency formatting. Positive amounts below one cent display as less than one cent; negative balances remain visible.

With a known total, severity follows the percentage remaining. Otherwise the default low threshold is 5 currency units, or 35 CNY; below twice that is moderate. Zero or negative is exhausted. An account takes its worst balance's status.

<a id="credits-in-android-overview"></a>

## Credits in Overview

On Android and iOS, open **Settings → Overview** and choose **Normal** to list balances or **Compact** to see the number of tracked credit accounts. When a current balance is below its own threshold, Compact also names the one furthest below, comparing each amount as a share of that threshold rather than comparing currencies. A still-current balance retained during a rate-limit pause can appear there; stale balances and ordinary failed reads cannot. Tap the compact row to open it. **Collapse** or leaving Overview restores the compact row. On Android, an expanded block survives configuration-state restoration; leaving Overview still collapses it.

**Show credits** comes before its dependent Normal/Compact and **Show first** controls on both platforms. **Show first** places Credits above or below usage accounts in List and Dials. The changing Overview preview follows all controls, so expanding it does not move the switch away from your finger. Hiding Credits removes only the block and its layout/order controls; it does not remove accounts or change refresh or widget settings.

**Manage credit accounts** in Settings → Overview stays available when the block is hidden. Normal and expanded Compact also offer management and Add. With no tracked credit accounts, Compact uses the normal empty state so you can add one.

With credit accounts but no usage accounts, Overview says **No usage accounts yet**, not that all accounts are missing. It keeps one visible Add credit path: the Credits heading supplies it in Normal or expanded Compact; the secondary empty-state action remains when populated Compact is collapsed or Credits is hidden.

On a German device these controls are under **Einstellungen → Übersicht**: **Guthabenkonten**, **Zuerst anzeigen**, **Guthaben anzeigen** and **Guthaben verwalten**. The credit-only heading reads **Noch keine Nutzungskonten**. Both native implementations ship in 0.5.1. Combined CI and physical-device acceptance notes remain in the [release checkpoint](release.md#release-050-checkpoint).

## Refresh and removal

Balances refresh alongside usage. Failed reads retain previous values and their age; one failed account does not block others. Credits widgets show up to one, three or six accounts, worst first, depending on size. On iOS, medium and large Credits widgets show the last-updated timestamp on its own line when it cannot fit beside the title. With larger text, fewer accounts may fit, but the widget still shows how many are hidden.

Android's running widget recomputes age, severity and order on presentation repaints without syncing. iOS prepares timeline entries at those age boundaries. Neither path promises when the system will display an update.

Keys stay in Android's encrypted credential store or iOS Keychain, separate from cached balances. Removing an account deletes its local key. Revoke the key in the provider's console to invalidate it elsewhere. See [Security](security.md).
