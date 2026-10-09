package com.sahay.app.main

import com.sahay.core.contracts.ConnectivityState
import com.sahay.designsystem.components.ConnectivityLevel

/** Internet beats SMS-only beats offline (DESIGN §5 ConnectivityChip). */
fun connectivityLevel(state: ConnectivityState): ConnectivityLevel = when {
    state.internet -> ConnectivityLevel.Online
    state.cellular -> ConnectivityLevel.SmsOnly
    else -> ConnectivityLevel.Offline
}

/** Things the "What works right now" sheet reports on. */
enum class Capability { SAVED_MAP, ALERTS_ONLINE, SMS, NEARBY_PHONES }

/** SAVED_MAP always works: it is read from the downloaded pack, never the network. */
fun capabilityAvailable(capability: Capability, state: ConnectivityState): Boolean = when (capability) {
    Capability.SAVED_MAP -> true
    Capability.ALERTS_ONLINE -> state.internet
    Capability.SMS -> state.cellular
    Capability.NEARBY_PHONES -> state.meshActive
}
