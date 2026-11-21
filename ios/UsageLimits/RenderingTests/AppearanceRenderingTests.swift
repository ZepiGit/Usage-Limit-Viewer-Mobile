import CoreText
import SwiftUI
import UIKit
import XCTest
import UsageLimitsKit

@MainActor
final class AppearanceRenderingTests: XCTestCase {
    func testNativeRGBAColorsMatchPortableContrastRoles() throws {
        for theme in AppearanceTheme.allCases {
            let palette = UsagePalette(theme: theme)
            let backgrounds = [palette.ground, palette.surface, palette.raised]
            for background in backgrounds {
                let ground = try rgb(background)
                for color in [palette.ink, palette.muted, palette.faint, palette.warning, palette.negativeText, palette.dataText] {
                    XCTAssertGreaterThanOrEqual(AppearancePalette.contrast(try rgb(color), ground), 4.5)
                }
                for severity in [Severity.healthy, .medium, .low, .limited, .exhausted, .error, .stale, .unknown] {
                    XCTAssertGreaterThanOrEqual(AppearancePalette.contrast(try rgb(palette.accent(severity)), ground), 3)
                    XCTAssertGreaterThanOrEqual(AppearancePalette.contrast(try rgb(palette.figureColor(severity)), ground), 4.5)
                }
            }
            XCTAssertEqual(try rgb(palette.ground), theme.palette.ground)
            XCTAssertEqual(try rgb(palette.data), theme.palette.data)
        }
    }

    func testRegisteredVariableFacesUseActualWeightsAndMoneyUsesRealBold() throws {
        for text in [AppearanceTextFont.robotoFlex, .figtree] {
            let appearance = UsageAppearance(settings: AppSettings(appearanceTextFont: text))
            for (role, weight) in [(UsageFont.body, 450), (.bodyStrong, 560)] {
                let font = UsageTypography.nativeFont(role, size: 14, appearance: appearance)
                let values = try XCTUnwrap(CTFontCopyVariation(font) as? [NSNumber: NSNumber])
                XCTAssertEqual(try XCTUnwrap(values[NSNumber(value: 0x77676874)]).intValue, weight)
            }
        }
        for figure in AppearanceFigureFont.allCases {
            let appearance = UsageAppearance(settings: AppSettings(appearanceFigureFont: figure))
            let font = UsageTypography.nativeFont(.money, size: 20, appearance: appearance)
            let name = CTFontCopyPostScriptName(font) as String
            if figure == .ibmPlexMono {
                XCTAssertEqual(name, "IBMPlexMono-Bold")
            } else {
                let values = try XCTUnwrap(CTFontCopyVariation(font) as? [NSNumber: NSNumber])
                XCTAssertEqual(try XCTUnwrap(values[NSNumber(value: 0x77676874)]).intValue, 700)
            }
            let advances = ["0", "1", "8"].map { digit in
                NSAttributedString(string: digit, attributes: [.font: font]).size().width
            }
            XCTAssertEqual(advances[0], advances[1], accuracy: 0.01)
            XCTAssertEqual(advances[0], advances[2], accuracy: 0.01)
        }
        let system = UsageAppearance(settings: AppSettings())
        XCTAssertNil(UsageTypography.face(.body, appearance: system))
        XCTAssertEqual(UsageTypography.face(.body, appearance: UsageAppearance(settings:
            AppSettings(appearanceTextFont: .ibmPlexSans))), "IBMPlexSans")
        XCTAssertEqual(UsageTypography.face(.bodyStrong, appearance: UsageAppearance(settings:
            AppSettings(appearanceTextFont: .ibmPlexSans))), "IBMPlexSans-Medm")
    }

    func testWidgetContextKeepsFixedPixelsAcrossAllAppAppearanceChoices() throws {
        let baseline = try render(UsageCard { Text(verbatim: "61% · $12.40").usageFont(.money) }
            .environment(\.usageAppearance, .widget))
        for theme in AppearanceTheme.allCases {
            for text in AppearanceTextFont.allCases {
                for figure in AppearanceFigureFont.allCases {
                    let settings = AppSettings(appearanceTheme: theme, appearanceTextFont: text, appearanceFigureFont: figure)
                    let image = try render(UsageCard { Text(verbatim: "61% · $12.40").usageFont(.money) }
                        .environment(\.usageAppearance, .widget).appAppearance(settings)
                        .environment(\.colorScheme, .dark))
                    XCTAssertEqual(image, baseline)
                }
            }
        }
    }

    func testAppFontRenderingScalesAtAccessibilitySizeAndFacesDiffer() throws {
        var samples: [Data] = []
        for figure in AppearanceFigureFont.allCases {
            let appearance = UsageAppearance(settings: AppSettings(appearanceFigureFont: figure))
            samples.append(try render(Text(verbatim: "61% 18% 00").usageFont(.figureLarge)
                .environment(\.usageAppearance, appearance)))
        }
        XCTAssertEqual(Set(samples).count, 3)
        let appearance = UsageAppearance(settings: AppSettings(appearanceTextFont: .robotoFlex))
        let standard = try render(Text(verbatim: "Readable figures 61%").usageFont(.body)
            .environment(\.usageAppearance, appearance).environment(\.dynamicTypeSize, .large))
        let large = try render(Text(verbatim: "Readable figures 61%").usageFont(.body)
            .environment(\.usageAppearance, appearance).environment(\.dynamicTypeSize, .accessibility5))
        XCTAssertNotEqual(standard, large)
    }

    private func rgb(_ color: Color) throws -> UInt32 {
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        XCTAssertTrue(UIColor(color).getRed(&red, green: &green, blue: &blue, alpha: &alpha))
        XCTAssertEqual(alpha, 1, accuracy: 0.0001)
        return UInt32((red * 255).rounded()) << 16 | UInt32((green * 255).rounded()) << 8 | UInt32((blue * 255).rounded())
    }

    private func render(_ view: some View) throws -> Data {
        let renderer = ImageRenderer(content: view.frame(width: 360, height: 160)
            .foregroundStyle(Color.white).background(Color.black))
        renderer.scale = 2
        return try XCTUnwrap(renderer.uiImage?.pngData())
    }
}
