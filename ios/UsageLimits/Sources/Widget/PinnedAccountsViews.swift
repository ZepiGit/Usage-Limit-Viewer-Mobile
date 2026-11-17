// The Pinned accounts widget's three Lock Screen families, drawn from one captured entry.
//
// Kept apart from the timeline provider and the widget declaration so the rendering tests can
// compile this file alone and draw every family at its accessory size. It depends only on SwiftUI,
// WidgetKit's accessory background, the Kit and Theme's catalog-backed texts.
//
// No `import Foundation`: SwiftUI re-exports it, and on Linux the framework shims' geometry types
// would otherwise be ambiguous with Foundation's.

import SwiftUI
import WidgetKit
import UsageLimitsKit

/// One family's content. The family is passed in rather than read from the environment, because
/// `widgetFamily` can only be read, and the rendering tests have to choose it.
struct PinnedAccountsFamilyView: View {
    let family: WidgetFamily
    let capture: PinnedAccountsCapture

    var body: some View {
        switch family {
        case .accessoryCircular:
            PinnedAccountsCircularView(capture: capture)
        case .accessoryInline:
            PinnedAccountsInlineView(capture: capture)
        default:
            PinnedAccountsRectangularView(capture: capture)
        }
    }
}

/// The silhouettes Android's status bar draws, from the same file (`shared/glyphs`), packaged into
/// whichever bundle compiled this code: the widget extension, or the rendering test bundle.
enum PinnedAccountGlyphs {
    private final class BundleToken {}

    /// Nil only if the file was not packaged; the rendering test fails on that, so a build that
    /// ships without it never passes CI. The widget then draws the plain disc for every account.
    static let shipped: StatusIconGlyphs? = {
        guard let url = Bundle(for: BundleToken.self).url(forResource: "status-icon-glyphs", withExtension: "json"),
              let data = try? Data(contentsOf: url) else { return nil }
        return try? StatusIconGlyphs.parse(data)
    }()

    static func glyph(for providerID: String?) -> [StatusIconGlyphs.Element] {
        shipped?.glyph(for: providerID) ?? discFallback
    }

    /// The same disc as the file's fallback, for the case the file itself is missing.
    private static let discFallback: [StatusIconGlyphs.Element] = [
        .move(.init(x: 20.5, y: 12)),
        .curve(.init(x: 20.5, y: 16.69), .init(x: 16.69, y: 20.5), .init(x: 12, y: 20.5)),
        .curve(.init(x: 7.31, y: 20.5), .init(x: 3.5, y: 16.69), .init(x: 3.5, y: 12)),
        .curve(.init(x: 3.5, y: 7.31), .init(x: 7.31, y: 3.5), .init(x: 12, y: 3.5)),
        .curve(.init(x: 16.69, y: 3.5), .init(x: 20.5, y: 7.31), .init(x: 20.5, y: 12)),
        .close,
    ]
}

/// A provider silhouette on its 24-unit square, fitted and centred in the frame it is given.
/// Filled in one colour (the non-zero rule, as on Android), so the system's vibrant rendering
/// tints it like any template image.
struct StatusIconGlyphShape: Shape {
    let elements: [StatusIconGlyphs.Element]

    func path(in rect: CGRect) -> Path {
        let scale = min(rect.width, rect.height) / StatusIconGlyphs.grid
        let originX = rect.midX - StatusIconGlyphs.grid / 2 * scale
        let originY = rect.midY - StatusIconGlyphs.grid / 2 * scale
        func point(_ p: StatusIconGlyphs.Point) -> CGPoint {
            CGPoint(x: originX + p.x * scale, y: originY + p.y * scale)
        }
        var path = Path()
        for element in elements {
            switch element {
            case .move(let p): path.move(to: point(p))
            case .line(let p): path.addLine(to: point(p))
            case .curve(let a, let b, let c): path.addCurve(to: point(c), control1: point(a), control2: point(b))
            case .close: path.closeSubpath()
            }
        }
        return path
    }
}

/// The exhausted sign as Android draws it: a circle crossed from lower left to upper right.
struct ExhaustedSignShape: Shape {
    func path(in rect: CGRect) -> Path {
        let side = min(rect.width, rect.height)
        let radius = side * 0.43
        let reach = side * 0.305
        var path = Path()
        path.addEllipse(in: CGRect(x: rect.midX - radius, y: rect.midY - radius, width: radius * 2, height: radius * 2))
        path.move(to: CGPoint(x: rect.midX - reach, y: rect.midY + reach))
        path.addLine(to: CGPoint(x: rect.midX + reach, y: rect.midY - reach))
        return path
    }
}

/// Glyph and content for one pinned account: the figure without a leading zero, the glyph alone
/// and larger at 100, an underline when the reading is outdated, the crossed circle when
/// exhausted, "!" on error and a dash when the figure is unknown.
struct PinnedAccountMark: View {
    let entry: PinnedAccountEntry
    /// The glyph's side beside a content mark; alone it is drawn a third larger.
    let glyphSize: CGFloat
    let font: Font

    var body: some View {
        content
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(PinnedAccountText.spoken(entry: entry, texts: LocalizedPinnedAccountTexts()))
    }

