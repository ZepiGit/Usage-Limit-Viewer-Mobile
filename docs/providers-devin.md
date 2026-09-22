# Devin

Devin is connected with its CLI OAuth flow. The app uses PKCE and a loopback callback bound to
an ephemeral local port, so the exact redirect URI is generated before the browser opens. The
browser URL is `https://app.devin.ai/auth/cli/continue` with `redirect_uri`, `state`,
`code_challenge`, `code_challenge_method=S256`, and `prompt=select_account`.

The authorization code is exchanged as JSON at `https://api.devin.ai/auth/cli/token`:

```json
{"code":"…","code_verifier":"…"}
```

The resulting session token is prefixed with `devin-session-token$` when the provider returns a
raw JWT. Identity comes from `GET https://api.devin.ai/v3/self`.

## Quota fields

The seat-management endpoint is:

```text
POST https://server.codeium.com/exa.seat_management_pb.SeatManagementService/GetUserStatus
Content-Type: application/json
Connect-Protocol-Version: 1
Authorization: Basic <session_token>-<session_token>
```

The JSON Connect request carries the session token in `metadata.apiKey` together with the
`chisel` client metadata. `userStatus.planStatus` reports daily and weekly *remaining*
percentages; the parser converts them to consumed percentages and preserves Unix reset times.
`planInfo.planName` supplies the plan label. Unknown fields and malformed individual windows are
ignored independently.
