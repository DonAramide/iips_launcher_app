package com.iips.launcher.core.di

import com.iips.launcher.network.LauncherPairingService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LauncherNetworkModule {

    @Provides
    @Singleton
    fun provideLauncherPairingService(retrofit: Retrofit): LauncherPairingService {
        return retrofit.create(LauncherPairingService::class.java)
    }
}
