// The Pinned accounts widget's three Lock Screen families, drawn from one captured entry.
//
// Kept apart from the timeline provider and the widget declaration so the rendering tests can
// compile this file alone and draw every family at its accessory size. It depends only on SwiftUI,
// WidgetKit's accessory background, the Kit and Theme's catalog-backed texts.
//
// No `import Foundation`: SwiftUI re-exports it, and on Linux the framework shims' geometry types
// would otherwise be ambiguous with Foundation's.

import SwiftUI
import UIKit
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

    /// Nil if the file is missing from this bundle or does not parse; every account then draws the
    /// plain disc. CI covers the two bundles separately: the iOS workflow compares the file inside
    /// the built widget extension with `shared/glyphs`, and the rendering test fails if its own
    /// bundle's copy is absent or malformed.
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

    nonisolated func path(in rect: CGRect) -> Path {
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
    nonisolated func path(in rect: CGRect) -> Path {
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
///
/// Sized from the space the cell is actually given. `preferredGlyph` grows with Dynamic Type, but
/// a cell cannot grow past its family, so the glyph, the sign and the digits (whose size follows
/// the glyph's) are capped to what fits: at the largest type sizes three cells keep every mark
/// whole and smaller instead of cutting one off.
struct PinnedAccountMark: View {
    let entry: PinnedAccountEntry
    let preferredGlyph: CGFloat

    var body: some View {
        GeometryReader { proxy in
            let glyph = Self.glyphSide(preferred: preferredGlyph, width: proxy.size.width, height: proxy.size.height)
            content(glyph: glyph)
                .frame(width: proxy.size.width, height: proxy.size.height)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(PinnedAccountText.spoken(entry: entry, texts: LocalizedPinnedAccountTexts()))
    }

    /// The glyph side that keeps the widest arrangement inside the cell: glyph, gap and two digits
    /// (about 2.4 glyph widths) or the glyph beside the sign (2.15), and the lone glyph at 100 with
    /// its underline (1.35 wide, 1.6 high).
    static func glyphSide(preferred: CGFloat, width: CGFloat, height: CGFloat) -> CGFloat {
        max(1, min(preferred, width / 2.4, height / 1.6))
    }

    @ViewBuilder
    private func content(glyph side: CGFloat) -> some View {
        let glyph = StatusIconGlyphShape(elements: PinnedAccountGlyphs.glyph(for: entry.account.providerID))
        switch entry.content.content {
        case .glyphOnly:
            VStack(spacing: side * 0.12) {
                glyph.frame(width: side * 1.35, height: side * 1.35)
                Rectangle()
                    .frame(width: side * 1.35, height: max(1, side * 0.09))
                    .opacity(entry.content.outdated ? 1 : 0)
            }
        case .exhausted:
            HStack(spacing: side * 0.15) {
                glyph.frame(width: side, height: side)
                ExhaustedSignShape()
                    .stroke(.primary, style: StrokeStyle(lineWidth: max(1.5, side * 0.13), lineCap: .round))
                    .frame(width: side, height: side)
            }
        case .figure, .error, .unknown:
            HStack(spacing: side * 0.15) {
                glyph.frame(width: side, height: side)
                Text(verbatim: PinnedAccountText.cellText(entry) ?? "")
                    .underline(entry.content.outdated)
                    .font(.system(size: side * 1.1, weight: .bold, design: .rounded))
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
            }
        }
    }
}

/// Up to three accounts side by side, in the app's account order (`PinnedAccounts.select`).
struct PinnedAccountsRectangularView: View {
    let capture: PinnedAccountsCapture

    @ScaledMetric(relativeTo: .title3) private var glyphSize: CGFloat = 18

    var body: some View {
        if capture.outcome == .sourceUnavailable {
            PinnedAccountsNotice(symbol: "lock.fill", title: String(localized: "Locked"),
                detail: String(localized: "Unlock to see usage"))
        } else if capture.outcome == .corruptSource {
            PinnedAccountsNotice(symbol: "exclamationmark.triangle", title: String(localized: "Can't read usage"),
                detail: String(localized: "Open UsageLimits to rebuild the data"))
        } else if capture.accounts.isEmpty {
            PinnedAccountsNotice(symbol: "pin", title: nil, detail: String(localized: "Pin an account in Settings"))
        } else {
            HStack(spacing: 4) {
                ForEach(capture.accounts) { account in
                    PinnedAccountMark(entry: account, preferredGlyph: glyphSize)
                        .frame(maxWidth: .infinity)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

/// The locked, unreadable and empty states of the rectangular family: two short lines at most,
/// scaled down rather than clipped. At accessibility type sizes the symbol and the detail line go
/// and the title alone stays, wrapped, with its type capped at AX2, because one word at that size
/// is all the family holds (at AX5 "Gesperrt" with its symbol was cut to "Gesp…"); VoiceOver
/// still reads title and detail.
private struct PinnedAccountsNotice: View {
    @Environment(\.dynamicTypeSize) private var typeSize

    let symbol: String
    let title: String?
    let detail: String

    var body: some View {
        HStack(alignment: .top, spacing: 6) {
            if !largeType || title == nil {
                Image(systemName: symbol)
                    .font(.headline)
                    .accessibilityHidden(true)
            }
            VStack(alignment: .leading, spacing: 1) {
                if let title {
                    Text(verbatim: title)
                        .font(.headline)
                        .lineLimit(largeType ? 2 : 1)
                        .minimumScaleFactor(0.5)
                }
                if title == nil || !largeType {
                    Text(verbatim: detail)
                        .font(title == nil ? .caption : .caption2)
                        .lineLimit(title == nil ? 3 : 2)
                        .minimumScaleFactor(0.6)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .dynamicTypeSize(...DynamicTypeSize.accessibility2)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title.map { "\($0), \(detail)" } ?? detail)
    }

    private var largeType: Bool { typeSize >= .accessibility1 }
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
                PinnedAccountMark(entry: first, preferredGlyph: glyphSize)
                    .padding(6)
            } else {
                emptyHint
            }
        }
    }

    /// Feature off or nothing pinned: the pin with the short hint the inline form shows, so the
    /// circle explains itself too. The hint's text has a fixed 10 pt size and does not follow
    /// Dynamic Type: as `caption2` at AX2 the 72 pt circle cut it to "Settin…" / "In Einste…".
    /// Two lines at most, scaled down rather than clipped; only the pin grows, up to AX2.
    /// VoiceOver reads the full sentence.
    private var emptyHint: some View {
        VStack(spacing: 1) {
            Image(systemName: "pin")
                .font(.footnote)
            Text(verbatim: String(localized: "Pin in Settings"))
                .font(.system(size: 10, weight: .semibold))
                .multilineTextAlignment(.center)
                .lineLimit(2)
                .minimumScaleFactor(0.6)
        }
        .padding(.horizontal, 8)
        .dynamicTypeSize(...DynamicTypeSize.accessibility2)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(String(localized: "Pin an account in Settings"))
    }

    private func symbol(_ name: String, label: String) -> some View {
        Image(systemName: name)
            .font(.title3)
            .accessibilityLabel(label)
    }
}

/// The first pinned account in one row (ADR 0003, docs/adr/0003-status-icons-and-lock-screen-substitute.md, section "iOS"): its glyph as the
/// family's one image, its figure with the side as the text. The system shows a single line of
/// text and at most one image here, in its own font and tint (WidgetKit,
/// `WidgetFamily.accessoryInline`; WWDC22 "Complications and widgets: Reloaded"). What the text
/// says is `PinnedAccountText.inlineRow`. Whether a real Lock Screen keeps the image is a release
/// gate in `docs/release.md`.
struct PinnedAccountsInlineView: View {
    let capture: PinnedAccountsCapture

    var body: some View {
        if capture.outcome == .sourceUnavailable {
            Label(String(localized: "Locked"), systemImage: "lock.fill")
        } else if capture.outcome == .corruptSource {
            Label(String(localized: "Can't read usage"), systemImage: "exclamationmark.triangle")
        } else if let row = PinnedAccountText.inlineRow(capture.accounts, texts: LocalizedPinnedAccountTexts()),
                  let first = capture.accounts.first {
            Label {
                Text(verbatim: row.text)
            } icon: {
                Image(uiImage: InlineGlyphImage.image(for: row.glyphProviderID))
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(PinnedAccountText.spoken(entry: first, texts: LocalizedPinnedAccountTexts()))
        } else {
            Label(String(localized: "Pin in Settings"), systemImage: "pin")
        }
    }
}

/// The inline glyph, supplied in this one function so a bundled template image could replace the
/// runtime-drawn one without touching the view. Today it is a small template bitmap drawn from the
/// shared path data: an image, because the inline family keeps images and text and drops other
/// views; a template, so the system tints it like the text; small, because the slot does not
/// shrink a large image.
enum InlineGlyphImage {
    static let side: CGFloat = 14

    static func image(for providerID: String?) -> UIImage {
        let path = StatusIconGlyphShape(elements: PinnedAccountGlyphs.glyph(for: providerID))
            .path(in: CGRect(x: 0, y: 0, width: side, height: side))
        let drawn = UIGraphicsImageRenderer(size: CGSize(width: side, height: side)).image { _ in
            UIColor.black.setFill()
            UIBezierPath(cgPath: path.cgPath).fill()
        }
        return drawn.withRenderingMode(.alwaysTemplate)
    }
}
