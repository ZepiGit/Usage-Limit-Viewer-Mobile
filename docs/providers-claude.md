# Claude

Add Claude in Accounts and complete sign-in on the provider's browser page. PKCE authorization starts at `https://claude.ai/oauth/authorize` with `code=true` and returns to `http://localhost:54545/callback`. Keep the app installed and return to it when prompted. Cancel the attempt before retrying if the redirect does not complete.

The code exchange and token renewal use JSON at `POST https://platform.claude.com/v1/oauth/token`. Both use `User-Agent: axios/1.15.2`. Profile and quota reads use `GET https://api.anthropic.com/api/oauth/profile` and `/api/oauth/usage`, with bearer authorization and `anthropic-beta: oauth-2025-04-20`. The profile provides account identity and the Pro or Max label when present.

## Reading quota

`utilization` and scoped `limits[].percent` are percentages consumed. The app derives remaining quota; zero consumption means 100% remaining. Missing or invalid measurements stay unknown, while explicit null window objects are absent.

The main five-hour and all-model weekly windows are global constraints. Opus, Sonnet, Cowork and Fable allowances retain their own scope and labels. Fable's flat key is `iguana_necktie`; scoped entries take priority and matching flat entries supplement them. Deduplication follows window identity rather than a coincident reset time.

Exhausting one model's allowance produces a named restriction while other quota remains. Unknown window shapes keep unknown duration and scope. `resets_at` is preserved as supplied, including dates farther away than a familiar window label suggests; time until reset is not treated as the window's duration.

No reset-credit redemption is offered. Removing an account removes its local credentials; revoke provider access through Claude's own settings. See [Security](security.md) for storage and the [release policy](release.md#owner-responsibilities) for provider restrictions.
