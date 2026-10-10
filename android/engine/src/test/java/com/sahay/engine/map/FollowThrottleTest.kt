package com.sahay.engine.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowThrottleTest {

    @Test fun `first move passes, moves inside two seconds are held back`() {
        val throttle = FollowThrottle()
        assertTrue(throttle.tryAcquire(10_000))
        assertFalse(throttle.tryAcquire(10_500))
        assertFalse(throttle.tryAcquire(11_999))
        assertTrue(throttle.tryAcquire(12_000))
    }

    @Test fun `held back moves do not push the window forward`() {
        val throttle = FollowThrottle()
        assertTrue(throttle.tryAcquire(0))
        assertFalse(throttle.tryAcquire(1_900))
        assertTrue(throttle.tryAcquire(2_000))
    }

    @Test fun `reset lets the next move through at once`() {
        val throttle = FollowThrottle()
        throttle.tryAcquire(0)
        throttle.reset()
        assertTrue(throttle.tryAcquire(1))
    }

    @Test fun `a clock that goes backwards does not block following`() {
        val throttle = FollowThrottle()
        throttle.tryAcquire(50_000)
        assertTrue(throttle.tryAcquire(10_000))
    }
}
