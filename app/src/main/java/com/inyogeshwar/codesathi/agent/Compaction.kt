package com.inyogeshwar.codesathi.agent

import com.inyogeshwar.codesathi.data.FallbackEntry
import com.inyogeshwar.codesathi.data.Message
import com.inyogeshwar.codesathi.data.ModelContext
import com.inyogeshwar.codesathi.data.TokenCounter

/**
 * Basic compaction (checklist Step 5).
 *
 * - All messages stay in Room forever. Compaction only changes what is
 *   *sent* to the model, and it never silently drops messages.
 * - estimateTokens = chars ÷ 4.
 * - Over 60% of the model's context window: old tool outputs are pruned
 *   to a short excerpt before sending.
 * - Over 75%: a structured Markdown summary is produced (objective /
 *   decisions / done / next / files) and kept beside the last ~15k tokens.
 * - The summary is produced by the model itself when one is available;
 *   [buildLocalSummary] is the offline fallback so the chat never loses
 *   its thread when the summary call fails.
 */
object Compaction {

    /** Default context window when the model does not declare one. */
    const val DEFAULT_CONTEXT_TOKENS = 128_000

    /** Tokens of the most recent conversation kept verbatim beside a summary. */
    const val KEEP_TOKENS = 15_000

    /**
     * Tokens kept verbatim beside a summary after a "context too long"
     * failure (Phase 7, Step B2 — the harder compaction). 5k keeps the
     * last user message and the last finished step, and no more, so the
     * retried step fits even in the smallest known window (64k).
     */
    const val HARD_KEEP_TOKENS = 5_000

    /**
     * The context window one turn is measured against (Phase 7,
     * Step B2 — AgentMT's cap, adapted). The cap is 75% of the
     * SMALLEST known window among the candidate models for the turn:
     * the session's model plus every fallback-chain model (those are
     * the models a turn may actually switch to). A model whose window
     * is unknown does not shrink the cap — it is skipped, not treated
     * as zero — and when NO candidate's window is known the fixed
     * [DEFAULT_CONTEXT_TOKENS] (128k) is used, exactly as before.
     * The local engine keeps its own 4,096 (see [LocalLlmClient]).
     * Because [needsPruning] / [needsSummary] are fractions of this
     * window, capping the window caps the thresholds: with a 64k model
     * in the chain, tool outputs prune above ~38k tokens and the
     * summary triggers above 48k, instead of waiting for 96k.
     */
    fun effectiveContextTokens(
        sessionModelId: String,
        fallbackChain: List<FallbackEntry> = emptyList()
    ): Int {
        val windows = (listOf(sessionModelId) + fallbackChain.map { it.modelId })
            .mapNotNull { ModelContext.knownWindow(it) }
        return windows.minOrNull() ?: DEFAULT_CONTEXT_TOKENS
    }

    data class Compacted(
        val messagesToSend: List<Message>,
        val summaryMarkdown: String?,
        val didPruneToolOutputs: Boolean,
        val didSummarise: Boolean
    )

    fun needsPruning(estimate: Int, contextTokens: Int): Boolean =
        estimate > (contextTokens * 0.60).toInt()

    fun needsSummary(estimate: Int, contextTokens: Int): Boolean =
        estimate > (contextTokens * 0.75).toInt()

