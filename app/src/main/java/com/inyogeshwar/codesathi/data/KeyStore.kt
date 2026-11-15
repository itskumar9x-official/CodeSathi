package com.inyogeshwar.codesathi.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/**
 * API keys are stored ONLY in EncryptedSharedPreferences.
 * Never in plain SharedPreferences, never in providers.json, never in Room.
 * (The app we learned from stored keys in plain text — CodeSathi does better.)
 *
 * Phase 2, Step 1: a provider can hold SEVERAL keys. The keys for one
 * provider live as one encrypted JSON list (`api_keys.<providerId>`), so
 * no key ever sits in plain text. Each key has a stable id — the first 16
 * hex chars of its SHA-256 — so the fallback engine can mark one key
 * exhausted without ever storing or logging the key itself. The UI only
 * ever sees masked keys (first 3 + last 4 chars).
 */
class KeyStore(context: Context) {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "codesathi_api_keys",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** One stored key: its stable id plus the value (value never leaves this class). */
    data class StoredKey(val id: String, val value: String)

    /**
     * AWS credentials for Bedrock (Phase 4): an access key id, a secret
     * access key and a region. Only ever stored here, encrypted — never
     * in DataStore / Room / providers.json / a URL / a log. The access
     * key id may be shown masked (like other keys); the secret is never
     * displayed anywhere.
     */
    data class AwsCredentials(
        val accessKeyId: String,
        val secretAccessKey: String,
        val region: String
    )

    /** Masked view for the UI — the value is never shown. */
    data class KeyDisplay(val id: String, val masked: String)

    /** First key for a provider (legacy single-key entry counts as one key). */
    fun getKey(providerId: String): String? = getKeyValues(providerId).firstOrNull()

    fun hasKey(providerId: String): Boolean = getKey(providerId) != null

    /** Backwards-compatible add: v1's "save key" now adds to the key list. */
    fun saveKey(providerId: String, apiKey: String) {
        addKey(providerId, apiKey)
    }

    /** All key values for a provider, in the order they were added. */
    fun getKeyValues(providerId: String): List<String> {
        val stored = readList(providerId)
        if (stored.isNotEmpty()) return stored
        // Legacy single-key entry (v1) — read as a one-key list.
        return prefs.getString(keyFor(providerId), null)
            ?.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
    }

    /** Stable ids for a provider's keys, in the same order as [getKeyValues]. */
    fun getKeyIds(providerId: String): List<String> = getKeyValues(providerId).map { keyId(it) }

    /** Masked keys for the UI — values never leave this class. */
    fun listKeys(providerId: String): List<KeyDisplay> =
        getKeyValues(providerId).map { KeyDisplay(keyId(it), maskKey(it)) }

    /** Provider ids that have at least one key (legacy or multi-key). */
    fun providerIdsWithKeys(): List<String> {
        val ids = mutableSetOf<String>()
        prefs.all.keys.forEach { k ->
            when {
                k.startsWith("api_keys.") -> ids += k.removePrefix("api_keys.")
                k.startsWith("api_key.") -> ids += k.removePrefix("api_key.")
            }
        }
        return ids.sorted()
    }

    /**
     * Add a key; returns its stable id, or null when the input is blank.
     * A value already stored is not duplicated.
     */
    fun addKey(providerId: String, apiKey: String): String? {
        val value = apiKey.trim()
        if (value.isEmpty()) return null
        val values = getKeyValues(providerId).toMutableList()
        if (value !in values) values += value
        writeList(providerId, values)
        // The legacy single-key entry is superseded by the list.
        prefs.edit().remove(keyFor(providerId)).apply()
        return keyId(value)
    }

    /**
     * Remove one key by its stable id. When nothing is left, both the list
     * and any legacy single-key entry are removed — otherwise the legacy
     * entry would resurface as a one-key list on the next read.
     */
    fun removeKey(providerId: String, keyId: String) {
        val values = getKeyValues(providerId).filter { keyId(it) != keyId }
        if (values.isEmpty()) {
            prefs.edit().remove(listKeyFor(providerId)).remove(keyFor(providerId)).apply()
        } else {
            writeList(providerId, values)
            prefs.edit().remove(keyFor(providerId)).apply()
        }
    }

