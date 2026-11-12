package com.inyogeshwar.codesathi.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.json.Json
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