    @ViewBuilder
    private var content: some View {
        let glyph = StatusIconGlyphShape(elements: PinnedAccountGlyphs.glyph(for: entry.account.providerID))
        switch entry.content.content {
        case .none:
            VStack(spacing: glyphSize * 0.12) {
                glyph.frame(width: glyphSize * 1.35, height: glyphSize * 1.35)
                Rectangle()
                    .frame(width: glyphSize * 1.35, height: max(1, glyphSize * 0.09))
                    .opacity(entry.content.outdated ? 1 : 0)
            }
        case .exhausted:
            HStack(spacing: glyphSize * 0.15) {
                glyph.frame(width: glyphSize, height: glyphSize)
                ExhaustedSignShape()
                    .stroke(.primary, style: StrokeStyle(lineWidth: max(1.5, glyphSize * 0.13), lineCap: .round))
                    .frame(width: glyphSize, height: glyphSize)
            }
        case .figure, .error, .unknown:
            HStack(spacing: glyphSize * 0.15) {
                glyph.frame(width: glyphSize, height: glyphSize)
                Text(verbatim: PinnedAccountText.cellText(entry) ?? "")
                    .underline(entry.content.outdated)
                    .font(font)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }
        }
    }
}

/// Up to three accounts side by side, in pinned order.
struct PinnedAccountsRectangularView: View {
    let capture: PinnedAccountsCapture

    @ScaledMetric(relativeTo: .title3) private var glyphSize: CGFloat = 18

    var body: some View {
        if capture.outcome == .sourceUnavailable {
            PinnedAccountsNotice(symbol: "lock.fill", title: String(localized: "Locked"),
                detail: String(localized: "Unlock to see your quota"))
        } else if capture.outcome == .corruptSource {
            PinnedAccountsNotice(symbol: "exclamationmark.triangle", title: String(localized: "Can't read usage"),
                detail: String(localized: "Open UsageLimits to rebuild the data"))
        } else if capture.accounts.isEmpty {
            PinnedAccountsNotice(symbol: "pin", title: nil, detail: String(localized: "Pin an account in Settings"))
        } else {
            HStack(spacing: 4) {
                ForEach(capture.accounts) { account in
                    PinnedAccountMark(entry: account, glyphSize: glyphSize,
                        font: .system(.title3, design: .rounded).weight(.bold))
                        .frame(maxWidth: .infinity)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

/// The locked, unreadable and empty states of the rectangular family: two short lines at most,
/// scaled down rather than clipped at large type sizes.
private struct PinnedAccountsNotice: View {
    let symbol: String
    let title: String?
    let detail: String

    var body: some View {
        HStack(alignment: .top, spacing: 6) {
            Image(systemName: symbol)
                .font(.headline)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 1) {
                if let title {
                    Text(verbatim: title)
                        .font(.headline)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                }
                Text(verbatim: detail)
                    .font(title == nil ? .caption : .caption2)
                    .lineLimit(title == nil ? 3 : 2)
                    .minimumScaleFactor(0.75)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

/// The first pinned account: its glyph and content, as one cell of the rectangular family.
struct PinnedAccountsCircularView: View {
    let capture: PinnedAccountsCapture

    @ScaledMetric(relativeTo: .title3) private var glyphSize: CGFloat = 20

    var body: some View {
        ZStack {
            AccessoryWidgetBackground()
            if capture.outcome == .sourceUnavailable {
                symbol("lock.fill", label: String(localized: "Locked"))
            } else if capture.outcome == .corruptSource {
                symbol("exclamationmark.triangle", label: String(localized: "Can't read usage"))
            } else if let first = capture.accounts.first {
                PinnedAccountMark(entry: first, glyphSize: glyphSize,
                    font: .system(.title3, design: .rounded).weight(.bold))
                    .padding(6)
            } else {
                symbol("pin", label: String(localized: "Pin an account in Settings"))
            }
        }
    }

    private func symbol(_ name: String, label: String) -> some View {
        Image(systemName: name)
            .font(.title3)
            .accessibilityLabel(label)
    }
}

/// One text row: the system draws this family as a single line beside the date, so there are no
/// glyphs here by design. What the row says, and how it shortens, is `PinnedAccountText.inline`.
struct PinnedAccountsInlineView: View {
    let capture: PinnedAccountsCapture

    var body: some View {
        if capture.outcome == .sourceUnavailable {
            Label(String(localized: "Locked"), systemImage: "lock.fill")
        } else if capture.outcome == .corruptSource {
            Label(String(localized: "Can't read usage"), systemImage: "exclamationmark.triangle")
        } else if capture.accounts.isEmpty {
            Label(String(localized: "Pin in Settings"), systemImage: "pin")
        } else {
            row
                .lineLimit(1)
                .accessibilityLabel(PinnedAccountText.spokenList(capture.accounts, texts: LocalizedPinnedAccountTexts()))
        }
    }

    private var row: Text {
        let parts = PinnedAccountText.inlineParts(capture.accounts, texts: LocalizedPinnedAccountTexts())
        var text = Text(verbatim: "")
        for (index, part) in parts.enumerated() {
            if index > 0 { text = text + Text(verbatim: " · ") }
            text = text + Text(verbatim: "\(part.name) ") + Text(verbatim: part.value).underline(part.outdated)
        }
        return text
    }
}
