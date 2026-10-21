# Security

Usage Limits connects directly to providers using your OAuth credentials or API keys. There is no app account, backend, analytics or crash-reporting service. Browser sign-in happens on the provider's page, outside the app; the app receives an authorization result rather than your password.

## What needs protecting

Some provider scopes permit more than quota reading, including inference. A stolen credential can therefore expose account capabilities beyond the numbers shown here. The app uses credentials for profile, quota and balance reads, token renewal, and an explicitly requested Codex reset-credit redemption. It sends no prompts or inference requests.

The storage design protects against other ordinary apps and copied credential files. Someone holding an unlocked phone can see the app's account labels and readings. A rooted device or compromised operating system is outside this protection: code running as the app can use its credentials.

## Storage boundaries

On Android, `KeystoreCredentialStore` encrypts each credential record with AES-128-GCM using the platform-default key size. The nonexportable key lives in Android Keystore under `usage_limits_credentials_v1`; encrypted records live in private `usage_limits_credentials` preferences. The platform generates a fresh random IV for each encryption. The IV is stored with the ciphertext, and authentication failure makes the record unreadable. Writes complete synchronously before success is reported.

The key does not require a biometric prompt or recent unlock, so scheduled refresh can use it while the phone is locked. Hardware-backed key protection depends on the device. This protects key material from export; it does not stop a compromised app process from requesting decryption.

On iOS, credentials live in Keychain with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`. They become available after the first unlock following a restart and remain usable for background refresh. The device-only setting prevents credential migration to another device. The widget extension has no credential access.

Android Room tables and iOS account files contain metadata, credential references and cached readings. Widgets read presentation data from the local cache or App Group snapshot. Notifications use labels and quota values. These surfaces carry no tokens or keys, but account metadata and usage are still private information.

Android disables cloud backup and device transfer for app data using `allowBackup=false` and exclusion rules. iOS device-only Keychain items do not migrate through backup or transfer. Reconnect accounts on a replacement device. Uninstalling Android removes accounts, encrypted credentials and widget configuration; see [upgrade instructions](release.md#preserving-data-on-upgrade).

## Authentication and transport

Redirect sign-in uses PKCE with S256 and random state held in memory. The callback must match the pending state before exchanging its code. Claude validates both query and embedded `code#state` forms. Codex accepts its full random state with an optional provider-added suffix.

Loopback listeners bind to `127.0.0.1` and close when the attempt ends. Claude uses port 54545, Antigravity 51121 and Codex 1455; Devin selects an ephemeral port. Codex also offers device-code sign-in. Local HTTP is limited to loopback; provider traffic uses HTTPS and the platform trust store, without certificate pinning. Callback error text is HTML-escaped.

xAI discovery endpoints must use HTTPS on `x.ai` or a dot-separated subdomain. The saved token endpoint is checked again before renewal. Device polling observes expiry, denial and permanent slow-down responses.

## Credential lifecycle

Renewal is serialized per credential reference and re-reads the stored credential inside the lock. Removed credentials cannot be replaced by a waiting refresh. Ambiguous delivery of an authorization code or rotating refresh token ends the exchange instead of replaying it. Android permits bounded retries for connection failures known to precede transmission.

Account removal deletes local credentials before account data; reconnecting reuses the existing reference. Removal does not revoke a provider session or key. Use the provider's account settings or console to revoke access elsewhere.

Credential objects redact tokens when formatted. Logs and displayed errors use fixed messages, endpoint names or status codes rather than response bodies. Redact tokens, codes, cookies and personal identifiers before sharing diagnostics.

## Provider policy

Antigravity includes Google's installed-application client secret, a public native-client value under RFC 8252 §8.5. PKCE protects authorization; this value is not a confidential user credential. Codex's device flow receives its PKCE pair from the server rather than generating it locally.

The app uses compatibility headers required by provider endpoints. It does not imitate TLS fingerprints, solve bot challenges or substitute another application's identity to bypass restrictions. Provider permission and distribution requirements belong to the [release owner](release.md#owner-responsibilities).
