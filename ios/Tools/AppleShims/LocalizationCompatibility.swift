import Foundation

// Compiled into the app's `swiftc -typecheck` run by `typecheck-app.sh`, next to the app sources
// and deliberately outside `Sources/` so it never becomes part of the AppleShims package or the
// kit.
//
// swift-corelibs-foundation has no `String(localized:)`; Apple's Foundation does, and the app
// (including the Foundation-only `LocalizedTexts.swift`) calls it with a literal. This lets that
// one constructor resolve on Linux, nothing more: it returns the literal as written, so it
// neither reads `Localizable.xcstrings` nor selects a plural variation. `check-xcstrings.py`
// covers the catalog; the real SDK build on macOS stays authoritative for lookup behaviour.
#if os(Linux)
extension String {
    init(localized value: String) { self = value }
}
#endif
