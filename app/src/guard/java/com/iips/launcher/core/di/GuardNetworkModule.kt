package com.iips.launcher.core.di

import com.iips.launcher.network.GuardPairingService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object GuardNetworkModule {

    @Provides
    @Singleton
    fun provideGuardPairingService(retrofit: Retrofit): GuardPairingService {
        return retrofit.create(GuardPairingService::class.java)
    }
}
