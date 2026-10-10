package com.sahay.engine.map

import androidx.lifecycle.Lifecycle

/** What a MapView needs from the host lifecycle. A seam, so the ordering rules can be unit-tested. */
internal interface MapLifecycleTarget {
    fun create()
    fun start()
    fun resume()
    fun pause()
    fun stop()
    fun destroy()
}

/**
 * Forwards an Android [Lifecycle] to a MapView in the order it requires (create → start → resume, and back down).
 * Safe to call with any state in any order, and after destroy; every callback runs at most once per transition.
 * A composable can enter when the host is already RESUMED, so [moveTo] catches up step by step.
 */
internal class MapLifecycleBridge(private val target: MapLifecycleTarget) {

    private enum class Phase { NEW, CREATED, STARTED, RESUMED, DESTROYED }

    private var phase = Phase.NEW

    fun moveTo(state: Lifecycle.State) {
        val goal = when (state) {
            Lifecycle.State.DESTROYED -> Phase.DESTROYED
            Lifecycle.State.INITIALIZED, Lifecycle.State.CREATED -> Phase.CREATED
            Lifecycle.State.STARTED -> Phase.STARTED
            Lifecycle.State.RESUMED -> Phase.RESUMED
        }
        moveTo(goal)
    }

    /** Ends the MapView's life (pause, stop, destroy as needed), e.g. when the composable leaves the screen. */
    fun release() = moveTo(Phase.DESTROYED)

    private fun moveTo(goal: Phase) {
        if (phase == Phase.DESTROYED) return
        if (goal == Phase.DESTROYED) {
            stepDownTo(Phase.CREATED)
            if (phase == Phase.NEW) target.create()       // never created: still give it a clean start-and-end
            target.destroy()
            phase = Phase.DESTROYED
            return
        }
        while (phase.ordinal < goal.ordinal) stepUp()
        stepDownTo(goal)
    }

    private fun stepUp() {
        when (phase) {
            Phase.NEW -> target.create().also { phase = Phase.CREATED }
            Phase.CREATED -> target.start().also { phase = Phase.STARTED }
            Phase.STARTED -> target.resume().also { phase = Phase.RESUMED }
            else -> Unit
        }
    }

    private fun stepDownTo(goal: Phase) {
        while (phase.ordinal > goal.ordinal && phase != Phase.DESTROYED) {
            when (phase) {
                Phase.RESUMED -> target.pause().also { phase = Phase.STARTED }
                Phase.STARTED -> target.stop().also { phase = Phase.CREATED }
                else -> return
            }
        }
    }
}
