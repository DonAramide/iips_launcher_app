package com.iips.launcher.core.di

import android.content.Context
import com.iips.launcher.data.GuardMobileDatabase
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
    fun provideDatabase(@ApplicationContext context: Context): GuardMobileDatabase {
        return GuardMobileDatabase.getDatabase(context)
    }

    @Provides
    fun provideManagerProfileDao(db: GuardMobileDatabase) = db.managerProfileDao()

    @Provides
    fun providePairedDeviceDao(db: GuardMobileDatabase) = db.pairedDeviceDao()

    @Provides
    fun provideAlertFeedDao(db: GuardMobileDatabase) = db.alertFeedDao()

    @Provides
    fun provideTrackedCoordinateDao(db: GuardMobileDatabase) = db.trackedCoordinateDao()
}
