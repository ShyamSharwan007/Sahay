package com.sahay.di

import com.sahay.core.contracts.AuthTokenProvider
import com.sahay.core.contracts.ProfileStore
import com.sahay.core.contracts.UiPreferences
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds @Singleton
    abstract fun profileStore(impl: InMemoryProfileStore): ProfileStore

    @Binds @Singleton
    abstract fun authTokenProvider(impl: InMemoryAuthTokenProvider): AuthTokenProvider

    @Binds @Singleton
    abstract fun uiPreferences(impl: InMemoryUiPreferences): UiPreferences
}
