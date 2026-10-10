package com.sahay.engine.di

import com.sahay.core.contracts.EmergencyModeController
import com.sahay.core.contracts.LocationProvider
import com.sahay.core.contracts.PackRepository
import com.sahay.core.contracts.RiskMonitor
import com.sahay.core.contracts.RoutingEngine
import com.sahay.engine.emergency.RealEmergencyModeController
import com.sahay.engine.location.RealLocationProvider
import com.sahay.engine.pack.RealPackRepository
import com.sahay.engine.risk.RealRiskMonitor
import com.sahay.engine.routing.RealRoutingEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the engine's real implementations. The `Fake*` classes stay in :engine for tests and previews. */
@Module
@InstallIn(SingletonComponent::class)
abstract class EngineModule {
    @Binds @Singleton
    abstract fun packRepository(impl: RealPackRepository): PackRepository

    @Binds @Singleton
    abstract fun locationProvider(impl: RealLocationProvider): LocationProvider

    @Binds @Singleton
    abstract fun routingEngine(impl: RealRoutingEngine): RoutingEngine

    @Binds @Singleton
    abstract fun riskMonitor(impl: RealRiskMonitor): RiskMonitor

    @Binds @Singleton
    abstract fun emergencyModeController(impl: RealEmergencyModeController): EmergencyModeController
}
