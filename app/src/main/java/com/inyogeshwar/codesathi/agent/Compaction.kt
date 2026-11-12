package com.inyogeshwar.codesathi.agent

import com.inyogeshwar.codesathi.data.Message
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
     * messages totalling at most [KEEP_TOKENS]; everything older is covered
     * by the summary.
     */
    fun splitForSummary(messages: List<Message>): Pair<List<Message>, List<Message>> {
        var kept = 0
        val tail = mutableListOf<Message>()
        for (msg in messages.asReversed()) {
            val cost = TokenCounter.estimateTokens(msg.text)
            if (tail.isNotEmpty() && kept + cost > KEEP_TOKENS) break
            tail.add(0, msg)
            kept += cost
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
