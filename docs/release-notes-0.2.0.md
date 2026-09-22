# Usage Limits Mobile 0.2.0

This release adds the OAuth providers currently exposed by CliProxyAPI beyond the providers
already in the app:

- **Devin** — PKCE browser login with an ephemeral loopback callback, profile lookup, and daily /
  weekly seat quotas.
- **Meta Muse** — RFC 8628 device login, Muse API-key minting, and rolling / weekly subscription
  quota windows. The DCA bearer token and minted API key are stored separately.

Both providers are implemented on Android and iOS, with parser and synthetic HTTP-flow tests.
Provider icons, account selection, settings, widgets and encrypted credential persistence include
both providers. Google Vertex is intentionally excluded because CliProxyAPI exposes it through
API-key/service-account authentication rather than OAuth.

The release workflow builds the signed Android AAB/APK from tag `v0.2.0` and an unsigned iOS
archive. No live provider credentials are included in tests or artifacts.
