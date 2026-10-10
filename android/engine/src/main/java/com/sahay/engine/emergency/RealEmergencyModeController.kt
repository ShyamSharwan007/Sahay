package com.sahay.engine.emergency

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.sahay.core.contracts.EmergencyModeController
import com.sahay.core.contracts.RiskMonitor
import com.sahay.engine.state.EngineStateStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Emergency Mode switch. [isActive] is saved, so Emergency Mode is still on after the app is restarted,
 * and turning it on keeps the [RiskMonitor] following the user (also while the app is in the background).
 *
 * No foreground service and no WorkManager: other modules observe [isActive] and react.
 */
@Singleton
class RealEmergencyModeController internal constructor(
    private val store: EngineStateStore,
    private val riskMonitor: RiskMonitor,
    private val isAppInForeground: () -> Boolean,
    private val scope: CoroutineScope,
) : EmergencyModeController {

    @Inject
    constructor(@ApplicationContext context: Context, riskMonitor: RiskMonitor) : this(
        store = EngineStateStore(context),
        riskMonitor = riskMonitor,
        isAppInForeground = {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    private val active = MutableStateFlow(false)
    override val isActive: StateFlow<Boolean> = active.asStateFlow()

    /** True once someone has called [activate] or [deactivate], so a slow restore can never override them. */
    private var changedByCaller = false       // guarded by stateLock
    private val stateLock = Any()
    private val saveLock = Mutex()

    init {
        scope.launch { restore() }
    }

    override fun activate() {
        setByCaller(true)
        riskMonitor.startMonitoring()
        save()
    }

    override fun deactivate() {
        setByCaller(false)
        // In the foreground the app's screens still own monitoring; in the background nobody needs it any more.
        if (!isAppInForeground()) riskMonitor.stopMonitoring()
        save()
    }

    private suspend fun restore() {
        if (!store.readEmergencyActive()) return
        val restored = synchronized(stateLock) {
            (!changedByCaller).also { if (it) active.value = true }
        }
        if (restored) riskMonitor.startMonitoring()
    }

    private fun setByCaller(value: Boolean) = synchronized(stateLock) {
        changedByCaller = true
        active.value = value
    }

    /** Saves whatever the current value is when the write runs, so quick on/off/on calls end with the latest state. */
    private fun save() {
        scope.launch { saveLock.withLock { store.writeEmergencyActive(active.value) } }
    }
}
