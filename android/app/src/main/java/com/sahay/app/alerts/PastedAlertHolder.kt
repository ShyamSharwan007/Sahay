package com.sahay.app.alerts

import com.sahay.core.contracts.SahayAlert
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers the alert from the last "Paste an alert" so its detail screen can open even if the
 * repository does not list pasted alerts. Memory only: pasted text is never persisted by the app.
 */
@Singleton
class PastedAlertHolder @Inject constructor() {
    @Volatile
    private var last: SahayAlert? = null

    fun remember(alert: SahayAlert) {
        last = alert
    }

    fun find(id: String): SahayAlert? = last?.takeIf { it.id == id }
}
