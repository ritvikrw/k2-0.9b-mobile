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

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val notificationRepo = NotificationRepository(db.notificationDao())
    private val ruleRepo = NotificationRuleRepository(db.notificationRuleDao())
    private val summaryManager = NotificationSummaryManager(application)
    private val prefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)

    val importantNotifications = notificationRepo.importantNotifications
    val unimportantNotifications = notificationRepo.unimportantNotifications
    val rules = ruleRepo.allRules

    val modelState = K2InferenceManager.getInstance(application).state

    private val _isNotificationAccessEnabled = MutableStateFlow(false)
    val isNotificationAccessEnabled: StateFlow<Boolean> = _isNotificationAccessEnabled

    private val _isEnabled = MutableStateFlow(prefs.getBoolean("enabled", true))
    val isEnabled: StateFlow<Boolean> = _isEnabled

    private val _importantContext = MutableStateFlow(prefs.getString("important_context", "") ?: "")
    val importantContext: StateFlow<String> = _importantContext

    private val _isAiAlertSoundEnabled = MutableStateFlow(prefs.getBoolean("ai_alert_sound_enabled", true))
    val isAiAlertSoundEnabled: StateFlow<Boolean> = _isAiAlertSoundEnabled

    init {
        checkNotificationAccess()
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
