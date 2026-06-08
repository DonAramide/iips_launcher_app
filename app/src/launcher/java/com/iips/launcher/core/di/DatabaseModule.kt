package com.iips.launcher.core.di

import android.content.Context
import com.iips.launcher.data.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getDatabase(context)
    }

    @Provides
    fun provideAppPolicyDao(db: AppDatabase) = db.appPolicyDao()

    @Provides
    fun provideAllowedAppDao(db: AppDatabase) = db.allowedAppDao()

    @Provides
    fun provideTelemetryDao(db: AppDatabase) = db.telemetryDao()

    @Provides
    fun provideAppInventoryDao(db: AppDatabase) = db.appInventoryDao()

    @Provides
    fun provideRuntimeDao(db: AppDatabase) = db.runtimeDao()

    @Provides
    fun provideDeploymentDao(db: AppDatabase) = db.deploymentDao()

    @Provides
    fun provideGuardEventDao(db: AppDatabase) = db.guardEventDao()

    @Provides
    fun provideLocationReportDao(db: AppDatabase) = db.locationReportDao()

    @Provides
    fun provideGeofenceRuleDao(db: AppDatabase) = db.geofenceRuleDao()

    @Provides
    fun provideTrackingSessionDao(db: AppDatabase) = db.trackingSessionDao()
}
