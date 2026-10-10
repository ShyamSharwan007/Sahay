package com.sahay.app

import com.sahay.app.trip.RegionsUi
import com.sahay.app.trip.TripSetupViewModel
import com.sahay.app.trip.TripStep
import com.sahay.core.contracts.PackDownloadState
import com.sahay.core.contracts.PackInfo
import com.sahay.core.contracts.PackRepository
import com.sahay.core.contracts.ProfileStore
import com.sahay.core.contracts.Region
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class TripSetupViewModelTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val today = LocalDate.of(2026, 10, 10)
    private val clock = Clock.fixed(Instant.parse("2026-10-10T08:00:00Z"), ZoneOffset.UTC)
    private val region = Region("mahabalipuram", "Mahabalipuram", listOf(80.16, 12.59, 80.21, 12.65))
    private val pack = PackInfo(
        "mahabalipuram", "Mahabalipuram", "v1", region.bbox, today, today.plusDays(3), 0,
        emptyList(), emptyMap(), emptyList(), emptyList(), "key", 1,
    )

    private val packs = mockk<PackRepository>()
    private val profiles = mockk<ProfileStore> { every { profile } returns MutableStateFlow(null) }

    private fun vm(regions: List<Region>? = listOf(region)): TripSetupViewModel {
        if (regions == null) coEvery { packs.regions() } throws RuntimeException("boom")
        else coEvery { packs.regions() } returns regions
        return TripSetupViewModel(packs, profiles, clock)
    }

    @Test fun `regions load, and a failure becomes the error state with a working retry`() {
        val failing = vm(regions = null)
        assertEquals(RegionsUi.Error, failing.state.value.regions)

        coEvery { packs.regions() } returns listOf(region)
        failing.loadRegions()
        assertEquals(RegionsUi.Loaded(listOf(region)), failing.state.value.regions)
    }

    @Test fun `no regions is the empty state, not an error`() {
        assertEquals(RegionsUi.Loaded(emptyList()), vm(regions = emptyList()).state.value.regions)
    }

    @Test fun `happy path reaches Ready with the pack`() {
        every { packs.download(any(), any(), any()) } returns flowOf(
            PackDownloadState.Downloading("MANIFEST", 0f),
            PackDownloadState.Done(pack),
        )
        val vm = vm()
        vm.selectRegion(region)
        assertEquals(TripStep.DATES, vm.state.value.step)
        vm.confirmDates(today, today.plusDays(3))
        assertEquals(TripStep.READY, vm.state.value.step)
        assertEquals(pack, vm.state.value.pack)
    }

    @Test fun `invalid dates never start a download`() {
        val vm = vm()
        vm.selectRegion(region)
        vm.confirmDates(today.minusDays(2), today)
        vm.confirmDates(today, today.plusDays(30))
        assertEquals(TripStep.DATES, vm.state.value.step)
    }

    @Test fun `engine failure is shown and retry runs the download again`() {
        var attempts = 0
        every { packs.download(any(), any(), any()) } answers {
            attempts++
            if (attempts == 1) flowOf(PackDownloadState.Failed("net", retryable = true)) else flowOf(PackDownloadState.Done(pack))
        }
        val vm = vm()
        vm.selectRegion(region)
        vm.confirmDates(today, today.plusDays(1))
        assertTrue(vm.state.value.download is PackDownloadState.Failed)
        assertEquals(TripStep.DOWNLOAD, vm.state.value.step)

        vm.startDownload()
        assertEquals(TripStep.READY, vm.state.value.step)
    }

    @Test fun `a thrown exception or a flow that just ends becomes a retryable failure`() {
        every { packs.download(any(), any(), any()) } returns flow { throw java.io.IOException("offline") }
        val vm = vm()
        vm.selectRegion(region)
        vm.confirmDates(today, today.plusDays(1))
        assertEquals(PackDownloadState.Failed("download", true), vm.state.value.download)

        every { packs.download(any(), any(), any()) } returns flowOf(PackDownloadState.Downloading("DATA", 0.2f))
        vm.startDownload()
        assertEquals(PackDownloadState.Failed("download", true), vm.state.value.download)
    }

    @Test fun `back steps from Download to Dates to Region, then leaves`() {
        every { packs.download(any(), any(), any()) } returns flowOf(PackDownloadState.Failed("x", true))
        val vm = vm()
        vm.selectRegion(region)
        vm.confirmDates(today, today.plusDays(1))
        assertTrue(vm.back())
        assertEquals(TripStep.DATES, vm.state.value.step)
        assertTrue(vm.back())
        assertEquals(TripStep.REGION, vm.state.value.step)
        assertEquals(false, vm.back())
    }
}
