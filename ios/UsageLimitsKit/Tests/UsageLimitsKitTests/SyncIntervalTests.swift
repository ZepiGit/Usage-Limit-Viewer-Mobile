import Foundation
import UsageLimitsKit
import XCTest

final class SyncIntervalTests: XCTestCase {
    private struct VectorFile: Decodable {
        let cases: [Vector]

        init(from decoder: any Decoder) throws {
            try requireExactKeys(decoder, ["cases"])
            let container = try decoder.container(keyedBy: Keys.self)
            cases = try container.decode([Vector].self, forKey: Keys("cases"))
        }
    }

    private struct Vector: Decodable {
        let name: String
        let requestedMinutes: Int
        let normalizedMinutes: Int
        let staleAfterSeconds: Double

        init(from decoder: any Decoder) throws {
            try requireExactKeys(decoder, [
                "name", "requestedMinutes", "normalizedMinutes", "staleAfterSeconds",
            ])
            let container = try decoder.container(keyedBy: Keys.self)
            name = try container.decode(String.self, forKey: Keys("name"))
            requestedMinutes = try container.decode(Int.self, forKey: Keys("requestedMinutes"))
            normalizedMinutes = try container.decode(Int.self, forKey: Keys("normalizedMinutes"))
            staleAfterSeconds = try container.decode(Double.self, forKey: Keys("staleAfterSeconds"))
        }
    }

    func testSettingsAndStalenessMatchSharedVectors() throws {
        let data = try Data(contentsOf: SharedVectors.file("sync-interval.json"))
        let vectors = try SharedVectors.parse(VectorFile.self, from: data).cases
        XCTAssertFalse(vectors.isEmpty)

        for vector in vectors {
            let settings = AppSettings(syncIntervalMinutes: vector.requestedMinutes)
            XCTAssertEqual(settings.syncIntervalMinutes, vector.normalizedMinutes, vector.name)
            XCTAssertEqual(settings.staleAfter, vector.staleAfterSeconds, vector.name)

            var edited = AppSettings()
            edited.syncIntervalMinutes = vector.requestedMinutes
            let normalised = edited.normalised()
            XCTAssertEqual(normalised.syncIntervalMinutes, vector.normalizedMinutes, vector.name)
            XCTAssertEqual(normalised.staleAfter, vector.staleAfterSeconds, vector.name)

            let encoded = try JSONEncoder().encode(edited)
            let decoded = try JSONDecoder().decode(AppSettings.self, from: encoded)
            XCTAssertEqual(decoded.syncIntervalMinutes, vector.normalizedMinutes, vector.name)
            XCTAssertEqual(decoded.staleAfter, vector.staleAfterSeconds, vector.name)
        }
    }

    func testStoredLegacyIntervalsSurviveRestart() async throws {
        for minutes in [15, 30, 60, 180] {
            let directory = FileManager.default.temporaryDirectory
                .appendingPathComponent("legacy-sync-interval-\(UUID().uuidString)")
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            defer { try? FileManager.default.removeItem(at: directory) }
            try Data("{\"syncIntervalMinutes\":\(minutes)}".utf8)
                .write(to: directory.appendingPathComponent("settings.json"))

            let store = SettingsStore(directory: directory)
            let loaded = await store.settings()
            XCTAssertEqual(loaded.syncIntervalMinutes, minutes)
            try await store.save(loaded)

            let reopened = await SettingsStore(directory: directory).settings()
            XCTAssertEqual(reopened.syncIntervalMinutes, minutes)
        }
    }

    func testDefaultRefreshIntervalStaysThirtyMinutes() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("default-sync-interval-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        XCTAssertEqual(AppSettings().syncIntervalMinutes, 30)
        let missing = await SettingsStore(directory: directory).settings()
        XCTAssertEqual(missing.syncIntervalMinutes, 30)

        try Data("{}".utf8).write(to: directory.appendingPathComponent("settings.json"))
        let store = SettingsStore(directory: directory)
        let decoded = await store.settings()
        XCTAssertEqual(decoded.syncIntervalMinutes, 30)
        try await store.save(decoded)

        let reopened = await SettingsStore(directory: directory).settings()
        XCTAssertEqual(reopened.syncIntervalMinutes, 30)
    }

    func testFiveMinuteReadingsBecomeStaleAtFortyFiveMinutes() {
        let fetchedAt = Date(timeIntervalSince1970: 1_757_000_000)
        let snapshot = UsageSnapshot(
            accountID: "five-minute-account", fetchedAt: fetchedAt, status: .ok, windows: [])
        let threshold = AppSettings(syncIntervalMinutes: 5).staleAfter

        XCTAssertEqual(threshold, 2_700)
        XCTAssertFalse(snapshot.isStale(at: fetchedAt.addingTimeInterval(2_699), staleAfter: threshold))
        XCTAssertTrue(snapshot.isStale(at: fetchedAt.addingTimeInterval(2_700), staleAfter: threshold))
        XCTAssertTrue(snapshot.isStale(at: fetchedAt.addingTimeInterval(2_701), staleAfter: threshold))
    }

    func testFiveMinuteRefreshSurvivesRestart() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("sync-interval-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let settings = AppSettings(syncIntervalMinutes: 5)
        XCTAssertEqual(settings.syncIntervalMinutes, 5)

        let saved = try await SettingsStore(directory: directory).save(settings)
        XCTAssertEqual(saved.syncIntervalMinutes, 5)

        let reopened = await SettingsStore(directory: directory).settings()
        XCTAssertEqual(reopened.syncIntervalMinutes, 5)
    }
}
