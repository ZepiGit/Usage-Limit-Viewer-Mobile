import Foundation

public struct FillStyle {
    public init(eoFill: Bool = false, antialiased: Bool = true) {}
}

extension Path {
    public mutating func addRect(_ rectangle: CGRect) {}
}

public struct UnitPoint {
    public static let center = UnitPoint()
    public static let topLeading = UnitPoint()
}

/// Only the Canvas drawing surface used by the mascot; these shims never render pixels.
public struct GraphicsContext {
    public var opacity: Double = 1
    public mutating func translateBy(x: CGFloat, y: CGFloat) {}
    public mutating func scaleBy(x: CGFloat, y: CGFloat) {}
    public mutating func rotate(by angle: Angle) {}

    public struct Shading {
        public static func color(_ color: Color) -> Shading { Shading() }
    }

    public func fill(_ path: Path, with shading: Shading, style: FillStyle = FillStyle()) {}

    public struct ResolvedText {
        public func measure(in size: CGSize) -> CGSize { .zero }
    }

    @MainActor
    public func resolve(_ text: Text) -> ResolvedText { ResolvedText() }
    public func draw(_ text: ResolvedText, at point: CGPoint, anchor: UnitPoint = .center) {}
}

public enum ColorRenderingMode {
    case nonLinear, linear, extendedLinear
}

public struct Canvas: View {
    nonisolated public init(opaque: Bool = false, colorMode: ColorRenderingMode = .nonLinear,
                            rendersAsynchronously: Bool = false,
                            renderer: @escaping (inout GraphicsContext, CGSize) -> Void) {}
    public var body: some View { ShimLeaf() }
}

public protocol TimelineSchedule {}

public struct AnimationTimelineSchedule: TimelineSchedule {
    public init(minimumInterval: Double? = nil, paused: Bool = false) {}
}

extension TimelineSchedule where Self == AnimationTimelineSchedule {
    public static func animation(minimumInterval: Double? = nil, paused: Bool = false) -> AnimationTimelineSchedule {
        AnimationTimelineSchedule(minimumInterval: minimumInterval, paused: paused)
    }
}

public struct TimelineView<Schedule: TimelineSchedule, Content: View>: View {
    public struct Context {
        public var date: Date { Date() }
    }

    public init(_ schedule: Schedule, @ViewBuilder content: @escaping (Context) -> Content) {}
    public var body: some View { ShimLeaf() }
}
