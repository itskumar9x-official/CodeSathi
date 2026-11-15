package com.inyogeshwar.codesathi.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Ported from AgentMT's RouteRunnerTest (the `classifier` test) to
 * CodeSathi's Routing.kt. Only the pure logic is tested — the
 * classifier, the Retry-After parser, the route bounds and the
 * mid-stream marker — no Android framework, no LLM call.
 *
 * The other eight RouteRunnerTest cases (first candidate succeeds,
 * rate-limit fallback, auth retry, bad-request stop, retry budget,
 * skipping cooling/invalid/text-only candidates, no fallback after
 * output, cancellation) are NOT ported: they drive AgentMT's
 * RouteRunner.run, which has no counterpart in CodeSathi — the retry
 * loop lives in AgentRunner + FallbackEngine, which need Room,
 * DataStore and a live LLM client, none of which a plain JUnit test
 * can provide. The classifier those tests rely on is covered here.
 */
class RoutingTest {

    @Test
    fun rateLimit429Gets60sCooldownAndFallsBack() {
        val failure = FailureClassifier.classify(LlmException("rate limited", 429, "rate limited"))
        assertEquals(FailureKind.RATE_LIMIT, failure.kind)
        assertEquals(60_000L, failure.cooldownMs)
        assertTrue(failure.fallback)
        assertEquals("rate limited", failure.label)
    }

    @Test
    fun retryAfterHeaderOverridesTheDefaultCooldown() {
        val failure = FailureClassifier.classify(LlmException("slow down", 429, "slow down", 30_000))
        assertEquals(FailureKind.RATE_LIMIT, failure.kind)
        assertEquals(30_000L, failure.cooldownMs)
    }

    @Test
    fun dailyQuota429GetsOneHourCooldown() {
        val failure = FailureClassifier.classify(
            LlmException("free-models-per-day exceeded", 429, "free-models-per-day exceeded")
        )
        assertEquals(FailureKind.QUOTA, failure.kind)
        assertEquals(FailureClassifier.QUOTA_COOLDOWN_MS, failure.cooldownMs)
        assertEquals(3_600_000L, failure.cooldownMs)
        assertTrue(failure.fallback)
    }

    @Test
    fun outOfCredit402() {
        val failure = FailureClassifier.classify(
            LlmException("insufficient credit", 402, "insufficient credit")
        )
        assertEquals(FailureKind.NO_CREDIT, failure.kind)
        assertEquals(FailureClassifier.CREDIT_COOLDOWN_MS, failure.cooldownMs)
        assertTrue(failure.fallback)
    }

    @Test
    fun authFailureIsInvalidNotCooldown() {
        val failure = FailureClassifier.classify(LlmException("bad key", 401, "bad key"))
        assertEquals(FailureKind.AUTH, failure.kind)
        assertEquals(0L, failure.cooldownMs)
        assertTrue(failure.fallback)
    }

    @Test
    fun serverError502() {
        val failure = FailureClassifier.classify(LlmException("bad gateway", 502, "bad gateway"))
        assertEquals(FailureKind.SERVER, failure.kind)
        assertEquals(FailureClassifier.SERVER_COOLDOWN_MS, failure.cooldownMs)
        assertTrue(failure.fallback)
    }

    @Test
    fun networkFailureWithoutHttpResponse() {
        val failure = FailureClassifier.classify(IOException("no route to host"))
        assertEquals(FailureKind.NETWORK, failure.kind)
        assertEquals(FailureClassifier.NETWORK_COOLDOWN_MS, failure.cooldownMs)
        assertTrue(failure.fallback)
    }

    @Test
    fun plainBadRequestDoesNotFallBack() {
        val failure = FailureClassifier.classify(LlmException("bad request", 400, "bad request"))
        assertEquals(FailureKind.BAD_REQUEST, failure.kind)
        assertFalse(failure.fallback)
    }

    @Test
    fun contextTooLong413() {
        val failure = FailureClassifier.classify(
            LlmException("context length exceeded", 413, "context length exceeded")
        )
        assertEquals(FailureKind.CONTEXT, failure.kind)
        assertTrue(failure.fallback)
    }

    @Test
    fun retryAfterSeconds() {
        assertEquals(120_000L, parseRetryAfterMs("120", null))
    }

    @Test
    fun retryAfterHttpDate() {
        assertEquals(120_000L, parseRetryAfterMs("Thu, 01 Jan 1970 00:02:00 GMT", null, 0L))
    }

    @Test
    fun rateLimitResetEpochSeconds() {
        assertEquals(180_000L, parseRetryAfterMs(null, "180", 0L))
    }

    @Test
    fun noHeaderMeansNoWait() {
        assertNull(parseRetryAfterMs(null, null))
        assertNull(parseRetryAfterMs("", ""))
    }

    @Test
    fun routeBoundsMatchAgentMT() {
        assertEquals(4, RouteBounds.MAX_ROUTE_ATTEMPTS)
        assertEquals(180_000L, RouteBounds.PRE_FIRST_TOKEN_DEADLINE_MS)
    }

    @Test
    fun midStreamFailureKeepsItsLabel() {
        val failure = FailureClassifier.classify(IOException("connection reset"))
        val error = MidStreamException(failure, IOException("connection reset"))
        assertTrue(error.message!!.contains("unreachable"))
    }
}