    /** Remove every key for a provider. */
    fun removeAllKeys(providerId: String) {
        prefs.edit().remove(listKeyFor(providerId)).remove(keyFor(providerId)).apply()
    }

    // ---- Remote-mode password (Phase 2, Step 7) ----

    /**
     * The password another device must send as `Authorization: Bearer …`
     * to use the remote server. Stored encrypted here only, never in
     * DataStore / Room / logs / a URL. At least 4 characters.
     */
    fun saveRemotePassword(password: String): Boolean {
        val value = password.trim()
        if (value.length < 4) return false
        prefs.edit().putString(REMOTE_PASSWORD_KEY, value).apply()
        return true
    }

    fun getRemotePassword(): String? =
        prefs.getString(REMOTE_PASSWORD_KEY, null)?.takeIf { it.isNotBlank() }

    fun hasRemotePassword(): Boolean = getRemotePassword() != null

    fun removeRemotePassword() {
        prefs.edit().remove(REMOTE_PASSWORD_KEY).apply()
    }

    /** Masked password for the UI — the value never leaves this class. */
    fun remotePasswordMasked(): String? =
        getRemotePassword()?.let { maskKey(it) }

    // ---- AWS Bedrock credentials (Phase 4) ----

    /**
     * Save the AWS credential set for Bedrock. Returns false when the
     * access key id or the secret is blank (nothing is stored). A blank
     * region means the default, us-east-1. The region is not validated
     * beyond that — a wrong region surfaces as a Bedrock error, and new
     * AWS regions appear over time.
     */
    fun saveAwsCredential(accessKeyId: String, secretAccessKey: String, region: String): Boolean {
        val id = accessKeyId.trim()
        val secret = secretAccessKey.trim()
        val reg = region.trim().ifBlank { DEFAULT_AWS_REGION }
        if (id.isEmpty() || secret.isEmpty()) return false
        prefs.edit()
            .putString(AWS_ACCESS_KEY_ID_KEY, id)
            .putString(AWS_SECRET_ACCESS_KEY_KEY, secret)
            .putString(AWS_REGION_KEY, reg)
            .apply()
        return true
    }

    /** The full AWS credential set, or null when any part is missing. */
    fun awsCredentials(): AwsCredentials? {
        val id = getAwsAccessKeyId() ?: return null
        val secret = getAwsSecretAccessKey() ?: return null
        return AwsCredentials(id, secret, getAwsRegion())
    }

    fun getAwsAccessKeyId(): String? =
        prefs.getString(AWS_ACCESS_KEY_ID_KEY, null)?.takeIf { it.isNotBlank() }

    fun getAwsSecretAccessKey(): String? =
        prefs.getString(AWS_SECRET_ACCESS_KEY_KEY, null)?.takeIf { it.isNotBlank() }

