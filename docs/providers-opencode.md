# OpenCode Go

API key only. The usage route is undocumented (source: the `opencode-go-limits` project; an
official usage API would be tracked in anomalyco/opencode#16017), so this provider can break
without notice.

## Auth

The user pastes an OpenCode Go key. It is stored in the keychain/Keystore, never logged, and
validated against the usage endpoint before an account row exists. No refresh grant; a revoked
key surfaces as a 401. No regions.

## Endpoint

`GET https://opencode.ai/zen/go/v1/usage` with `Authorization: Bearer <key>`.

```json
{"usage":{
  "rolling":{"status":"ok","percent":98,"resetsAt":"2026-10-07T22:03:52.000Z"},
  "weekly":{"status":"ok","percent":39,"resetsAt":"2026-10-12T00:00:00.000Z"},
  "monthly":{"status":"ok","percent":19,"resetsAt":"2026-10-22T18:11:46.000Z"}}}
```

## Field semantics

- `rolling` is the 5-hour window, `weekly` the week, `monthly` the month (nominally 30 days; the
  real period follows the subscription date).
- `percent` is an integer percent **used**; `resetsAt` is ISO-8601.
- Only `status: "ok"` is trusted. Any other status, a missing `percent` or one outside 0-100
  leaves that window unknown rather than guessed. A window object that is absent is not drawn.

## Identity

The response names no user: the account is filed under a one-way digest of the key (see
Z.ai). A rotated key is a new account.

## Not covered

Balance, request counts and per-model usage.
