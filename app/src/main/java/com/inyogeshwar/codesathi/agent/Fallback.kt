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
 * Both used to mark the key exhausted until next midnight; since
 * Phase 7, Step B1 the full [Failure] carries a per-kind cooldown
 * instead (rate 60 s, daily quota 1 h, out of credit 6 h, server 20 s,
 * network 15 s) — see [FallbackEngine.markExhausted]. [LimitKind] is
 * kept for the switch notice, which only distinguishes quota vs rate.
 */
enum class LimitKind { QUOTA, RATE }

/** The [LimitKind] view of a full [Failure], or null when it is not a limit error. */
fun Failure.limitKind(): LimitKind? = when (kind) {
    FailureKind.QUOTA, FailureKind.NO_CREDIT -> LimitKind.QUOTA
    FailureKind.RATE_LIMIT -> LimitKind.RATE
    else -> null
}

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
 * A key marked exhausted is skipped until its failure's cooldown
 * ends (Phase 7, Step B1: rate 60 s, daily quota 1 h, out of credit
 * 6 h, server 20 s, network 15 s — NOT until midnight any more); a
 * key whose failure was an auth rejection is marked invalid in the
 * health ledger instead and stays skipped until the user resets it.
 * The flag lives in DataStore (never with the key itself) and is
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

    /**
     * The ordered attempts for ONE provider + model only (pinned
     * routing, Phase 7 Step B1): the session model's keys, in order,
     * with no fallback-chain step. The chain order is untouched —
     * pinned mode simply does not walk it.
     */
    suspend fun attemptsForSingle(provider: Provider, modelId: String): List<FallbackAttempt> =
        attemptsForModel(provider, modelId, mutableSetOf())

    /** True when one key is unusable right now: cooling down, exhausted, or invalid. */
    private fun isKeyBlocked(
        exhaustionKey: String,
        exhausted: Map<String, com.inyogeshwar.codesathi.data.ExhaustionInfo>,
        health: Map<String, com.inyogeshwar.codesathi.data.KeyHealth>,
        now: Long
    ): Boolean {
        if ((exhausted[exhaustionKey]?.untilMillis ?: 0L) > now) return true
        val entry = health[exhaustionKey] ?: return false
        if (entry.status == com.inyogeshwar.codesathi.data.KeyHealth.STATUS_INVALID) return true
        return entry.cooldownUntil > now
    }

    /** Attempts for one (provider, model): one per usable (non-blocked) key. */
    private suspend fun attemptsForModel(
        provider: Provider,
        modelId: String,
        covered: MutableSet<String>
    ): List<FallbackAttempt> {
        if (!covered.add("${provider.id}/$modelId")) return emptyList()
        if (provider.local) return listOf(FallbackAttempt(provider, modelId, null, null))
        val now = System.currentTimeMillis()
        val exhausted = prefs.exhaustedMap()
        val health = prefs.keyHealthMap()
        if (provider.kind == "bedrock") {
            // Bedrock has one credential set (not a key list): a single
            // keyless attempt while the credential exists, none without
            // it. The synthetic key id "aws" lets a throttled Bedrock
            // model cool down like any other provider's key — the
            // attempt carries no key value, so nothing secret is stored
            // or logged with it.
            if (!keys.hasAwsCredential()) return emptyList()
            val exhaustedKey = "${provider.id}|$modelId|aws"
            if (isKeyBlocked(exhaustedKey, exhausted, health, now)) return emptyList()
            return listOf(FallbackAttempt(provider, modelId, null, "aws"))
        }
        val ids = keys.getKeyIds(provider.id)
        val values = keys.getKeyValues(provider.id)
        return ids.zip(values)
            .filter { (id, _) -> !isKeyBlocked("${provider.id}|$modelId|$id", exhausted, health, now) }
            .map { (id, value) -> FallbackAttempt(provider, modelId, value, id) }
    }

    /**
     * Mark the key an attempt used as cooling down for the failure's
     * per-kind cooldown (Phase 7, Step B1). A failure with no cooldown
     * (auth rejection, context too long, capability mismatch) writes
     * no exhaustion flag — auth is recorded as "invalid" in the health
     * ledger by [ModelRouter.recordFailure] instead, and the others
     * are retryable at once on the next candidate.
     */
    suspend fun markExhausted(attempt: FallbackAttempt, failure: Failure) {
        val key = attempt.exhaustionKey() ?: return
        if (failure.cooldownMs <= 0) return
        prefs.markExhausted(key, failure.kind.name, System.currentTimeMillis() + failure.cooldownMs)
    }

    /** Mark the key an attempt used as exhausted until next midnight. */
    suspend fun markExhausted(attempt: FallbackAttempt, kind: LimitKind) {
        val key = attempt.exhaustionKey() ?: return
        prefs.markExhausted(key, kind.name, nextMidnightMillis())
    }

    /**
     * "providerId/modelId" values whose every key is currently blocked
     * (cooling down, exhausted, or marked invalid). Used for the
     * "Limit reached" badge on fallback entries.
     */
    suspend fun exhaustedModelKeys(): Set<String> {
        val map = prefs.exhaustedMap()
        val health = prefs.keyHealthMap()
        val now = System.currentTimeMillis()
        val out = mutableSetOf<String>()
        val blockedKeys = mutableSetOf<String>()
        blockedKeys += map.filterValues { it.untilMillis > now }.keys
        blockedKeys += health.filterValues {
            it.status == com.inyogeshwar.codesathi.data.KeyHealth.STATUS_INVALID ||
                it.cooldownUntil > now
        }.keys
        val byModel = blockedKeys.mapNotNull { raw ->
            val parts = raw.split("|")
            if (parts.size != 3) null else Triple(parts[0], parts[1], parts[2])
        }.groupBy({ "${it.first}/${it.second}" }, { it.third })
        for ((modelKey, blockedIds) in byModel) {
            val providerId = modelKey.substringBefore("/")
            // Bedrock's only "key id" is the synthetic "aws" (see
            // attemptsForModel); every other provider uses its real ids.
            val allIds = if (providerId == "bedrock") listOf("aws") else keys.getKeyIds(providerId)
            if (allIds.isNotEmpty() && blockedIds.toSet().containsAll(allIds.toSet())) {
                out += modelKey
            }
        }
        return out
    }

    /**
     * One plain line per fully-blocked "providerId/modelId": why it is
     * blocked and when it can be tried again, e.g. "rate limited —
     * retries in ~1 min" or "key rejected — reset limit flags to try
     * again". The Providers screen shows this under the fallback entry
     * instead of the old "resets at midnight" line.
     */
    suspend fun exhaustedLabels(): Map<String, String> {
        val map = prefs.exhaustedMap()
        val health = prefs.keyHealthMap()
        val now = System.currentTimeMillis()
        val out = mutableMapOf<String, String>()
        for (modelKey in exhaustedModelKeys()) {
            val prefix = "$modelKey|"
            val kinds = map.filterKeys { it.startsWith(prefix) }.values.map { it.kind }
            val entries = health.filterKeys { it.startsWith(prefix) }.values
            val label = when {
                entries.any { it.status == com.inyogeshwar.codesathi.data.KeyHealth.STATUS_INVALID } ->
                    "key rejected — reset limit flags to try again"
                kinds.any { it == FailureKind.NO_CREDIT.name } ->
                    "out of credit — retries in ${cooldownIn(entries, map, modelKey, now)}"
                kinds.any { it == FailureKind.QUOTA.name } ->
                    "daily quota reached — retries in ${cooldownIn(entries, map, modelKey, now)}"
                kinds.any { it == FailureKind.RATE_LIMIT.name } ->
                    "rate limited — retries in ${cooldownIn(entries, map, modelKey, now)}"
                kinds.any { it == FailureKind.SERVER.name || it == FailureKind.TIMEOUT.name } ->
                    "server error — retries in ${cooldownIn(entries, map, modelKey, now)}"
                kinds.any { it == FailureKind.NETWORK.name } ->
                    "unreachable — retries in ${cooldownIn(entries, map, modelKey, now)}"
                else -> "cooling down — retries in ${cooldownIn(entries, map, modelKey, now)}"
            }
            out[modelKey] = label
        }
        return out
    }

    /** Longest remaining cooldown for one model, in plain words. */
    private fun cooldownIn(
        entries: Collection<com.inyogeshwar.codesathi.data.KeyHealth>,
        map: Map<String, com.inyogeshwar.codesathi.data.ExhaustionInfo>,
        modelKey: String,
        now: Long
    ): String {
        val prefix = "$modelKey|"
        var latest = entries.maxOfOrNull { it.cooldownUntil } ?: 0L
        map.filterKeys { it.startsWith(prefix) }.values.forEach {
            if (it.untilMillis > latest) latest = it.untilMillis
        }
        val remainingMs = (latest - now).coerceAtLeast(0L)
        val seconds = remainingMs / 1000
        return when {
            seconds < 90 -> "under a minute"
            seconds < 3600 -> "~${seconds / 60} min"
            else -> "~${seconds / 3600} h"
        }
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
