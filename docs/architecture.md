# Architecture

Provider adapters turn responses into shared account, quota and balance models. Screens and widgets use those models rather than interpreting provider JSON. Android uses Kotlin, Compose, Room and Glance; iOS uses SwiftUI, WidgetKit and the shared `UsageLimitsKit` package.

## Code layout

Android has one `:app` Gradle module. `core/` contains models, networking, authentication, storage, settings and sync. `providers/` owns each service's endpoints and parsers. `feature/` contains screens and ViewModels, `ui/` shared presentation, and `widget/` home-screen rendering. `AppContainer` wires dependencies through constructors.

On iOS, `UsageLimitsKit` owns provider clients, authentication, models and sync. The app handles browser presentation and background work; the widget extension reads the published App Group snapshot. The platforms share behavior, not a database or credentials.

## Quota model

`ProviderAccount` identifies an account by provider and external account ID. Its `credentialReference` names a separate credential-store entry. Nonsecret routing details, such as region or project ID, live in account attributes.

`UsageWindow` stores percent consumed and derives percent remaining. Adapters convert remaining fractions and amount/capacity pairs at this boundary. Missing or invalid measurements stay unknown. Window categories follow declared durations; unfamiliar durations retain their labels and reset times. Global constraints, model or feature pools, and unknown scopes remain distinct.

`UsageSnapshot` records measurement time, attempt time, status, windows and reset credits. Failed refreshes retain the previous readings and their original age. Partial results keep measurements alongside a separate warning.

Severity follows remaining quota: above 50% is healthy, above 20% moderate, above zero low, and zero exhausted. Global limits govern availability; an exhausted model pool can restrict that model while other quota remains. Summaries prefer global constraints rather than averaging independent pools. Credit balances have a separate decimal-money model described in [Credits](credits.md).

## Storage and sync

Android encrypts credentials with a Keystore key and stores account metadata, readings and widget configuration in Room. iOS uses Keychain credentials and local account files. Widget inputs contain presentation data rather than tokens; see [Security](security.md).

Sync isolates failures per account. Token renewal is serialized per credential reference, re-reading credentials inside the lock to protect rotating refresh tokens. Removal and credential replacement are checked before saving results. Cancellation does not become an account error.

Foreground actions use the sync engine directly. Android background work uses WorkManager with a connected-network constraint and a minimum 15-minute periodic interval. Repeated queued refresh requests collapse into one pass. iOS background refresh follows system scheduling.

Cache changes request widget updates and notification evaluation. Presentation updates also handle stale data and elapsed resets without inventing a new measurement. Both platforms honor reduced-motion settings. [Widgets](widgets.md) describes selection and refresh behavior.
