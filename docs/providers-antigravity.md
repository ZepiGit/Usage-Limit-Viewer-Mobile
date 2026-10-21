# Antigravity

Add Antigravity in Accounts and sign in with Google in the browser. Authorization uses PKCE with `access_type=offline` and `prompt=consent`, returning to `http://localhost:51121/oauth-callback`. Token exchange and renewal use `https://oauth2.googleapis.com/token`; Google userinfo supplies account identity. The existing refresh token is retained when renewal omits a replacement.

The app resolves the account's project through `v1internal:loadCodeAssist` with `ideType: ANTIGRAVITY` metadata, storing its project ID and reported tier. Quota requests use `POST v1internal:retrieveUserQuotaSummary` with `{"project":"<project id>"}` on these hosts:

- `daily-cloudcode-pa.googleapis.com`
- `daily-cloudcode-pa.sandbox.googleapis.com`
- `cloudcode-pa.googleapis.com`

Results are combined by bucket ID, keeping the first measurement when hosts overlap. Both project lookup and quota requests send the reference client's compatibility header:

```text
User-Agent: antigravity/hub/2.25.0 (aidev_client; os_type=darwin; arch=arm64)
```

## Reading quota

The app displays returned `groups[]` and `buckets[]` as shared quota pools rather than one row per model. Responses can contain Gemini Models and third-party pools, with five-hour and weekly buckets. Only returned buckets appear; the app does not infer a missing Gemini allowance or weekly window.

`remainingFraction` is a fraction from 0 to 1 remaining. A value of 0.53 displays 53% remaining. An omitted fraction with a reset time means exhausted unless the bucket is disabled; without either value, the row remains unknown. `resetTime` is an absolute timestamp. Five-hour buckets lead, followed by weekly and other labeled windows.

The Hub version comes from the [official updater manifest](https://antigravity-hub-auto-updater-974169037036.us-central1.run.app/manifest/latest-arm64-mac.yml). Header formatting follows [CLIProxyAPI](https://github.com/router-for-me/CLIProxyAPI/blob/0db19025571684b198a2ef508f99b41c717337e8/internal/misc/antigravity_version.go), omitting the build suffix when unknown. The grouped summary route follows [Management Center](https://github.com/router-for-me/Cli-Proxy-API-Management-Center/blob/714de0760a3ce86094b178fb46f834d34bc4a2a5/src/features/quota/providers/antigravity/data.ts). Google's installed-application client secret is a public native-client value; [Security](security.md) explains the storage boundary. No reset-credit action is offered.
