package com.inyogeshwar.codesathi.agent

import com.inyogeshwar.codesathi.data.KeyHealth
import com.inyogeshwar.codesathi.data.KeyStore
import com.inyogeshwar.codesathi.data.PreferencesStore
import com.inyogeshwar.codesathi.data.Provider

/**
 * Routing profiles + per-credential health, adapted from AgentMT's
 * ModelRouter to CodeSathi's stack (DataStore + FallbackEngine instead
 * of AgentMT's Room credential tables).
 *
 * Two modes — the user-visible contract is the fallback-chain ORDER,
 * which neither mode changes; the mode only decides WHICH candidates
 * the ordered list is built from:
 * - PINNED: stay on the session's provider + model; only its keys are
 *   rotated (a key that fails gives way to the next healthy key of the
 *   SAME provider + model). The fallback chain is not walked.
 * - FALLBACK (default): the session model first (all its keys), then
 *   the user's fallback chain in order — exactly the Phase 2, Step 1
 *   order.
 *
 * Health ledger: one [KeyHealth] row per "providerId|modelId|keyId"
 * (the same key [FallbackAttempt.exhaustionKey] uses), persisted in
 * DataStore JSON — see [PreferencesStore] for why DataStore, not Room.
 * [recordFailure] / [recordSuccess] are called by [AgentRunner] for
 * every attempt; [resetCredential] / [resetAllLimits] are the
 * user-facing "try this key again" actions on the Providers screen.
 */
enum class RoutingMode { PINNED, FALLBACK }

class ModelRouter(
    private val fallbackEngine: FallbackEngine,
    private val prefs: PreferencesStore,
    private val keys: KeyStore
) {

    /**
     * The ordered candidates for one turn. Pinned mode returns only the
     * session model's attempts; fallback mode returns the full order
     * (session model, then the fallback chain). Both go through the
     * engine, so cooling-down / invalid keys are skipped the same way.
     */
    suspend fun route(
        provider: Provider,
        modelId: String,
        mode: RoutingMode
    ): List<FallbackAttempt> = if (mode == RoutingMode.PINNED) {
        fallbackEngine.attemptsForSingle(provider, modelId)
    } else {
        fallbackEngine.attemptsFor(provider, modelId)
    }

    /**
     * Key rotation: the next healthy key id of the same provider +
     * model — the first key that is neither cooling down nor marked
     * invalid. Null when no key of this model is usable right now.
     * (The engine already walks keys in this order per turn; this is
     * the same check exposed for callers that need one key, e.g. the
     * Providers screen's per-key status.)
     */
    suspend fun nextHealthyKey(providerId: String, modelId: String): String? {
        val health = prefs.keyHealthMap()
        val exhausted = prefs.exhaustedMap()
        val now = System.currentTimeMillis()
        return keys.getKeyIds(providerId).firstOrNull { keyId ->
            val ledgerKey = "$providerId|$modelId|$keyId"
            val entry = health[ledgerKey]
            val cooling = exhausted[ledgerKey]?.untilMillis?.let { it > now } == true ||
                (entry?.cooldownUntil ?: 0L) > now
            val invalid = entry?.status == KeyHealth.STATUS_INVALID
            !cooling && !invalid
        }
    }

    /** Health row for one key of one model, or null when never tried. */
    suspend fun healthFor(providerId: String, modelId: String, keyId: String): KeyHealth? =
        prefs.keyHealthMap()["$providerId|$modelId|$keyId"]

    /**
     * Record a failed attempt: the key cools down for the failure's
     * per-kind cooldown (rate 60 s, daily quota 1 h, out of credit 6 h,
     * server 20 s, network 15 s) and the ledger notes the failure, its
     * status and its error label. An auth failure (401/403) carries no
     * cooldown — the key is marked invalid instead, so it stays skipped
     * until the user resets it (a rejected key will not start working
     * on its own).
     */
    suspend fun recordFailure(attempt: FallbackAttempt, failure: Failure) {
        fallbackEngine.markExhausted(attempt, failure)
        val key = attempt.exhaustionKey() ?: return
        prefs.recordKeyFailure(key, failure)
    }

    /**
     * Record a successful attempt: the key's cooldown is cleared (a
     * key that works again is usable at once, even if an old flag had
     * not expired yet), its status goes back to ok, and its token
     * counts and latency are added to the ledger.
     */
    suspend fun recordSuccess(
        attempt: FallbackAttempt,
        latencyMs: Long,
        inputTokens: Long,
        outputTokens: Long
    ) {
        val key = attempt.exhaustionKey() ?: return
        prefs.clearExhausted(key)
        prefs.recordKeySuccess(key, latencyMs, inputTokens, outputTokens)
    }

    /**
     * Reset one credential (AgentMT's `resetCredential`): clear its
     * cooldown and its invalid flag, so it is tried again on the next
     * turn. Counts are kept — the reset only lifts the block.
     */
    suspend fun resetCredential(providerId: String, modelId: String, keyId: String) {
        val key = "$providerId|$modelId|$keyId"
        prefs.resetKeyHealth(key)
        prefs.clearExhausted(key)
    }

    /**
     * Reset every limit flag at once ("Reset limit flags" on the
     * Providers screen): all cooldowns and invalid flags are lifted.
     * Counts are kept. The next turn retries the first key / model
     * again — a key that is still really limited will simply fail and
     * cool down again.
     */
    suspend fun resetAllLimits() {
        prefs.resetAllKeyHealth()
        prefs.clearAllExhausted()
    }
}
