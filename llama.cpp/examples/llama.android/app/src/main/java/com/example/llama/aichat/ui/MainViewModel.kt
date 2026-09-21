package com.example.llama.aichat.ui

import android.app.Application
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.llama.aichat.ai.K2InferenceManager
import com.example.llama.aichat.data.*
import com.example.llama.aichat.notification.NotificationSummaryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class RetentionPeriod(val label: String, val durationMillis: Long) {
    HOURS_24("24 Hours", 24 * 60 * 60 * 1000L),
    DAYS_2("2 Days", 2 * 24 * 60 * 60 * 1000L),
    DAYS_3("3 Days", 3 * 24 * 60 * 60 * 1000L),
    DAYS_4("4 Days", 4 * 24 * 60 * 60 * 1000L),
    DAYS_5("5 Days", 5 * 24 * 60 * 60 * 1000L),
    DAYS_6("6 Days", 6 * 24 * 60 * 60 * 1000L),
    DAYS_7("7 Days", 7 * 24 * 60 * 60 * 1000L);

    companion object {
        fun fromName(name: String?): RetentionPeriod {
            return entries.find { it.name == name } ?: HOURS_24
        }
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val notificationRepo = NotificationRepository(db.notificationDao())
    private val ruleRepo = NotificationRuleRepository(db.notificationRuleDao())
    private val summaryManager = NotificationSummaryManager(application)
    private val prefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _retentionPeriod = MutableStateFlow(
        RetentionPeriod.fromName(prefs.getString("retention_period", RetentionPeriod.HOURS_24.name))
    )
    val retentionPeriod: StateFlow<RetentionPeriod> = _retentionPeriod.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val importantNotifications: StateFlow<List<NotificationRecord>> = _retentionPeriod.flatMapLatest { period ->
        val cutoff = System.currentTimeMillis() - period.durationMillis
        notificationRepo.getImportantNotificationsSince(cutoff)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val unimportantNotifications: StateFlow<List<NotificationRecord>> = _retentionPeriod.flatMapLatest { period ->
        val cutoff = System.currentTimeMillis() - period.durationMillis
        notificationRepo.getUnimportantNotificationsSince(cutoff)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val allNotifications: StateFlow<List<NotificationRecord>> = _retentionPeriod.flatMapLatest { period ->
        val cutoff = System.currentTimeMillis() - period.durationMillis
        notificationRepo.getNotificationsSince(cutoff)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rules = ruleRepo.allRules

    val modelState = K2InferenceManager.getInstance(application).state

    private val _isNotificationAccessEnabled = MutableStateFlow(false)
    val isNotificationAccessEnabled: StateFlow<Boolean> = _isNotificationAccessEnabled

    private val _isNotificationPermissionGranted = MutableStateFlow(true)
    val isNotificationPermissionGranted: StateFlow<Boolean> = _isNotificationPermissionGranted

    private val _isEnabled = MutableStateFlow(prefs.getBoolean("enabled", true))
    val isEnabled: StateFlow<Boolean> = _isEnabled

    private val _importantContext = MutableStateFlow(prefs.getString("important_context", "") ?: "")
    val importantContext: StateFlow<String> = _importantContext

    private val _isAiAlertSoundEnabled = MutableStateFlow(prefs.getBoolean("ai_alert_sound_enabled", true))
    val isAiAlertSoundEnabled: StateFlow<Boolean> = _isAiAlertSoundEnabled

    private val _selectedNotification = MutableStateFlow<NotificationRecord?>(null)
    val selectedNotification: StateFlow<NotificationRecord?> = _selectedNotification

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _activeFilter = MutableStateFlow("ALL") // "ALL", "IMPORTANT", "NOT_IMPORTANT"
    val activeFilter: StateFlow<String> = _activeFilter

    init {
        checkNotificationAccess()

        // Clean up any historical notifications exceeding maximum 7-day retention
        viewModelScope.launch(Dispatchers.IO) {
            val maxCutoff = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
            notificationRepo.deleteOlderThan(maxCutoff)
        }

        // Maintain persistent notification shade summary in sync with important records
        viewModelScope.launch {
            importantNotifications.collectLatest { list ->
                summaryManager.updateSummary(list)
            }
        }
    }

    fun setRetentionPeriod(period: RetentionPeriod) {
        _retentionPeriod.value = period
        prefs.edit().putString("retention_period", period.name).apply()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setActiveFilter(filter: String) {
        _activeFilter.value = filter
    }

    fun deleteNotification(record: NotificationRecord) {
        viewModelScope.launch {
            notificationRepo.delete(record)
            if (_selectedNotification.value?.id == record.id) {
                _selectedNotification.value = null
            }
        }
    }

    fun updateNotificationPermissionStatus(granted: Boolean) {
        _isNotificationPermissionGranted.value = granted
    }

    fun selectNotification(record: NotificationRecord?) {
        _selectedNotification.value = record
    }

    fun toggleEnabled() {
        val newState = !_isEnabled.value
        _isEnabled.value = newState
        prefs.edit().putBoolean("enabled", newState).apply()
    }

    fun toggleAiAlertSound() {
        val newState = !_isAiAlertSoundEnabled.value
        _isAiAlertSoundEnabled.value = newState
        prefs.edit().putBoolean("ai_alert_sound_enabled", newState).apply()
    }

    fun updateImportantContext(text: String) {
        _importantContext.value = text
        prefs.edit().putString("important_context", text).apply()
    }

    fun checkNotificationAccess() {
        val context = getApplication<Application>()
        val enabled = NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
        _isNotificationAccessEnabled.value = enabled
    }

    fun addRule(text: String) {
        viewModelScope.launch {
            ruleRepo.insert(NotificationRule(text = text.trim()))
        }
    }

    fun updateRule(rule: NotificationRule, newText: String) {
        viewModelScope.launch {
            ruleRepo.update(rule.copy(text = newText.trim(), updatedAt = System.currentTimeMillis()))
        }
    }

    fun toggleRule(rule: NotificationRule) {
        viewModelScope.launch {
            ruleRepo.update(rule.copy(enabled = !rule.enabled, updatedAt = System.currentTimeMillis()))
        }
    }

    fun deleteRule(rule: NotificationRule) {
        viewModelScope.launch {
            ruleRepo.delete(rule)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            notificationRepo.clearHistory()
            summaryManager.clearSummary()
        }
    }

    val errorMessage = K2InferenceManager.getInstance(application).errorMessage

    fun retryModelLoad() {
        viewModelScope.launch {
            K2InferenceManager.getInstance(getApplication()).findAndLoadModel()
        }
    }

    fun importModel(uri: android.net.Uri) {
        viewModelScope.launch {
            K2InferenceManager.getInstance(getApplication()).importModelFromUri(uri)
        }
    }
}
