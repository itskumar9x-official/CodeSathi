package com.inyogeshwar.codesathi.data

import com.inyogeshwar.codesathi.agent.ApprovalMode
import com.inyogeshwar.codesathi.agent.PermissionRule
import com.inyogeshwar.codesathi.agent.PluginRecord
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
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
    val hasToken: Boolean = false,
    /** Plugin this server came from, if any — only the plugin's
     *  uninstall removes it; the MCP screen hides its Delete button. */
    val pluginId: String? = null
)

/**
 * One user-defined slash command (Phase 3, Step 1). Typing "/[name]"
 * in chat expands to [prompt] before the message reaches the model.
 * [prompt] may contain "{input}" — the text typed after the command
 * name replaces it; otherwise the text is appended after the prompt.
 * Commands hold no secrets, so plain DataStore storage is fine.
 */
@Serializable
data class CustomCommand(
    val id: String,
    val name: String,
    val prompt: String,
    val description: String = "",
    val enabled: Boolean = true,
    /** Plugin this command came from, if any — only the plugin's
     *  uninstall removes it; the Commands screen shows it like any
     *  other command while the plugin is installed. */
    val pluginId: String? = null
)

/**
 * Why a key stopped working, and when it may be tried again.
 * `kind` is "QUOTA" (HTTP 402, or a body mentioning quota) or "RATE"
 * (plain HTTP 429). `untilMillis` is the end of that failure's
 * cooldown (Phase 7, Step B1: rate 60 s, daily quota 1 h, out of
 * credit 6 h, server 20 s, network 15 s) — NOT midnight any more.
 * Entries whose [untilMillis] has passed are pruned on read.
 */
@Serializable
data class ExhaustionInfo(val kind: String, val untilMillis: Long)

/**
 * One credential's health (Phase 7, Step B1 — the per-key ledger from
 * AgentMT's ModelRouter, stored here as DataStore JSON, not Room: the
 * ledger is a small map keyed by the same "providerId|modelId|keyId"
 * string the exhaustion flags already use, it is read and written
 * together with those flags, and it needs no query, index or migration
 * — a Room entity + v5 → v6 migration would add all three for data
 * that is naturally a map. No secret is stored here: the key is the
 * key id (a hash prefix), never the key value.
 */
