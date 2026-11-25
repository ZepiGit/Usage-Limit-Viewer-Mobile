# Devin

Add Devin in Accounts and sign in through the browser. Authorization opens `https://app.devin.ai/auth/cli/continue` with PKCE, state and an ephemeral loopback redirect chosen before opening the page. Code exchange uses JSON at `https://api.devin.ai/auth/cli/token`; identity comes from `GET https://api.devin.ai/v3/self`.

The resulting bearer session token has no refresh grant. It stays in encrypted credential storage; reconnect when the session expires.

Quota comes from `POST https://server.codeium.com/exa.seat_management_pb.SeatManagementService/GetUserStatus`, using Connect JSON and session authorization. `userStatus.planStatus` reports daily and weekly percentages remaining, and Unix reset times. Because Connect omits zero-valued fields, a window with a reset but no percentage means exhausted. `planInfo.planName` supplies the plan label.

Malformed individual windows are ignored independently. Removing the account deletes its local session token; provider-side revocation is separate.
