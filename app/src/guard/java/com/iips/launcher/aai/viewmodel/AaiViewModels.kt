package com.iips.launcher.aai.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iips.launcher.aai.filter.AaiFilterPreferences
import com.iips.launcher.aai.repository.AaiRepository
import com.iips.launcher.network.GuardAaiWebSocketManager
import com.iips.launcher.network.models.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AaiSessionViewModel @Inject constructor(
    private val repository: AaiRepository,
    private val filterPrefs: AaiFilterPreferences
) : ViewModel() {

    private val _filters = MutableStateFlow(filterPrefs.loadFilters())
    val filters: StateFlow<AaiFilterParams> = _filters.asStateFlow()

    private val _sessions = MutableStateFlow<UiState<AaiPagedResponse<AaiSession>>>(UiState.Loading)
    val sessions: StateFlow<UiState<AaiPagedResponse<AaiSession>>> = _sessions.asStateFlow()

    private val _sessionDetail = MutableStateFlow<UiState<AaiSessionDetail>>(UiState.Empty)
    val sessionDetail: StateFlow<UiState<AaiSessionDetail>> = _sessionDetail.asStateFlow()

    private var currentPage = 0
    private var allSessions = mutableListOf<AaiSession>()

    init {
        loadSessions()
    }

    fun applyFilters(params: AaiFilterParams) {
        _filters.value = params.copy(page = 0)
        filterPrefs.saveFilters(params)
        currentPage = 0
        allSessions.clear()
        loadSessions()
    }

    fun loadSessions(loadMore: Boolean = false) {
        if (loadMore) currentPage++
        viewModelScope.launch {
            if (!loadMore) _sessions.value = UiState.Loading
            val f = _filters.value
            val result = repository.getSessions(
                deviceId = f.deviceId,
                packageName = f.packageName,
                correlationId = f.correlationId,
                userId = f.userId,
                dateFrom = f.dateFrom,
                dateTo = f.dateTo,
                page = currentPage,
                limit = f.limit
            )
            if (result.isSuccess) {
                val paged = result.getOrThrow()
                if (loadMore) {
                    allSessions.addAll(paged.data)
                    _sessions.value = UiState.Success(paged.copy(data = allSessions.toList()))
                } else {
                    allSessions.clear()
                    allSessions.addAll(paged.data)
                    _sessions.value = if (paged.data.isEmpty()) UiState.Empty
                    else UiState.Success(paged)
                }
            } else {
                _sessions.value = UiState.Error(result.exceptionOrNull()?.message ?: "Failed to load sessions")
            }
        }
    }

    fun loadSessionDetail(sessionId: String) {
        viewModelScope.launch {
            _sessionDetail.value = UiState.Loading
            val result = repository.getSessionDetail(sessionId)
            _sessionDetail.value = if (result.isSuccess) UiState.Success(result.getOrThrow())
            else UiState.Error(result.exceptionOrNull()?.message ?: "Failed to load session detail")
        }
    }

    fun refresh() {
        currentPage = 0
        allSessions.clear()
        loadSessions()
    }
}

