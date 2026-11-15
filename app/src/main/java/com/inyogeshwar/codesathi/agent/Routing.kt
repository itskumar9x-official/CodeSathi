package com.inyogeshwar.codesathi.agent

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Why one attempt failed, adapted from AgentMT's RouteRunner
 * (FailureClassifier) to CodeSathi's HttpURLConnection + SSE stack.
 *
 * AgentMT classified its own OkHttp LlmException; here the input is OUR
 * [LlmException] — [LlmException.httpCode] / [LlmException.body] /
 * [LlmException.retryAfterMs] — plus plain [IOException]s from the
 * connection itself. Nothing here imports AgentMT's package or types.
 *
 * Cooldowns (AgentMT's numbers, kept exactly): a rate-limited key retries
 * after 60 s, a daily-quota key after 1 h, an out-of-credit key after 6 h,
 * a server error after 20 s, a network failure after 15 s. These REPLACE
 * the old "exhausted until next local midnight" rule — the cooldown only
 * changes WHEN a key becomes eligible again, never the order in which
 * keys and fallback-chain steps are tried.
 */
enum class FailureKind {
    RATE_LIMIT,
    QUOTA,
    NO_CREDIT,
    AUTH,
    SERVER,
    NETWORK,
    TIMEOUT,
    CONTEXT,
    CAPABILITY,
    BAD_REQUEST,
    OTHER
}

data class Failure(
    val kind: FailureKind,
    val code: Int?,
    val message: String,
    val cooldownMs: Long,
    /** True when another candidate may be tried after this failure. */
    val fallback: Boolean
) {
    val label: String get() = when (kind) {
        FailureKind.RATE_LIMIT -> "rate limited"
        FailureKind.QUOTA -> "daily quota reached"
        FailureKind.NO_CREDIT -> "out of credit"
        FailureKind.AUTH -> "key rejected"
        FailureKind.SERVER -> "server error"
        FailureKind.NETWORK -> "unreachable"
        FailureKind.TIMEOUT -> "timed out"
        FailureKind.CONTEXT -> "context too long"
        FailureKind.CAPABILITY -> "not supported by this model"
        FailureKind.BAD_REQUEST -> "request rejected"
        FailureKind.OTHER -> "failed"
    }
}

object FailureClassifier {
    /** A plain rate limit: retry after 60 s (or the Retry-After header). */
    const val RATE_COOLDOWN_MS = 60_000L
    /** A daily quota (429 whose body says quota / per day): retry after 1 h. */
    const val QUOTA_COOLDOWN_MS = 60 * 60_000L
    /** Out of credit (402 / insufficient credit): retry after 6 h. */
    const val CREDIT_COOLDOWN_MS = 6 * 60 * 60_000L
    /** A 5xx / timeout: retry after 20 s. */
    const val SERVER_COOLDOWN_MS = 20_000L
    /** A network failure (no route / DNS): retry after 15 s. */
    const val NETWORK_COOLDOWN_MS = 15_000L
    /** No cooldown ever exceeds 24 h, even when a header asks for more. */
    const val MAX_COOLDOWN_MS = 24 * 60 * 60_000L

    /**
     * Classify one failed attempt. [httpCode]/[body]/[retryAfterMs] come
     * from [LlmException]; [cause] is the thrown exception itself, so a
     * bare [IOException] (no HTTP response at all) still classifies.
     */
    fun classify(
        httpCode: Int?,
        body: String?,
        retryAfterMs: Long? = null,
        cause: Throwable? = null
    ): Failure {
        val msg = ((body?.takeIf { it.isNotBlank() } ?: cause?.message)?.take(300)
            ?: cause?.javaClass?.simpleName ?: "failed")
        val low = msg.lowercase()
        if (httpCode != null) {
            return when {
                httpCode == 429 -> {
                    // A 429 whose body talks about a daily quota is a
                    // quota failure, not a short rate limit — the two
                    // have different cooldowns (1 h vs 60 s).
                    val daily = listOf(
                        "per day", "daily", "quota",
                        "free-models-per-day", "exceeded your current quota"
                    ).any { it in low }
                    val cooldown = (retryAfterMs
                        ?: if (daily) QUOTA_COOLDOWN_MS else RATE_COOLDOWN_MS)
                        .coerceIn(1_000, MAX_COOLDOWN_MS)
                    Failure(
                        if (daily) FailureKind.QUOTA else FailureKind.RATE_LIMIT,
                        429, msg, cooldown, true
                    )
                }
                httpCode == 402 || "insufficient credit" in low ||
                    "insufficient_quota" in low || "credit balance" in low ||
                    "out of credit" in low ->
                    Failure(FailureKind.NO_CREDIT, httpCode, msg, CREDIT_COOLDOWN_MS, true)
                httpCode == 401 || httpCode == 403 ->
                    // The key itself is rejected. No cooldown — the key
                    // is marked invalid in the health ledger instead, so
                    // it is skipped until the user resets it.
                    Failure(FailureKind.AUTH, httpCode, msg, 0, true)
                httpCode == 413 || "context length" in low || "context_length" in low ||
                    "too many tokens" in low || "maximum context" in low ||
                    "context window" in low || "prompt too long" in low ->
                    Failure(FailureKind.CONTEXT, httpCode, msg, 0, true)
                httpCode == 408 ->
                    Failure(FailureKind.TIMEOUT, httpCode, msg, SERVER_COOLDOWN_MS, true)
                httpCode >= 500 ->
                    Failure(FailureKind.SERVER, httpCode, msg, SERVER_COOLDOWN_MS, true)
                httpCode == 404 ->
                    // Model missing on this provider: another model may work.
                    Failure(FailureKind.BAD_REQUEST, httpCode, msg, 0, true)
                httpCode == 400 && ("image" in low || "tool" in low ||
                    "does not support" in low || "not supported" in low ||
                    "unsupported" in low) ->
                    // Capability mismatch (e.g. images sent to a text-only
                    // model): another model may work.
                    Failure(FailureKind.CAPABILITY, httpCode, msg, 0, true)
                httpCode in 400..499 ->
                    Failure(FailureKind.BAD_REQUEST, httpCode, msg, 0, false)
                else -> Failure(FailureKind.OTHER, httpCode, msg, 0, false)
            }
        }
        return when (cause) {
            is SocketTimeoutException, is InterruptedIOException ->
                Failure(FailureKind.TIMEOUT, null, msg, SERVER_COOLDOWN_MS, true)
            is UnknownHostException ->
                Failure(FailureKind.NETWORK, null, msg, NETWORK_COOLDOWN_MS, true)
            is IOException ->
                Failure(FailureKind.NETWORK, null, msg, NETWORK_COOLDOWN_MS, true)
            else -> Failure(FailureKind.OTHER, null, msg, 0, false)
        }
    }

    /** Classify a thrown exception the way [classify] does. */
    fun classify(e: Throwable): Failure = when (e) {
        is LlmException -> classify(e.httpCode, e.body, e.retryAfterMs, e)
        else -> classify(null, null, null, e)
    }
}

/** Classify a thrown exception (see [FailureClassifier.classify]). */
fun classifyFailure(e: Throwable): Failure = FailureClassifier.classify(e)

/**
 * Parse a Retry-After / X-RateLimit-Reset header value into a wait in
 * millis, the way AgentMT's `retryAfterMs` did for OkHttp responses.
 * Retry-After may be seconds ("120") or an HTTP date; a reset header may
 * be epoch seconds, epoch millis, or plain seconds. Returns null when
 * neither value parses. Never negative.
 */
fun parseRetryAfterMs(
    retryAfter: String?,
    rateLimitReset: String?,
    now: Long = System.currentTimeMillis()
): Long? {
    retryAfter?.trim()?.takeIf { it.isNotEmpty() }?.let { v ->
        v.toLongOrNull()?.let { return (it * 1000).coerceAtLeast(0) }
        runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                .parse(v)!!.time - now
        }.getOrNull()?.let { return it.coerceAtLeast(0) }
    }
    rateLimitReset?.trim()?.takeIf { it.isNotEmpty() }?.toLongOrNull()?.let { v ->
        return when {
            v > 1_000_000_000_000L -> (v - now).coerceAtLeast(0) // epoch ms
            v > 1_000_000_000L -> (v * 1000 - now).coerceAtLeast(0) // epoch s
            else -> (v * 1000).coerceAtLeast(0) // plain seconds
        }
    }
    return null
}

/** Thrown when a stream failed after it had already produced output. */
class MidStreamException(val failure: Failure, cause: Throwable) :
    Exception("${failure.label}: ${failure.message}", cause)

/**
 * Bounds for one model turn, adapted from AgentMT's RouteRunner:
 * at most [MAX_ROUTE_ATTEMPTS] upstream attempts per turn, and a
 * [PRE_FIRST_TOKEN_DEADLINE_MS] wall-clock budget before the first
 * token — after that the turn stops trying new candidates and reports
 * the last failure. The deadline only covers "before the first token":
 * once a stream has produced text, the stream itself is governed by
 * the HTTP read timeout, not by this budget.
 */
object RouteBounds {
    /** Max upstream attempts per model turn (AgentMT default 4, max 8). */
    const val MAX_ROUTE_ATTEMPTS = 4
    /** Wall-clock budget before the first token (AgentMT: 180 s). */
    const val PRE_FIRST_TOKEN_DEADLINE_MS = 180_000L
}
