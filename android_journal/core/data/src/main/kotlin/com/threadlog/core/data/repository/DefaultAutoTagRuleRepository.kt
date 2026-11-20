package com.threadlog.core.data.repository

import com.threadlog.core.data.local.dao.AutoTagRuleDao
import com.threadlog.core.data.mapper.toDomain
import com.threadlog.core.data.mapper.toEntity
import com.threadlog.core.model.AutoTagRule
import com.threadlog.core.model.contract.AutoTagRuleRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class DefaultAutoTagRuleRepository @Inject constructor(
    private val autoTagRuleDao: AutoTagRuleDao,
) : AutoTagRuleRepository {

    override fun observeRules(): Flow<List<AutoTagRule>> =
        autoTagRuleDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun getEnabledRules(): List<AutoTagRule> =
        autoTagRuleDao.getEnabled().map { it.toDomain() }

    override suspend fun upsertRule(rule: AutoTagRule) {
        autoTagRuleDao.upsert(rule.toEntity())
    }

    override suspend fun deleteRule(ruleId: String) {
        autoTagRuleDao.deleteById(ruleId)
    }
}
