package com.iips.launcher.aai.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iips.launcher.aai.repository.AaiRepository
import com.iips.launcher.network.GuardAaiWebSocketManager
import com.iips.launcher.network.models.AaiDashboardSummary
import com.iips.launcher.network.models.AaiLiveFeedEvent
import com.iips.launcher.network.models.AaiStreamFrame
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class UiState<out T> {
    object Loading : UiState<Nothing>()
    object Empty : UiState<Nothing>()
    data class Success<T>(val data: T) : UiState<T>()
    data class Error(val message: String) : UiState<Nothing>()
}

@HiltViewModel
class AaiDashboardViewModel @Inject constructor(
    private val repository: AaiRepository,
    private val wsManager: GuardAaiWebSocketManager
) : ViewModel() {

    private val _summary = MutableStateFlow<UiState<AaiDashboardSummary>>(UiState.Loading)
    val summary: StateFlow<UiState<AaiDashboardSummary>> = _summary.asStateFlow()

    private val _liveFeed = MutableStateFlow<List<AaiLiveFeedEvent>>(emptyList())
    val liveFeed: StateFlow<List<AaiLiveFeedEvent>> = _liveFeed.asStateFlow()

    val streamState = wsManager.connectionState

    val streamFrames: Flow<AaiStreamFrame> = wsManager.frames

    init {
        loadDashboard()
        startStream()
        collectStreamFrames()
    }

    fun loadDashboard(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _summary.value = UiState.Loading
            val result = repository.getDashboardSummary(forceRefresh)
            _summary.value = if (result.isSuccess) {
                UiState.Success(result.getOrThrow())
            } else {
                UiState.Error(result.exceptionOrNull()?.message ?: "Failed to load dashboard")
            }
        }
    }

    fun loadLiveFeed(deviceId: String? = null) {
        viewModelScope.launch {
            val result = repository.getLiveFeed(deviceId)
            if (result.isSuccess) {
                _liveFeed.value = result.getOrThrow()
            }
        }
    }

    private fun startStream() {
        wsManager.connect()
    }

    private fun collectStreamFrames() {
        viewModelScope.launch {
            wsManager.frames.collect { frame ->
                val significant = frame.type in listOf(
                    "LIVE_EVENT", "SESSION_START", "SESSION_END", "COMPLIANCE_BREACH",
                    "APP_INSTALL", "APP_UNINSTALL"
                )
                if (significant) {
                    frame.payload?.let { event ->
                        val updated = (listOf(event) + _liveFeed.value).take(100)
                        _liveFeed.value = updated
                    }
                    // Invalidate cache entries that may be stale after this event
                    repository.invalidateCacheOnStreamEvent(frame.type)
                    // Force-refresh KPI summary bypassing the (now invalidated) cache
                    loadDashboard(forceRefresh = true)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        wsManager.disconnect()
    }
}
