package com.sahay

import android.app.Application
import com.sahay.designsystem.phone.PhoneNumbers
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SahayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PhoneNumbers.init(this) // phone number rules are needed by the profile screens and the SOS contacts
    }
}
