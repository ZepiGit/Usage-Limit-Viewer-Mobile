# Providers

Supported services in [Usage Limits Mobile](../README.md) 0.5.1: eleven subscriptions and ten prepaid credit APIs, on Android and iOS.

[Back to the README](../README.md) · Per-provider setup notes are linked from each name where a dedicated guide exists.

<h2 id="services">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="images/readme/headings/services-dark.png">
    <img src="images/readme/headings/services-light.png" alt="Services" height="48">
  </picture>
</h2>


For Claude, Codex, Antigravity and Devin you sign in on the service’s own web page, in your normal browser. The app never sees your password; it only receives the sign-in result. A **Sign-in** notification keeps the app awake so it can catch that result (Android 14 and later can pause background apps while the browser is open). If signing in takes longer than three minutes, the notification asks you to come back to the app.

A pasted key from the Kimi console reads usage either way. Kimi may need to approve the app’s name before device-code sign-in can read usage.

| Service | What you see | How you connect |
| :--- | :--- | :--- |
| **[OpenAI Codex](providers/codex.md)** | Five-hour and weekly limits, plan limits, reset credits | Sign in through your browser (or a device code) |
| **[Claude](providers/claude.md)** | Five-hour, weekly and per-model limits | Sign in through your browser |
| **[Antigravity](providers/antigravity.md)** | Quota per model | Sign in with Google in your browser |
| **Grok** | Weekly credits and the monthly billing window | Device code |
| **[Kimi Code](providers/kimi.md)** | Coding quota and its reset times, on kimi.com or kimi.ai | Device code, or a key from your Kimi console |
| **[Devin](providers/devin.md)** | Daily and weekly seat quota | Sign in through your browser |
| **[Meta Muse](providers/meta.md)** | Rolling window and weekly quota | Device code |
| **[Z.ai GLM Coding Plan](providers/zai.md)** | Five-hour, weekly and monthly limits, on z.ai or bigmodel.cn | API key |
| **[OpenCode Go](providers/opencode.md)** | Five-hour, weekly and monthly limits | API key |
| **[Ollama Cloud](providers/ollama.md)** | Included allowance, or a legacy plan’s session and weekly limits | API key |
| **[MiniMax Token Plan](providers/minimax.md)** | Five-hour and weekly limits per model, on minimax.io or minimaxi.com | API key |

**Why isn’t my service here?** Some were left out on purpose. GitHub Copilot and Warp only answer apps that pretend to be their own tools. Gemini CLI no longer accepts personal Google accounts (Antigravity covers that now). Cursor, Perplexity, Mistral, Zed and Qwen only work with browser cookies. Vertex AI counts quota per cloud project, not per person. See the [provider policy](release.md#owner-responsibilities).

## Prepaid credit APIs

The **Credits** section shows how much money is left on each account, in that account’s currency, and can warn you when it gets low. Keys stay on the device; the app talks to the provider directly. See [Credit balances](credits.md).

| Service | Which key it needs |
| :--- | :--- |
| **OpenRouter** | A management key shows the whole account; a normal key shows that key’s own limit |
| **DeepSeek** | Your normal API key |
| **Moonshot (Kimi API)** | Your platform API key, for the international or the China site |
| **[xAI](providers/xai.md)** | A management key (the team is found automatically, or you type it in) |
| **fal.ai** | A key with billing access |
| **Novita** | Your normal API key |
| **Vercel AI Gateway** | An AI Gateway key or a Vercel access token; the team id is optional |
| **DeepInfra** | Your normal API key, with the `account:read` scope |
| **TokenRouter** | A management key, not an inference key |
| **[Ollama Cloud](providers/ollama.md)** | Your Ollama API key, the same key the usage account reads |

The same Ollama key can be a usage account and a credit account. **Anthropic and OpenAI API balances cannot be shown:** neither offers a way to read the remaining balance with an API key, so the app says so instead of guessing.

Set a low-balance threshold per account, and choose Normal or Compact under **Settings → Overview**.
