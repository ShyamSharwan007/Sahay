package com.sahay.comms.fake

import com.sahay.core.contracts.AlertRepository
import com.sahay.core.contracts.AlertSource
import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.SahayAlert
import com.sahay.core.contracts.Verification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FakeAlertRepository @Inject constructor() : AlertRepository {

    private val now = System.currentTimeMillis() / 1000
    private val list = MutableStateFlow(initialAlerts())
    private val unread = MutableStateFlow(list.value.count { !it.read })

    override val alerts: StateFlow<List<SahayAlert>> = list.asStateFlow()
    override val unreadCount: StateFlow<Int> = unread.asStateFlow()

    override suspend fun refresh(): Result<Unit> = Result.success(Unit)

    override suspend fun translatePasted(text: String): SahayAlert {
        val alert = SahayAlert(
            id = "pasted_${System.nanoTime()}",
            templateCode = null,
            severity = 1,
            area = null,
            radiusM = null,
            issuedAtEpochSec = System.currentTimeMillis() / 1000,
            isSimulation = false,
            title = "Pasted message",
            body = text.take(MAX_BODY_CHARS),
            titleEn = "Pasted message",
            bodyEn = text.take(MAX_BODY_CHARS),
            originalText = text,
            source = AlertSource.PASTED,
            verification = Verification.UNVERIFIED,
            receivedAtEpochSec = System.currentTimeMillis() / 1000,
            read = false,
        )
        publish(listOf(alert) + list.value)
        return alert
    }

    override suspend fun markRead(id: String) {
        publish(list.value.map { if (it.id == id) it.copy(read = true) else it })
    }

    /** Keeps the list and the unread counter in step. */
    private fun publish(alerts: List<SahayAlert>) {
        list.value = alerts
        unread.value = alerts.count { !it.read }
    }

    private fun initialAlerts() = listOf(
        SahayAlert(
            id = "a_evac", templateCode = "FLD_EVAC", severity = 3,
            area = GeoPoint(12.6208, 80.1945), radiusM = 2000,
            issuedAtEpochSec = now - 600, isSimulation = true,
            title = "Flooding: go to safety",
            body = "Move to higher ground or a shelter now. Do not walk through flood water.",
            titleEn = "Flooding: go to safety",
            bodyEn = "Move to higher ground or a shelter now. Do not walk through flood water.",
            originalText = null, source = AlertSource.INTERNET,
            verification = Verification.VERIFIED_OFFICIAL, receivedAtEpochSec = now - 590, read = false,
        ),
        SahayAlert(
            id = "a_sms", templateCode = "CYC_WARN", severity = 2,
            area = GeoPoint(12.6208, 80.1945), radiusM = 5000,
            issuedAtEpochSec = now - 7200, isSimulation = false,
            title = "Cyclone warning",
            body = "Stay indoors. Secure your belongings. Keep away from windows.",
            titleEn = "Cyclone warning",
            bodyEn = "Stay indoors. Secure your belongings. Keep away from windows.",
            originalText = "TN-ALERT: Cyclone warning for Chengalpattu coast. Stay indoors.",
            source = AlertSource.SMS_OFFICIAL,
            verification = Verification.MATCHED_OFFICIAL_SMS, receivedAtEpochSec = now - 7190, read = false,
        ),
        SahayAlert(
            id = "a_pasted", templateCode = "TEST", severity = 0,
            area = null, radiusM = null,
            issuedAtEpochSec = now - 86_400, isSimulation = false,
            title = "Test message",
            body = "This is a test message. No action is needed.",
            titleEn = "Test message",
            bodyEn = "This is a test message. No action is needed.",
            originalText = "Test message, please ignore.",
            source = AlertSource.PASTED,
            verification = Verification.UNVERIFIED, receivedAtEpochSec = now - 86_000, read = true,
        ),
    )

    private companion object {
        const val MAX_BODY_CHARS = 300
    }
}
