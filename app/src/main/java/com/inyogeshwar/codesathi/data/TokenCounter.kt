package com.inyogeshwar.codesathi.data

/**
 * Token accounting for v1.
 * The provider's real usage (if returned) is recorded separately; the UI
 * counter is a chars÷4 estimate, shown as approximate ("~").
 */
object TokenCounter {
    /** Rough estimate: ~4 characters per token. */
    fun estimateTokens(text: String): Int = (text.length + 3) / 4

    fun estimateTokens(messages: List<Message>): Int =
        messages.sumOf { estimateTokens(it.text) }

    fun formatApproximate(tokens: Int): String = when {
        tokens < 1_000 -> "~$tokens tok"
        tokens < 1_000_000 -> "~%.1fK tok".format(tokens / 1_000.0)
        else -> "~%.1fM tok".format(tokens / 1_000_000.0)
    }
}