    /**
     * Split [messages] into (summary, recent tail). The tail is the newest
     * messages totalling at most [keepTokens]; everything older is covered
     * by the summary.
     *
     * Pair boundary (Phase 7, Step B2 — AgentMT's "the kept tail must
     * never split a tool call/result pair", adapted to OUR storage order):
     * in Room a turn is stored as its tool messages FIRST and its one
     * assistant message AFTER them (the assistant text is inserted when
     * the turn ends), so a tool message's "caller" is the assistant
     * message that follows it. Walking backwards from the newest message
     * we meet the assistant message first, so a tail can only split a
     * pair one way: it keeps an assistant message while the tool
     * messages of that same turn fell outside the budget. When that
     * happens the assistant message is dropped from the tail too (it
     * joins the covered part, so the summary still describes the turn)
     * — the model never sees an assistant message whose tool results
     * it was never sent. A tail that starts with a tool message is
     * fine here: its assistant message is newer, so it is already in
     * the tail. The loop repeats because dropping one assistant message
     * can expose the next one in the same position.
     */
    fun splitForSummary(
        messages: List<Message>,
        keepTokens: Int = KEEP_TOKENS
    ): Pair<List<Message>, List<Message>> {
        var kept = 0
        val tail = mutableListOf<Message>()
        for (msg in messages.asReversed()) {
            val cost = TokenCounter.estimateTokens(msg.text)
            if (tail.isNotEmpty() && kept + cost > keepTokens) break
            tail.add(0, msg)
            kept += cost
        }
        // Pair boundary: drop a leading assistant message whose turn's
        // tool messages sit just outside the tail.
        while (tail.isNotEmpty()) {
            val first = tail.first()
            if (first.role != "assistant") break
            val indexInFull = messages.indexOf(first)
            if (indexInFull <= 0) break
            if (messages[indexInFull - 1].role != "tool") break
            tail.removeAt(0)
        }
        val covered = messages.dropLast(tail.size)
        return covered to tail
    }

    /**
     * Build the message list actually sent to the model for one turn.
     * When [summaryMarkdown] is given (compaction already happened), the
     * older messages are replaced by the summary plus the newest tail
     * (at most [KEEP_TOKENS]); otherwise the full history is used.
     * Old tool outputs are pruned to an excerpt when the estimate is over
     * 60% of the context window. The returned list is a copy — the
     * messages stored in Room are never modified or dropped.
     */
    fun buildSendMessages(
        messages: List<Message>,
        summaryMarkdown: String?,
        contextTokens: Int = DEFAULT_CONTEXT_TOKENS
    ): List<Message> {
        val base = if (summaryMarkdown != null) {
            val (_, tail) = splitForSummary(messages)
            listOf(
                Message(
                    sessionId = messages.firstOrNull()?.sessionId.orEmpty(),
                    role = "system",
                    text = "Summary of the earlier conversation (older messages are kept in history):\n$summaryMarkdown",
                    createdAt = System.currentTimeMillis()
                )
            ) + tail
        } else {
            messages
        }
        val estimate = TokenCounter.estimateTokens(base)
        return if (needsPruning(estimate, contextTokens)) pruneToolOutputs(base) else base
    }

    /** Shorten tool messages to an excerpt before sending (full text stays in Room). */
    fun pruneToolOutputs(messages: List<Message>): List<Message> =
        messages.map { msg ->
            if (msg.role == "tool" && msg.text.length > 1_000) {
                msg.copy(text = msg.text.take(1_000) + "\n…[output truncated for context; full output kept in history]")
            } else msg
        }

    /** Offline fallback summary — structured, honest about what it does not know. */
    fun buildLocalSummary(covered: List<Message>, folderName: String): String {
        val userGoals = covered.filter { it.role == "user" }.takeLast(3).map { it.text }
        val files = covered.flatMap { msg ->
            Regex("`([^`]+\\.[a-zA-Z0-9]+)`").findAll(msg.text).map { it.groupValues[1] }
        }.distinct().take(10)
        return buildString {
            appendLine("## Chat summary (auto, offline)")
            appendLine()
            appendLine("**Project:** $folderName")
            appendLine()
            appendLine("### Objective")
            if (userGoals.isEmpty()) appendLine("- (no user goal recorded yet)") else userGoals.forEach { appendLine("- $it") }
            appendLine()
            appendLine("### Files mentioned")
            if (files.isEmpty()) appendLine("- (none)") else files.forEach { appendLine("- `$it`") }
            appendLine()
            appendLine("### Note")
            appendLine("This summary was built locally because the model summary call was unavailable. Older messages are still kept in history.")
        }
    }

    /** Prompt sent to the model to produce a proper structured summary. */
    fun summaryPrompt(coveredText: String): String = """
        Summarise the conversation below into structured Markdown with exactly
        these sections: Objective, Decisions, Completed work, Active work,
        Blockers, Next moves, Files. Keep it short and factual.
        Do not invent work that did not happen.

        $coveredText
    """.trimIndent()
}
