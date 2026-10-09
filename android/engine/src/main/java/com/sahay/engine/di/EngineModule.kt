package com.sahay.engine.di

import com.sahay.core.contracts.EmergencyModeController
import com.sahay.core.contracts.LocationProvider
import com.sahay.core.contracts.PackRepository
import com.sahay.core.contracts.RiskMonitor
import com.sahay.core.contracts.RoutingEngine
import com.sahay.engine.fake.FakeEmergencyModeController
import com.sahay.engine.fake.FakeLocationProvider
import com.sahay.engine.fake.FakePackRepository
import com.sahay.engine.fake.FakeRiskMonitor
import com.sahay.engine.fake.FakeRoutingEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Phase 0: every binding points at a fake. Person B swaps them for the real engine. */
@Module
@InstallIn(SingletonComponent::class)
abstract class EngineModule {
    @Binds @Singleton
    abstract fun packRepository(impl: FakePackRepository): PackRepository

    @Binds @Singleton
    abstract fun locationProvider(impl: FakeLocationProvider): LocationProvider

    @Binds @Singleton
    abstract fun routingEngine(impl: FakeRoutingEngine): RoutingEngine

    @Binds @Singleton
    abstract fun riskMonitor(impl: FakeRiskMonitor): RiskMonitor

    @Binds @Singleton
    abstract fun emergencyModeController(impl: FakeEmergencyModeController): EmergencyModeController
}
