package com.inyogeshwar.codesathi.agent

import com.inyogeshwar.codesathi.data.KeyStore
import com.inyogeshwar.codesathi.data.PreferencesStore
import com.inyogeshwar.codesathi.data.Provider
import com.inyogeshwar.codesathi.data.ProviderRepository
import kotlinx.coroutines.flow.first
import java.util.Calendar

/**
 * Why a key/model stopped working.
 * QUOTA — a long limit: HTTP 402, or a response body that mentions quota.
 * RATE — a short rate limit: a plain HTTP 429 with no quota wording.
 * Both mark the key exhausted until next midnight (see [FallbackEngine]);
 * the kind only decides what the switch notice says.
 */
enum class LimitKind { QUOTA, RATE }

/** Classify an HTTP failure as a limit error, or null when it is not one. */
fun classifyLimitError(httpCode: Int?, body: String?): LimitKind? {
    if (httpCode == 402) return LimitKind.QUOTA
    if (body != null && body.contains("quota", ignoreCase = true)) return LimitKind.QUOTA
    if (httpCode == 429) return LimitKind.RATE
    return null
}

/** One concrete attempt: a provider + model + one of its keys. */
data class FallbackAttempt(
    val provider: Provider,
    val modelId: String,
    /** Null for local providers, which need no key. */
    val apiKey: String?,
    /** Stable id of the key used (SHA-256 prefix), null when keyless. */
    val keyId: String?
) {
    /** "providerId/modelId" — the key used for favourites and badges. */
    val modelKey: String get() = "${provider.id}/$modelId"

    /** Key in the exhaustion map, null for keyless/local attempts. */
    fun exhaustionKey(): String? = keyId?.let { "${provider.id}|$modelId|$it" }
}

/**
 * Multi-key + single-provider fallback / auto-switch (Phase 2, Step 1).
 *
 * Order for one turn, exactly as the user asked:
 *  1. Every other non-exhausted key of the SAME provider + model, in the
 *     order the keys were added.
 *  2. Then the user's fallback chain, in order — each entry with its own
 *     keys, skipping entries already tried. Because a chain entry may name
 *     another model of the SAME provider, this is also how single-provider
 *     fallback works (e.g. TokenHarbor paid claude-sonnet-5.5 → free
 *     deepseek-v4.1-flash:free): the user puts the free model in the
 *     chain, and the engine walks to it when the paid model is exhausted.
 *
 * A key marked exhausted is skipped until the start of the next local
 * day; the flag lives in DataStore (never with the key itself) and is
 * pruned on read by [PreferencesStore.exhaustedMap].
 *
 * The engine never sees a full key except to hand it to the LLM call —
 * exhaustion is tracked by key id (a hash prefix), never by value.
 */
class FallbackEngine(
    private val providerRepository: ProviderRepository,
    private val keys: KeyStore,
    private val prefs: PreferencesStore
) {

    /**
     * The ordered attempts for one turn, starting with the current
     * provider + model. Empty when nothing is usable: no key and no
     * fallback entry with a usable key.
     */
    suspend fun attemptsFor(provider: Provider, modelId: String): List<FallbackAttempt> {
        val covered = mutableSetOf<String>()
        val attempts = mutableListOf<FallbackAttempt>()
        attempts += attemptsForModel(provider, modelId, covered)
        for (entry in prefs.fallbackChain.first()) {
            val p = providerRepository.provider(entry.providerId) ?: continue
            attempts += attemptsForModel(p, entry.modelId, covered)
        }
        return attempts
    }

    /** Attempts for one (provider, model): one per non-exhausted key. */
    private suspend fun attemptsForModel(
        provider: Provider,
        modelId: String,
        covered: MutableSet<String>
    ): List<FallbackAttempt> {
        if (!covered.add("${provider.id}/$modelId")) return emptyList()
        if (provider.local) return listOf(FallbackAttempt(provider, modelId, null, null))
        if (provider.kind == "bedrock") {
            // Bedrock has one credential set (not a key list): a single
            // keyless attempt while the credential exists, none without
            // it. The synthetic key id "aws" lets exhaustion mark this
            // model until midnight like any other provider's key — the
            // attempt carries no key value, so nothing secret is stored
            // or logged with it.
            if (!keys.hasAwsCredential()) return emptyList()
            val exhaustedKey = "${provider.id}|$modelId|aws"
            if (prefs.exhaustedMap()[exhaustedKey] != null) return emptyList()
            return listOf(FallbackAttempt(provider, modelId, null, "aws"))
        }
        val exhausted = prefs.exhaustedMap()
        val ids = keys.getKeyIds(provider.id)
        val values = keys.getKeyValues(provider.id)
        return ids.zip(values)
            .filter { (id, _) -> exhausted["${provider.id}|$modelId|$id"] == null }
            .map { (id, value) -> FallbackAttempt(provider, modelId, value, id) }
    }

    /** Mark the key an attempt used as exhausted until next midnight. */
    suspend fun markExhausted(attempt: FallbackAttempt, kind: LimitKind) {
        val key = attempt.exhaustionKey() ?: return
        prefs.markExhausted(key, kind.name, nextMidnightMillis())
    }

    /**
     * "providerId/modelId" values whose every key is currently exhausted.
     * Used for the "Limit reached" badge on fallback entries.
     */
    suspend fun exhaustedModelKeys(): Set<String> {
        val map = prefs.exhaustedMap()
        if (map.isEmpty()) return emptySet()
        val out = mutableSetOf<String>()
        val byModel = map.keys.mapNotNull { raw ->
            val parts = raw.split("|")
            if (parts.size != 3) null else Triple(parts[0], parts[1], parts[2])
        }.groupBy({ "${it.first}/${it.second}" }, { it.third })
        for ((modelKey, exhaustedIds) in byModel) {
            val providerId = modelKey.substringBefore("/")
            // Bedrock's only "key id" is the synthetic "aws" (see
            // attemptsForModel); every other provider uses its real ids.
            val allIds = if (providerId == "bedrock") listOf("aws") else keys.getKeyIds(providerId)
            if (allIds.isNotEmpty() && exhaustedIds.toSet().containsAll(allIds.toSet())) {
                out += modelKey
            }
        }
        return out
    }

    companion object {
        /** Start of the next local day (midnight), in epoch millis. */
        fun nextMidnightMillis(now: Long = System.currentTimeMillis()): Long {
            val cal = Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return cal.timeInMillis
        }
    }
}
