package com.sahay.comms.alerts

import com.sahay.comms.wire.TestVectors
import com.sahay.core.contracts.ShelterStatus
import io.mockk.coVerify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Shelter statuses are fetched in the same cycle as the alerts, and only signed wires are applied. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShelterStatusSyncTest {

    private lateinit var harness: AlertHarness
    private val shelterWire get() = TestVectors.firstValid("S")          // poi_123, FULL

    private fun newHarness(hasPack: Boolean = true) =
        AlertHarness(RuntimeEnvironment.getApplication(), hasPack = hasPack).also { harness = it }

    @Before fun setUp() { newHarness() }

    @After fun tearDown() = harness.close()

    @Test fun `refresh applies a verified shelter status to the pack`() = runBlocking {
        harness.api.shelterWires = listOf(shelterWire)

        assertTrue(harness.repository.refresh().isSuccess)

        assertEquals(listOf("mahabalipuram"), harness.api.shelterRequests)
        coVerify(exactly = 1) { harness.pack.updateShelterStatus("poi_123", ShelterStatus.FULL, 1_760_000_000L) }
    }

    @Test fun `a tampered status is not applied`() = runBlocking {
        // FULL → OPEN, signature left as it was
        harness.api.shelterWires = listOf(shelterWire.replace("*F*", "*O*"))

        harness.repository.refresh()

        coVerify(exactly = 0) { harness.pack.updateShelterStatus(any(), any(), any()) }
    }

    @Test fun `junk, missing and non-shelter wires are skipped while good ones still apply`() = runBlocking {
        harness.api.shelterWires = listOf(null, "hello", TestVectors.firstValid("A"), shelterWire)

        assertTrue(harness.repository.refresh().isSuccess)

        coVerify(exactly = 1) { harness.pack.updateShelterStatus(any(), any(), any()) }
    }

    @Test fun `a failing shelter call does not fail the alert refresh`() = runBlocking {
        harness.api.alertWires = listOf(TestVectors.firstValid("A"))
        harness.api.failShelters = true

        assertTrue(harness.repository.refresh().isSuccess)

        assertEquals(1, harness.repository.alerts.await { it.size == 1 }.size)
        coVerify(exactly = 0) { harness.pack.updateShelterStatus(any(), any(), any()) }
    }

    @Test fun `shelters are not asked for when the alert call failed`() = runBlocking {
        harness.api.failAlerts = true

        assertTrue(harness.repository.refresh().isFailure)

        assertTrue(harness.api.shelterRequests.isEmpty())
    }

    @Test fun `without a trip pack nothing is requested`() = runBlocking {
        newHarness(hasPack = false)

        assertTrue(harness.repository.refresh().isSuccess)

        assertTrue(harness.api.alertRequests.isEmpty())
        assertTrue(harness.api.shelterRequests.isEmpty())
    }
}