    /** Stored region, or the default us-east-1 when none was saved. */
    fun getAwsRegion(): String =
        prefs.getString(AWS_REGION_KEY, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_AWS_REGION

    fun hasAwsCredential(): Boolean = awsCredentials() != null

    fun removeAwsCredential() {
        prefs.edit()
            .remove(AWS_ACCESS_KEY_ID_KEY)
            .remove(AWS_SECRET_ACCESS_KEY_KEY)
            .remove(AWS_REGION_KEY)
            .apply()
    }

    /** Masked access key id for the UI — the secret is never displayed. */
    fun awsAccessKeyIdMasked(): String? =
        getAwsAccessKeyId()?.let { maskKey(it) }

    private companion object {
        const val REMOTE_PASSWORD_KEY = "remote_password"
        const val GIT_USERNAME_KEY = "git_username"
        const val GIT_TOKEN_KEY = "git_token"
        const val AWS_ACCESS_KEY_ID_KEY = "aws_access_key_id"
        const val AWS_SECRET_ACCESS_KEY_KEY = "aws_secret_access_key"
        const val AWS_REGION_KEY = "aws_region"
        /** The region used when the user leaves the region field blank. */
        const val DEFAULT_AWS_REGION = "us-east-1"
    }

    // ---- Git remote credential (Phase 2, Step 8) ----

    /**
     * Username + token/password for Git push / pull / private clones over
     * HTTPS. Stored encrypted here only, never in DataStore / Room / logs
     * / a URL. Used as HTTP Basic auth (GitHub: username + personal
     * access token). No SSH keys — SSH remotes are not supported.
     */
    fun saveGitCredential(username: String, token: String): Boolean {
        val user = username.trim()
        val value = token.trim()
        if (user.isEmpty() || value.isEmpty()) return false
        prefs.edit().putString(GIT_USERNAME_KEY, user).putString(GIT_TOKEN_KEY, value).apply()
        return true
    }

    fun getGitUsername(): String? =
        prefs.getString(GIT_USERNAME_KEY, null)?.takeIf { it.isNotBlank() }

    fun getGitToken(): String? =
        prefs.getString(GIT_TOKEN_KEY, null)?.takeIf { it.isNotBlank() }

    fun hasGitCredential(): Boolean = getGitToken() != null

    fun removeGitCredential() {
        prefs.edit().remove(GIT_USERNAME_KEY).remove(GIT_TOKEN_KEY).apply()
    }

    /** Masked token for the UI — the value never leaves this class. */
    fun gitTokenMasked(): String? =
        getGitToken()?.let { maskKey(it) }

    // ---- MCP server auth tokens (Phase 2, Step 3) ----

    /** One token per server, under "mcp_token.<serverId>". Same rule as
     *  provider keys: encrypted here only, never in DataStore / Room / logs. */
    fun saveMcpToken(serverId: String, token: String): Boolean {
        val value = token.trim()
        if (value.isEmpty()) return false
        prefs.edit().putString(mcpTokenKeyFor(serverId), value).apply()
        return true
    }

    fun getMcpToken(serverId: String): String? =
        prefs.getString(mcpTokenKeyFor(serverId), null)?.takeIf { it.isNotBlank() }

    fun hasMcpToken(serverId: String): Boolean = getMcpToken(serverId) != null

    fun removeMcpToken(serverId: String) {
        prefs.edit().remove(mcpTokenKeyFor(serverId)).apply()
    }

    /** Masked token for the UI — the value never leaves this class. */
    fun mcpTokenMasked(serverId: String): String? =
        getMcpToken(serverId)?.let { maskKey(it) }

    private fun mcpTokenKeyFor(serverId: String) = "mcp_token.$serverId"

    // ---- Encrypted-secrets export / import (Phase 7, Step B3) ----

    /**
     * Every secret this device holds, as one JSON object for the
     * encrypted backup's `secrets.enc` entry (see EncryptedBackup.kt).
     * Keys are the same names the vault uses internally, so an import
     * can put them back in the same place:
     * - `api_keys.<providerId>`: the provider's key list (all keys).
     * - `aws`: the Bedrock credential (access key id, secret, region).
     * - `remote_password`: the Remote-mode password.
     * - `git`: the Git username + token.
     * - `mcp_token.<serverId>`: one entry per server id passed in
     *   (only servers that actually have a token are included).
     *
     * The JSON itself is NOT encrypted here — the caller encrypts it
     * with [BackupCrypto] before it is written anywhere, and this
     * value must never be logged or written to a plain file. The
     * count is the number of top-level entries (one per provider /
     * credential), for the export result message.
     */
    data class SecretsExport(val json: String, val entries: Int)

    fun exportSecretsJson(mcpServerIds: List<String>): SecretsExport {
        val entries = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        providerIdsWithKeys().forEach { providerId ->
            val values = getKeyValues(providerId)
            if (values.isNotEmpty()) {
                entries["api_keys.$providerId"] = kotlinx.serialization.json.JsonArray(
                    values.map { kotlinx.serialization.json.JsonPrimitive(it) }
                )
            }
        }
        awsCredentials()?.let { aws ->
            entries["aws"] = kotlinx.serialization.json.buildJsonObject {
                put("accessKeyId", aws.accessKeyId)
                put("secretAccessKey", aws.secretAccessKey)
                put("region", aws.region)
            }
        }
        getRemotePassword()?.let { entries["remote_password"] = kotlinx.serialization.json.JsonPrimitive(it) }
        val gitUser = getGitUsername()
        val gitToken = getGitToken()
        if (gitUser != null && gitToken != null) {
            entries["git"] = kotlinx.serialization.json.buildJsonObject {
                put("username", gitUser)
                put("token", gitToken)
            }
        }
        mcpServerIds.forEach { serverId ->
            getMcpToken(serverId)?.let { token ->
                entries["mcp_token.$serverId"] = kotlinx.serialization.json.JsonPrimitive(token)
            }
        }
        val obj = kotlinx.serialization.json.JsonObject(entries)
        return SecretsExport(json.encodeToString(obj), obj.size)
    }

    /**
     * Restore secrets from an exported (and already decrypted) JSON
     * object. Returns how many top-level entries were restored.
     * NEVER overwrites: an entry is restored only when that
     * credential is currently absent on this device — a provider
     * that already has keys keeps its keys, an AWS credential that
     * is already set is kept, and so on. A malformed entry is
     * skipped, never applied half-way, and a malformed file restores
     * nothing. The passphrase check already happened before this is
     * called, so a wrong passphrase never reaches here.
     */
    fun importSecretsJson(jsonText: String): Int {
        val obj = try {
            json.parseToJsonElement(jsonText).jsonObject
        } catch (_: Exception) {
            return 0
        }
        var restored = 0
        obj.forEach { (key, value) ->
            when {
                key.startsWith("api_keys.") -> {
                    val providerId = key.removePrefix("api_keys.")
                    if (providerId.isBlank() || getKeyValues(providerId).isNotEmpty()) return@forEach
                    val values = try {
                        value.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
                            .filter { it.isNotBlank() }
                    } catch (_: Exception) {
                        return@forEach
                    }
                    if (values.isNotEmpty()) {
                        writeList(providerId, values)
                        prefs.edit().remove(keyFor(providerId)).apply()
                        restored++
                    }
                }
                key == "aws" -> {
                    if (hasAwsCredential()) return@forEach
                    val o = try { value.jsonObject } catch (_: Exception) { return@forEach }
                    val id = o["accessKeyId"]?.jsonPrimitive?.contentOrNull
                    val secret = o["secretAccessKey"]?.jsonPrimitive?.contentOrNull
                    val region = o["region"]?.jsonPrimitive?.contentOrNull
                    if (!id.isNullOrBlank() && !secret.isNullOrBlank() && !region.isNullOrBlank()) {
                        if (saveAwsCredential(id, secret, region)) restored++
                    }
                }
                key == "remote_password" -> {
                    if (hasRemotePassword()) return@forEach
                    val password = value.jsonPrimitive.contentOrNull
                    if (!password.isNullOrBlank() && saveRemotePassword(password)) restored++
                }
                key == "git" -> {
                    if (hasGitCredential()) return@forEach
                    val o = try { value.jsonObject } catch (_: Exception) { return@forEach }
                    val user = o["username"]?.jsonPrimitive?.contentOrNull
                    val token = o["token"]?.jsonPrimitive?.contentOrNull
                    if (!user.isNullOrBlank() && !token.isNullOrBlank()) {
                        if (saveGitCredential(user, token)) restored++
                    }
                }
                key.startsWith("mcp_token.") -> {
                    val serverId = key.removePrefix("mcp_token.")
                    if (serverId.isBlank() || getMcpToken(serverId) != null) return@forEach
                    val token = value.jsonPrimitive.contentOrNull
                    if (!token.isNullOrBlank() && saveMcpToken(serverId, token)) restored++
                }
                else -> Unit
            }
        }
        return restored
    }

    private fun readList(providerId: String): List<String> {
        val raw = prefs.getString(listKeyFor(providerId), null) ?: return emptyList()
        return try {
            json.decodeFromString<List<String>>(raw).filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeList(providerId: String, values: List<String>) {
        prefs.edit().putString(listKeyFor(providerId), json.encodeToString(values)).apply()
    }

    /**
     * Stable id for a key value: the first 16 hex chars of its SHA-256.
     * Safe to store in DataStore / Room and to show in logs — it cannot
     * be turned back into the key.
     */
    fun keyId(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    /** Masked form for display: first 3 chars + last 4, never the full key. */
    fun maskKey(value: String): String =
        if (value.length <= 7) value.take(3) + "…"
        else value.take(3) + "…" + value.takeLast(4)

    private fun keyFor(providerId: String) = "api_key.$providerId"
    private fun listKeyFor(providerId: String) = "api_keys.$providerId"
}
