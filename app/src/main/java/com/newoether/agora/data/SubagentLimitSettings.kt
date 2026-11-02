package com.newoether.agora.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Límites persistentes de subagentes (Fase 4 54ea0e6c). Vive en su propio store por la
 * política de tamaño de 999 líneas; SettingsManager lo expone como propiedad.
 */
class SubagentLimitSettings(private val context: Context) {
    private val store get() = context.dataStore

    val maxTotal: Flow<Int> = store.data.map { normalizeSubagentMaxTotal(it[SUBAGENT_MAX_TOTAL]) }
    val maxConcurrent: Flow<Int> = store.data.map {
        normalizeSubagentMaxConcurrent(it[SUBAGENT_MAX_CONCURRENT], normalizeSubagentMaxTotal(it[SUBAGENT_MAX_TOTAL]))
    }

    suspend fun saveMaxTotal(value: Int) {
        store.edit { it[SUBAGENT_MAX_TOTAL] = normalizeSubagentMaxTotal(value) }
    }

    suspend fun saveMaxConcurrent(value: Int) {
        // La concurrencia nunca puede exceder el total normalizado vigente.
        val maxTotal = normalizeSubagentMaxTotal(store.data.first()[SUBAGENT_MAX_TOTAL])
        store.edit { it[SUBAGENT_MAX_CONCURRENT] = normalizeSubagentMaxConcurrent(value, maxTotal) }
    }
}
