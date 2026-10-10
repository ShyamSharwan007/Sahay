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
import com.sahay.comms.groups.RealGroupService
import com.sahay.comms.net.CommsApi
import com.sahay.comms.net.GroupsApi
import com.sahay.comms.net.OkHttpCommsApi
import com.sahay.comms.net.ReportsApi
import com.sahay.comms.reports.RealReportRepository
import com.sahay.comms.reports.ReportDao
import com.sahay.comms.reports.ReportDatabase
import com.sahay.comms.sos.AndroidSmsDispatcher
import com.sahay.comms.sos.RealSosService
import com.sahay.comms.sos.SmsDispatcher
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
 * Everything is real now. (The Fake* classes stay in the module for tests and previews.)
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CommsModule {
    @Binds @Singleton
    abstract fun alertRepository(impl: RealAlertRepository): AlertRepository

    @Binds @Singleton
    abstract fun sosService(impl: RealSosService): SosService

    @Binds @Singleton
    abstract fun reportRepository(impl: RealReportRepository): ReportRepository

    @Binds @Singleton
    abstract fun groupService(impl: RealGroupService): GroupService

    @Binds @Singleton
    abstract fun connectivityMonitor(impl: RealConnectivityMonitor): ConnectivityMonitor

    @Binds @Singleton
    abstract fun commsApi(impl: OkHttpCommsApi): CommsApi

    @Binds @Singleton
    abstract fun reportsApi(impl: OkHttpCommsApi): ReportsApi

    @Binds @Singleton
    abstract fun groupsApi(impl: OkHttpCommsApi): GroupsApi

    @Binds @Singleton
    abstract fun smsDispatcher(impl: AndroidSmsDispatcher): SmsDispatcher

    @Binds @Singleton
    abstract fun alertNotifier(impl: SystemAlertNotifier): AlertNotifier

    companion object {
        @Provides @Singleton
        fun commsDatabase(@ApplicationContext context: Context): CommsDatabase =
            Room.databaseBuilder(context, CommsDatabase::class.java, "sahay_comms.db")
                .fallbackToDestructiveMigration(dropAllTables = true)   // alerts are re-fetched; v1 has no migrations
                .build()

        // Queued reports are user data: no destructive fallback here, a schema change needs a real Migration.
        @Provides @Singleton
        fun reportDatabase(@ApplicationContext context: Context): ReportDatabase =
            Room.databaseBuilder(context, ReportDatabase::class.java, "sahay_reports.db")
                .addMigrations(ReportDatabase.MIGRATION_1_2)
                .build()

        @Provides
        fun reportDao(db: ReportDatabase): ReportDao = db.reportDao()

        @Provides
        fun alertDao(db: CommsDatabase): AlertDao = db.alertDao()

        @Provides
        fun templateCacheDao(db: CommsDatabase): TemplateCacheDao = db.templateCacheDao()
    }
}
