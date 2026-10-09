package com.sahay.comms.di

import com.sahay.comms.fake.FakeAlertRepository
import com.sahay.comms.fake.FakeConnectivityMonitor
import com.sahay.comms.fake.FakeGroupService
import com.sahay.comms.fake.FakeReportRepository
import com.sahay.comms.fake.FakeSosService
import com.sahay.core.contracts.AlertRepository
import com.sahay.core.contracts.ConnectivityMonitor
import com.sahay.core.contracts.GroupService
import com.sahay.core.contracts.ReportRepository
import com.sahay.core.contracts.SosService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Phase 0: every binding points at a fake. Person D swaps them for the real comms stack. */
@Module
@InstallIn(SingletonComponent::class)
abstract class CommsModule {
    @Binds @Singleton
    abstract fun alertRepository(impl: FakeAlertRepository): AlertRepository

    @Binds @Singleton
    abstract fun sosService(impl: FakeSosService): SosService

    @Binds @Singleton
    abstract fun reportRepository(impl: FakeReportRepository): ReportRepository

    @Binds @Singleton
    abstract fun groupService(impl: FakeGroupService): GroupService

    @Binds @Singleton
    abstract fun connectivityMonitor(impl: FakeConnectivityMonitor): ConnectivityMonitor
}
