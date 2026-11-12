package com.inyogeshwar.codesathi.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * API keys are stored ONLY in EncryptedSharedPreferences.
 * Never in plain SharedPreferences, never in providers.json, never in Room.
 * (The app we learned from stored keys in plain text — CodeSathi does better.)
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

    fun getKey(providerId: String): String? =
        prefs.getString(keyFor(providerId), null)?.takeIf { it.isNotBlank() }

    fun hasKey(providerId: String): Boolean = getKey(providerId) != null

    fun saveKey(providerId: String, apiKey: String) {
        prefs.edit().putString(keyFor(providerId), apiKey.trim()).apply()
    }

    fun removeKey(providerId: String) {
        prefs.edit().remove(keyFor(providerId)).apply()
    }

    private fun keyFor(providerId: String) = "api_key.$providerId"
}
