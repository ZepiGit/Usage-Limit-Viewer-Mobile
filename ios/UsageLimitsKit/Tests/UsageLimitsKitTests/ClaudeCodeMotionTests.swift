import UsageLimitsKit
import XCTest

final class ClaudeCodeMotionTests: XCTestCase {
    func testBreathingPeaksKeepTheEightyFivePercentPivotFixed() {
        for (mood, peak) in [(ClaudeCodeMood.asleep, 2.2), (.tired, 1.1), (.idle, 1.6)] {
            let pose = ClaudeCodeMotion.pose(mood: mood, elapsed: peak)
            XCTAssertEqual(pose.scaleY, 0.9685, accuracy: 0.000001, mood.rawValue)
            let pivot = pose.transformed(x: 12, y: 20.4)
            XCTAssertEqual(pivot.x, 12, accuracy: 0.000001)
            XCTAssertEqual(pivot.y, 20.4, accuracy: 0.000001)
            XCTAssertEqual(pose.transformed(x: 12, y: 5).y, 5.4851, accuracy: 0.000001)
        }
    }

    func testWorkingBobAndOppositeArmPeaksUseTheirOwnUnits() {
        let bob = ClaudeCodeMotion.pose(mood: .working, elapsed: 0.22)
        XCTAssertEqual(bob.offsetY, -0.504, accuracy: 0.000001)
        let left = ClaudeCodeMotion.pose(mood: .working, elapsed: 0.12)
        XCTAssertEqual(left.leftArmY, -1.085, accuracy: 0.000001)
        XCTAssertEqual(left.rightArmY, 0, accuracy: 0.000001)
        let right = ClaudeCodeMotion.pose(mood: .working, elapsed: 0)
        XCTAssertEqual(right.leftArmY, 0, accuracy: 0.000001)
        XCTAssertEqual(right.rightArmY, -1.085, accuracy: 0.000001)
        XCTAssertEqual(right.eye(0).y, 8.81375, accuracy: 0.000001)
    }

    func testDanceSquashHopSwayAndAlternatingLimbs() {
        let squash = ClaudeCodeMotion.pose(mood: .dancing, elapsed: 0.06)
        XCTAssertEqual(squash.scaleY, 0.937, accuracy: 0.000001)
        let hop = ClaudeCodeMotion.pose(mood: .dancing, elapsed: 0.225)
        XCTAssertEqual(hop.offsetY, -2.184, accuracy: 0.000001)
        XCTAssertEqual(hop.scaleY, 1, accuracy: 0.000001)
        XCTAssertEqual(ClaudeCodeMotion.pose(mood: .dancing, elapsed: 0).rotationDegrees, -4.2, accuracy: 0.000001)
        XCTAssertEqual(ClaudeCodeMotion.pose(mood: .dancing, elapsed: 0.5).rotationDegrees, 4.2, accuracy: 0.000001)
        let step = ClaudeCodeMotion.pose(mood: .dancing, elapsed: 0.25)
        XCTAssertEqual(step.leftArmY, -4.65, accuracy: 0.000001)
        XCTAssertEqual(step.rightArmY, 0, accuracy: 0.000001)
        XCTAssertEqual(step.evenLegY, -1.1684, accuracy: 0.000001)
        XCTAssertEqual(step.oddLegY, 0, accuracy: 0.000001)
    }

    func testHoorayRaisesArmsOnceAndKeepsThemRaisedAcrossJumps() {
        XCTAssertEqual(ClaudeCodeMotion.pose(mood: .hooray, elapsed: 0).leftArmY, 0)
        let peak = ClaudeCodeMotion.pose(mood: .hooray, elapsed: 0.28)
        XCTAssertEqual(peak.offsetY, -2.688, accuracy: 0.000001)
        for time in [0.3, 0.8, 2.4, 4] {
            let pose = ClaudeCodeMotion.pose(mood: .hooray, elapsed: time)
            XCTAssertEqual(pose.leftArmY, -4.65, accuracy: 0.000001)
            XCTAssertEqual(pose.rightArmY, -4.65, accuracy: 0.000001)
        }
    }

    func testIdleEyesGlanceAndBlinkAboutTheirOwnCentre() {
        let left = ClaudeCodeMotion.pose(mood: .idle, elapsed: 3)
        let right = ClaudeCodeMotion.pose(mood: .idle, elapsed: 5.25)
        XCTAssertEqual(left.eyeOffsetX, -0.6696, accuracy: 0.000001)
        XCTAssertEqual(right.eyeOffsetX, 0.6696, accuracy: 0.000001)
        let blink = ClaudeCodeMotion.pose(mood: .idle, elapsed: 4.416)
        XCTAssertEqual(blink.eyeScaleY, 0.1, accuracy: 0.000001)
        XCTAssertEqual(blink.eye(0).height, 0.2847, accuracy: 0.000001)
        XCTAssertEqual(blink.eye(0).y + blink.eye(0).height / 2, 9.5255, accuracy: 0.000001)
        for (mood, time) in [(ClaudeCodeMood.working, 3.264), (.dancing, 2.496), (.hooray, 2.496)] {
            XCTAssertEqual(ClaudeCodeMotion.pose(mood: mood, elapsed: time).eyeScaleY, 0.1, accuracy: 0.000001)
        }
    }

