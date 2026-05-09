package com.iips.launcher.core.di

import com.iips.launcher.deployment.*
import com.iips.launcher.deployment.health.*
import com.iips.launcher.deployment.verification.*
import com.iips.launcher.deployment.rollout.*
import com.iips.launcher.deployment.rollback.*
import com.iips.launcher.deployment.data.DeploymentDao
import com.iips.launcher.core.StructuredLogger
import com.iips.launcher.install.SilentInstallEngine
import com.iips.launcher.install.InstallVerificationEngine
import com.iips.launcher.kiosk.KioskManager
import com.iips.launcher.policy.PolicyRepository
import com.iips.launcher.reconciliation.PolicyReconciliationManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext

@Module
@InstallIn(SingletonComponent::class)
object DeploymentModule {

    @Provides
    @Singleton
    fun provideDeploymentConvergenceTracker(
        deploymentDao: DeploymentDao,
        structuredLogger: StructuredLogger
    ): DeploymentConvergenceTracker = DeploymentConvergenceTracker(deploymentDao, structuredLogger)

    @Provides
    @Singleton
    fun provideRolloutTargetResolver(@ApplicationContext context: Context): com.iips.launcher.deployment.rollout.RolloutTargetResolver = 
        com.iips.launcher.deployment.rollout.RolloutTargetResolver(context)

    @Provides
    @Singleton
    fun provideRolloutCohortService(
        @ApplicationContext context: Context,
        targetResolver: RolloutTargetResolver
    ): RolloutCohortService = RolloutCohortService(context, targetResolver)

    @Provides
    @Singleton
    fun provideRolloutSegmentationEngine(@ApplicationContext context: Context): RolloutSegmentationEngine = 
        RolloutSegmentationEngine(context)

    @Provides
    @Singleton
    fun provideDeploymentCheckpointManager(
        @ApplicationContext context: Context,
        deploymentDao: DeploymentDao,
        policyRepository: PolicyRepository,
        kioskManager: KioskManager
    ): DeploymentCheckpointManager = DeploymentCheckpointManager(context, deploymentDao, policyRepository, kioskManager)

    @Provides
    @Singleton
    fun provideStagedInstallEngine(
        @ApplicationContext context: Context,
        silentInstallEngine: SilentInstallEngine,
        verificationEngine: InstallVerificationEngine,
        checkpointManager: DeploymentCheckpointManager,
        convergenceTracker: DeploymentConvergenceTracker
    ): StagedInstallEngine = StagedInstallEngine(context, silentInstallEngine, verificationEngine, checkpointManager, convergenceTracker)

    @Provides
    @Singleton
    fun provideRolloutHealthService(@ApplicationContext context: Context): RolloutHealthService = 
        RolloutHealthService(context)

    @Provides
    @Singleton
    fun provideBlastRadiusEstimator(healthService: RolloutHealthService): BlastRadiusEstimator = 
        BlastRadiusEstimator(healthService)

    @Provides
    @Singleton
    fun provideDeploymentRiskAnalyzer(
        healthService: RolloutHealthService,
        blastRadiusEstimator: BlastRadiusEstimator
    ): DeploymentRiskAnalyzer = DeploymentRiskAnalyzer(healthService, blastRadiusEstimator)

    @Provides
    @Singleton
    fun provideDeploymentVerifier(
        healthService: RolloutHealthService,
        reconciliationManager: PolicyReconciliationManager,
        convergenceTracker: DeploymentConvergenceTracker
    ): DeploymentVerifier = DeploymentVerifier(healthService, reconciliationManager, convergenceTracker)

    @Provides
    @Singleton
    fun provideDeploymentRecoveryService(
        @ApplicationContext context: Context,
        policyRepository: PolicyRepository,
        kioskManager: KioskManager
    ): DeploymentRecoveryService = DeploymentRecoveryService(context, policyRepository, kioskManager)

    @Provides
    @Singleton
    fun provideRollbackCoordinator(
        riskAnalyzer: DeploymentRiskAnalyzer,
        checkpointManager: DeploymentCheckpointManager,
        convergenceTracker: DeploymentConvergenceTracker,
        recoveryService: DeploymentRecoveryService,
        structuredLogger: StructuredLogger
    ): RollbackCoordinator = RollbackCoordinator(riskAnalyzer, checkpointManager, convergenceTracker, recoveryService, structuredLogger)

    @Provides
    @Singleton
    fun provideRolloutManager(
        @ApplicationContext context: Context,
        deploymentDao: DeploymentDao,
        convergenceTracker: DeploymentConvergenceTracker,
        cohortService: RolloutCohortService,
        segmentationEngine: RolloutSegmentationEngine,
        stagedInstallEngine: StagedInstallEngine,
        deploymentVerifier: DeploymentVerifier,
        rollbackCoordinator: RollbackCoordinator
    ): RolloutManager = RolloutManager(context, deploymentDao, convergenceTracker, cohortService, segmentationEngine, stagedInstallEngine, deploymentVerifier, rollbackCoordinator)
}
