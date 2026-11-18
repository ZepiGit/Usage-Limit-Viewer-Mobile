import Foundation

/// What the user chose, kept across launches.
///
/// In the shared container rather than `UserDefaults` for one reason that matters: a background
/// task and the widget's extension are separate processes, and the sync interval and the
/// staleness threshold derived from it have to mean the same thing in all three. A suite-scoped
/// `UserDefaults` would work too; a file keeps one storage story with the account cache beside
/// it rather than two.
///
/// Nothing here is a secret, so unlike the credential store this needs no protection class of
/// its own beyond the container's.
public actor SettingsStore {

    private let fileURL: URL
    private var current: AppSettings?

    /// No I/O here, for the reason given on `AccountRepository.init`: this is constructed on the
    /// launch path, and an actor initialiser runs inline on the thread that built it.
    public init(directory: URL, fileName: String = "settings.json") {
        self.fileURL = directory.appendingPathComponent(fileName)
    }

    public func settings() -> AppSettings {
        if let current { return current }
        let loaded = Self.load(from: fileURL)
        current = loaded
        return loaded
    }

    /// Saves, and reports what was actually stored.
    ///
    /// The interval is normalised to the supported minimum, so the returned value may differ
    /// from the input. The settings screen uses the returned value.
    @discardableResult
    public func save(_ settings: AppSettings) throws -> AppSettings {
        // Copy-based normalization preserves every preference, including fields added later.
        let normalised = settings.normalised()

        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .prettyPrinted]
        try encoder.encode(normalised).write(to: fileURL, options: ContainerFile.writingOptions)

        current = normalised
        return normalised
    }

    /// Unpins `id` in the settings in use even when the file cannot be written, then saves.
    ///
    /// For an account that is gone: its pin must not come back with it. Removal and sign-in stay
    /// successful when this write fails, so the stale id would otherwise linger in `current` and
    /// pin the same account again the moment it signs in. Throws the write error afterwards.
    public func releasePin(_ id: String) throws {
        var settings = settings()
        guard settings.pinnedAccountIDs.contains(id) else { return }
        settings.pinnedAccountIDs.removeAll { $0 == id }
        current = settings.normalised()
        try save(settings)
    }

    private static func load(from url: URL) -> AppSettings {
        guard
            let data = try? Data(contentsOf: url),
            let stored = try? JSONDecoder().decode(AppSettings.self, from: data)
        else {
            // Defaults rather than a failure. These are preferences: losing them costs the user
            // a trip back to the settings screen, where refusing to launch over an unreadable
            // preferences file costs them the app.
            return AppSettings()
        }
        // Normalised again, for the same reason as `save`.
        return stored.normalised()
    }
}
