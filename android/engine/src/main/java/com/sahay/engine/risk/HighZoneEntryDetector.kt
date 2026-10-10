package com.sahay.engine.risk

import com.sahay.core.contracts.RiskLevel
import com.sahay.core.contracts.RiskZone

/**
 * Decides when a stream of "which zone is the user in" observations contains an ENTRY into a HIGH zone
 * that deserves a notification.
 *
 * - The very first observation never counts: someone who starts the app inside a zone was not "entering" it.
 * - Moving between two HIGH zones is not an entry (the user is already in a flood-prone area).
 * - After a notification, further entries within [cooldownMs] are ignored, so GPS jitter on the zone
 *   border cannot produce a burst of notifications.
 */
internal class HighZoneEntryDetector(private val cooldownMs: Long = DEFAULT_COOLDOWN_MS) {
    private var wasInHighZone: Boolean? = null     // null = nothing observed yet
    private var lastNotifiedAtMs: Long? = null

    /** Records the zone at [nowMs]; returns true when the caller should notify the user now. */
    @Synchronized
    fun onObservation(zone: RiskZone?, nowMs: Long): Boolean {
        val inHighZone = zone?.level == RiskLevel.HIGH
        val entered = inHighZone && wasInHighZone == false
        wasInHighZone = inHighZone
        if (!entered) return false

        val last = lastNotifiedAtMs
        if (last != null && nowMs - last < cooldownMs) return false
        lastNotifiedAtMs = nowMs
        return true
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 10 * 60 * 1000L
    }
}
