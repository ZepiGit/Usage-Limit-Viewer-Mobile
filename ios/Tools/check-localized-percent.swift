import Foundation

// Host regression for the `%` in localized literals. `check-localized-percent.sh` compiles the
// String Catalog with `xcstringstool`, then runs this against the compiled `<lang>.lproj`
// folders. It needs Apple's Foundation: swift-corelibs-foundation has no `String(localized:)`.
//
// Not a UI test: the question is which key Foundation derives from a literal containing `%`
// and what comes back for it, which needs no simulator and no SwiftUI.
//
// Every case repeats the call-site literal verbatim. The derived key depends on how the literal
// is written (fixed text or an interpolation), so a String handed to `LocalizationValue` would
// test a different path than the app takes. `key` is only printed, to show what Foundation
// derived when a case fails.
//
// The language comes from the `<lang>.lproj` bundle passed as `bundle:`. `locale:` alone does
// not choose a strings file, so it is set to match and nothing more.

struct Case {
    let name: String
    let key: String
    let english: String
    let german: String
    let make: (Bundle, Locale) -> String
}

func scalars(_ text: String) -> String {
    text.unicodeScalars.map { String($0.value, radix: 16, uppercase: true) }.joined(separator: " ")
}

func run() -> Int32 {
    guard CommandLine.arguments.count == 2 else {
        print("usage: check-localized-percent.swift <compiled-catalog-directory>")
        return 2
    }
    let compiled = CommandLine.arguments[1]

    // The pieces the app passes in: a bare percent token and two names.
    let token = "73"
    let name = "Claude"
    let label = "5h limit"

    let cases: [Case] = [
        Case(
            name: "Above 80% used (settings title)",
            key: LocalizedStringResource("Above 80% used").key,
            english: "Above 80% used",
            german: "Über 80 % verbraucht",
            make: { bundle, locale in String(localized: "Above 80% used", bundle: bundle, locale: locale) }),
        Case(
            name: "Above 90% used (settings title)",
            key: LocalizedStringResource("Above 90% used").key,
            english: "Above 90% used",
            german: "Über 90 % verbraucht",
            make: { bundle, locale in String(localized: "Above 90% used", bundle: bundle, locale: locale) }),
        Case(
            name: "Below 10% left (settings title)",
            key: LocalizedStringResource("Below 10% left").key,
            english: "Below 10% left",
            german: "Unter 10 % übrig",
            make: { bundle, locale in String(localized: "Below 10% left", bundle: bundle, locale: locale) }),
        Case(
            name: "Below 20% left (settings title)",
            key: LocalizedStringResource("Below 20% left").key,
            english: "Below 20% left",
            german: "Unter 20 % übrig",
            make: { bundle, locale in String(localized: "Below 20% left", bundle: bundle, locale: locale) }),
        Case(
            name: "\\(token)% (interpolated figure)",
            key: LocalizedStringResource("\(token)%").key,
            english: "73%",
            german: "73 %",
            make: { bundle, locale in String(localized: "\(token)%", bundle: bundle, locale: locale) }),
        Case(
            name: "warning notification, 20% (interpolated sentence)",
            key: LocalizedStringResource("\(name) · \(label): less than 20% remaining").key,
            english: "Claude · 5h limit: less than 20% remaining",
            german: "Claude · 5h limit: weniger als 20 % übrig",
            make: { bundle, locale in
                String(localized: "\(name) · \(label): less than 20% remaining", bundle: bundle, locale: locale)
            }),
    ]

    var failures: [String] = []

    for language in ["en", "de"] {
        let folder = "\(compiled)/\(language).lproj"
        // A missing language is a failure, never a skip: it would otherwise pass for "nothing wrong".
        guard FileManager.default.fileExists(atPath: "\(folder)/Localizable.strings"),
              let bundle = Bundle(path: folder)
        else {
            failures.append("[\(language)] no compiled Localizable.strings at \(folder)")
            continue
        }
        let locale = Locale(identifier: language)
        for c in cases {
            let expected = language == "en" ? c.english : c.german
            let actual = c.make(bundle, locale)
            if actual == expected {
                print("ok    [\(language)] \(c.name): \(actual)")
            } else {
                print("FAIL  [\(language)] \(c.name)")
                print("        key:      \(c.key)")
                print("        expected: \(expected)  (\(scalars(expected)))")
                print("        actual:   \(actual)  (\(scalars(actual)))")
                failures.append("[\(language)] \(c.name)")
            }
        }
    }

    guard failures.isEmpty else {
        let summary = "\n\(failures.count) localized percent check(s) failed:\n"
            + failures.map { "  \($0)\n" }.joined()
        FileHandle.standardError.write(Data(summary.utf8))
        return 1
    }
    print("All localized percent checks passed.")
    return 0
}

exit(run())