@HiltViewModel
class AaiAppExplorerViewModel @Inject constructor(
    private val repository: AaiRepository,
    private val filterPrefs: AaiFilterPreferences
) : ViewModel() {

    private val _filters = MutableStateFlow(filterPrefs.loadFilters())
    val filters: StateFlow<AaiFilterParams> = _filters.asStateFlow()

    private val _apps = MutableStateFlow<UiState<AaiPagedResponse<AaiInstalledApp>>>(UiState.Loading)
    val apps: StateFlow<UiState<AaiPagedResponse<AaiInstalledApp>>> = _apps.asStateFlow()

    private val _appDetail = MutableStateFlow<UiState<AaiAppDetail>>(UiState.Empty)
    val appDetail: StateFlow<UiState<AaiAppDetail>> = _appDetail.asStateFlow()

    private var currentPage = 0
    private val allApps = mutableListOf<AaiInstalledApp>()

    init { loadApps() }

    fun applyFilters(params: AaiFilterParams) {
        _filters.value = params.copy(page = 0)
        filterPrefs.saveFilters(params)
        currentPage = 0
        allApps.clear()
        loadApps()
    }

    fun loadApps(loadMore: Boolean = false) {
        if (loadMore) currentPage++
        viewModelScope.launch {
            if (!loadMore) _apps.value = UiState.Loading
            val f = _filters.value
            val result = repository.getInstalledApps(
                deviceId = f.deviceId,
                packageName = f.packageName,
                category = f.category,
                riskLevel = f.riskLevel,
                trustMin = f.trustMin,
                page = currentPage,
                limit = f.limit
            )
            if (result.isSuccess) {
                val paged = result.getOrThrow()
                if (loadMore) {
                    allApps.addAll(paged.data)
                    _apps.value = UiState.Success(paged.copy(data = allApps.toList()))
                } else {
                    allApps.clear()
                    allApps.addAll(paged.data)
                    _apps.value = if (paged.data.isEmpty()) UiState.Empty else UiState.Success(paged)
                }
            } else {
                _apps.value = UiState.Error(result.exceptionOrNull()?.message ?: "Failed to load apps")
            }
        }
    }

    fun loadAppDetail(packageName: String, deviceId: String? = null) {
        viewModelScope.launch {
            _appDetail.value = UiState.Loading
            val result = repository.getAppDetail(packageName, deviceId)
            _appDetail.value = if (result.isSuccess) UiState.Success(result.getOrThrow())
            else UiState.Error(result.exceptionOrNull()?.message ?: "Failed to load app detail")
        }
    }

    fun refresh() {
        currentPage = 0
        allApps.clear()
        loadApps()
    }
}

@HiltViewModel
class AaiInvestigationViewModel @Inject constructor(
    private val repository: AaiRepository
) : ViewModel() {

    private val _timeline = MutableStateFlow<UiState<AaiInvestigationTimeline>>(UiState.Empty)
    val timeline: StateFlow<UiState<AaiInvestigationTimeline>> = _timeline.asStateFlow()

    private val _searchResults = MutableStateFlow<UiState<AaiPagedResponse<AaiInvestigationTimeline>>>(UiState.Empty)
    val searchResults: StateFlow<UiState<AaiPagedResponse<AaiInvestigationTimeline>>> = _searchResults.asStateFlow()

    fun lookupByCorrelationId(correlationId: String) {
        viewModelScope.launch {
            _timeline.value = UiState.Loading
            val result = repository.getInvestigationTimeline(correlationId)
            _timeline.value = if (result.isSuccess) UiState.Success(result.getOrThrow())
            else UiState.Error(result.exceptionOrNull()?.message ?: "No timeline found")
        }
    }

    fun search(
        correlationId: String? = null,
        sessionId: String? = null,
        deviceId: String? = null,
        dateFrom: String? = null,
        dateTo: String? = null
    ) {
        viewModelScope.launch {
            _searchResults.value = UiState.Loading
            val result = repository.searchInvestigations(correlationId, sessionId, deviceId, dateFrom, dateTo)
            _searchResults.value = if (result.isSuccess) {
                val data = result.getOrThrow()
                if (data.data.isEmpty()) UiState.Empty else UiState.Success(data)
            } else {
                UiState.Error(result.exceptionOrNull()?.message ?: "Search failed")
            }
        }
    }
}

