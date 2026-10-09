package com.sahay.engine.fake

import com.sahay.core.contracts.EmergencyModeController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FakeEmergencyModeController @Inject constructor() : EmergencyModeController {

    private val active = MutableStateFlow(false)
    override val isActive: StateFlow<Boolean> = active.asStateFlow()

    override fun activate() {
        active.value = true
    }

    override fun deactivate() {
        active.value = false
    }
}
