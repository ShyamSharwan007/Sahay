package com.sahay.comms.sos

import android.content.Context
import android.util.Log
import com.sahay.core.contracts.LocationFix
import com.sahay.core.contracts.LocationProvider
import com.sahay.core.contracts.ProfileStore
import com.sahay.core.contracts.SosResult
import com.sahay.core.contracts.SosService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends the SOS SMS to every emergency contact. Needs no internet: the text is built from the saved profile and
 * the best location available (a fresh fix if one arrives within 5 s, otherwise the last known one).
 *
 * All contacts are texted at the same time. Each one is "sent" only when the network confirms every part, or
 * "failed" with a reason after [SEND_TIMEOUT_MS].
 */
@Singleton
class RealSosService internal constructor(
    private val context: Context,
    private val profileStore: ProfileStore,
    private val locationProvider: LocationProvider,
    private val dispatcher: SmsDispatcher,
    private val zone: () -> ZoneId,
) : SosService {

    @Inject constructor(
        @ApplicationContext context: Context,
        profileStore: ProfileStore,
        locationProvider: LocationProvider,
        dispatcher: SmsDispatcher,
    ) : this(context, profileStore, locationProvider, dispatcher, { ZoneId.systemDefault() })

    /** The exact text [sendSos] would send now. Waits up to 5 s for a fresh location, like [sendSos] does. */
    override suspend fun previewMessage(): String = draft().message

    override suspend fun sendSos(): SosResult {
        val draft = draft()
        val contacts = contactNumbers()
        val point = draft.fix?.point
        if (contacts.isEmpty()) {
            return SosResult(emptyList(), listOf(draft.strings.failure(SmsFailure.NO_CONTACTS)), point, draft.message)
        }
        val blocked = dispatcher.unavailableReason()
        val outcomes = coroutineScope {
            contacts.map { number ->
                async { number to (blocked ?: sendTo(number, draft.message)) }
            }.awaitAll()
        }
        return SosResult(
            sentTo = outcomes.filter { it.second == null }.map { it.first },
            failed = outcomes.mapNotNull { (number, failure) -> failure?.let { draft.strings.failedEntry(number, it) } },
            includedLocation = point,
            message = draft.message,
        )
    }

    private class SendAnswer(val failure: SmsFailure?)

    private class Draft(val message: String, val fix: LocationFix?, val strings: ResourceSosStrings)

    private suspend fun draft(): Draft {
        val profile = profileStore.profile.value
        val strings = ResourceSosStrings(context, profile?.language)
        val fix = bestFix()
        val input = SosInput(
            name = profile?.displayName,
            fix = fix,
            bloodGroup = profile?.bloodGroup,
            allergies = profile?.allergies,
            hotel = profile?.hotelName,
            zone = zone(),
        )
        return Draft(SosMessageBuilder.build(strings, input), fix, strings)
    }

    /** A fresh fix within [FIX_TIMEOUT_MS], else the last known one, else null. Never throws. */
    private suspend fun bestFix(): LocationFix? {
        val fresh = try {
            if (locationProvider.hasPermission()) {
                withTimeoutOrNull(FIX_TIMEOUT_MS + FIX_GRACE_MS) { locationProvider.currentFix(FIX_TIMEOUT_MS) }
            } else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not get a fresh location for the SOS: ${e.message}")
            null
        }
        return fresh ?: locationProvider.lastFix.value
    }

    /** Distinct contact numbers. An unusable number is kept as typed so it is reported as failed, not silently skipped. */
    private fun contactNumbers(): List<String> =
        profileStore.profile.value?.contacts.orEmpty()
            .map { it.phone.trim() }
            .filter { it.isNotEmpty() }
            .map { normalizePhone(it) ?: it }
            .distinct()

    private suspend fun sendTo(number: String, message: String): SmsFailure? {
        if (normalizePhone(number) == null) return SmsFailure.INVALID_NUMBER
        return try {
            // The wrapper tells "sent" (failure = null) apart from "no answer in time" (no wrapper at all).
            val answer = withTimeoutOrNull(SEND_TIMEOUT_MS) { SendAnswer(dispatcher.send(number, message)) }
            if (answer == null) SmsFailure.TIMEOUT else answer.failure
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "SOS to a contact failed: ${e.message}")
            SmsFailure.GENERIC
        }
    }

    internal companion object {
        private const val TAG = "SosService"
        const val FIX_TIMEOUT_MS = 5_000L
        private const val FIX_GRACE_MS = 1_000L
        const val SEND_TIMEOUT_MS = 20_000L
        private const val MIN_DIGITS = 5
        private const val MAX_DIGITS = 15

        /** Keeps a leading "+" and the digits; null if the result is not a plausible phone number. */
        fun normalizePhone(raw: String): String? {
            val trimmed = raw.trim()
            val digits = trimmed.filter { it in '0'..'9' }
            if (digits.length !in MIN_DIGITS..MAX_DIGITS) return null
            return if (trimmed.startsWith("+")) "+$digits" else digits
        }
    }
}
