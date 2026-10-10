package com.sahay.app.main

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.sahay.R
import com.sahay.core.contracts.ConnectivityState
import com.sahay.designsystem.components.ConnectivityLevel

/** Internet beats SMS-only beats offline (DESIGN §5 ConnectivityChip). */
fun connectivityLevel(state: ConnectivityState): ConnectivityLevel = when {
    state.internet -> ConnectivityLevel.Online
    state.cellular -> ConnectivityLevel.SmsOnly
    else -> ConnectivityLevel.Offline
}

/** Chip text: "Online", "SMS only", "Offline" or "Offline · 3 phones nearby". */
@Composable
fun connectivityLabel(state: ConnectivityState): String = when (connectivityLevel(state)) {
    ConnectivityLevel.Online -> stringResource(R.string.conn_online)
    ConnectivityLevel.SmsOnly -> stringResource(R.string.conn_sms_only)
    ConnectivityLevel.Offline ->
        if (state.meshPeers > 0) pluralStringResource(R.plurals.conn_offline_phones, state.meshPeers, state.meshPeers)
        else stringResource(R.string.conn_offline)
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
