import Foundation

/// A monotonic, externally clocked phase. Inactive time never enters the animation.
public struct ClaudeCodeActiveClock: Equatable, Sendable {
    private var accumulated: TimeInterval = 0
    private var startedAt: TimeInterval?

    public init() {}

    public func elapsed(at now: TimeInterval) -> TimeInterval {
        accumulated + (startedAt.map { max(0, now - $0) } ?? 0)
    }

    public mutating func setActive(_ active: Bool, at now: TimeInterval) {
        if active {
            if startedAt == nil { startedAt = now }
        } else if startedAt != nil {
            accumulated = elapsed(at: now)
            startedAt = nil
        }
    }

    public var isActive: Bool { startedAt != nil }

    public mutating func restart(at now: TimeInterval) {
        accumulated = 0
        if startedAt != nil { startedAt = now }
    }
}

/// The CH-3 appendix in 24-unit coordinates, independent of SwiftUI and wall time.
public enum ClaudeCodeMotion {
    public struct Rect: Equatable, Sendable {
        public let x: Double
        public let y: Double
        public let width: Double
        public let height: Double
    }

    public static let grid = 24.0
    public static let pivotX = 12.0
    public static let pivotY = 20.4
    public static let body = Rect(x: 3, y: 5, width: 18, height: 12.079)
    public static let arm = Rect(x: 0, y: 10.95, width: 3, height: 3.1)
    public static let leg = Rect(x: 4.487, y: 17.079, width: 1.513, height: 2.921)
    public static let normalEye = Rect(x: 6, y: 8.102, width: 1.488, height: 2.847)
    public static let sweatShoulder = 0.62

    public static func armRect(_ index: Int) -> Rect {
        Rect(x: index == 0 ? arm.x : 21, y: arm.y, width: arm.width, height: arm.height)
    }

    public static func legRect(_ index: Int) -> Rect {
        let x: Double
        switch index {
        case 0: x = leg.x
        case 1: x = 7.488
        case 2: x = 15
        default: x = 18
        }
        return Rect(x: x, y: leg.y, width: leg.width, height: leg.height)
    }

    public struct Pose: Equatable, Sendable {
        public let mood: ClaudeCodeMood
        public var offsetY = 0.0
        public var scaleY = 1.0
        public var rotationDegrees = 0.0
        public var leftArmY = 0.0
        public var rightArmY = 0.0
        public var evenLegY = 0.0
        public var oddLegY = 0.0
        public var eyeScaleY = 1.0
        public var eyeOffsetX = 0.0

        public func eye(_ index: Int) -> Rect {
            let base: Rect
            switch mood {
            case .asleep:
                base = Rect(x: index == 0 ? 5.6 : 16.2, y: 9.9, width: 2.2, height: 0.6)
            case .tired:
                base = Rect(x: index == 0 ? 6 : 16.51, y: 9.6, width: 1.488, height: 1.35)
            default:
                base = Rect(x: index == 0 ? normalEye.x : 16.51,
                            y: normalEye.y + (mood == .working ? normalEye.height * 0.25 : 0),
                            width: normalEye.width, height: normalEye.height)
            }
            return Rect(x: base.x + eyeOffsetX, y: base.y + base.height * (1 - eyeScaleY) / 2,
                        width: base.width, height: base.height * eyeScaleY)
        }

        /// A point after the whole-figure transform; useful for checking its fixed pivot.
        public func transformed(x: Double, y: Double) -> (x: Double, y: Double) {
            let radians = rotationDegrees * .pi / 180
            let dx = x - pivotX
            let dy = (y - pivotY) * scaleY + offsetY
            return (pivotX + dx * cos(radians) - dy * sin(radians),
                    pivotY + dx * sin(radians) + dy * cos(radians))
        }
    }

