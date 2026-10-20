# Z.ai GLM Coding Plan

API key only. The quota route is undocumented; the shape below comes from open-source
monitors (CodexBar, paseo, onWatch) and may change without notice.

## Auth

The user creates a key in the Z.ai console and pastes it. It is stored in the iOS keychain and
the Android Keystore like every other credential, never logged, and validated against the usage
endpoint **before** an account row exists. There is no refresh grant: `refresh()` returns the key
unchanged, and a revoked key surfaces as a 401 on the usage call.

### Region

Z.ai runs two services with separate accounts; a key only works on the host that issued it. The
user picks the region before pasting the key, and it is stored as `region` in the credential's
provider data (where a refresh can read it) and in the account attributes.

| `region` | Usage host |
|---|---|
| `intl` (default) | `https://api.z.ai` |
| `cn` | `https://open.bigmodel.cn` |

## Endpoint

`GET <host>/api/monitor/usage/quota/limit` with `Authorization: Bearer <key>` and
`Accept: application/json`.

```json
{"code":200,"msg":"Operation successful","data":{"limits":[
  {"type":"CREDIT_LIMIT","unit":3,"number":5,"usage":2000,"currentValue":402,"remaining":1597,"percentage":20,"nextResetTime":1788351145586},
  {"type":"CREDIT_LIMIT","unit":6,"number":1,"usage":10000,"currentValue":5207,"remaining":4792,"percentage":52,"nextResetTime":1788784466996}
],"level":"lite"},"success":true}
```

## Field semantics

- `type` is `TOKENS_LIMIT`, `TIME_LIMIT` or `CREDIT_LIMIT`; all three are read the same way.
- Window length is `number` of `unit`, with `unit` in minutes: `1` = day (1440), `3` = hour (60),
  `5` = minute (1), `6` = week (10080). So 5h is unit 3 number 5, weekly is unit 6 number 1.
  The monthly `TIME_LIMIT` arrives as unit 5 number 1 and is read as 30 days. An unknown unit
  leaves the length unknown (category `other`, labelled from `type`).
- Percent **used** is `percentage` when present (Max plans may carry only that and a reset
  time); otherwise `currentValue / usage`, where `usage` is the **cap**. A zero cap, a negative
  or a figure over 100 makes the window unknown, never zero and never full.
- `nextResetTime` is epoch milliseconds and is absent at 0 % use; the window then has no reset.
- `data.level` (`lite`, `pro`, `max`) is the plan label.
- A 200 whose body says `code: 401` is treated as a rejected key.

## Identity

The response names no user, so the account is filed under a stable, one-way digest of the key
(`pastedKeyIdentity`, the same fallback Kimi uses). Pasting the same key again updates the
account; a rotated key is a new account, because nothing in the response survives a rotation.

## Not covered

Per-model breakdowns, MCP tool-call counters beyond the generic windows, and the pay-as-you-go
API balance. No endpoint other than the quota route above is called.
