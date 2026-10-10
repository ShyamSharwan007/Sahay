package com.sahay.engine.map

import android.content.Context
import com.sahay.core.contracts.EmergencyModeController
import com.sahay.core.contracts.PackRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * The map composable has a fixed signature (docs/CONTRACTS.md §2), so it cannot receive the pack repository as a
 * parameter. It reads it from the Hilt graph through this entry point instead.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SahayMapEntryPoint {
    fun packRepository(): PackRepository
    fun emergencyModeController(): EmergencyModeController

    companion object {
        /** Null where there is no Hilt application (previews, plain tests): the map then shows a plain background. */
        fun from(context: Context): SahayMapEntryPoint? = try {
            EntryPointAccessors.fromApplication(context.applicationContext, SahayMapEntryPoint::class.java)
        } catch (e: IllegalStateException) {
            null
        }
    }
}
