package com.sahay.comms.fake

import com.sahay.core.contracts.ConnectivityMonitor
import com.sahay.core.contracts.ConnectivityState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FakeConnectivityMonitor @Inject constructor() : ConnectivityMonitor {

    private val current = MutableStateFlow(
        ConnectivityState(internet = true, cellular = true, meshActive = false, meshPeers = 0),
    )
    override val state: StateFlow<ConnectivityState> = current.asStateFlow()
}
