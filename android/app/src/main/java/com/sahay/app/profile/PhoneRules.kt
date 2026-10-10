package com.sahay.app.profile

import android.content.Context
import android.telephony.TelephonyManager
import com.sahay.designsystem.phone.PhoneFieldValue
import com.sahay.designsystem.phone.PhoneNumbers
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** The countries the phone knows about, used to pick a default country code. */
interface DeviceCountryProvider {
    /** ISO alpha-2 of the SIM card, or null without a SIM. */
    fun simCountry(): String?
    /** ISO alpha-2 of the device's region setting, or null. */
    fun localeCountry(): String?
}

/** For tests and previews: knows nothing, so the default falls back to India. */
object NoDeviceCountry : DeviceCountryProvider {
    override fun simCountry(): String? = null
    override fun localeCountry(): String? = null
}

@Singleton
class AndroidDeviceCountryProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeviceCountryProvider {
    override fun simCountry(): String? = try {
        context.getSystemService(TelephonyManager::class.java)?.simCountryIso?.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT)
    } catch (_: SecurityException) {
        null
    }

    override fun localeCountry(): String? = Locale.getDefault().country.takeIf { it.isNotBlank() }
}

/**
 * What the phone field shows for a stored number. A country the user chose ([manualRegion]) always wins;
 * otherwise the number's own dial code decides, and an empty field follows the automatic default ([autoRegion]).
 */
fun phoneFieldValue(phone: String, manualRegion: String?, autoRegion: String): PhoneFieldValue {
    val region = manualRegion
        ?: if (phone.isBlank()) autoRegion else PhoneNumbers.regionForPhone(phone, autoRegion) ?: autoRegion
    val national = if (phone.isBlank()) "" else PhoneNumbers.nationalPart(phone, region)
    return PhoneFieldValue(region, national, manual = manualRegion != null)
}

/** The text kept in the draft while editing: "+<dial code><digits>", or blank. */
fun PhoneFieldValue.rawPhone(): String = PhoneNumbers.toRawPhone(region, national)

/** The country to remember: only a choice the user made (or a detected one) stays fixed. */
fun PhoneFieldValue.manualRegion(): String? = region.takeIf { manual }