    func testStillFramesHaveRestBodiesAndMoodSpecificDetails() {
        for mood in ClaudeCodeMood.allCases {
            let pose = ClaudeCodeMotion.pose(mood: mood, elapsed: 29, still: true)
            XCTAssertEqual(pose.offsetY, 0)
            XCTAssertEqual(pose.scaleY, 1)
            XCTAssertEqual(pose.rotationDegrees, 0)
            XCTAssertEqual(pose.eyeScaleY, 1)
            XCTAssertEqual(pose.evenLegY, 0)
            XCTAssertEqual(pose.leftArmY, mood == .hooray ? -4.65 : 0, accuracy: 0.000001)
        }
        XCTAssertEqual(ClaudeCodeMotion.pose(mood: .asleep, elapsed: 0, still: true).eye(0).height, 0.6)
        XCTAssertEqual(ClaudeCodeMotion.pose(mood: .tired, elapsed: 0, still: true).eye(0).height, 1.35)
        XCTAssertEqual(ClaudeCodeMotion.particle(mood: .asleep, index: 0, elapsed: 0, still: true).opacity, 1)
        XCTAssertEqual(ClaudeCodeMotion.particle(mood: .asleep, index: 1, elapsed: 0, still: true).opacity, 0)
        for mood in [ClaudeCodeMood.tired, .dancing, .hooray] {
            for index in 0..<ClaudeCodeMotion.particleCount(mood: mood) {
                let particle = ClaudeCodeMotion.particle(mood: mood, index: index, elapsed: 29, still: true)
                XCTAssertEqual(particle.opacity, 1)
                XCTAssertEqual(particle.scale, 1)
                XCTAssertEqual(particle.translationX, 0)
                XCTAssertEqual(particle.translationY, 0)
            }
        }
    }

    func testParticlesFollowFixedStaggerAndPeakTimes() {
        XCTAssertEqual(ClaudeCodeMotion.particle(mood: .asleep, index: 0, elapsed: 0.54, still: false).opacity, 1, accuracy: 0.000001)
        let first = ClaudeCodeMotion.particle(mood: .asleep, index: 0, elapsed: 0.9, still: false)
        let repeated = ClaudeCodeMotion.particle(mood: .asleep, index: 0, elapsed: 3.6, still: false)
        XCTAssertEqual(first.opacity, repeated.opacity, accuracy: 0.000001)
        XCTAssertEqual(first.translationX, repeated.translationX, accuracy: 0.000001)
        XCTAssertEqual(first.translationY, repeated.translationY, accuracy: 0.000001)
        let sweat = ClaudeCodeMotion.particle(mood: .tired, index: 0, elapsed: 0.27, still: false)
        XCTAssertEqual(sweat.opacity, 1, accuracy: 0.000001)
        XCTAssertEqual(sweat.width, 2.16, accuracy: 0.000001)
        XCTAssertEqual(sweat.height, 3.12, accuracy: 0.000001)
        XCTAssertEqual(ClaudeCodeMotion.particle(mood: .dancing, index: 0, elapsed: 0.36, still: false).opacity, 1, accuracy: 0.000001)
        for (index, time) in [(0, 0.45), (1, 0.15), (2, 0.75)] {
            let sparkle = ClaudeCodeMotion.particle(mood: .hooray, index: index, elapsed: time, still: false)
            XCTAssertEqual(sparkle.opacity, 1, accuracy: 0.000001)
            XCTAssertEqual(sparkle.scale, 1, accuracy: 0.000001)
        }
    }

    func testAmplitudeDoesNotScaleLimbsEyesOrParticles() {
        let full = ClaudeCodeMotion.pose(mood: .dancing, elapsed: 0.25, amplitude: 1)
        let small = ClaudeCodeMotion.pose(mood: .dancing, elapsed: 0.25, amplitude: 0.6)
        XCTAssertEqual(small.offsetY, full.offsetY * 0.6, accuracy: 0.000001)
        XCTAssertEqual(small.rotationDegrees, full.rotationDegrees * 0.6, accuracy: 0.000001)
        XCTAssertEqual(small.leftArmY, full.leftArmY)
        XCTAssertEqual(small.evenLegY, full.evenLegY)
        XCTAssertEqual(small.eye(0), full.eye(0))
    }

    func testActiveClockExcludesCoveredTimeAndResumesItsPhase() {
        var clock = ClaudeCodeActiveClock()
        clock.setActive(true, at: 100)
        XCTAssertEqual(clock.elapsed(at: 101.25), 1.25, accuracy: 0.000001)
        clock.setActive(false, at: 101.25)
        XCTAssertEqual(clock.elapsed(at: 900), 1.25, accuracy: 0.000001)
        clock.setActive(false, at: 950)
        clock.setActive(true, at: 1000)
        clock.setActive(true, at: 1001)
        XCTAssertEqual(clock.elapsed(at: 1002), 3.25, accuracy: 0.000001)
        clock.restart(at: 1002)
        XCTAssertEqual(clock.elapsed(at: 1002), 0, accuracy: 0.000001)
        XCTAssertEqual(clock.elapsed(at: 1003), 1, accuracy: 0.000001)
        clock.setActive(false, at: 1003)
        clock.restart(at: 2000)
        XCTAssertEqual(clock.elapsed(at: 3000), 0, accuracy: 0.000001)
    }
}
