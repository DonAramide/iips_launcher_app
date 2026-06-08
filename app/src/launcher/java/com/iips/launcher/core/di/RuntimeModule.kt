package com.iips.launcher.core.di

import com.iips.launcher.reconciliation.*
import com.iips.launcher.selfheal.*
import com.iips.launcher.convergence.*
import com.iips.launcher.presence.*
import com.iips.launcher.health.*
import com.iips.launcher.runtime.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RuntimeModule {

    @Provides
    @Singleton
    fun provideDriftDetectionEngine(): DriftDetectionEngine = DriftDetectionEngine()

    @Provides
    @Singleton
    fun provideExpectedStateCache(policyRepository: com.iips.launcher.policy.PolicyRepository): ExpectedStateCache = 
        ExpectedStateCache(policyRepository)

    @Provides
    @Singleton
    fun providePolicyReconciliationManager(
        stateResolver: DeviceStateResolver,
        expectedStateCache: ExpectedStateCache,
        driftEngine: DriftDetectionEngine,
        structuredLogger: com.iips.launcher.core.StructuredLogger
    ): PolicyReconciliationManager {
        return PolicyReconciliationManager(stateResolver, expectedStateCache, driftEngine, structuredLogger)
    }

    @Provides
    @Singleton
    fun provideSelfHealingManager(
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
        reconciliationManager: PolicyReconciliationManager,
        recoveryEngine: RuntimeRecoveryEngine,
        driftResolver: PolicyDriftResolver,
        structuredLogger: com.iips.launcher.core.StructuredLogger
    ): SelfHealingManager {
        return SelfHealingManager(context, reconciliationManager, recoveryEngine, driftResolver, structuredLogger)
    }
}
