# Ollama Cloud

Add Ollama Cloud in Accounts and paste an API key from `https://ollama.com/settings/keys`. There is no region choice. The app checks the key against the balance call before saving it in Android's encrypted credential store or iOS Keychain. Keys do not expire and have no refresh grant; replace a rejected key in the app.

Usage and the prepaid balance come from one documented call: `GET https://ollama.com/api/balance` with `Authorization: Bearer <key>` and no query parameters ([docs.ollama.com/api/balance](https://docs.ollama.com/api/balance)). The app adds only `Accept: application/json`. One key can therefore be added twice — as a usage account and as an [Ollama Cloud credit account](../credits.md) — and the two accounts read the same payload for different rows.

## Reading quota

A current plan's `included` block states `balance_usd` and `allowance_usd` over a `period`. That is one window, **Included allowance**, whose used share is `(allowance − balance) / allowance` clamped to 0–100 and whose reset is `period.until`. A zero or missing allowance omits the window rather than drawing 0% used, and a null `period` keeps the window with no reset.

A legacy plan states `session` and `weekly` inside `included` instead, each with `remaining_percent` and `resets_at`. That percentage is what remains, so the app shows `100 − remaining_percent` as used; a value outside 0–100 leaves that window unknown. `weekly` is categorised as a week.

Neither the legacy session limit nor the included period has a documented duration — the included period follows the plan's own reset schedule, annual plans included — so both keep their label and reset with no category. The windows appear in the order **Session**, **Weekly** and **Included allowance**. The names are the app's own rather than payload field names: it translates Session and Included allowance, and Weekly was already one of its labels.

`purchased.balance_usd` is the credit row: unexpired prepaid credit in USD, labelled **Purchased**. The documentation states no cap, so the row draws no bar. A missing `purchased` block fails the credit account's read, never a zero; a stated `0` is a reading.

A body with no `included` block gives the usage account no windows, and a body with neither block is not this endpoint's answer, so it stays unknown rather than reading as a plan without limits. The endpoint allows ten requests a minute per user, shared across keys and devices, and answers HTTP 429 with `Retry-After`; the app then pauses automatic reads for that account. HTTP 401 is an invalid key and 403 an account suspended, and neither is retried.

The response names no user, so a one-way key digest identifies the account. Pasting the same key again updates the account; removal deletes the local key, and revocation belongs in the Ollama account.

Evidence: the shape above is the OpenAPI block published on [docs.ollama.com/api/balance](https://docs.ollama.com/api/balance); the page documents no duration for the legacy session limit. No live Ollama account was checked.
