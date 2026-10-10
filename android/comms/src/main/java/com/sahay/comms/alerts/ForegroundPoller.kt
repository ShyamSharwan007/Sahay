package com.sahay.comms.alerts

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs [action] every [intervalMs] between [start] and [stop] (first run at once, only while [shouldPoll]).
 * Hooked to the app process lifecycle with [attachToProcess]: runs only while some screen is visible.
 */
class ForegroundPoller(
    private val scope: CoroutineScope,
    private val intervalMs: Long,
    private val shouldPoll: () -> Boolean,
    private val action: suspend () -> Unit,
) : DefaultLifecycleObserver {

    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                if (shouldPoll()) runAction()
                delay(intervalMs)
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    override fun onStart(owner: LifecycleOwner) = start()
    override fun onStop(owner: LifecycleOwner) = stop()

    /** Observers must be added on the main thread. */
    fun attachToProcess() {
        Handler(Looper.getMainLooper()).post { ProcessLifecycleOwner.get().lifecycle.addObserver(this) }
    }

    private suspend fun runAction() {
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Poll failed: ${e.message}")   // next round tries again
        }
    }

    private companion object {
        const val TAG = "ForegroundPoller"
    }
}
