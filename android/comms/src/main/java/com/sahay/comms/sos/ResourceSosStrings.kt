package com.sahay.comms.sos

import android.content.Context
import android.content.res.Configuration
import com.sahay.comms.R
import java.util.Locale

/**
 * SOS wording from comms `strings.xml`. Translated for [TRANSLATED]; any other language uses English,
 * so a Japanese phone never sends a half-translated message.
 */
class ResourceSosStrings(context: Context, language: String?) : SosStrings {

    override val locale: Locale = Locale.forLanguageTag(language?.takeIf { it in TRANSLATED } ?: "en")
    private val resources = localizedContext(context, locale)

    override fun unknownName() = resources.getString(R.string.comms_sos_unknown_name)
    override fun intro(name: String) = resources.getString(R.string.comms_sos_intro, name)
    override fun location(coordinates: String, accuracyM: Int, time: String) =
        resources.getString(R.string.comms_sos_location, coordinates, accuracyM.toString(), time)
    override fun locationWithoutAccuracy(coordinates: String, time: String) =
        resources.getString(R.string.comms_sos_location_no_accuracy, coordinates, time)
    override fun locationUnavailable() = resources.getString(R.string.comms_sos_location_unavailable)
    override fun bloodGroup(value: String) = resources.getString(R.string.comms_sos_blood, value)
    override fun allergies(value: String) = resources.getString(R.string.comms_sos_allergies, value)
    override fun staying(value: String) = resources.getString(R.string.comms_sos_staying, value)

    /** One line for [com.sahay.core.contracts.SosResult.failed]: the number and why it failed. */
    fun failedEntry(phone: String, reason: SmsFailure): String =
        resources.getString(R.string.comms_sos_failed_entry, phone, failure(reason))

    fun failure(reason: SmsFailure): String = resources.getString(
        when (reason) {
            SmsFailure.NO_CONTACTS -> R.string.comms_sos_fail_no_contacts
            SmsFailure.NO_PERMISSION -> R.string.comms_sos_fail_no_permission
            SmsFailure.AIRPLANE_MODE -> R.string.comms_sos_fail_airplane
            SmsFailure.NO_SIM -> R.string.comms_sos_fail_no_sim
            SmsFailure.NO_SERVICE -> R.string.comms_sos_fail_no_service
            SmsFailure.INVALID_NUMBER -> R.string.comms_sos_fail_invalid_number
            SmsFailure.TIMEOUT -> R.string.comms_sos_fail_timeout
            SmsFailure.GENERIC -> R.string.comms_sos_fail_generic
        },
    )

    companion object {
        val TRANSLATED = setOf("en", "de", "fr", "ar")

        // A function, not an `apply` block on Configuration: there `locale` would mean Configuration.locale.
        private fun localizedContext(context: Context, locale: Locale): Context {
            val configuration = Configuration(context.resources.configuration)
            configuration.setLocale(locale)
            return context.createConfigurationContext(configuration)
        }
    }
}
