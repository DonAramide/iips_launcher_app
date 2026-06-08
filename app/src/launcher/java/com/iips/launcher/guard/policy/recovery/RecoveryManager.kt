package com.iips.launcher.guard.policy.recovery

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecoveryManager @Inject constructor() {
    private val strategies = mutableListOf<RecoveryStrategy>()

    fun registerStrategy(strategy: RecoveryStrategy) {
        strategies.add(strategy)
    }

    fun verifyRecovery(type: RecoveryType, payload: String): Boolean {
        val strategy = strategies.find { it.type == type }
        return strategy?.authenticate(payload) ?: false
    }
}
