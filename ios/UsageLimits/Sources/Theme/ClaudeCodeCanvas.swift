import SwiftUI
import UsageLimitsKit

/// Both widget stills and live frames use this transparent, overflow-capable drawing.
struct ClaudeCodeCanvas: View {
    let mood: ClaudeCodeMood
    let size: CGFloat
    let ink: Color
    let elapsed: TimeInterval
    let amplitude: Double
    let still: Bool
    var rendersAsynchronously = false

    var body: some View {
        Canvas(rendersAsynchronously: rendersAsynchronously) { context, _ in
            context.translateBy(x: size, y: size)
            context.scaleBy(x: size / ClaudeCodeMotion.grid, y: size / ClaudeCodeMotion.grid)
            drawFigure(in: context)
            for index in 0..<ClaudeCodeMotion.particleCount(mood: mood) {
                drawParticle(ClaudeCodeMotion.particle(mood: mood, index: index, elapsed: elapsed, still: still),
                             in: context)
            }
        }
        // Particles need a larger canvas, not a larger layout slot.
        .frame(width: size * 3, height: size * 3)
        .frame(width: size, height: size)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    private func drawFigure(in context: GraphicsContext) {
        let pose = ClaudeCodeMotion.pose(mood: mood, elapsed: elapsed, amplitude: amplitude, still: still)
        var figure = context
        figure.translateBy(x: ClaudeCodeMotion.pivotX, y: ClaudeCodeMotion.pivotY)
        figure.rotate(by: .degrees(pose.rotationDegrees))
        figure.translateBy(x: 0, y: pose.offsetY)
        figure.scaleBy(x: 1, y: pose.scaleY)
        figure.translateBy(x: -ClaudeCodeMotion.pivotX, y: -ClaudeCodeMotion.pivotY)

        var body = Path()
        body.addRect(rect(ClaudeCodeMotion.body))
        for index in 0..<2 { body.addRect(rect(pose.eye(index))) }
        // Cut out the eyes rather than painting a background a widget may not have.
        figure.fill(body, with: .color(ink), style: FillStyle(eoFill: true))
        for index in 0..<2 {
            let arm = ClaudeCodeMotion.armRect(index)
            fill(rect(arm, offsetY: index == 0 ? pose.leftArmY : pose.rightArmY), in: figure)
        }
        for index in 0..<4 {
            let leg = ClaudeCodeMotion.legRect(index)
            fill(rect(leg, offsetY: index.isMultiple(of: 2) ? pose.evenLegY : pose.oddLegY), in: figure)
        }
    }

    private func rect(_ source: ClaudeCodeMotion.Rect, offsetY: Double = 0) -> CGRect {
        CGRect(x: source.x, y: source.y + offsetY, width: source.width, height: source.height)
    }

    private func fill(_ rectangle: CGRect, in context: GraphicsContext) {
        var path = Path()
        path.addRect(rectangle)
        context.fill(path, with: .color(ink))
    }

    private func drawParticle(_ particle: ClaudeCodeMotion.Particle, in context: GraphicsContext) {
        guard particle.opacity > 0 else { return }
        var effect = context
        effect.opacity = particle.opacity
        switch particle.kind {
        case .sleep, .note:
            let text = Text(verbatim: particle.kind == .sleep ? "z" : "♪")
                .font(.system(size: particle.height, weight: particle.kind == .sleep ? .bold : .regular,
                              design: particle.kind == .sleep ? .monospaced : .default))
                .foregroundColor(particle.kind == .sleep ? Self.sleepInk : ink)
            let resolved = effect.resolve(text)
            let measured = resolved.measure(in: CGSize(width: CGFloat.infinity, height: CGFloat.infinity))
            position(particle, width: measured.width, height: measured.height, in: &effect)
            effect.draw(resolved, at: CGPoint(x: 0, y: 0), anchor: .topLeading)
        case .sweat:
            position(particle, width: particle.width, height: particle.height, in: &effect)
            var drop = Path()
            let w = particle.width
            let h = particle.height
            let curve = 0.5522847498307936
            let shoulder = ClaudeCodeMotion.sweatShoulder
            drop.move(to: CGPoint(x: w / 2, y: 0))
            drop.addCurve(to: CGPoint(x: w, y: h * shoulder),
                          control1: CGPoint(x: w * (1 + curve) / 2, y: 0),
                          control2: CGPoint(x: w, y: h * shoulder * (1 - curve)))
            drop.addCurve(to: CGPoint(x: w / 2, y: h),
                          control1: CGPoint(x: w, y: h * (shoulder + (1 - shoulder) * curve)),
                          control2: CGPoint(x: w * (1 + curve) / 2, y: h))
            drop.addCurve(to: CGPoint(x: 0, y: h * shoulder),
                          control1: CGPoint(x: w * (1 - curve) / 2, y: h),
                          control2: CGPoint(x: 0, y: h * (shoulder + (1 - shoulder) * curve)))
            drop.addCurve(to: CGPoint(x: w / 2, y: 0),
                          control1: CGPoint(x: 0, y: h * shoulder * (1 - curve)),
                          control2: CGPoint(x: w * (1 - curve) / 2, y: 0))
            drop.closeSubpath()
            effect.fill(drop, with: .color(Self.sweatInk))
        case .sparkle:
            position(particle, width: particle.width, height: particle.height, in: &effect)
            var square = Path()
            square.addRect(CGRect(x: 0, y: 0, width: particle.width, height: particle.height))
            effect.fill(square, with: .color(Self.sparkleInk))
        }
    }

    private func position(_ particle: ClaudeCodeMotion.Particle, width: Double, height: Double,
                          in context: inout GraphicsContext) {
        context.translateBy(x: particle.x + width * particle.translationX + width / 2,
                            y: particle.y + height * particle.translationY + height / 2)
        context.scaleBy(x: particle.scale, y: particle.scale)
        context.translateBy(x: -width / 2, y: -height / 2)
    }

    private static let sleepInk = Color(red: 180 / 255, green: 178 / 255, blue: 169 / 255)
    private static let sweatInk = Color(red: 156 / 255, green: 195 / 255, blue: 230 / 255)
    private static let sparkleInk = Color(red: 230 / 255, green: 194 / 255, blue: 74 / 255)
}
