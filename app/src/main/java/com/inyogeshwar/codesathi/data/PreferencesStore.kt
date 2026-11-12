package com.inyogeshwar.codesathi.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "codesathi_prefs")

/**
 * Plain (non-secret) settings in DataStore Preferences.
 * API keys NEVER live here — see KeyStore (EncryptedSharedPreferences).
 */
class PreferencesStore(private val context: Context) {

    companion object {
        private val ONBOARDED = booleanPreferencesKey("onboarded")
        private val DEFAULT_PROVIDER_ID = stringPreferencesKey("default_provider_id")
        private val DEFAULT_MODEL_ID = stringPreferencesKey("default_model_id")
        private val LAST_SESSION_ID = stringPreferencesKey("last_session_id")
        private val TOOL_RULES = stringPreferencesKey("tool_rules_json")

        /** Per-tool permission rule: "allow" | "ask" | "deny". */
        const val RULE_ALLOW = "allow"
        const val RULE_ASK = "ask"
        const val RULE_DENY = "deny"

        /**
         * Defaults: read-only tools run freely (they only read), writes are
         * always asked about (the diff card is the ask — a write is never
         * applied without Approve, even when the rule is "allow").
         */
        val DEFAULT_TOOL_RULES = mapOf(
            "read" to RULE_ALLOW,
            "glob" to RULE_ALLOW,
            "grep" to RULE_ALLOW,
            "write" to RULE_ASK,
            "edit" to RULE_ASK
        )
    }

    val onboarded: Flow<Boolean> = context.dataStore.data.map { it[ONBOARDED] ?: false }
    val defaultProviderId: Flow<String?> = context.dataStore.data.map { it[DEFAULT_PROVIDER_ID] }
    val defaultModelId: Flow<String?> = context.dataStore.data.map { it[DEFAULT_MODEL_ID] }
    val lastSessionId: Flow<String?> = context.dataStore.data.map { it[LAST_SESSION_ID] }

    suspend fun setOnboarded(done: Boolean) {
        context.dataStore.edit { it[ONBOARDED] = done }
    }

    suspend fun setDefaultModel(providerId: String, modelId: String) {
        context.dataStore.edit {
            it[DEFAULT_PROVIDER_ID] = providerId
            it[DEFAULT_MODEL_ID] = modelId
        }
    }

    suspend fun setLastSession(id: String?) {
        context.dataStore.edit {
            if (id == null) it.remove(LAST_SESSION_ID) else it[LAST_SESSION_ID] = id
        }
    }

    // ---- Per-tool permission rules (allow / ask / deny) ----

    val toolRules: Flow<Map<String, String>> = context.dataStore.data.map { prefs ->
        val stored = prefs[TOOL_RULES] ?: return@map DEFAULT_TOOL_RULES
        try {
            DEFAULT_TOOL_RULES + Json.decodeFromString<Map<String, String>>(stored)
        } catch (_: Exception) {
            DEFAULT_TOOL_RULES
        }
    }

    suspend fun toolRule(toolName: String): String =
        toolRules.first()[toolName] ?: DEFAULT_TOOL_RULES[toolName] ?: RULE_ASK

    suspend fun setToolRule(toolName: String, rule: String) {
        val current = toolRules.first().toMutableMap()
        current[toolName] = rule
        val encoded = Json.encodeToString<Map<String, String>>(current)
        context.dataStore.edit { it[TOOL_RULES] = encoded }
    }
}
