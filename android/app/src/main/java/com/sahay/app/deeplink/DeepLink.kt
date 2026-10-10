package com.sahay.app.deeplink

import com.sahay.core.contracts.DeepLinks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Where a notification tap should take the user (CONTRACTS §2, [DeepLinks]). */
sealed interface DeepLinkTarget {
    data object NavigateSafe : DeepLinkTarget
    data object Emergency : DeepLinkTarget
    data object Groups : DeepLinkTarget
    data class Alert(val id: String) : DeepLinkTarget
}

private const val ALERT_PREFIX = "alert:"

/** Anything unknown or malformed gives null, so a bad link is ignored instead of crashing. */
fun parseDeepLink(raw: String?): DeepLinkTarget? = when {
    raw == null -> null
    raw == DeepLinks.NAVIGATE_SAFE -> DeepLinkTarget.NavigateSafe
    raw == DeepLinks.EMERGENCY -> DeepLinkTarget.Emergency
    raw == DeepLinks.GROUPS -> DeepLinkTarget.Groups
    raw.startsWith(ALERT_PREFIX) -> raw.removePrefix(ALERT_PREFIX).takeIf { it.isNotBlank() }?.let { DeepLinkTarget.Alert(it) }
    else -> null
}

/**
 * Hands a link from MainActivity to the main screens. The link waits here until those screens
 * exist (for example while the user is still in onboarding), so it is never lost.
 */
@Singleton
class DeepLinkRouter @Inject constructor() {
    private val _pending = MutableStateFlow<DeepLinkTarget?>(null)
    val pending: StateFlow<DeepLinkTarget?> = _pending.asStateFlow()

    fun post(target: DeepLinkTarget) {
        _pending.value = target
    }

    fun consume() {
        _pending.value = null
    }
}
