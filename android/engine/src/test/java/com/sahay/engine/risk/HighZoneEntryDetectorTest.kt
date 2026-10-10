package com.sahay.engine.risk

import com.sahay.core.contracts.RiskLevel
import com.sahay.core.contracts.RiskZone
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighZoneEntryDetectorTest {
    private val high = RiskZone("h1", "Low coast", RiskLevel.HIGH, emptyList())
    private val otherHigh = RiskZone("h2", "River bank", RiskLevel.HIGH, emptyList())
    private val medium = RiskZone("m1", null, RiskLevel.MEDIUM, emptyList())

    private val minute = 60_000L

    @Test
    fun walkingIntoAHighZoneNotifiesOnce() {
        val detector = HighZoneEntryDetector()
        assertFalse(detector.onObservation(null, 0))
        assertTrue(detector.onObservation(high, minute))
        assertFalse(detector.onObservation(high, 2 * minute))        // still inside
        assertFalse(detector.onObservation(high, 3 * minute))
    }

    @Test
    fun startingInsideAHighZoneIsNotAnEntry() {
        val detector = HighZoneEntryDetector()
        assertFalse(detector.onObservation(high, 0))
        assertFalse(detector.onObservation(high, minute))
    }

    @Test
    fun mediumZoneNeverNotifies() {
        val detector = HighZoneEntryDetector()
        detector.onObservation(null, 0)
        assertFalse(detector.onObservation(medium, minute))
    }

    @Test
    fun mediumToHighIsAnEntry() {
        val detector = HighZoneEntryDetector()
        detector.onObservation(medium, 0)
        assertTrue(detector.onObservation(high, minute))
    }

    @Test
    fun movingBetweenTwoHighZonesIsNotANewEntry() {
        val detector = HighZoneEntryDetector()
        detector.onObservation(null, 0)
        assertTrue(detector.onObservation(high, minute))
        assertFalse(detector.onObservation(otherHigh, 2 * minute))
    }

    @Test
    fun jitterOnTheBorderDoesNotRepeatTheNotification() {
        val detector = HighZoneEntryDetector(cooldownMs = 10 * minute)
        detector.onObservation(null, 0)
        assertTrue(detector.onObservation(high, minute))
        assertFalse(detector.onObservation(null, 2 * minute))        // stepped out ...
        assertFalse(detector.onObservation(high, 3 * minute))        // ... and back in: inside the cool-down
    }

    @Test
    fun enteringAgainAfterTheCooldownNotifiesAgain() {
        val detector = HighZoneEntryDetector(cooldownMs = 10 * minute)
        detector.onObservation(null, 0)
        assertTrue(detector.onObservation(high, minute))
        detector.onObservation(null, 5 * minute)
        assertTrue(detector.onObservation(high, 12 * minute))
    }

    @Test
    fun aSuppressedEntryDoesNotRestartTheCooldown() {
        val detector = HighZoneEntryDetector(cooldownMs = 10 * minute)
        detector.onObservation(null, 0)
        assertTrue(detector.onObservation(high, minute))             // notified at 1 min
        detector.onObservation(null, 2 * minute)
        assertFalse(detector.onObservation(high, 8 * minute))        // suppressed
        detector.onObservation(null, 9 * minute)
        assertTrue(detector.onObservation(high, 12 * minute))        // 11 min after the real notification
    }
}