    public static func pose(mood: ClaudeCodeMood, elapsed: TimeInterval,
                            amplitude: Double = 0.7, still: Bool = false) -> Pose {
        var pose = Pose(mood: mood)
        if still {
            if mood == .hooray {
                pose.leftArmY = -arm.height * 1.5
                pose.rightArmY = pose.leftArmY
            }
            return pose
        }
        switch mood {
        case .asleep, .tired, .idle:
            let duration = mood == .asleep ? 4.4 : mood == .tired ? 2.2 : 3.2
            pose.scaleY = 1 - 0.045 * amplitude * pulse(phase(elapsed, duration))
        case .working:
            pose.offsetY = -grid * 0.03 * amplitude * pulse(phase(elapsed, 0.44))
            pose.leftArmY = -arm.height * 0.35 * pulse(phase(elapsed, 0.24))
            pose.rightArmY = -arm.height * 0.35 * pulse(phase(elapsed + 0.12, 0.24))
        case .dancing:
            let hop = phase(elapsed, 0.5)
            if hop < 0.12 {
                pose.scaleY = segment(hop, 0, 0.12, 1, 1 - 0.09 * amplitude)
            } else if hop < 0.45 {
                pose.scaleY = segment(hop, 0.12, 0.45, 1 - 0.09 * amplitude, 1)
                pose.offsetY = segment(hop, 0.12, 0.45, 0, -grid * 0.13 * amplitude)
            } else if hop < 0.78 {
                pose.offsetY = segment(hop, 0.45, 0.78, -grid * 0.13 * amplitude, 0)
            }
            pose.rotationDegrees = -6 * amplitude + 12 * amplitude * pulse(phase(elapsed, 1))
            let left = pulse(hop)
            let right = pulse(phase(elapsed + 0.25, 0.5))
            pose.leftArmY = -arm.height * 1.5 * left
            pose.rightArmY = -arm.height * 1.5 * right
            pose.evenLegY = -leg.height * 0.4 * left
            pose.oddLegY = -leg.height * 0.4 * right
        case .hooray:
            pose.leftArmY = -arm.height * 1.5 * eased(min(max(elapsed, 0) / 0.3, 1), .easeOut)
            pose.rightArmY = pose.leftArmY
            let jump = phase(elapsed, 0.8)
            if jump < 0.35 {
                pose.offsetY = segment(jump, 0, 0.35, 0, -grid * 0.16 * amplitude, .easeOut)
            } else if jump < 0.65 {
                pose.offsetY = segment(jump, 0.35, 0.65, -grid * 0.16 * amplitude, 0, .easeOut)
            }
        }
        if mood == .idle || mood == .working || mood == .dancing || mood == .hooray {
            let duration = mood == .idle ? 4.6 : mood == .working ? 3.4 : 2.6
            let blink = phase(elapsed, duration)
            if blink >= 0.93 && blink < 0.96 {
                pose.eyeScaleY = segment(blink, 0.93, 0.96, 1, 0.1, .ease)
            } else if blink >= 0.96 {
                pose.eyeScaleY = segment(blink, 0.96, 1, 0.1, 1, .ease)
            }
        }
        if mood == .idle {
            let look = phase(elapsed, 7.5)
            let distance = normalEye.width * 0.45
            switch look {
            case 0.28..<0.36: pose.eyeOffsetX = segment(look, 0.28, 0.36, 0, -distance)
            case 0.36..<0.58: pose.eyeOffsetX = -distance
            case 0.58..<0.66: pose.eyeOffsetX = segment(look, 0.58, 0.66, -distance, distance)
            case 0.66..<0.88: pose.eyeOffsetX = distance
            case 0.88...1: pose.eyeOffsetX = segment(look, 0.88, 1, distance, 0)
            default: break
            }
        }
        return pose
    }

    public struct Particle: Equatable, Sendable {
        public enum Kind: Equatable, Sendable { case sleep, sweat, note, sparkle }
        public let kind: Kind
        public let x: Double
        public let y: Double
        public let width: Double
        public let height: Double
        public var opacity = 1.0
        public var scale = 1.0
        /// Translation relative to the particle's measured dimensions, as in CSS.
        public var translationX = 0.0
        public var translationY = 0.0
    }

    public static func particleCount(mood: ClaudeCodeMood) -> Int {
        switch mood {
        case .asleep, .hooray: return 3
        case .tired: return 1
        case .dancing: return 2
        case .idle, .working: return 0
        }
    }

