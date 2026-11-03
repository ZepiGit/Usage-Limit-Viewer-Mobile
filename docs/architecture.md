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

## Presentation and localization

Every screen and widget presents a usage window through one `WindowPresentation`, never through its own copy of the rules. It carries the figure token, the side the figure counts (left or used), the fill fraction and an exhausted flag, and holds no strings or colors. `core/model/WindowPresentation.kt` on Android and `Model/WindowPresentation.swift` in the Kit have the same names and rules, and `WindowPresentationTest` and `WindowPresentationTests` run the same vector table. Before rounding, the numeric Left and Used values are complements derived from remaining quota. Displayed tokens can round independently, and exhaustion takes precedence in Used mode. `Severity` stays keyed on remaining in both modes.

The reading's validity and the fill state decide how a bar is painted. `ReadingValidity` (current, stale, reset due, error) outranks severity: a stale or reset-due reading keeps its figure under a grey fill, and a failed one draws no fill. Only a current reading lets severity decide, where an exhausted window draws no fill in Left but is the full bar in Used. `FillState` (data, stale, no fill) is the result; each surface maps it to its own palette, and Glance widgets restate the app's because they cannot read its theme. Two ring surfaces preserve their existing ink independently of fill state: Android widget ring arcs use the account accent, and the iOS circular Lock Screen arc uses the primary color. Both still take the fraction from the presentation.

Words and the percent sign stay with the surface. Pure code that has no resources receives them through adapters, with English as the default so tests need no resources. Android passes `CountdownTexts` and `NotificationTexts`, implemented in `ui/text` by `ResourceCountdownTexts` and `ResourceNotificationTexts`. The Kit declares protocols of the same names, `CountdownTexts` and `NotificationTexts`, and the app target implements them with `String(localized:)` in `LocalizedTexts.swift`. The Kit therefore performs no resource lookups and stays compatible with plain Foundation on Linux. Its English text implementations remain as defaults; app and widget entry points inject localized implementations. A few English functions remain only as references that tests hold equal to the resources.

Strings live in one catalog per platform: Android's `values` and `values-de`, and iOS's `Localizable.xcstrings`, shared by the app and the widget extension. Both ship English and German. The percent sign comes from a template, so German reads “73 %”. Provider-reported window labels and model names are data and are not translated. `StringResourcesCompletenessTest` checks that every Android string exists in both languages, and `ios/Tools/check-xcstrings.py` checks the iOS catalog and the literals in the app and widget sources.

On Android a widget's own choice is a nullable column on its configuration row, and null follows the app setting. On iOS 17 it is an intent parameter, resolved as widget override, then app setting. The widgets that have no configuration read the setting from the published snapshot.

## Storage and sync

Android encrypts credentials with a Keystore key and stores account metadata, readings and widget configuration in Room. iOS uses Keychain credentials and local account files. Widget inputs contain presentation data rather than tokens; see [Security](security.md).

Sync isolates failures per account. Token renewal is serialized per credential reference, re-reading credentials inside the lock to protect rotating refresh tokens. Removal and credential replacement are checked before saving results. Cancellation does not become an account error.

Foreground actions use the sync engine directly. Android background work uses WorkManager with a connected-network constraint and a minimum 15-minute periodic interval. Repeated queued refresh requests collapse into one pass. iOS background refresh follows system scheduling.

Cache changes request widget updates and notification evaluation. Presentation updates also handle stale data and elapsed resets without inventing a new measurement. Both platforms honor reduced-motion settings. [Widgets](widgets.md) describes selection and refresh behavior.
