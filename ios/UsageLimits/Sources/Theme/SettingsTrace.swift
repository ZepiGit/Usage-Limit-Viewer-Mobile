#if DEBUG
import Foundation
#if canImport(os)
import os
#endif

@MainActor
enum SettingsTrace {
    #if canImport(os)
    private static let logger = Logger(subsystem: "com.usagelimits.app", category: "SettingsTrace")
    #endif
    static let isEnabled = ProcessInfo.processInfo.arguments.contains("-ui-testing")
        && ProcessInfo.processInfo.arguments.contains("-settings-trace")

    nonisolated static let gapVariant: String? = {
        let arguments = ProcessInfo.processInfo.arguments
        guard arguments.contains("-ui-testing"),
              let option = arguments.firstIndex(of: "-settings-gap-mode"),
              option + 1 < arguments.count else { return nil }
        let variant = arguments[option + 1]
        return ["animated", "still", "no-figure"].contains(variant) ? variant : nil
    }()

    private static let capacity = 128
    private static var lines: [String] = []
    private static var nextLine = 0
    private static var bodyCount = 0
    private static var figureCount = 0
    private static var settingsFigureCount = 0
    private(set) static var activeFigures = 0
    private static var previousBodyCount = 0
    private static var previousFigureCount = 0
    private static var previousSettingsFigureCount = 0
    static var nativeControlBoundary: (identity: String, field: String)?

    static func settingsBody() {
        guard isEnabled else { return }
        bodyCount += 1
    }

    static func figureActivityChanged(to active: Bool) {
        guard isEnabled else { return }
        activeFigures += active ? 1 : -1
    }

    static func figureFrame(_ subject: String?) {
        guard isEnabled else { return }
        figureCount += 1
        if let subject, subject.hasPrefix("preview-") {
            settingsFigureCount += 1
        }
    }

    static func record(_ event: @autoclosure () -> String) {
        guard isEnabled else { return }
        let event = event()
        let entry = line(event)
        previousBodyCount = bodyCount
        previousFigureCount = figureCount
        previousSettingsFigureCount = settingsFigureCount
        if lines.count < capacity {
            lines.append(entry)
        } else {
            lines[nextLine] = entry
        }
        nextLine = (nextLine + 1) % capacity
        #if canImport(os)
        logger.notice("[settings-trace] \(entry, privacy: .public)")
        #else
        print("[settings-trace] \(entry)")
        #endif
        if let boundary = nativeControlBoundary,
           event.hasPrefix("settings didSet source=control edit "),
           event.contains("\(boundary.field)=") {
            nativeControlBoundary = nil
            let changes = event.dropFirst("settings didSet source=control edit ".count)
            record("native first-binding \(boundary.identity) field=\(boundary.field) changes={\(changes)}")
        }
    }

    static func snapshot() -> String {
        guard isEnabled else { return "" }
        // Accessibility reads must not consume counters or add events.
        let recent = lines.count < capacity ? lines : Array(lines[nextLine...] + lines[..<nextLine])
        return (recent + [line("capture")]).joined(separator: "\n")
    }

    private static func line(_ event: String) -> String {
        let uptime = String(
            format: "%.6f", locale: Locale(identifier: "en_US_POSIX"),
            ProcessInfo.processInfo.systemUptime)
        return "uptime=\(uptime) settingsBodyDelta=\(bodyCount - previousBodyCount) "
            + "figureFrameDelta=\(figureCount - previousFigureCount) "
            + "settingsPreviewFrameDelta=\(settingsFigureCount - previousSettingsFigureCount) "
            + String(event.prefix(2048))
    }
}
#endif
