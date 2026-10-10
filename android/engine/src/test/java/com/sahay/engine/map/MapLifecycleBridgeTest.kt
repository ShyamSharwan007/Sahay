package com.sahay.engine.map

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Test

class MapLifecycleBridgeTest {

    private class Recorder : MapLifecycleTarget {
        val calls = mutableListOf<String>()
        override fun create() { calls += "create" }
        override fun start() { calls += "start" }
        override fun resume() { calls += "resume" }
        override fun pause() { calls += "pause" }
        override fun stop() { calls += "stop" }
        override fun destroy() { calls += "destroy" }
    }

    private val target = Recorder()
    private val bridge = MapLifecycleBridge(target)

    @Test fun `entering while the host is already resumed catches up in order`() {
        bridge.moveTo(Lifecycle.State.RESUMED)
        assertEquals(listOf("create", "start", "resume"), target.calls)
    }

    @Test fun `normal life of a screen`() {
        bridge.moveTo(Lifecycle.State.CREATED)
        bridge.moveTo(Lifecycle.State.STARTED)
        bridge.moveTo(Lifecycle.State.RESUMED)
        bridge.moveTo(Lifecycle.State.STARTED)
        bridge.moveTo(Lifecycle.State.CREATED)
        bridge.moveTo(Lifecycle.State.DESTROYED)
        assertEquals(listOf("create", "start", "resume", "pause", "stop", "destroy"), target.calls)
    }

    @Test fun `repeating a state does nothing`() {
        repeat(3) { bridge.moveTo(Lifecycle.State.STARTED) }
        assertEquals(listOf("create", "start"), target.calls)
    }

    @Test fun `going back to the foreground resumes again`() {
        bridge.moveTo(Lifecycle.State.RESUMED)
        bridge.moveTo(Lifecycle.State.CREATED)
        bridge.moveTo(Lifecycle.State.RESUMED)
        assertEquals(listOf("create", "start", "resume", "pause", "stop", "start", "resume"), target.calls)
    }

    @Test fun `release while resumed pauses, stops and destroys`() {
        bridge.moveTo(Lifecycle.State.RESUMED)
        target.calls.clear()
        bridge.release()
        assertEquals(listOf("pause", "stop", "destroy"), target.calls)
    }

    @Test fun `release before anything started still creates then destroys`() {
        bridge.release()
        assertEquals(listOf("create", "destroy"), target.calls)
    }

    @Test fun `nothing happens after destroy`() {
        bridge.moveTo(Lifecycle.State.STARTED)
        bridge.release()
        target.calls.clear()
        bridge.moveTo(Lifecycle.State.RESUMED)
        bridge.release()
        bridge.moveTo(Lifecycle.State.DESTROYED)
        assertEquals(emptyList<String>(), target.calls)
    }

    @Test fun `initialized counts as created`() {
        bridge.moveTo(Lifecycle.State.INITIALIZED)
        assertEquals(listOf("create"), target.calls)
    }
}
