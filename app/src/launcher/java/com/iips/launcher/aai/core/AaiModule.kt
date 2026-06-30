package com.iips.launcher.aai.core

import android.content.Context
import com.iips.launcher.aai.store.AaiDatabase
import com.iips.launcher.aai.store.AaiEventDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AaiModule {

    @Provides
    @Singleton
    fun provideAaiDatabase(@ApplicationContext context: Context): AaiDatabase {
        return AaiDatabase.getDatabase(context)
    }

    @Provides
    @Singleton
    fun provideAaiEventDao(database: AaiDatabase): AaiEventDao {
        return database.aaiEventDao()
    }
}
