import Foundation

/// Devin account status returned by `SeatManagementService/GetUserStatus`.
public struct DevinUserStatus: Sendable, Equatable {
    public var email: String?
    public var userName: String?
    public var userID: String?
    public var teamID: String?
    public var orgID: String?
    public var orgName: String?
    public var plan: String?
    public var dailyRemainingPercent: Double?
    public var weeklyRemainingPercent: Double?
    public var dailyResetAt: Date?
    public var weeklyResetAt: Date?
    public var planStart: Date?
    public var planEnd: Date?

    public init(
        email: String? = nil,
        userName: String? = nil,
        userID: String? = nil,
        teamID: String? = nil,
        orgID: String? = nil,
        orgName: String? = nil,
        plan: String? = nil,
        dailyRemainingPercent: Double? = nil,
        weeklyRemainingPercent: Double? = nil,
        dailyResetAt: Date? = nil,
        weeklyResetAt: Date? = nil,
        planStart: Date? = nil,
        planEnd: Date? = nil
    ) {
        self.email = email
        self.userName = userName
        self.userID = userID
        self.teamID = teamID
        self.orgID = orgID
        self.orgName = orgName
        self.plan = plan
        self.dailyRemainingPercent = dailyRemainingPercent
        self.weeklyRemainingPercent = weeklyRemainingPercent
        self.dailyResetAt = dailyResetAt
        self.weeklyResetAt = weeklyResetAt
        self.planStart = planStart
        self.planEnd = planEnd
    }
}

/// Parses both encodings of Devin's Connect-RPC status response.
///
/// The official CLI currently sends/receives protobuf (`application/proto`). CPAMC and a few
/// gateway deployments expose the same message as JSON. Keeping the wire parser here avoids a
/// generated protobuf dependency in the small iOS package while still accepting either response.
public enum DevinUsageParser {

    private static let dailySeconds: Int64 = 86_400
    private static let weeklySeconds: Int64 = 604_800

