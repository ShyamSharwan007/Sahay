package com.sahay.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.google.firebase.auth.FirebaseAuth
import com.sahay.app.auth.AuthRepository
import com.sahay.app.auth.FirebaseAuthRepository
import com.sahay.app.data.DataStoreProfileStore
import com.sahay.app.data.DataStoreUiPreferences
import com.sahay.app.data.FirebaseAuthTokenProvider
import com.sahay.app.data.LoadableProfileStore
import com.sahay.app.onboarding.AndroidAppLocaleController
import com.sahay.app.onboarding.AppLocaleController
import com.sahay.core.contracts.AuthTokenProvider
import com.sahay.core.contracts.ProfileStore
import com.sahay.core.contracts.UiPreferences
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.sahayDataStore: DataStore<Preferences> by preferencesDataStore(name = "sahay")

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds @Singleton
    abstract fun profileStore(impl: DataStoreProfileStore): ProfileStore

    @Binds @Singleton
    abstract fun loadableProfileStore(impl: DataStoreProfileStore): LoadableProfileStore

    @Binds @Singleton
    abstract fun authTokenProvider(impl: FirebaseAuthTokenProvider): AuthTokenProvider

    @Binds @Singleton
    abstract fun uiPreferences(impl: DataStoreUiPreferences): UiPreferences

    @Binds @Singleton
    abstract fun authRepository(impl: FirebaseAuthRepository): AuthRepository

    @Binds @Singleton
    abstract fun localeController(impl: AndroidAppLocaleController): AppLocaleController

    companion object {
        @Provides @Singleton
        fun dataStore(@ApplicationContext context: Context): DataStore<Preferences> = context.sahayDataStore

        @Provides @Singleton
        fun firebaseAuth(): FirebaseAuth = FirebaseAuth.getInstance()

        /** Injected so ViewModels can be tested with a fixed time. */
        @Provides @Singleton
        fun clock(): java.time.Clock = java.time.Clock.systemDefaultZone()
    }
}