@Serializable
data class KeyHealth(
    val failures: Int = 0,
    val requests: Int = 0,
    /** "unknown" | "ok" | "rate_limited" | "no_credit" | "invalid" | "error" */
    val status: String = STATUS_UNKNOWN,
    val lastError: String? = null,
    /** Epoch millis when this key may be tried again; 0 = not cooling down. */
    val cooldownUntil: Long = 0,
    val lastUsedAt: Long = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val lastLatencyMs: Long? = null
) {
    companion object {
        const val STATUS_UNKNOWN = "unknown"
        const val STATUS_OK = "ok"
        const val STATUS_RATE_LIMITED = "rate_limited"
        const val STATUS_NO_CREDIT = "no_credit"
        const val STATUS_INVALID = "invalid"
        const val STATUS_ERROR = "error"
    }
}

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
        private val APPROVAL_MODE = stringPreferencesKey("approval_mode")
        private val PERMISSION_RULES = stringPreferencesKey("permission_rules_json")
        private val FALLBACK_CHAIN = stringPreferencesKey("fallback_chain_json")
        private val EXHAUSTED = stringPreferencesKey("exhausted_json")
        private val KEY_HEALTH = stringPreferencesKey("key_health_json")
        private val ROUTING_MODE = stringPreferencesKey("routing_mode")
        private val MCP_SERVERS = stringPreferencesKey("mcp_servers_json")
        private val MCP_DEFAULTS_SEEDED = booleanPreferencesKey("mcp_defaults_seeded")
        private val COMMANDS = stringPreferencesKey("commands_json")
        private val COMMAND_DEFAULTS_SEEDED = booleanPreferencesKey("commands_defaults_seeded")
        private val REASONING_EFFORT = stringPreferencesKey("reasoning_effort_json")
        private val AZURE_RESOURCE_NAMES = stringPreferencesKey("azure_resource_names_json")
        private val LOCAL_MODEL_URI = stringPreferencesKey("local_model_uri")
        private val LOCAL_MODEL_NAME = stringPreferencesKey("local_model_name")
        private val SKILL_ENABLED = stringPreferencesKey("skill_enabled_json")
        private val VOICE_OUTPUT = booleanPreferencesKey("voice_output_enabled")
        private val REMOTE_ENABLED = booleanPreferencesKey("remote_enabled")
        private val PLUGINS = stringPreferencesKey("plugins_json")
        private val TERMUX_ENABLED = booleanPreferencesKey("termux_enabled")

        /** Pre-filled MCP entry for Exa web search. Disabled by default —
         *  the user enables it and adds their own key when they want it.
         *  No key is bundled with the app. */
        const val EXA_MCP_URL = "https://mcp.exa.ai/mcp"

        /** Reasoning-effort values (Phase 3, Step 2): "low" | "medium" | "high". */
        const val EFFORT_LOW = "low"
        const val EFFORT_MEDIUM = "medium"
        const val EFFORT_HIGH = "high"

        /** The only values [setReasoningEffort] stores; anything else is dropped. */
        val VALID_EFFORTS = setOf(EFFORT_LOW, EFFORT_MEDIUM, EFFORT_HIGH)

        /**
         * Azure resource names (Phase 4): 3–24 chars, lowercase letters /
         * digits / hyphens, starting and ending with a letter or digit.
         * Azure's own naming rule — enforced on save and on read.
         */
        fun isValidAzureResourceName(name: String): Boolean =
            name.length in 3..24 &&
                name.matches(Regex("^[a-z0-9]([a-z0-9-]{1,22}[a-z0-9])?$"))

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
            // A shell can change or delete anything in the folder, so it
            // asks — nothing runs until the user has approved it at least
            // once (the rule can then be set to "allow" per the same
            // Tool permissions screen as every other tool).
            "run" to RULE_ASK,
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

    /**
     * The stored per-tool rules only (no v1 defaults merged) — the
     * permission precedence (Phase 7, Step B2) reads this map once per
     * turn, so the decision function can be a plain (non-suspend)
     * lambda. See [storedToolRule].
     */
    suspend fun storedToolRules(): Map<String, String> {
        val raw = context.dataStore.data.first()[TOOL_RULES] ?: return emptyMap()
        return try {
            Json.decodeFromString<Map<String, String>>(raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * The per-tool rule the user actually STORED for one tool, or null
     * when they stored none. Unlike [toolRules] (which merges the v1
     * defaults over the stored map), this reads the stored map only —
     * the permission precedence (Phase 7, Step B2) must treat the
     * defaults as "no explicit rule", so a glob rule or the approval
     * mode can decide for a tool the user never touched. A stored
     * "deny" is the one thing nothing can override.
     */
    suspend fun storedToolRule(toolName: String): String? = storedToolRules()[toolName]

    // ---- Approval mode + glob permission rules (Phase 7, Step B2) ----

    /**
     * The approval mode (see [ApprovalMode]): "ask_all" (the default —
     * behaves exactly like v1), "ask_writes", "auto_edits", "auto_all".
     * Anything else stored reads back as "ask_all", so a corrupt entry
     * can never silently auto-approve tools.
     */
    val approvalMode: Flow<String> = context.dataStore.data.map { prefs ->
        when (prefs[APPROVAL_MODE]) {
            "ask_writes" -> "ask_writes"
            "auto_edits" -> "auto_edits"
            "auto_all" -> "auto_all"
            else -> "ask_all"
        }
    }

    /** Set the approval mode; any value other than the three named modes means AskAll. */
    suspend fun setApprovalMode(mode: String) {
        val value = when (mode) {
            "ask_writes", "auto_edits", "auto_all" -> mode
            else -> "ask_all"
        }
        context.dataStore.edit { it[APPROVAL_MODE] = value }
    }

    /** Approval mode as the enum, for [com.inyogeshwar.codesathi.agent.PermissionEngine]. */
    suspend fun approvalModeEnum(): ApprovalMode = when (approvalMode.first()) {
        "ask_writes" -> ApprovalMode.ASK_WRITES
        "auto_edits" -> ApprovalMode.AUTO_EDITS
        "auto_all" -> ApprovalMode.AUTO_ALL
        else -> ApprovalMode.ASK_ALL
    }

    /**
     * Ordered glob permission rules (first match wins). Rules hold no
     * secrets — tool names and path/command patterns only — so plain
     * DataStore JSON is fine. A rule whose action is not allow / ask /
     * deny, or whose tool is blank, is dropped on read; a blank pattern
     * reads as "*" (every target).
     */
    val permissionRules: Flow<List<PermissionRule>> = context.dataStore.data.map { prefs ->
        val raw = prefs[PERMISSION_RULES] ?: return@map emptyList()
        try {
            Json.decodeFromString<List<PermissionRule>>(raw)
                .filter { it.tool.isNotBlank() && it.action in setOf("allow", "ask", "deny") }
                .map { if (it.pattern.isBlank()) it.copy(pattern = "*") else it }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun addPermissionRule(rule: PermissionRule) {
        val current = permissionRules.first()
        if (current.any { it.id == rule.id }) return
        val cleaned = rule.copy(
            tool = rule.tool.trim(),
            pattern = rule.pattern.trim().ifBlank { "*" }
        )
        if (cleaned.tool.isBlank() || cleaned.action !in setOf("allow", "ask", "deny")) return
        context.dataStore.edit {
            it[PERMISSION_RULES] = Json.encodeToString<List<PermissionRule>>(current + cleaned)
        }
    }

    suspend fun removePermissionRule(ruleId: String) {
        val current = permissionRules.first().filterNot { it.id == ruleId }
        context.dataStore.edit { it[PERMISSION_RULES] = Json.encodeToString<List<PermissionRule>>(current) }
    }

    /** Move one rule from [fromIndex] to [toIndex]; out-of-range moves are ignored. */
    suspend fun movePermissionRule(fromIndex: Int, toIndex: Int) {
        val current = permissionRules.first().toMutableList()
        if (fromIndex !in current.indices || toIndex !in current.indices) return
        val item = current.removeAt(fromIndex)
        current.add(toIndex, item)
        context.dataStore.edit { it[PERMISSION_RULES] = Json.encodeToString<List<PermissionRule>>(current) }
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

    /** Remove every MCP server a plugin contributed (on uninstall). */
    suspend fun removeMcpServersForPlugin(pluginId: String) {
        val current = mcpServers.first().filterNot { it.pluginId == pluginId }
        context.dataStore.edit { it[MCP_SERVERS] = Json.encodeToString<List<McpServer>>(current) }
    }

    /** Enable / disable every MCP server a plugin contributed, together. */
    suspend fun setMcpServersEnabledForPlugin(pluginId: String, enabled: Boolean) {
        val current = mcpServers.first().map {
            if (it.pluginId == pluginId) it.copy(enabled = enabled) else it
        }
        context.dataStore.edit { it[MCP_SERVERS] = Json.encodeToString<List<McpServer>>(current) }
    }

    // ---- Plugins (Phase 7, Step B4) ----

    /** Installed plugins, from DataStore JSON. A plugin record holds
     *  no code and no secrets — only names and what it contributed. */
    val plugins: Flow<List<PluginRecord>> = context.dataStore.data.map { prefs ->
        val raw = prefs[PLUGINS] ?: return@map emptyList()
        try {
            Json.decodeFromString<List<PluginRecord>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Add (or replace) a plugin record; a record with the same id replaces the old one. */
    suspend fun addPlugin(plugin: PluginRecord) {
        val current = plugins.first().filterNot { it.id == plugin.id } + plugin
        context.dataStore.edit { it[PLUGINS] = Json.encodeToString<List<PluginRecord>>(current) }
    }

    suspend fun removePlugin(pluginId: String) {
        val current = plugins.first().filterNot { it.id == pluginId }
        context.dataStore.edit { it[PLUGINS] = Json.encodeToString<List<PluginRecord>>(current) }
    }

    suspend fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        val current = plugins.first().map {
            if (it.id == pluginId) it.copy(enabled = enabled) else it
        }
        context.dataStore.edit { it[PLUGINS] = Json.encodeToString<List<PluginRecord>>(current) }
    }

    // ---- Termux backend for the agent's run tool (Phase 7, Step B4) ----

    /** True when the agent's `run` tool should execute in the user's
     *  Termux instead of the in-app shell. Off by default. */
    val termuxEnabled: Flow<Boolean> = context.dataStore.data.map { it[TERMUX_ENABLED] ?: false }

    suspend fun setTermuxEnabled(enabled: Boolean) {
        context.dataStore.edit { it[TERMUX_ENABLED] = enabled }
    }

    // ---- Custom commands (Phase 3, Step 1) ----

    val customCommands: Flow<List<CustomCommand>> = context.dataStore.data.map { prefs ->
        val raw = prefs[COMMANDS] ?: return@map emptyList()
        try {
            Json.decodeFromString<List<CustomCommand>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Seed the two built-in example commands once. Never overwrites a
     * list the user already has — if commands exist, this only marks
     * the seeding done. The user can edit or delete both examples.
     */
    suspend fun ensureDefaultCommands() {
        val prefs = context.dataStore.data.first()
        if (prefs[COMMAND_DEFAULTS_SEEDED] == true) return
        val existing = try {
            prefs[COMMANDS]?.let { Json.decodeFromString<List<CustomCommand>>(it) }.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        context.dataStore.edit {
            if (existing.isEmpty()) {
                it[COMMANDS] = Json.encodeToString<List<CustomCommand>>(
                    listOf(
                        CustomCommand(
                            id = "review-code",
                            name = "review-code",
                            prompt = "Review the code in this project carefully. Look for bugs, " +
                                "security problems, and places where the code is hard to read or " +
                                "maintain. For each problem, name the file, explain what is wrong " +
                                "in plain words, and suggest a concrete fix. End with a short list " +
                                "of the most important fixes first. If I named a specific file or " +
                                "area, focus the review on that: {input}",
                            description = "Review the project code for bugs, security issues and readability.",
                            enabled = true
                        ),
                        CustomCommand(
                            id = "write-tests",
                            name = "write-tests",
                            prompt = "Write tests for the code I mention. Use the testing style " +
                                "already used in this project if there is one; otherwise use the " +
                                "standard test setup for the language. Cover the main behaviour and " +
                                "the edge cases, and keep each test short and focused on one thing. " +
                                "If I did not name any code, pick the most important untested part " +
                                "of the project and say which part you chose. " +
                                "The code or area to test: {input}",
                            description = "Write tests for the code you name.",
                            enabled = true
                        )
                    )
                )
            }
            it[COMMAND_DEFAULTS_SEEDED] = true
        }
    }

    /** Add a command; a command with the same id replaces the old one. */
    suspend fun addCustomCommand(command: CustomCommand) {
        val current = customCommands.first().filterNot { it.id == command.id } + command
        context.dataStore.edit { it[COMMANDS] = Json.encodeToString<List<CustomCommand>>(current) }
    }

    /** Replace a command by id (used for edits); no-op when the id is unknown. */
    suspend fun updateCustomCommand(command: CustomCommand) {
        val current = customCommands.first()
        if (current.none { it.id == command.id }) return
        val updated = current.map { if (it.id == command.id) command else it }
        context.dataStore.edit { it[COMMANDS] = Json.encodeToString<List<CustomCommand>>(updated) }
    }

    suspend fun removeCustomCommand(commandId: String) {
        val current = customCommands.first().filterNot { it.id == commandId }
        context.dataStore.edit { it[COMMANDS] = Json.encodeToString<List<CustomCommand>>(current) }
    }

    suspend fun setCustomCommandEnabled(commandId: String, enabled: Boolean) {
        val current = customCommands.first().map {
            if (it.id == commandId) it.copy(enabled = enabled) else it
        }
        context.dataStore.edit { it[COMMANDS] = Json.encodeToString<List<CustomCommand>>(current) }
    }

    /** Remove every slash command a plugin contributed (on uninstall). */
    suspend fun removeCustomCommandsForPlugin(pluginId: String) {
        val current = customCommands.first().filterNot { it.pluginId == pluginId }
        context.dataStore.edit { it[COMMANDS] = Json.encodeToString<List<CustomCommand>>(current) }
    }

    /** Enable / disable every slash command a plugin contributed, together. */
    suspend fun setCustomCommandsEnabledForPlugin(pluginId: String, enabled: Boolean) {
        val current = customCommands.first().map {
            if (it.pluginId == pluginId) it.copy(enabled = enabled) else it
        }
        context.dataStore.edit { it[COMMANDS] = Json.encodeToString<List<CustomCommand>>(current) }
    }

    // ---- Reasoning effort per model (Phase 3, Step 2) ----

    /**
     * "providerId/modelId" → "low" | "medium" | "high", same JSON-map
     * pattern as [toolRules] / [skillEnabled]. No entry means "default":
     * the app sends no effort field at all for that model. Only values
     * in [VALID_EFFORTS] are ever stored; anything else is dropped on
     * read, so a corrupt entry can never reach a provider request.
     */
    val reasoningEfforts: Flow<Map<String, String>> = context.dataStore.data.map { prefs ->
        val raw = prefs[REASONING_EFFORT] ?: return@map emptyMap()
        try {
            Json.decodeFromString<Map<String, String>>(raw)
                .filterValues { it in VALID_EFFORTS }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** The stored effort for one model, or null when it is unset (default). */
    suspend fun reasoningEffort(providerId: String, modelId: String): String? =
        reasoningEfforts.first()["$providerId/$modelId"]

    /**
     * Set the effort for one model. [effort] is "low" | "medium" |
     * "high", or null to go back to "default" (the entry is removed, so
     * no effort is sent). Any other value is ignored.
     */
    suspend fun setReasoningEffort(providerId: String, modelId: String, effort: String?) {
        val current = reasoningEfforts.first().toMutableMap()
        val key = "$providerId/$modelId"
        if (effort == null) {
            current.remove(key)
        } else if (effort in VALID_EFFORTS) {
            current[key] = effort
        } else {
            return
        }
        context.dataStore.edit { it[REASONING_EFFORT] = Json.encodeToString<Map<String, String>>(current) }
    }

    // ---- Azure OpenAI resource name (Phase 4) ----

    /**
     * Azure OpenAI resource name per provider id ("azure-openai" → "myresource").
     * The resource name is NOT a secret — it is part of the URL — so plain
     * DataStore storage is fine. Only values matching Azure's naming rules
     * are ever stored; anything else is dropped on read, so a corrupt entry
     * can never reach a request URL.
     */
    val azureResourceNames: Flow<Map<String, String>> = context.dataStore.data.map { prefs ->
        val raw = prefs[AZURE_RESOURCE_NAMES] ?: return@map emptyMap()
        try {
            Json.decodeFromString<Map<String, String>>(raw)
                .filterValues { isValidAzureResourceName(it) }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** The stored Azure resource name for one provider, or null when unset. */
    suspend fun azureResourceName(providerId: String): String? =
        azureResourceNames.first()[providerId]

    // ---- On-device .task model file (Phase 6, Step 5) ----

    /**
     * The .task model file the user picked: its SAF URI string plus its
     * display name. The URI is NOT a secret — it is a file location,
     * like the project folder URI — so plain DataStore storage is fine.
     * The persistable read grant is taken when the file is picked (see
     * AppViewModel.setLocalModelFile); the engine copies the file into
     * app-private storage from this URI (see LocalLlmClient). No model
     * file is ever bundled with the app.
     */
    val localModelUri: Flow<String?> = context.dataStore.data.map { it[LOCAL_MODEL_URI] }
    val localModelName: Flow<String?> = context.dataStore.data.map { it[LOCAL_MODEL_NAME] }

    /** Remember a picked .task file (URI + display name). */
    suspend fun setLocalModel(uri: String, name: String) {
        context.dataStore.edit {
            it[LOCAL_MODEL_URI] = uri
            it[LOCAL_MODEL_NAME] = name
        }
    }

    /** Forget the picked file (the cached copy is dropped by LocalLlmClient). */
    suspend fun clearLocalModel() {
        context.dataStore.edit {
            it.remove(LOCAL_MODEL_URI)
            it.remove(LOCAL_MODEL_NAME)
        }
    }

    /**
     * Set the Azure resource name for one provider. Returns false when the
     * name does not match Azure's naming rules (nothing is stored then).
     * [name] is trimmed and lowercased first — Azure resource names are
     * lowercase-only.
     */
    suspend fun setAzureResourceName(providerId: String, name: String): Boolean {
        val value = name.trim().lowercase()
        if (!isValidAzureResourceName(value)) return false
        val current = azureResourceNames.first().toMutableMap()
        current[providerId] = value
        context.dataStore.edit { it[AZURE_RESOURCE_NAMES] = Json.encodeToString<Map<String, String>>(current) }
        return true
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
     * read — that is how a cooldown expires (rate 60 s, daily quota 1 h,
     * out of credit 6 h, server 20 s, network 15 s).
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

    /** Drop one key's exhaustion flag (it may be tried again at once). */
    suspend fun clearExhausted(key: String) {
        val current = exhaustedMap().toMutableMap()
        if (current.remove(key) == null) return
        context.dataStore.edit { it[EXHAUSTED] = Json.encodeToString(current) }
    }

    /** Drop every exhaustion flag (the "Reset limit flags" action). */
    suspend fun clearAllExhausted() {
        context.dataStore.edit { it.remove(EXHAUSTED) }
    }

    // ---- Routing mode + per-key health ledger (Phase 7, Step B1) ----

    /**
     * "pinned" (stay on the session's model, rotate only its keys) or
     * "fallback" (session model, then the fallback chain). Anything
     * else stored reads back as "fallback" — the default.
     */
    val routingMode: Flow<String> = context.dataStore.data.map { prefs ->
        when (prefs[ROUTING_MODE]) {
            "pinned" -> "pinned"
            else -> "fallback"
        }
    }

    /** Set the routing mode; any value other than "pinned" means fallback. */
    suspend fun setRoutingMode(mode: String) {
        context.dataStore.edit { it[ROUTING_MODE] = if (mode == "pinned") "pinned" else "fallback" }
    }

    /** Per-key health, keyed by "providerId|modelId|keyId". */
    val keyHealth: Flow<Map<String, KeyHealth>> = context.dataStore.data.map { prefs ->
        val raw = prefs[KEY_HEALTH] ?: return@map emptyMap()
        try {
            Json.decodeFromString<Map<String, KeyHealth>>(raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    suspend fun keyHealthMap(): Map<String, KeyHealth> = keyHealth.first()

    private suspend fun updateKeyHealth(key: String, update: (KeyHealth) -> KeyHealth) {
        val current = keyHealthMap().toMutableMap()
        current[key] = update(current[key] ?: KeyHealth())
        context.dataStore.edit { it[KEY_HEALTH] = Json.encodeToString(current) }
    }

    /**
     * Record a failed attempt for one key: one more failure, the
     * status for the failure kind, the error label, and the cooldown
     * (only when the failure carries one — an auth failure has none
     * and instead marks the key invalid, so it stays skipped until
     * reset). The old cooldown is kept when the new failure has none.
     */
    suspend fun recordKeyFailure(key: String, failure: com.inyogeshwar.codesathi.agent.Failure) {
        val now = System.currentTimeMillis()
        val status = when (failure.kind) {
            com.inyogeshwar.codesathi.agent.FailureKind.AUTH -> KeyHealth.STATUS_INVALID
            com.inyogeshwar.codesathi.agent.FailureKind.NO_CREDIT -> KeyHealth.STATUS_NO_CREDIT
            com.inyogeshwar.codesathi.agent.FailureKind.RATE_LIMIT,
            com.inyogeshwar.codesathi.agent.FailureKind.QUOTA -> KeyHealth.STATUS_RATE_LIMITED
            else -> KeyHealth.STATUS_ERROR
        }
        val label = failure.label + (failure.code?.let { " ($it)" } ?: "")
        updateKeyHealth(key) { old ->
            old.copy(
                failures = old.failures + 1,
                status = status,
                lastError = label,
                cooldownUntil = if (failure.cooldownMs > 0) now + failure.cooldownMs else old.cooldownUntil,
                lastUsedAt = now
            )
        }
    }

    /** Record a successful attempt: counts + latency, status ok, cooldown cleared. */
    suspend fun recordKeySuccess(key: String, latencyMs: Long, inputTokens: Long, outputTokens: Long) {
        val now = System.currentTimeMillis()
        updateKeyHealth(key) { old ->
            old.copy(
                requests = old.requests + 1,
                status = KeyHealth.STATUS_OK,
                lastError = null,
                cooldownUntil = 0,
                lastUsedAt = now,
                inputTokens = old.inputTokens + inputTokens,
                outputTokens = old.outputTokens + outputTokens,
                lastLatencyMs = latencyMs
            )
        }
    }

    /** Lift one key's block (cooldown + invalid flag); counts are kept. */
    suspend fun resetKeyHealth(key: String) {
        updateKeyHealth(key) { old ->
            old.copy(status = KeyHealth.STATUS_UNKNOWN, lastError = null, cooldownUntil = 0)
        }
    }

    /** Lift every key's block (the "Reset limit flags" action); counts are kept. */
    suspend fun resetAllKeyHealth() {
        val current = keyHealthMap().mapValues { (_, old) ->
            old.copy(status = KeyHealth.STATUS_UNKNOWN, lastError = null, cooldownUntil = 0)
        }
        context.dataStore.edit { it[KEY_HEALTH] = Json.encodeToString(current) }
    }
}
