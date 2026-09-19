package com.example.llama.aichat.data

import kotlinx.coroutines.flow.Flow

class NotificationRuleRepository(private val ruleDao: NotificationRuleDao) {
    val allRules: Flow<List<NotificationRule>> = ruleDao.getAllRules()

    suspend fun getEnabledRules(): List<NotificationRule> {
        return ruleDao.getEnabledRules()
    }

    suspend fun insert(rule: NotificationRule) {
        ruleDao.insert(rule)
    }

    suspend fun update(rule: NotificationRule) {
        ruleDao.update(rule)
    }

    suspend fun delete(rule: NotificationRule) {
        ruleDao.delete(rule)
    }
}
