package com.sahay.engine.fake

import com.sahay.core.contracts.LocationFix
import com.sahay.core.contracts.LocationMode
import com.sahay.core.contracts.LocationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/** Always reports a fix at the demo centre of Mahabalipuram. */
@Singleton
class FakeLocationProvider @Inject constructor() : LocationProvider {

    private val fix = MutableStateFlow<LocationFix?>(newFix())
    override val lastFix: StateFlow<LocationFix?> = fix.asStateFlow()

    override fun hasPermission(): Boolean = true

    override fun updates(mode: LocationMode): Flow<LocationFix> = flow {
        while (true) {
            val next = newFix()
            fix.value = next
            emit(next)
            delay(UPDATE_INTERVAL_MS)
        }
    }

    override suspend fun currentFix(timeoutMs: Long): LocationFix? = newFix().also { fix.value = it }

    private fun newFix() = LocationFix(FakeEngineData.center, accuracyM = 8f, timeMs = System.currentTimeMillis())

    private companion object {
        const val UPDATE_INTERVAL_MS = 2_000L
    }
}
