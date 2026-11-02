package net.torosaba.train

import net.torosaba.train.train.MasconProfile
import kotlin.test.Test
import kotlin.test.assertEquals

/** MasconCart の MasconMotionProfileTest と同じ数値を確かめる */
class MasconProfileTest {
    @Test
    fun onlyP1UsesReducedConstantAcceleration() {
        assertEquals(0.005, MasconProfile.update(0.0, 1, 0.1), 1e-12)
        assertEquals(0.105, MasconProfile.update(0.1, 1, 0.1), 1e-12)
        for (notch in 2..7) assertEquals(0.02, MasconProfile.update(0.0, notch, 0.1), 1e-12)
        assertEquals(0.52, MasconProfile.update(0.5, 7, 0.1), 1e-12)
        var speed = 0.0
        repeat(10) { speed = MasconProfile.update(speed, 7, 0.1) }
        assertEquals(16.0, speed * 80.0, 1e-9)
    }

    @Test
    fun everyNotchUsesTargetAndCoastsAtTarget() {
        for (notch in 1..7) {
            val target = MasconProfile.MAX_SPEED / 7.0 * notch
            assertEquals(target, MasconProfile.update(target - 0.001, notch, 0.1), 1e-12)
            assertEquals(target * 0.995, MasconProfile.update(target, notch, 0.1), 1e-12)
        }
    }

    @Test
    fun neutralServiceBrakesAndEmergencyStop() {
        assertEquals(0.995, MasconProfile.update(1.0, 0, 0.1), 1e-12)
        for (brake in 1..7) assertEquals(1.0 - 0.007 * brake, MasconProfile.update(1.0, -brake, 0.1), 1e-12)
        assertEquals(0.0, MasconProfile.update(1.0, -8, 0.1))
        assertEquals(0.0, MasconProfile.update(0.001, -7, 0.1))
    }

    @Test
    fun stepsOfOneTickMatchStepsOfTwoTicks() {
        // MasconCart は 2 tick ごと、TORO-Train は毎 tick 計算するので、同じ時間で同じ速さになることを確かめる
        var perTick = 1.0
        var perTwoTicks = 1.0
        repeat(100) {
            perTick = MasconProfile.update(perTick, 0, 0.05)
            if (it % 2 == 1) perTwoTicks = MasconProfile.update(perTwoTicks, 0, 0.1)
        }
        assertEquals(perTwoTicks, perTick, 1e-9)
    }

    @Test
    fun maxSpeedScalesTargets() {
        assertEquals(1.0, MasconProfile.update(0.999, 7, 0.1, maxSpeed = 1.0), 1e-12)
        assertEquals(1.0, MasconProfile.update(5.0, 0, 0.1, maxSpeed = 1.0), 1e-12)
    }

    @Test
    fun labels() {
        assertEquals("N", MasconProfile.label(0))
        assertEquals("P7", MasconProfile.label(7))
        assertEquals("B3", MasconProfile.label(-3))
        assertEquals("EB", MasconProfile.label(-8))
    }
}
