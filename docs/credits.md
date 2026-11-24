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

<a id="credits-in-android-overview"></a>

## Credits in Overview

On Android and iOS, open **Settings → Overview** and choose **Normal** to list balances or **Compact** to see the number of tracked credit accounts. When a current balance is below its own threshold, Compact also names the one furthest below, comparing each amount as a share of that threshold rather than comparing currencies. A still-current balance retained during a rate-limit pause can appear there; stale balances and ordinary failed reads cannot. Tap the compact row to open it. **Collapse** or leaving Overview restores the compact row. On Android, an expanded block survives configuration-state restoration; leaving Overview still collapses it.

**Show credits** comes before its dependent Normal/Compact and **Show first** controls on both platforms. **Show first** places Credits above or below usage accounts in List and Dials. The changing Overview preview follows all controls, so expanding it does not move the switch away from your finger. Hiding Credits removes only the block and its layout/order controls; it does not remove accounts or change refresh or widget settings.

**Manage credit accounts** in Settings → Overview stays available when the block is hidden. Normal and expanded Compact also offer management and Add. With no tracked credit accounts, Compact uses the normal empty state so you can add one.

With credit accounts but no usage accounts, Overview says **No usage accounts yet**, not that all accounts are missing. It keeps one visible Add credit path: the Credits heading supplies it in Normal or expanded Compact; the secondary empty-state action remains when populated Compact is collapsed or Credits is hidden.

On a German device these controls are under **Einstellungen → Übersicht**: **Guthabenkonten**, **Zuerst anzeigen**, **Guthaben anzeigen** and **Guthaben verwalten**. The credit-only heading reads **Noch keine Nutzungskonten**. Both native implementations are integrated in the 0.5.0 candidate; [combined CI and device acceptance](release.md#release-050-checkpoint) remain pending.

## Refresh and removal

Balances refresh alongside usage. Failed reads retain previous values and their age; one failed account does not block others. Credits widgets show up to one, three or six accounts, worst first, depending on size. On iOS, medium and large Credits widgets show the last-updated timestamp on its own line when it cannot fit beside the title. With larger text, fewer accounts may fit, but the widget still shows how many are hidden.

Android's running widget recomputes age, severity and order on presentation repaints without syncing. iOS prepares timeline entries at those age boundaries. Neither path promises when the system will display an update.

Keys stay in Android's encrypted credential store or iOS Keychain, separate from cached balances. Removing an account deletes its local key. Revoke the key in the provider's console to invalidate it elsewhere. See [Security](security.md).
