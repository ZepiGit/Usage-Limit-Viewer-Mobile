# OpenCode Go

Add OpenCode Go in Accounts and paste your Go API key. There is no region choice. The app checks the key against the usage endpoint before saving it in Android's encrypted credential store or iOS Keychain. Keys have no refresh grant; replace a rejected or revoked key in the app.

Usage is `GET https://opencode.ai/zen/go/v1/usage` with `Authorization: Bearer <key>`.

The response's `rolling`, `weekly` and `monthly` windows represent five-hour, weekly and subscription-month limits. `percent` is consumed quota; the app displays the remaining percentage. `resetsAt` is an ISO timestamp. A valid percentage requires `status: "ok"` and a value from 0 to 100; other states stay unknown. Absent window objects are omitted.

The response supplies no user identity, so a one-way key digest identifies the local account. Pasting the same key updates it; rotating the key creates a new account. Removal deletes the local key, while revocation belongs in the provider's console. This route reports quota, separately from prepaid balances.