    public static func parse(_ data: Data) throws -> DevinUserStatus {
        let bytes = [UInt8](data)
        if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            return parse(json)
        }
        return try parseProto(bytes)
    }

    public static func parse(_ payload: [String: Any]) -> DevinUserStatus {
        let root = JSONSupport.object(payload, "userStatus", "user_status", "status", "result")
            ?? payload
        let status = JSONSupport.object(root, "userStatus", "user_status") ?? root
        let planStatus = JSONSupport.object(status, "planStatus", "plan_status") ?? status
        let planInfo = JSONSupport.object(planStatus, "planInfo", "plan_info")

        return DevinUserStatus(
            email: JSONSupport.string(status, "email") ?? JSONSupport.string(payload, "email"),
            userName: JSONSupport.string(status, "user_name", "userName", "name"),
            userID: JSONSupport.string(status, "user_id", "userId", "id"),
            teamID: JSONSupport.string(status, "team_id", "teamId"),
            orgID: JSONSupport.string(status, "org_id", "orgId"),
            orgName: JSONSupport.string(status, "org_name", "orgName"),
            plan: JSONSupport.string(
                planInfo, "plan_name", "planName", "name")
                ?? JSONSupport.string(planStatus, "plan_name", "planName", "plan"),
            dailyRemainingPercent: percent(
                JSONSupport.double(
                    planStatus,
                    "daily_quota_remaining_percent", "dailyQuotaRemainingPercent",
                    "daily_remaining_percent", "dailyRemainingPercent")),
            weeklyRemainingPercent: percent(
                JSONSupport.double(
                    planStatus,
                    "weekly_quota_remaining_percent", "weeklyQuotaRemainingPercent",
                    "weekly_remaining_percent", "weeklyRemainingPercent")),
            dailyResetAt: date(
                JSONSupport.int64(
                    planStatus,
                    "daily_quota_reset_at", "dailyQuotaResetAt", "daily_quota_reset_at_unix",
                    "dailyQuotaResetAtUnix"),
                JSONSupport.string(
                    planStatus,
                    "daily_quota_reset_at", "dailyQuotaResetAt", "daily_quota_reset_at_unix",
                    "dailyQuotaResetAtUnix")),
            weeklyResetAt: date(
                JSONSupport.int64(
                    planStatus,
                    "weekly_quota_reset_at", "weeklyQuotaResetAt", "weekly_quota_reset_at_unix",
                    "weeklyQuotaResetAtUnix"),
                JSONSupport.string(
                    planStatus,
                    "weekly_quota_reset_at", "weeklyQuotaResetAt", "weekly_quota_reset_at_unix",
                    "weeklyQuotaResetAtUnix")),
            planStart: date(
                JSONSupport.int64(planStatus, "plan_start", "planStart"),
                JSONSupport.string(planStatus, "plan_start", "planStart")),
            planEnd: date(
                JSONSupport.int64(planStatus, "plan_end", "planEnd"),
                JSONSupport.string(planStatus, "plan_end", "planEnd")))
    }

    /// Converts the two Devin percentage windows to the app's consumed-percent model.
    public static func windows(from status: DevinUserStatus) -> [UsageWindow] {
        var result: [UsageWindow] = []
        if let remaining = status.dailyRemainingPercent {
            result.append(UsageWindow(
                id: "devin-daily",
                label: "Daily",
                category: .other,
                usedPercent: 100 - remaining,
                periodSeconds: dailySeconds,
                resetAt: status.dailyResetAt,
                exhausted: remaining <= 0))
        }
        if let remaining = status.weeklyRemainingPercent {
            result.append(UsageWindow(
                id: "devin-weekly",
                label: "Weekly",
                category: .weekly,
                usedPercent: 100 - remaining,
                periodSeconds: weeklySeconds,
                resetAt: status.weeklyResetAt,
                exhausted: remaining <= 0))
        }
        return result
    }

    // MARK: - Connect-RPC protobuf

    /// Builds the request message used by the official `chisel` client.
    public static func requestData(sessionToken: String, deviceFingerprint: String) -> Data {
        let fingerprint = deviceFingerprint.isEmpty
            ? deviceFingerprintForSeed(sessionToken)
            : deviceFingerprint
        var metadata: [UInt8] = []
        appendString(&metadata, field: 1, value: "chisel")
        appendString(&metadata, field: 2, value: "3000.10.21")
        appendString(&metadata, field: 3, value: sessionToken)
        appendString(&metadata, field: 4, value: "en")
        appendString(&metadata, field: 5, value: "ios")
        appendString(&metadata, field: 7, value: "3000.10.21")
        appendString(&metadata, field: 12, value: "chisel")
        appendString(&metadata, field: 31, value: fingerprint)

        var request: [UInt8] = []
        appendBytes(&request, field: 1, value: metadata)
        return Data(request)
    }

    /// Generates the same 732-character deterministic shape as CLIProxyAPI when no persisted
    /// device fingerprint exists. It is a pseudonymous identifier and contains no account data.
    public static func deviceFingerprintForSeed(_ seed: String) -> String {
        var result = ""
        var counter = 0
        while result.count < ProviderEndpoints.Devin.fingerprintHexLength {
            let digest = DevinDigest.hexDigest("\(seed)-\(counter)")
            result += digest
            counter += 1
        }
        return String(result.prefix(ProviderEndpoints.Devin.fingerprintHexLength))
    }

    /// Decodes the binary response from the seat-management RPC.
    public static func parseProto(_ data: [UInt8]) throws -> DevinUserStatus {
        guard !data.isEmpty else { throw ProviderError.malformedPayload("devin status was empty") }
        var status = DevinUserStatus()
        var reader = ProtoReader(data)
        while let field = try reader.next() {
            switch (field.number, field.wire) {
            case (1, .bytes):
                status = parseUserStatus(try reader.bytes(), into: status)
            default:
                try reader.skip(field.wire)
            }
        }
        return status
    }

    private static func parseUserStatus(_ data: [UInt8], into initial: DevinUserStatus) -> DevinUserStatus {
        var status = initial
        var reader = ProtoReader(data)
        while let field = try? reader.next() {
            switch (field.number, field.wire) {
            case (3, .bytes): status.userName = try? reader.string()
            case (5, .bytes): status.teamID = try? reader.string()
            case (7, .bytes): status.email = try? reader.string()
            case (13, .bytes): status = parsePlanStatus((try? reader.bytes()) ?? [], into: status)
            case (36, .bytes): status.userID = try? reader.string()
            default: try? reader.skip(field.wire)
            }
        }
        return status
    }

    private static func parsePlanStatus(_ data: [UInt8], into initial: DevinUserStatus) -> DevinUserStatus {
        var status = initial
        var reader = ProtoReader(data)
        while let field = try? reader.next() {
            switch (field.number, field.wire) {
            case (1, .bytes): status = parsePlanInfo((try? reader.bytes()) ?? [], into: status)
            case (2, .bytes): status.planStart = timestamp((try? reader.bytes()) ?? [])
            case (3, .bytes): status.planEnd = timestamp((try? reader.bytes()) ?? [])
            case (14, .varint): status.dailyRemainingPercent = percent(Double((try? reader.varint()) ?? 0))
            case (15, .varint): status.weeklyRemainingPercent = percent(Double((try? reader.varint()) ?? 0))
            case (17, .varint): status.dailyResetAt = epochDate((try? reader.varint()) ?? 0)
            case (18, .varint): status.weeklyResetAt = epochDate((try? reader.varint()) ?? 0)
            default: try? reader.skip(field.wire)
            }
        }
        return status
    }

    private static func parsePlanInfo(_ data: [UInt8], into initial: DevinUserStatus) -> DevinUserStatus {
        var status = initial
        var reader = ProtoReader(data)
        while let field = try? reader.next() {
            switch (field.number, field.wire) {
            case (2, .bytes): status.plan = try? reader.string()
            case (33, .bytes): status = parseOrganisation((try? reader.bytes()) ?? [], into: status)
            default: try? reader.skip(field.wire)
            }
        }
        return status
    }

    private static func parseOrganisation(_ data: [UInt8], into initial: DevinUserStatus) -> DevinUserStatus {
        var status = initial
        var reader = ProtoReader(data)
        while let field = try? reader.next() {
            switch (field.number, field.wire) {
            case (4, .bytes): status.orgID = try? reader.string()
            case (8, .bytes): status.orgName = try? reader.string()
            default: try? reader.skip(field.wire)
            }
        }
        return status
    }

    private static func timestamp(_ data: [UInt8]) -> Date? {
        var reader = ProtoReader(data)
        while let field = try? reader.next() {
            if field.number == 1, field.wire == .varint { return epochDate((try? reader.varint()) ?? 0) }
            try? reader.skip(field.wire)
        }
        return nil
    }

    private static func percent(_ value: Double?) -> Double? {
        guard let value, value.isFinite, value >= 0, value <= 100 else { return nil }
        return value
    }

    private static func date(_ integer: Int64?, _ string: String?) -> Date? {
        if let integer { return JSONSupport.date(epoch: integer) }
        return string.flatMap(JSONSupport.date)
    }

    private static func epochDate(_ value: UInt64) -> Date? {
        guard value > 0, value <= UInt64(Int64.max) else { return nil }
        return JSONSupport.date(epoch: Int64(value))
    }

    // MARK: - Minimal protobuf wire reader/writer

    private enum Wire: UInt8 {
        case varint = 0
        case bytes = 2
    }

    private struct Field {
        let number: Int
        let wire: Wire
    }

    private struct ProtoReader {
        let data: [UInt8]
        var index: Int = 0

        init(_ data: [UInt8]) { self.data = data }

        mutating func next() throws -> Field? {
            guard index < data.count else { return nil }
            let tag = try varint()
            let number = Int(tag >> 3)
            guard number > 0, let wire = Wire(rawValue: UInt8(tag & 7)) else {
                throw ProviderError.malformedPayload("devin status used an unsupported protobuf field")
            }
            return Field(number: number, wire: wire)
        }

        mutating func varint() throws -> UInt64 {
            var value: UInt64 = 0
            for shift in stride(from: 0, through: 63, by: 7) {
                guard index < data.count else {
                    throw ProviderError.malformedPayload("devin status had a truncated protobuf value")
                }
                let byte = data[index]
                index += 1
                value |= UInt64(byte & 0x7f) << UInt64(shift)
                if byte & 0x80 == 0 { return value }
            }
            throw ProviderError.malformedPayload("devin status had an oversized protobuf value")
        }

        mutating func bytes() throws -> [UInt8] {
            let length = try varint()
            guard length <= UInt64(data.count - index) else {
                throw ProviderError.malformedPayload("devin status had a truncated protobuf field")
            }
            let end = index + Int(length)
            defer { index = end }
            return Array(data[index..<end])
        }

        mutating func string() throws -> String? {
            String(bytes: try bytes(), encoding: .utf8)
        }

        mutating func skip(_ wire: Wire) throws {
            switch wire {
            case .varint: _ = try varint()
            case .bytes: _ = try bytes()
            }
        }
    }

    private static func appendString(_ data: inout [UInt8], field: Int, value: String) {
        appendBytes(&data, field: field, value: Array(value.utf8))
    }

    private static func appendBytes(_ data: inout [UInt8], field: Int, value: [UInt8]) {
        appendVarint(&data, UInt64(field << 3 | Int(Wire.bytes.rawValue)))
        appendVarint(&data, UInt64(value.count))
        data.append(contentsOf: value)
    }

    private static func appendVarint(_ data: inout [UInt8], _ value: UInt64) {
        var value = value
        while value >= 0x80 {
            data.append(UInt8(value & 0x7f) | 0x80)
            value >>= 7
        }
        data.append(UInt8(value))
    }
}

private enum DevinDigest {
    static func hexDigest(_ value: String) -> String {
        // OAuthFlows.swift contains the package's platform-neutral SHA-256 implementation.
        // Reuse it here so the parser builds on Linux as well as Apple platforms; importing
        // CryptoKit inside a function is illegal Swift and would make the package unbuildable.
        return SHA256.hash(Array(value.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}
