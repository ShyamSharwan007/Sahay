package com.sahay.comms.di

import android.content.Context
import androidx.room.Room
import com.sahay.comms.alerts.AlertDao
import com.sahay.comms.alerts.AlertNotifier
import com.sahay.comms.alerts.CommsDatabase
import com.sahay.comms.alerts.RealAlertRepository
import com.sahay.comms.alerts.SystemAlertNotifier
import com.sahay.comms.alerts.TemplateCacheDao
import com.sahay.comms.connectivity.RealConnectivityMonitor
import com.sahay.comms.fake.FakeGroupService
import com.sahay.comms.fake.FakeReportRepository
import com.sahay.comms.fake.FakeSosService
import com.sahay.comms.net.CommsApi
import com.sahay.comms.net.OkHttpCommsApi
import com.sahay.core.contracts.AlertRepository
import com.sahay.core.contracts.ConnectivityMonitor
import com.sahay.core.contracts.GroupService
import com.sahay.core.contracts.ReportRepository
import com.sahay.core.contracts.SosService
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Alerts and connectivity are real; SOS, reports and groups still point at fakes until their turn.
 * (The Fake* classes stay in the module for tests and previews.)
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CommsModule {
    @Binds @Singleton
    abstract fun alertRepository(impl: RealAlertRepository): AlertRepository

    @Binds @Singleton
    abstract fun sosService(impl: FakeSosService): SosService

    @Binds @Singleton
    abstract fun reportRepository(impl: FakeReportRepository): ReportRepository

    @Binds @Singleton
    abstract fun groupService(impl: FakeGroupService): GroupService

    @Binds @Singleton
    abstract fun connectivityMonitor(impl: RealConnectivityMonitor): ConnectivityMonitor

    @Binds @Singleton
    abstract fun commsApi(impl: OkHttpCommsApi): CommsApi

    @Binds @Singleton
    abstract fun alertNotifier(impl: SystemAlertNotifier): AlertNotifier

    companion object {
        @Provides @Singleton
        fun commsDatabase(@ApplicationContext context: Context): CommsDatabase =
            Room.databaseBuilder(context, CommsDatabase::class.java, "sahay_comms.db")
                .fallbackToDestructiveMigration(dropAllTables = true)   // alerts are re-fetched; v1 has no migrations
                .build()

        @Provides
        fun alertDao(db: CommsDatabase): AlertDao = db.alertDao()

        @Provides
        fun templateCacheDao(db: CommsDatabase): TemplateCacheDao = db.templateCacheDao()
    }
}