    public static func particle(mood: ClaudeCodeMood, index: Int, elapsed: TimeInterval,
                                still: Bool) -> Particle {
        switch mood {
        case .asleep:
            var particle = Particle(kind: .sleep, x: grid * (0.64 + Double(index) * 0.09),
                                    y: grid * (0.06 - Double(index) * 0.06),
                                    width: grid * (0.16 + Double(index) * 0.05),
                                    height: grid * (0.16 + Double(index) * 0.05))
            if still { particle.opacity = index == 0 ? 1 : 0; return particle }
            let p = phase(elapsed + Double(index) * 0.9, 2.7)
            particle.opacity = p < 0.2 ? segment(p, 0, 0.2, 0, 1, .easeOut)
                : segment(p, 0.2, 1, 1, 0, .easeOut)
            let travel = eased(p, .easeOut)
            particle.scale = 0.6 + 0.4 * travel
            particle.translationX = 0.7 * travel
            particle.translationY = -1.7 * travel
            return particle
        case .tired:
            var particle = Particle(kind: .sweat, x: grid * 0.8, y: grid * 0.04,
                                    width: grid * 0.09, height: grid * 0.13)
            if still { return particle }
            let p = phase(elapsed, 1.8)
            particle.opacity = p < 0.15 ? segment(p, 0, 0.15, 0, 1, .easeIn)
                : p <= 0.75 ? 1 : segment(p, 0.75, 1, 1, 0, .easeIn)
            particle.translationY = 1.4 * eased(p, .easeIn)
            return particle
        case .dancing:
            var particle = Particle(kind: .note, x: grid * (index == 0 ? -0.08 : 0.92),
                                    y: grid * (index == 0 ? 0.02 : -0.04),
                                    width: grid * 0.24, height: grid * 0.24)
            if still { return particle }
            let p = phase(elapsed + Double(index) * 0.6, 1.2)
            particle.opacity = p < 0.3 ? segment(p, 0, 0.3, 0, 1, .easeOut)
                : segment(p, 0.3, 1, 1, 0, .easeOut)
            particle.translationY = 0.2 - 1.5 * eased(p, .easeOut)
            return particle
        case .hooray:
            var particle = Particle(kind: .sparkle, x: grid * (index == 0 ? 0.02 : index == 1 ? 0.86 : 0.46),
                                    y: grid * (index == 0 ? 0 : index == 1 ? 0.06 : -0.14),
                                    width: grid * 0.07, height: grid * 0.07)
            if still { return particle }
            let twinkle = pulse(phase(elapsed + Double(index) * 0.3, 0.9))
            particle.opacity = twinkle
            particle.scale = 0.3 + 0.7 * twinkle
            return particle
        case .idle, .working:
            preconditionFailure("This mood has no particles")
        }
    }

    private enum Easing { case easeInOut, easeOut, easeIn, ease }

    private static func phase(_ elapsed: Double, _ duration: Double) -> Double {
        max(elapsed, 0).truncatingRemainder(dividingBy: duration) / duration
    }

    private static func pulse(_ phase: Double) -> Double {
        phase < 0.5 ? segment(phase, 0, 0.5, 0, 1) : segment(phase, 0.5, 1, 1, 0)
    }

    private static func segment(_ phase: Double, _ start: Double, _ end: Double,
                                _ from: Double, _ to: Double, _ easing: Easing = .easeInOut) -> Double {
        from + (to - from) * eased((phase - start) / (end - start), easing)
    }

    private static func eased(_ progress: Double, _ easing: Easing) -> Double {
        let x = min(max(progress, 0), 1)
        if x == 0 || x == 1 { return x }
        let x1: Double, x2: Double, y1: Double
        switch easing {
        case .easeInOut: (x1, x2, y1) = (0.42, 0.58, 0)
        case .easeOut: (x1, x2, y1) = (0, 0.58, 0)
        case .easeIn: (x1, x2, y1) = (0.42, 1, 0)
        case .ease: (x1, x2, y1) = (0.25, 0.25, 0.1)
        }
        // CSS easing uses the Bézier's x coordinate as time, not its curve parameter.
        var low = 0.0
        var high = 1.0
        for _ in 0..<24 {
            let t = (low + high) / 2
            let inverse = 1 - t
            let value = 3 * inverse * inverse * t * x1 + 3 * inverse * t * t * x2 + t * t * t
            if value < x { low = t } else { high = t }
        }
        let t = (low + high) / 2
        return 3 * (1 - t) * (1 - t) * t * y1 + 3 * (1 - t) * t * t + t * t * t
    }
}
