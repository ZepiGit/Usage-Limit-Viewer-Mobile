import XCTest
@testable import UsageLimitsKit

/// The shape of the shared provider payload vectors.
///
/// The file pins how a documented response becomes credit rows or usage windows, and which
/// failures stay unknown rather than zero. Both parsers read it — iOS here, Android in
/// `ProviderPayloadMappingVectorsTest` — so a section this file loses or a field one side reads
/// differently has to fail a suite rather than pass as an empty test.
///
/// Only the shape is checked; each slice adds one test method per section that maps the same cases
/// through its parser.
final class ProviderPayloadMappingVectorsTests: XCTestCase {

    private let sections = [
        "openrouterKeyWindow", "vercelAiGateway", "deepinfra", "tokenrouter", "ollama", "minimax",
    ]

    private static let file = SharedVectors.file("provider-payload-mapping.json")

    private func root() throws -> [String: Any] {
        let data = try Data(contentsOf: Self.file)
        let object = try JSONSerialization.jsonObject(with: data)
        return try XCTUnwrap(object as? [String: Any], "the provider payload vector file must be one JSON object")
    }

    private func cases(_ section: String) throws -> [[String: Any]] {
        let value = try XCTUnwrap(try root()[section] as? [Any], "\(section) must be a JSON array")
        return try value.map { try XCTUnwrap($0 as? [String: Any], "\(section) holds only JSON objects") }
    }

    private func string(_ where_: String, _ value: Any?) throws -> String {
        try XCTUnwrap(value as? String, "\(where_) must be a JSON string")
    }

    private func decimalText(_ where_: String, _ value: Any?) throws {
        let text = try string(where_, value)
        XCTAssertNotNil(text.range(of: #"^-?\d+(\.\d+)?$"#, options: .regularExpression),
                        "\(where_) must be a decimal string, was \"\(text)\"")
        XCTAssertNotNil(Decimal(string: text, locale: Locale(identifier: "en_US_POSIX")),
                        "\(where_) must parse as a decimal, was \"\(text)\"")
    }

    private func instant(_ where_: String, _ value: Any?) throws {
        let text = try string(where_, value)
        XCTAssertNotNil(Self.instantFormatter.date(from: text),
                        "\(where_) must be an ISO-8601 UTC instant, was \"\(text)\"")
    }

    /// A JSON number stays a number: `true` serialized back is text, not digits.
    private func numberText(_ value: Any) -> String? {
        guard let data = try? JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed]) else {
            return nil
        }
        return String(decoding: data, as: UTF8.self)
    }

    func testTheFileHoldsTheDescriptionAndExactlyTheSixSections() throws {
        let root = try root()
        XCTAssertEqual(Set(root.keys), Set(sections + ["description"]))
        XCTAssertFalse(try string("description", root["description"]).isEmpty)
    }

    func testEveryCaseCarriesANameAStatusABodyAndOneExpectForm() throws {
        for section in sections {
            for (index, vector) in try cases(section).enumerated() {
                let where_ = "\(section)[\(index)]"
                let allowed: Set<String> = ["name", "status", "body", "expect", "now"]
                let required: Set<String> = ["name", "status", "body", "expect"]
                XCTAssertTrue(allowed.isSuperset(of: vector.keys), "\(where_) has unexpected keys \(vector.keys)")
                XCTAssertTrue(Set(vector.keys).isSuperset(of: required),
                              "\(where_) is missing \(required.subtracting(vector.keys))")
                XCTAssertFalse(try string("\(where_).name", vector["name"]).isEmpty)
                let status = try XCTUnwrap(vector["status"], "\(where_).status is missing")
                XCTAssertNotNil(Int(try XCTUnwrap(numberText(status), "\(where_).status is not a number")),
                                "\(where_).status must be a JSON integer")
                if let now = vector["now"] { try instant("\(where_).now", now) }
                let expect = try XCTUnwrap(vector["expect"] as? [String: Any],
                                           "\(where_).expect must be a JSON object")
                try checkExpect("\(where_).expect", expect)
            }
        }
    }

    func testBalancesCarryStringAmountsAndWindowsCarryAUsedPercentThatMayBeNull() throws {
        for section in sections {
            for (index, vector) in try cases(section).enumerated() {
                let expect = try XCTUnwrap(vector["expect"] as? [String: Any])
                if let balances = expect["balances"] as? [Any] {
                    for (row, element) in balances.enumerated() {
                        let where_ = "\(section)[\(index)].expect.balances[\(row)]"
                        let balance = try XCTUnwrap(element as? [String: Any], "\(where_) must be a JSON object")
                        let allowed: Set<String> = ["currency", "amount", "total"]
                        XCTAssertTrue(allowed.isSuperset(of: balance.keys),
                                      "\(where_) has unexpected keys \(balance.keys)")
                        XCTAssertTrue(Set(balance.keys).isSuperset(of: ["currency", "amount"]),
                                      "\(where_) is missing \(Set(["currency", "amount"]).subtracting(balance.keys))")
                        _ = try string("\(where_).currency", balance["currency"])
                        try decimalText("\(where_).amount", balance["amount"])
                        if let total = balance["total"], !(total is NSNull) { try decimalText("\(where_).total", total) }
                    }
                }
                if let windows = expect["windows"] as? [Any] {
                    for (row, element) in windows.enumerated() {
                        let where_ = "\(section)[\(index)].expect.windows[\(row)]"
                        let window = try XCTUnwrap(element as? [String: Any], "\(where_) must be a JSON object")
                        XCTAssertEqual(Set(window.keys), ["id", "usedPercent", "resetAt"])
                        _ = try string("\(where_).id", window["id"])
                        if let used = window["usedPercent"], !(used is NSNull) {
                            let text = try XCTUnwrap(numberText(used), "\(where_).usedPercent must be a JSON number or null")
                            XCTAssertNotNil(Double(text), "\(where_).usedPercent must be a JSON number or null")
                        }
                        if let reset = window["resetAt"], !(reset is NSNull) { try instant("\(where_).resetAt", reset) }
                    }
                }
            }
        }
    }

    private func checkExpect(_ where_: String, _ expect: [String: Any]) throws {
        let forms: Set<String> = ["balances", "windows", "error"]
        XCTAssertTrue(forms.isSuperset(of: expect.keys), "\(where_) has unexpected keys \(expect.keys)")
        XCTAssertFalse(expect.isEmpty, "\(where_) states none of balances, windows or error")
        if expect["error"] != nil {
            XCTAssertEqual(Set(expect.keys), ["error"], "\(where_): error cannot accompany balances or windows")
            XCTAssertTrue(["invalidKey", "forbidden", "unknown"].contains(try string("\(where_).error", expect["error"])),
                          "\(where_).error must be invalidKey, forbidden or unknown")
        }
        if let balances = expect["balances"] { XCTAssertTrue(balances is [Any], "\(where_).balances must be an array") }
        if let windows = expect["windows"] { XCTAssertTrue(windows is [Any], "\(where_).windows must be an array") }
    }

    private static let instantFormatter: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter
    }()
}
