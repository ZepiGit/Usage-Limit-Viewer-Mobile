import Foundation
import XCTest
@testable import UsageLimitsKit

final class MetaDevinUsageParserTests: XCTestCase {
    func testMetaParsesCPAMCSubscriptionWindows() {
        let payload: [String: Any] = [
            "user_email": "muse@example.test",
            "user_full_name": "Muse Tester",
            "subs_tier_name": "Pro",
            "subs_usage": [
                "window": [
                    "used_percent": 25.0,
                    "resets_at": "2026-09-22T12:00:00Z",
                    "window_duration_mins": 300,
                ],
                "weekly": [
                    "used_percent": 60.0,
                    "resets_at": "2026-09-29T00:00:00Z",
                    "window_duration_mins": 10080,
                ],
            ],
        ]

        let identity = MetaUsageParser.identity(in: payload)
        XCTAssertEqual(identity.email, "muse@example.test")
        XCTAssertEqual(identity.displayName, "Muse Tester")
        XCTAssertEqual(identity.plan, "Pro")

        let windows = MetaUsageParser.parse(payload)
        XCTAssertEqual(windows.count, 2)
        XCTAssertEqual(windows[0].usedPercent, 25)
        XCTAssertEqual(windows[0].periodSeconds, 18_000)
        XCTAssertEqual(windows[0].category, .fiveHour)
        XCTAssertEqual(windows[1].usedPercent, 60)
        XCTAssertEqual(windows[1].periodSeconds, 604_800)
        XCTAssertEqual(windows[1].category, .weekly)
    }

    func testDevinParsesJSONStatusAndBuildsFingerprint() throws {
        let payload: [String: Any] = [
            "userStatus": [
                "email": "devin@example.test",
                "userId": "user-123",
                "userName": "Devin Tester",
                "planStatus": [
                    "planInfo": ["planName": "Team"],
                    "dailyQuotaRemainingPercent": 75,
                    "weeklyQuotaRemainingPercent": 40,
                    "dailyQuotaResetAt": 1_800_000_000,
                    "weeklyQuotaResetAt": 1_800_060_000,
                ],
            ],
        ]
        let data = try JSONSerialization.data(withJSONObject: payload, options: [])
        let status = try DevinUsageParser.parse(data)
        XCTAssertEqual(status.email, "devin@example.test")
        XCTAssertEqual(status.userID, "user-123")
        XCTAssertEqual(status.plan, "Team")
        XCTAssertEqual(status.dailyRemainingPercent, 75)
        XCTAssertEqual(status.weeklyRemainingPercent, 40)
        XCTAssertEqual(DevinUsageParser.windows(from: status).map(\.usedPercent), [25, 60])

        let fingerprint = DevinUsageParser.deviceFingerprintForSeed("session")
        XCTAssertEqual(fingerprint.count, ProviderEndpoints.Devin.fingerprintHexLength)
        let request = DevinUsageParser.requestData(sessionToken: "session", deviceFingerprint: fingerprint)
        XCTAssertFalse(request.isEmpty)
    }

    func testDevinParsesConnectRPCBinaryStatus() throws {
        var planInfo: [UInt8] = []
        appendString(&planInfo, field: 2, value: "Pro")
        var planStatus: [UInt8] = []
        appendBytes(&planStatus, field: 1, value: planInfo)
        appendVarintField(&planStatus, field: 14, value: 80)
        appendVarintField(&planStatus, field: 15, value: 50)
        appendVarintField(&planStatus, field: 17, value: 1_800_000_000)
        var userStatus: [UInt8] = []
        appendString(&userStatus, field: 3, value: "Devin Tester")
        appendString(&userStatus, field: 7, value: "devin@example.test")
        appendString(&userStatus, field: 36, value: "user-123")
        appendBytes(&userStatus, field: 13, value: planStatus)
        var outer: [UInt8] = []
        appendBytes(&outer, field: 1, value: userStatus)

        let status = try DevinUsageParser.parseProto(outer)
        XCTAssertEqual(status.userID, "user-123")
        XCTAssertEqual(status.plan, "Pro")
        XCTAssertEqual(status.dailyRemainingPercent, 80)
        XCTAssertEqual(status.weeklyRemainingPercent, 50)
        XCTAssertEqual(status.dailyResetAt, Date(timeIntervalSince1970: 1_800_000_000))
    }

    private func appendString(_ bytes: inout [UInt8], field: Int, value: String) {
        appendBytes(&bytes, field: field, value: Array(value.utf8))
    }

    private func appendBytes(_ bytes: inout [UInt8], field: Int, value: [UInt8]) {
        appendVarint(&bytes, UInt64(field << 3 | 2))
        appendVarint(&bytes, UInt64(value.count))
        bytes.append(contentsOf: value)
    }

    private func appendVarint(_ bytes: inout [UInt8], _ value: UInt64) {
        var value = value
        while value >= 0x80 {
            bytes.append(UInt8(value & 0x7f) | 0x80)
            value >>= 7
        }
        bytes.append(UInt8(value))
    }

    private func appendVarintField(_ bytes: inout [UInt8], field: Int, value: UInt64) {
        appendVarint(&bytes, UInt64(field << 3))
        appendVarint(&bytes, value)
    }
}
