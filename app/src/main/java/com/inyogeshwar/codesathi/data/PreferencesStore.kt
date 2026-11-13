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
 * One MCP server (Phase 2, Step 3). The auth token is NOT here — it lives
 * in KeyStore (EncryptedSharedPreferences); [hasToken] only records that
 * one is saved, so the UI can say "Token saved" without ever seeing it.
 */
@Serializable
data class McpServer(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val hasToken: Boolean = false
)

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
        private val MCP_SERVERS = stringPreferencesKey("mcp_servers_json")
        private val MCP_DEFAULTS_SEEDED = booleanPreferencesKey("mcp_defaults_seeded")
        private val SKILL_ENABLED = stringPreferencesKey("skill_enabled_json")
        private val VOICE_OUTPUT = booleanPreferencesKey("voice_output_enabled")
        private val REMOTE_ENABLED = booleanPreferencesKey("remote_enabled")

        /** Pre-filled MCP entry for Exa web search. Disabled by default —
         *  the user enables it and adds their own key when they want it.
         *  No key is bundled with the app. */
        const val EXA_MCP_URL = "https://mcp.exa.ai/mcp"

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
            "edit" to RULE_ASK,
            // A skill can lead to writes, so it asks — and any write it
            // proposes still needs the diff-card Approve anyway.
            "skill" to RULE_ASK
        )
    }

    val onboarded: Flow<Boolean> = context.dataStore.data.map { it[ONBOARDED] ?: false }
    /** Remote mode (Phase 2, Step 7): the local HTTP server, off by default. */
    val remoteEnabled: Flow<Boolean> = context.dataStore.data.map { it[REMOTE_ENABLED] ?: false }

    suspend fun setRemoteEnabled(enabled: Boolean) {
        context.dataStore.edit { it[REMOTE_ENABLED] = enabled }
    }
    /** Voice output (Phase 2, Step 5): speak assistant replies, off by default. */
    val voiceOutputEnabled: Flow<Boolean> = context.dataStore.data.map { it[VOICE_OUTPUT] ?: false }

    suspend fun setVoiceOutputEnabled(enabled: Boolean) {
        context.dataStore.edit { it[VOICE_OUTPUT] = enabled }
    }
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

    /** Drop every stored rule whose tool name starts with [prefix] —
     *  used when an MCP server is removed, so its tools leave no orphans. */
    suspend fun removeToolRules(prefix: String) {
        val current = toolRules.first().filterKeys { !it.startsWith(prefix) }
        context.dataStore.edit { it[TOOL_RULES] = Json.encodeToString<Map<String, String>>(current) }
    }

    // ---- Skills enabled / disabled (Phase 2, Step 4) ----

    /**
     * Skill id → enabled, for skills the user has switched off. A skill
     * with no entry here is enabled — so new skills (built-in or added)
     * work without the user having to turn them on first.
     */
    val skillEnabled: Flow<Map<String, Boolean>> = context.dataStore.data.map { prefs ->
        val raw = prefs[SKILL_ENABLED] ?: return@map emptyMap()
        try {
            Json.decodeFromString<Map<String, Boolean>>(raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    suspend fun isSkillEnabled(skillId: String): Boolean =
        skillEnabled.first()[skillId] ?: true

    suspend fun setSkillEnabled(skillId: String, enabled: Boolean) {
        val current = skillEnabled.first().toMutableMap()
        current[skillId] = enabled
        context.dataStore.edit { it[SKILL_ENABLED] = Json.encodeToString<Map<String, Boolean>>(current) }
    }

    /** Drop the stored flag for a deleted skill, so it leaves no orphan. */
    suspend fun removeSkillEnabled(skillId: String) {
        val current = skillEnabled.first().filterKeys { it != skillId }
        context.dataStore.edit { it[SKILL_ENABLED] = Json.encodeToString<Map<String, Boolean>>(current) }
    }

    // ---- MCP servers (Phase 2, Step 3) ----

    val mcpServers: Flow<List<McpServer>> = context.dataStore.data.map { prefs ->
        val raw = prefs[MCP_SERVERS] ?: return@map emptyList()
        try {
            Json.decodeFromString<List<McpServer>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Seed the pre-filled Exa entry once, disabled by default. Never
     * overwrites a list the user already has — if servers exist, this
     * only marks the seeding done.
     */
    suspend fun ensureDefaultServers() {
        val prefs = context.dataStore.data.first()
        if (prefs[MCP_DEFAULTS_SEEDED] == true) return
        val existing = try {
            prefs[MCP_SERVERS]?.let { Json.decodeFromString<List<McpServer>>(it) }.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        context.dataStore.edit {
            if (existing.isEmpty()) {
                it[MCP_SERVERS] = Json.encodeToString<List<McpServer>>(
                    listOf(
                        McpServer(
                            id = "exa",
                            name = "Exa",
                            url = EXA_MCP_URL,
                            enabled = false,
                            hasToken = false
                        )
                    )
                )
            }
            it[MCP_DEFAULTS_SEEDED] = true
        }
    }

    /** Add a server; a server with the same id replaces the old one. */
    suspend fun addMcpServer(server: McpServer) {
        val current = mcpServers.first().filterNot { it.id == server.id } + server
        context.dataStore.edit { it[MCP_SERVERS] = Json.encodeToString<List<McpServer>>(current) }
    }

    suspend fun removeMcpServer(serverId: String) {
        val current = mcpServers.first().filterNot { it.id == serverId }
        context.dataStore.edit { it[MCP_SERVERS] = Json.encodeToString<List<McpServer>>(current) }
    }

    suspend fun setMcpServerEnabled(serverId: String, enabled: Boolean) {
        val current = mcpServers.first().map {
            if (it.id == serverId) it.copy(enabled = enabled) else it
        }
        context.dataStore.edit { it[MCP_SERVERS] = Json.encodeToString<List<McpServer>>(current) }
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
