package com.sahay.app

import com.sahay.app.main.criticalAlertToAutoActivate
import com.sahay.core.contracts.AlertSource
import com.sahay.core.contracts.SahayAlert
import com.sahay.core.contracts.Verification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoEmergencyTest {
    private val now = 1_760_000_000L

    private fun alert(id: String, severity: Int, code: String? = null, read: Boolean = false, ageSec: Long = 60) = SahayAlert(
        id = id, templateCode = code, severity = severity, area = null, radiusM = null,
        issuedAtEpochSec = now - ageSec, isSimulation = true, title = "t", body = "b", titleEn = "t", bodyEn = "b",
        originalText = null, source = AlertSource.INTERNET, verification = Verification.VERIFIED_OFFICIAL,
        receivedAtEpochSec = now, read = read,
    )

    @Test fun `severity 3 and FLD_EVAC trigger`() {
        assertEquals("a", criticalAlertToAutoActivate(listOf(alert("a", 3)), emptySet(), now)?.id)
        assertEquals("b", criticalAlertToAutoActivate(listOf(alert("b", 2, "FLD_EVAC")), emptySet(), now)?.id)
    }

    @Test fun `warnings, read, handled and old alerts do not trigger`() {
        assertNull(criticalAlertToAutoActivate(listOf(alert("a", 2)), emptySet(), now))
        assertNull(criticalAlertToAutoActivate(listOf(alert("a", 3, read = true)), emptySet(), now))
        assertNull(criticalAlertToAutoActivate(listOf(alert("a", 3)), setOf("a"), now))
        assertNull(criticalAlertToAutoActivate(listOf(alert("a", 3, ageSec = 7 * 3600)), emptySet(), now))
    }
}
