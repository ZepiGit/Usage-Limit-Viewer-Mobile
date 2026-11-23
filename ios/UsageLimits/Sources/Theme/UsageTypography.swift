import SwiftUI
import UsageLimitsKit
#if !os(Linux)
import CoreText
import UIKit
#endif

enum UsageTypography {
    static func font(_ role: UsageFont, size: CGFloat, appearance: UsageAppearance) -> Font {
        guard appearance.isApp else {
            return .system(size: size, weight: role.spec.weight, design: role.spec.design).monospacedDigit()
        }
        #if os(Linux)
        if let name = face(role, appearance: appearance) {
            return .custom(name, fixedSize: size).weight(role.spec.weight).monospacedDigit()
        }
        return .system(size: size, weight: role.spec.weight, design: role.spec.design).monospacedDigit()
        #else
        return Font(nativeFont(role, size: size, appearance: appearance)).monospacedDigit()
        #endif
    }

    static func face(_ role: UsageFont, appearance: UsageAppearance) -> String? {
        let strong = role.weightValue >= 500
        if role.isFigure {
            switch appearance.figureFont {
            case .robotoMono: return "RobotoMono-Regular"
            case .jetBrainsMono: return "JetBrainsMono-Regular"
            case .ibmPlexMono:
                return role.weightValue == 700 ? "IBMPlexMono-Bold" : strong ? "IBMPlexMono-Medm" : "IBMPlexMono"
            case nil: return nil
            }
        }
        switch appearance.textFont {
        case .system: return nil
        case .robotoFlex: return "RobotoFlex-Regular"
        case .ibmPlexSans: return strong ? "IBMPlexSans-Medm" : "IBMPlexSans"
        case .figtree: return "Figtree-Light"
        }
    }

    #if !os(Linux)
    static func nativeFont(_ role: UsageFont, size: CGFloat, appearance: UsageAppearance) -> CTFont {
        let weight: UIFont.Weight = role.weightValue == 700 ? .bold : role.weightValue >= 500 ? .medium : .regular
        let fallback = role.isFigure
            ? UIFont.monospacedSystemFont(ofSize: size, weight: weight)
            : UIFont.systemFont(ofSize: size, weight: weight)
        let base = face(role, appearance: appearance).flatMap { UIFont(name: $0, size: size) } ?? fallback
        let ctFont = base as CTFont
        let weightAxis = NSNumber(value: 0x77676874)
        guard let axes = CTFontCopyVariationAxes(ctFont) as? [[String: Any]],
              axes.contains(where: { ($0[kCTFontVariationAxisIdentifierKey as String] as? NSNumber) == weightAxis }) else {
            return ctFont
        }
        // Font.Weight rundet Zwischengewichte; der echte wght-Wert muss im Descriptor stehen.
        let descriptor = CTFontDescriptorCreateCopyWithVariation(
            CTFontCopyFontDescriptor(ctFont), weightAxis as CFNumber, CGFloat(role.weightValue))
        return CTFontCreateWithFontDescriptor(descriptor, size, nil)
    }
    #endif
}

extension UsageFont {
    var isFigure: Bool {
        switch self {
        case .lead, .widgetLead, .money, .widgetMoney, .rowMoney, .figure, .figureLarge,
             .ringFigure, .compactFigure, .dialFigure, .previewFigure, .displayPreviewFigure:
            return true
        default: return false
        }
    }

    var weightValue: Int {
        switch self {
        case .money, .widgetMoney, .rowMoney: return 700
        case .body, .small, .previewWindow: return 450
        default: return isFigure ? 500 : 560
        }
    }
}
