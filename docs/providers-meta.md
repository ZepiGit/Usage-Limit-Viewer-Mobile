# Meta Muse

Meta Muse is connected with the OAuth device authorization grant used by the Muse CLI. The app
uses the public Muse client id and never asks for Google Vertex credentials.

## Sign in

Android and iOS POST the public client id to
`https://auth.meta.com/oidc/device/authorization/`, show the returned user code, and poll
`https://auth.meta.com/oidc/device/token/` with the RFC 8628 device-code grant. Pending and
slow-down responses are handled at the provider's interval. The returned DCA token is kept in
the encrypted credential store/keychain. The app then mints the Muse API key through
`POST https://api.meta.ai/muse-code/key`.

The API key is used as the normal Muse credential. Subscription quota calls intentionally use
the DCA bearer token instead:

```text
POST https://api.meta.ai/muse-code/key
Authorization: Bearer <dca_token>
x-api-version: 1.0.0
Content-Type: application/json
{}
```

The DCA token and API key are never written to the account row or widget cache.

## Quota fields

The response can include account metadata and `subs_usage`. The parser reads only the safe
quota fields:

- `subs_tier_name` (falling back to `subs_usage.tier` for the plan label)
- `is_subs_active`
- `subs_usage.window.used_percent`, `window_duration_mins`, `resets_at`
- `subs_usage.weekly.used_percent`, `resets_at`

`used_percent` is converted to the app's remaining-percent display. Missing usage blocks remain
unknown; the response is never treated as zero usage. Other response fields, including the
minted key and personal data, are discarded after parsing.
