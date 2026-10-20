# Credit Viewer

Prepaid and API balances, shown beside the usage-limit accounts but separate from them: a credit
account has an amount of money left, not windows and not a reset. Shared by the Android and iOS
apps; raw values, field names and rules below are identical on both.

## Providers

| id | Provider | Endpoint | Auth | Key you need | Reads |
|---|---|---|---|---|---|
| `openrouter` | OpenRouter | `GET https://openrouter.ai/api/v1/credits`; on **403** only, `GET https://openrouter.ai/api/v1/key` | Bearer | Management key (account balance) or any API key (that key's own limit) | `/credits`: `total_credits - total_usage` USD, total = `total_credits`. `/key`: `effective_budget.remaining_usd` when non-null (total = `limit_usd`), else `limit_remaining` (total = `limit`); `limit` null means no cap: shows `usage_monthly` as spent, label "No limit", never $0 |
| `deepseek` | DeepSeek | `GET https://api.deepseek.com/user/balance` | Bearer | API key | one balance per `balance_infos[]` currency (`total_balance` is a string); `is_available: false` reads as empty |
| `moonshot` | Moonshot (Kimi API) | intl `https://api.moonshot.ai/v1/users/me/balance` (USD); cn `https://api.moonshot.cn/v1/users/me/balance` (CNY) | Bearer | Platform API key (not a Kimi Code key); keys do not cross regions | `data.available_balance`; `cash_balance` may be negative and is ignored |
| `xai` | xAI (prepaid) | `GET https://management-api.x.ai/auth/management-keys/validation` for the team, then `GET https://management-api.x.ai/v1/billing/teams/{team_id}/prepaid/balance` | Bearer | Management key (console.x.ai, Settings, Management Keys), **not** an inference key | `total.val` is USD cents as a string from a ledger, sign inverted: left = `-val / 100`. Team id is `scopeId` when `scope == SCOPE_TEAM`, else the user types it. 404 or a missing total is an error, never $0 |
| `fal` | fal.ai | `GET https://api.fal.ai/v1/account/billing?expand=credits` | `Authorization: Key <key>` | Key with `billing:usage:read` (the BILLING preset), **not** an inference key | `credits.current_balance` in `credits.currency`; a response without `credits` is an error (it happens during fal incidents), never $0 |
| `novita` | Novita AI | `GET https://api.novita.ai/openapi/v1/billing/balance/detail` | Bearer | API key | `availableBalance` string in 1/10000 USD, divided by 10000; may be negative |

### Not possible

Shown in the add picker as disabled rows with the reason, and not stored or synced.

| Provider | Reason |
|---|---|
| Anthropic API | No balance endpoint for API keys. The Admin API cost report is spend only and needs an organisation admin key. |
| OpenAI API | No documented balance endpoint for API keys. The organisation costs endpoint is spend only and needs an admin key; the dashboard credit-grants route is undocumented and unreliable. |

## Model

- `CreditBalance`: `currency` (ISO 4217), `amount` (decimal, never a float), `total` (decimal,
  optional: cap or purchased amount, draws the bar), `label` (optional), `unlimited` (bool, an
  uncapped OpenRouter key: `amount` is the month's spend, not a balance).
- `CreditSnapshot`: `accountID`, `balances`, `fetchedAt`, `lastAttemptAt`, `status` (`ok` or
  `failed`), `error`. A failed sync keeps the previous balances and the original `fetchedAt`:
  stale numbers marked stale beat an empty card.
- `CreditAccount`: `id`, `provider`, `label` (defaults to the provider name),
  `credentialReference`, `attributes` (`region`, `teamId`, `lowThreshold`), `sortOrder`,
  `createdAt`.

## Severity

- `amount <= 0` is exhausted.
- Total known: the share left goes through the usage thresholds (above 50 healthy, above 20
  moderate, above 0 low).
- Otherwise absolute: below `lowThreshold` low, below twice it moderate, else healthy. The
  threshold is per account (`attributes.lowThreshold`), default 5 currency units, 35 for CNY.
- An account's severity is its worst balance. A failed read with no balances is an error; stale
  data follows the usage staleness rule (twice the sync interval).

## Formatting

Locale-aware currency, two fraction digits. A positive amount under one cent shows as `<$0.01`.
Negative amounts (arrears) are shown as they are.

## Where it lives

- iOS: `credits.json` in the shared container beside `accounts.json`; keys in the keychain under
  `credit:<account id>`. Balances are written as decimal strings. The widget reads the `credits`
  array that `GlanceSnapshot` now carries (absent in files from older builds).
- Refresh runs inside `UsageLimitsContainer.refresh()`, so the background task updates balances
  with usage. Each account is isolated; there is no token refresh.
- A key is checked against the provider before the account is created. A rejected key leaves no
  account and no keychain entry.

## Security notes

- The key is stored only in the keychain (iOS) or Keystore (Android), never in the account file,
  the widget snapshot or a log, and never in an error message: errors are built from endpoint
  names and HTTP statuses.
- Use the least powerful key that works: a BILLING-preset key for fal, a management key for xAI
  (read-only billing, it cannot spend), a normal key elsewhere. None of these keys is sent
  anywhere except its provider's host over HTTPS.
- Removing an account deletes the key from this device. It does not revoke it at the provider;
  revoke it in the provider's console if it may have leaked.
- Balance endpoints are documented by their providers except as noted, but a provider can change
  one at any time; a changed shape surfaces as a per-account error, not a wrong number.
