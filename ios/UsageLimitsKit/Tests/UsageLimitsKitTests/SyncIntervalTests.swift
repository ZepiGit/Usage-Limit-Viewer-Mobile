import Foundation
import Testing
import UsageLimitsKit

struct SyncIntervalTests {
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

    @Test func settingsAndStalenessMatchSharedVectors() throws {
        let data = try Data(contentsOf: SharedVectors.file("sync-interval.json"))
        let vectors = try SharedVectors.parse(VectorFile.self, from: data).cases
        try #require(vectors.isEmpty == false)

        for vector in vectors {
            let settings = AppSettings(syncIntervalMinutes: vector.requestedMinutes)
            #expect(settings.syncIntervalMinutes == vector.normalizedMinutes, "\(vector.name)")
            #expect(settings.staleAfter == vector.staleAfterSeconds, "\(vector.name)")

            var edited = AppSettings()
            edited.syncIntervalMinutes = vector.requestedMinutes
            let normalised = edited.normalised()
            #expect(normalised.syncIntervalMinutes == vector.normalizedMinutes, "\(vector.name)")
            #expect(normalised.staleAfter == vector.staleAfterSeconds, "\(vector.name)")

            let encoded = try JSONEncoder().encode(edited)
            let decoded = try JSONDecoder().decode(AppSettings.self, from: encoded)
            #expect(decoded.syncIntervalMinutes == vector.normalizedMinutes, "\(vector.name)")
            #expect(decoded.staleAfter == vector.staleAfterSeconds, "\(vector.name)")
        }
    }

    @Test(arguments: [15, 30, 60, 180])
    func storedLegacyIntervalSurvivesRestart(minutes: Int) async throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("legacy-sync-interval-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        try Data("{\"syncIntervalMinutes\":\(minutes)}".utf8)
            .write(to: directory.appendingPathComponent("settings.json"))

        let store = SettingsStore(directory: directory)
        let loaded = await store.settings()
        #expect(loaded.syncIntervalMinutes == minutes)
        try await store.save(loaded)

        let reopened = await SettingsStore(directory: directory).settings()
        #expect(reopened.syncIntervalMinutes == minutes)
    }

    @Test func defaultRefreshIntervalStaysThirtyMinutes() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("default-sync-interval-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        #expect(AppSettings().syncIntervalMinutes == 30)
        let missing = await SettingsStore(directory: directory).settings()
        #expect(missing.syncIntervalMinutes == 30)

        try Data("{}".utf8).write(to: directory.appendingPathComponent("settings.json"))
        let store = SettingsStore(directory: directory)
        let decoded = await store.settings()
        #expect(decoded.syncIntervalMinutes == 30)
        try await store.save(decoded)

        let reopened = await SettingsStore(directory: directory).settings()
        #expect(reopened.syncIntervalMinutes == 30)
    }

    @Test func fiveMinuteReadingsBecomeStaleAtFortyFiveMinutes() {
        let fetchedAt = Date(timeIntervalSince1970: 1_757_000_000)
        let snapshot = UsageSnapshot(
            accountID: "five-minute-account", fetchedAt: fetchedAt, status: .ok, windows: [])
        let threshold = AppSettings(syncIntervalMinutes: 5).staleAfter

        #expect(threshold == 2_700)
        #expect(snapshot.isStale(at: fetchedAt.addingTimeInterval(2_699), staleAfter: threshold) == false)
        #expect(snapshot.isStale(at: fetchedAt.addingTimeInterval(2_700), staleAfter: threshold))
        #expect(snapshot.isStale(at: fetchedAt.addingTimeInterval(2_701), staleAfter: threshold))
    }

    @Test func fiveMinuteRefreshSurvivesRestart() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("sync-interval-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let settings = AppSettings(syncIntervalMinutes: 5)
        #expect(settings.syncIntervalMinutes == 5)

        let saved = try await SettingsStore(directory: directory).save(settings)
        #expect(saved.syncIntervalMinutes == 5)

        let reopened = await SettingsStore(directory: directory).settings()
        #expect(reopened.syncIntervalMinutes == 5)
    }
}
