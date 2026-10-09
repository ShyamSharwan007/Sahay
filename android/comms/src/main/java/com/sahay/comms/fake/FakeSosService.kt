package com.sahay.comms.fake

import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.ProfileStore
import com.sahay.core.contracts.SosResult
import com.sahay.core.contracts.SosService
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/** Pretends to send the SOS SMS: each contact succeeds after 1 s. */
@Singleton
class FakeSosService @Inject constructor(
    private val profileStore: ProfileStore,
) : SosService {

    override suspend fun previewMessage(): String = buildMessage()

    override suspend fun sendSos(): SosResult {
        val recipients = recipients()
        val sent = mutableListOf<String>()
        for (phone in recipients) {
            delay(SEND_DELAY_MS)
            sent += phone
        }
        return SosResult(sentTo = sent, failed = emptyList(), includedLocation = LOCATION, message = buildMessage())
    }

    /** Real contacts when a profile exists, otherwise two demo numbers. */
    private fun recipients(): List<String> =
        profileStore.profile.value?.contacts?.map { it.phone }?.takeIf { it.isNotEmpty() } ?: DEMO_CONTACTS

    private fun buildMessage(): String {
        val name = profileStore.profile.value?.displayName?.takeIf { it.isNotBlank() } ?: "A Sahay user"
        return "SOS! $name needs help. Location: ${LOCATION.lat},${LOCATION.lon}"
    }

    private companion object {
        const val SEND_DELAY_MS = 1_000L
        val LOCATION = GeoPoint(12.6208, 80.1945)
        val DEMO_CONTACTS = listOf("+4915100000001", "+4915100000002")
    }
}
