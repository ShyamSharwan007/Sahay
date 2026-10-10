package com.sahay.engine.di

import com.sahay.core.contracts.EmergencyModeController
import com.sahay.core.contracts.LocationProvider
import com.sahay.core.contracts.PackRepository
import com.sahay.core.contracts.RiskMonitor
import com.sahay.core.contracts.RoutingEngine
import com.sahay.engine.fake.FakeEmergencyModeController
import com.sahay.engine.fake.FakeLocationProvider
import com.sahay.engine.fake.FakeRiskMonitor
import com.sahay.engine.pack.RealPackRepository
import com.sahay.engine.routing.RealRoutingEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Real pack repository and routing engine; the other bindings still point at fakes until the rest of the engine lands. */
@Module
@InstallIn(SingletonComponent::class)
abstract class EngineModule {
    @Binds @Singleton
    abstract fun packRepository(impl: RealPackRepository): PackRepository

    @Binds @Singleton
    abstract fun locationProvider(impl: FakeLocationProvider): LocationProvider

    @Binds @Singleton
    abstract fun routingEngine(impl: RealRoutingEngine): RoutingEngine

    @Binds @Singleton
    abstract fun riskMonitor(impl: FakeRiskMonitor): RiskMonitor

    @Binds @Singleton
    abstract fun emergencyModeController(impl: FakeEmergencyModeController): EmergencyModeController
}
