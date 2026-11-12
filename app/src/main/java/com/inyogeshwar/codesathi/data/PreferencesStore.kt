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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One step in the user's fallback order: a provider + one of its models. */
@Serializable
data class FallbackEntry(val providerId: String, val modelId: String)

/**
 * Why a key stopped working, and when it may be tried again.
 * `kind` is "QUOTA" (HTTP 402, or a body mentioning quota) or "RATE"
 * (plain HTTP 429). `untilMillis` is the start of the next local day —
 * exhaustion flags reset at midnight.
 */
@Serializable
data class ExhaustionInfo(val kind: String, val untilMillis: Long)

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
        private val FALLBACK_CHAIN = stringPreferencesKey("fallback_chain_json")
        private val EXHAUSTED = stringPreferencesKey("exhausted_json")

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

    // ---- Fallback chain (Phase 2, Step 1) ----

    /** The user's ordered fallback list, 1st → 2nd → 3rd … */
    val fallbackChain: Flow<List<FallbackEntry>> = context.dataStore.data.map { prefs ->
        val raw = prefs[FALLBACK_CHAIN] ?: return@map emptyList()
        try {
            Json.decodeFromString<List<FallbackEntry>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun setFallbackChain(entries: List<FallbackEntry>) {
        context.dataStore.edit { it[FALLBACK_CHAIN] = Json.encodeToString(entries) }
    }

    /** Append a (provider, model) step; a step already in the list is ignored. */
    suspend fun addFallbackEntry(entry: FallbackEntry) {
        val current = fallbackChain.first()
        if (current.any { it.providerId == entry.providerId && it.modelId == entry.modelId }) return
        setFallbackChain(current + entry)
    }

    suspend fun removeFallbackEntry(providerId: String, modelId: String) {
        setFallbackChain(
            fallbackChain.first()
                .filterNot { it.providerId == providerId && it.modelId == modelId }
        )
    }

    /** Move one step from [fromIndex] to [toIndex]; out-of-range moves are ignored. */
    suspend fun moveFallbackEntry(fromIndex: Int, toIndex: Int) {
        val current = fallbackChain.first().toMutableList()
        if (fromIndex !in current.indices || toIndex !in current.indices) return
        val item = current.removeAt(fromIndex)
        current.add(toIndex, item)
        setFallbackChain(current)
    }

    // ---- Exhaustion flags (Phase 2, Step 1) ----

    /**
     * Exhausted keys, keyed by "providerId|modelId|keyId" (see
     * [com.inyogeshwar.codesathi.agent.FallbackAttempt.exhaustionKey]).
     * Entries whose [ExhaustionInfo.untilMillis] has passed are pruned on
     * read — that is the midnight reset.
     */
    suspend fun exhaustedMap(): Map<String, ExhaustionInfo> {
        val raw = context.dataStore.data.first()[EXHAUSTED] ?: return emptyMap()
        val now = System.currentTimeMillis()
        val parsed = try {
            Json.decodeFromString<Map<String, ExhaustionInfo>>(raw)
        } catch (_: Exception) {
            emptyMap()
        }
        val pruned = parsed.filterValues { it.untilMillis > now }
        if (pruned.size != parsed.size) {
            context.dataStore.edit { it[EXHAUSTED] = Json.encodeToString(pruned) }
        }
        return pruned
    }

    /** Mark one key exhausted until [untilMillis] (start of next local day). */
    suspend fun markExhausted(key: String, kind: String, untilMillis: Long) {
        val current = exhaustedMap().toMutableMap()
        current[key] = ExhaustionInfo(kind, untilMillis)
        context.dataStore.edit { it[EXHAUSTED] = Json.encodeToString(current) }
    }
}
