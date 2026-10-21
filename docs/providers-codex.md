# OpenAI Codex

In Accounts, add Codex and sign in to ChatGPT in the browser. The default flow uses PKCE and returns to `http://localhost:1455/auth/callback`. If the port is occupied, the app switches to a device code. You can also choose **Use a device code** while waiting or after a failed browser attempt.

For device sign-in, approve the displayed code at `https://auth.openai.com/codex/device` and return to the app. The attempt expires after 15 minutes. Cancel before starting over. Identity and plan come from the returned ID token, including the ChatGPT account ID used for usage requests.

## Endpoints

Browser authorization uses `https://auth.openai.com/oauth/authorize`. Device authorization starts at `/api/accounts/deviceauth/usercode` and polls `/api/accounts/deviceauth/token` on the same host. Code exchange and renewal use `POST https://auth.openai.com/oauth/token`. Browser PKCE is generated locally; the device flow receives its verifier from OpenAI.

Usage is `GET https://chatgpt.com/backend-api/wham/usage`, with bearer authorization and `Chatgpt-Account-Id`. Reset credits use `/backend-api/wham/rate-limit-reset-credits` and its `/consume` route. Only credit calls add `OpenAI-Beta: codex-1` and `Originator: Codex Desktop`.

## Reading quota and resets

`used_percent` is consumed quota; the app shows `100 − used` remaining. Declared `limit_window_seconds` determines five-hour, weekly or monthly classification. Unfamiliar durations remain “Limit”; slot position does not establish a duration. Absolute `reset_at` takes precedence over relative `reset_after_seconds`.

Global, code-review and additional limits keep separate identities. `limit_reached` or `allowed: false` marks the corresponding family exhausted. Missing measurements remain unknown.

Available Codex reset credits appear in account details. Redemption spends a real credit and requires deliberate approval; it is unavailable when the applicable available count is zero. A consume request is never automatically repeated. The Resets tab lists upcoming window resets, independently of these credits. Removing the account deletes local credentials; manage session revocation with OpenAI.