@HiltViewModel
class AaiComplianceViewModel @Inject constructor(
    private val repository: AaiRepository
) : ViewModel() {

    private val _findings = MutableStateFlow<UiState<AaiPagedResponse<AaiComplianceFinding>>>(UiState.Loading)
    val findings: StateFlow<UiState<AaiPagedResponse<AaiComplianceFinding>>> = _findings.asStateFlow()

    private val _violations = MutableStateFlow<UiState<AaiPagedResponse<AaiPolicyViolation>>>(UiState.Loading)
    val violations: StateFlow<UiState<AaiPagedResponse<AaiPolicyViolation>>> = _violations.asStateFlow()

    private var findingsPage = 0
    private var violationsPage = 0

    init {
        loadFindings()
        loadViolations()
    }

    fun loadFindings(deviceId: String? = null, severity: String? = null, status: String? = null, loadMore: Boolean = false) {
        if (loadMore) findingsPage++ else { findingsPage = 0 }
        viewModelScope.launch {
            if (!loadMore) _findings.value = UiState.Loading
            val result = repository.getComplianceFindings(deviceId, severity, status, page = findingsPage)
            _findings.value = if (result.isSuccess) {
                val d = result.getOrThrow()
                if (d.data.isEmpty() && !loadMore) UiState.Empty else UiState.Success(d)
            } else UiState.Error(result.exceptionOrNull()?.message ?: "Failed to load findings")
        }
    }

    fun loadViolations(deviceId: String? = null, severity: String? = null, loadMore: Boolean = false) {
        if (loadMore) violationsPage++ else { violationsPage = 0 }
        viewModelScope.launch {
            if (!loadMore) _violations.value = UiState.Loading
            val result = repository.getPolicyViolations(deviceId, severity, page = violationsPage)
            _violations.value = if (result.isSuccess) {
                val d = result.getOrThrow()
                if (d.data.isEmpty() && !loadMore) UiState.Empty else UiState.Success(d)
            } else UiState.Error(result.exceptionOrNull()?.message ?: "Failed to load violations")
        }
    }

    fun refresh() {
        findingsPage = 0
        violationsPage = 0
        loadFindings()
        loadViolations()
    }
}

@HiltViewModel
class AaiOperationsViewModel @Inject constructor(
    private val repository: AaiRepository,
    private val wsManager: GuardAaiWebSocketManager
) : ViewModel() {

    val streamState = wsManager.connectionState
    val streamFrames = wsManager.frames

    private val _notifications = MutableStateFlow<UiState<AaiPagedResponse<AaiNotification>>>(UiState.Loading)
    val notifications: StateFlow<UiState<AaiPagedResponse<AaiNotification>>> = _notifications.asStateFlow()

    private val _exportJobs = MutableStateFlow<UiState<AaiPagedResponse<AaiExportJob>>>(UiState.Empty)
    val exportJobs: StateFlow<UiState<AaiPagedResponse<AaiExportJob>>> = _exportJobs.asStateFlow()

    private val _exportCreated = MutableStateFlow<AaiExportJob?>(null)
    val exportCreated: StateFlow<AaiExportJob?> = _exportCreated.asStateFlow()

    init {
        loadNotifications()
        loadExportJobs()
    }

    fun loadNotifications(isRead: Boolean? = null) {
        viewModelScope.launch {
            _notifications.value = UiState.Loading
            val result = repository.getNotifications(isRead)
            _notifications.value = if (result.isSuccess) {
                val d = result.getOrThrow()
                if (d.data.isEmpty()) UiState.Empty else UiState.Success(d)
            } else UiState.Error(result.exceptionOrNull()?.message ?: "Failed to load notifications")
        }
    }

    fun markRead(notificationId: String) {
        viewModelScope.launch { repository.markNotificationRead(notificationId) }
    }

    fun createExportJob(request: AaiExportRequest) {
        viewModelScope.launch {
            val result = repository.createExportJob(request)
            if (result.isSuccess) {
                _exportCreated.value = result.getOrThrow()
                loadExportJobs()
            }
        }
    }

    fun loadExportJobs() {
        viewModelScope.launch {
            val result = repository.getExportJobs()
            if (result.isSuccess) {
                val d = result.getOrThrow()
                _exportJobs.value = if (d.data.isEmpty()) UiState.Empty else UiState.Success(d)
            }
        }
    }

    fun pollExportJob(jobId: String) {
        viewModelScope.launch {
            repeat(10) {
                kotlinx.coroutines.delay(3000)
                val result = repository.getExportJobStatus(jobId)
                if (result.isSuccess) {
                    val job = result.getOrThrow()
                    _exportCreated.value = job
                    if (job.status == "COMPLETED" || job.status == "FAILED") return@launch
                }
            }
        }
    }
}
