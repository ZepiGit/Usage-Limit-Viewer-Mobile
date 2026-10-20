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

Locale-aware currency (`NumberFormat.getCurrencyInstance` with `Currency.getInstance(code)` on
Android, `Decimal.FormatStyle.Currency` on iOS), two fraction digits. A positive amount under one
cent shows as `<$0.01`. Negative amounts (arrears) are shown as they are. An uncapped OpenRouter key
reads "$12.50 spent this month" rather than a balance.

## Where it lives

| | Android | iOS |
|---|---|---|
| Accounts and last read | Room v11: `credit_accounts` and `credit_snapshots` (migration 10 to 11, additive, schema exported as `11.json`); a snapshot row cascades with its account | `credits.json` in the shared container beside `accounts.json` |
| Key | `KeystoreCredentialStore` under the account's reference `credit:<account id>` (stored as the access token, no refresh grant); never in Room | Keychain under `credit:<account id>` |
| Amounts on disk | Decimal strings in `credit_snapshots.balancesJson` (`BigDecimal`, never `Double`) | Decimal strings in `credits.json` (`Decimal`) |
| Account id | `credit-<provider>-<digest>`, digest = first 8 bytes of SHA-256 of the key, hex (`providers/KeyIdentity.kt`) | `credit-<provider>-<digest>`, digest = FNV-1a of the key, hex |
| Refresh | `CreditSyncEngine`, run beside `SyncEngine` by `SyncWorker` and the foreground refresh | `CreditSyncEngine`, run by `UsageLimitsContainer.refresh()` |
| Widget | `Credits` Glance widget: 1 account at 2x2, 3 at 4x2, 6 at 4x4, worst first; reads the credit repository only | `CreditsWidget`: small 1, medium 3, large 6; reads the `credits` array of the glance snapshot (absent in files from older builds) |
| UI | Overview "Credits" section, add screen, detail screen (edit name and low-balance figure, refresh, remove) | Overview "Credits" section, `CreditsScreen`, add and edit sheets |

The account id digest differs per platform because the two apps do not share storage; nothing
compares ids across them.

- A refresh updates every credit account in the same pass as the usage accounts, so the background
  task updates balances with usage. Each account is isolated: one bad key becomes that account's
  error line and nothing else's. There is no token refresh.
- A key is checked against the provider before the account is created. A rejected key leaves no
  account and no stored key. The same key pasted again updates the same account (it keeps its place
  and creation time). For xAI the team id is resolved at add time (a typed id wins, else the
  validation call's `scopeId`, else the add is refused) and stored in `attributes.teamId`.
- A failed sync keeps the previous balances and the original `fetchedAt`; only the attempt time,
  status and error change. A balance read for an account removed mid-fetch is dropped.
- Status mapping for every provider: 401 and 403 read as "the key was rejected (HTTP n)", 404 as
  not found, anything else as "answered HTTP n". OpenRouter falls back to `/key` on **403 only**;
  a 401 is a bad key and does not retry.

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
