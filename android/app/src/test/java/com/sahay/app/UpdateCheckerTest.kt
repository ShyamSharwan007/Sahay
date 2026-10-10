package com.sahay.app

import com.sahay.app.update.isNewerVersion
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test fun `higher tag is newer`() {
        assertTrue(isNewerVersion("v1.2", "1.1"))
        assertTrue(isNewerVersion("v2.0", "1.9"))
        assertTrue(isNewerVersion("v1.10", "1.9")) // numbers, not text
        assertTrue(isNewerVersion("v1.1.1", "1.1"))
    }

    @Test fun `same or lower tag is not newer`() {
        assertFalse(isNewerVersion("v1.1", "1.1"))
        assertFalse(isNewerVersion("1.1.0", "1.1"))
        assertFalse(isNewerVersion("v1.0", "1.1"))
    }

    @Test fun `unreadable tags are never newer`() {
        assertFalse(isNewerVersion("", "1.1"))
        assertFalse(isNewerVersion("latest", "1.1"))
        assertFalse(isNewerVersion("v1.x", "1.1"))
    }

    @Test fun `suffixes after the number are ignored`() {
        assertTrue(isNewerVersion("v1.2-beta", "1.1"))
    }
}
