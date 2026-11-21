import Foundation

public enum AppearanceTheme: String, Codable, CaseIterable, Sendable {
    case warmDark, pureBlack, coolGraphite, lightPaper

    public var isLight: Bool { self == .lightPaper }

    public var palette: AppearancePalette {
        switch self {
        case .warmDark: return Self.warm
        case .pureBlack: return Self.black
        case .coolGraphite: return Self.graphite
        case .lightPaper: return Self.paper
        }
    }

    private static let warm = AppearancePalette(ground: 0x0F0F0E, surface: 0x1A1918, raised: 0x242322,
        rule: 0x2E2D2B, control: 0x4A4844, ink: 0xF0EEE6, muted: 0xB4B2A9, faint: 0x8A887F)
    private static let black = AppearancePalette(ground: 0x000000, surface: 0x111111, raised: 0x1D1D1D,
        rule: 0x2A2A2A, control: 0x474747, ink: 0xF2F2F2, muted: 0xB3B3B3, faint: 0x8C8C8C)
    private static let graphite = AppearancePalette(ground: 0x0E1013, surface: 0x171A1F, raised: 0x21252B,
        rule: 0x2B3038, control: 0x464C56, ink: 0xECEFF3, muted: 0xAEB4BD, faint: 0x878E98)
    private static let paper = AppearancePalette(ground: 0xF3F0E8, surface: 0xFFFFFF, raised: 0xE8E5DC,
        rule: 0xE3E0D7, control: 0xBDB9AE, ink: 0x1A1918, muted: 0x5C5A53, faint: 0x6B6960,
        data: 0xB8654A, positive: 0x437345, warning: 0x8A5A0A, negative: 0xB03A31,
        negativeText: 0xB03A31, dataText: 0x1A1918)
}

public enum AppearanceTextFont: String, Codable, CaseIterable, Sendable {
    case system, robotoFlex, ibmPlexSans, figtree
}

public enum AppearanceFigureFont: String, Codable, CaseIterable, Sendable {
    case robotoMono, jetBrainsMono, ibmPlexMono
}

/// Opaque sRGB roles shared by the native renderer and the portable contrast checks.
public struct AppearancePalette: Equatable, Sendable {
    public let ground, surface, raised, rule, control: UInt32
    public let ink, muted, faint, data, positive, warning, negative, negativeText, dataText, quietText: UInt32

    init(ground: UInt32, surface: UInt32, raised: UInt32, rule: UInt32, control: UInt32,
         ink: UInt32, muted: UInt32, faint: UInt32, data: UInt32 = 0xD97757,
         positive: UInt32 = 0x6FBF73, warning: UInt32 = 0xE0A33E, negative: UInt32 = 0xD9584F,
         negativeText: UInt32 = 0xE8756B, dataText: UInt32 = 0xD97757) {
        self.ground = ground; self.surface = surface; self.raised = raised
        self.rule = rule; self.control = control; self.ink = ink; self.muted = muted
        self.faint = faint; self.data = data; self.positive = positive; self.warning = warning
        self.negative = negative; self.negativeText = negativeText; self.dataText = dataText
        // Faint ist auf erhöhten Flächen nicht immer lesbar; ein Text-Rollenwert gilt für alle drei.
        quietText = Self.contrast(faint, ground) >= 4.5 && Self.contrast(faint, surface) >= 4.5
            && Self.contrast(faint, raised) >= 4.5 ? faint : muted
    }

    public func accent(_ severity: Severity) -> UInt32 {
        switch severity {
        case .healthy: return positive
        case .medium, .low, .limited: return warning
        case .exhausted, .error: return negative
        case .stale, .unknown: return faint
        }
    }

    public func figure(_ severity: Severity) -> UInt32 {
        switch severity {
        case .healthy, .medium: return ink
        case .low, .limited: return warning
        case .exhausted: return negativeText
        case .stale, .unknown, .error: return quietText
        }
    }

    public static func contrast(_ foreground: UInt32, _ background: UInt32) -> Double {
        func luminance(_ rgb: UInt32) -> Double {
            func channel(_ shift: UInt32) -> Double {
                let value = Double((rgb >> shift) & 255) / 255
                return value <= 0.04045 ? value / 12.92 : pow((value + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }
        let a = luminance(foreground), b = luminance(background)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }
}
