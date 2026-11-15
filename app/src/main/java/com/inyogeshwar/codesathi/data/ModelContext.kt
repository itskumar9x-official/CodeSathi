package com.inyogeshwar.codesathi.data

/**
 * Known context windows, Phase 7 Step B2 (compaction cap).
 *
 * Our bundled catalog (`providers.json`) carries NO context sizes —
 * its models are plain id strings, and the task's premise that "the
 * catalog's models carry a context size where known" does not hold for
 * our catalog (checked 2026-11-14: `Provider.models` is a
 * `List<String>`). So the known sizes live here instead, as a small
 * hand-written table of the well-known models, and everything else
 * falls back to [Compaction.DEFAULT_CONTEXT_TOKENS] (128k).
 *
 * Numbers are the vendors' standard published windows (Nov 2026,
 * cross-checked against public comparisons 2026-11-14): GPT-5 400k,
 * Claude 4.5 200k standard (the 1M tier is an opt-in beta, so the
 * standard 200k is used — the conservative choice), Gemini 2.5 Pro /
 * Flash 1M, DeepSeek V3-era chat/reasoner 64k (the V4 line is 1M, but
 * the bundled ids are the V3-era ones), GPT-4o / 4-turbo 128k,
 * GPT-4.1 1M, Grok-4 256k, Mistral Large 128k, Llama 3.3 128k,
 * Qwen 2.5 128k. A model whose window cannot be verified is NOT in
 * this table — guessing a window would silently move the compaction
 * thresholds, so unknown means the 128k default.
 *
 * Matching is by normalised id (lowercase, provider prefix before `/`
 * stripped, e.g. `openai/gpt-4o` → `gpt-4o`): the longest table key
 * that is a prefix of the id wins, so `gpt-5-mini` matches `gpt-5`
 * and `claude-sonnet-4-5-20250929` matches `claude-sonnet-4-5`.
 * Dated suffixes therefore need no table entry of their own.
 */
object ModelContext {

    /** Model-id prefix → context window in tokens. Longest prefix wins. */
    private val KNOWN_WINDOWS: Map<String, Int> = mapOf(
        // OpenAI
        "gpt-5" to 400_000,
        "gpt-4.1" to 1_000_000,
        "gpt-4o" to 128_000,
        "gpt-4-turbo" to 128_000,
        "gpt-4" to 128_000,
        "o1" to 200_000,
        "o3" to 200_000,
        "o4-mini" to 200_000,
        // Anthropic (standard tier; the 1M tier is opt-in beta)
        "claude-opus-4" to 200_000,
        "claude-sonnet-4" to 200_000,
        "claude-haiku-4" to 200_000,
        "claude-3-5-sonnet" to 200_000,
        "claude-3-5-haiku" to 200_000,
        "claude-3-opus" to 200_000,
        "claude-3-sonnet" to 200_000,
        "claude-3-haiku" to 200_000,
        // Google
        "gemini-2.5-pro" to 1_000_000,
        "gemini-2.5-flash" to 1_000_000,
        "gemini-2.0-flash" to 1_000_000,
        "gemini-1.5-pro" to 2_000_000,
        "gemini-1.5-flash" to 1_000_000,
        // DeepSeek (V3-era bundled ids; the V4 line is 1M)
        "deepseek-chat" to 64_000,
        "deepseek-reasoner" to 64_000,
        // xAI
        "grok-4" to 256_000,
        "grok-3" to 131_000,
        // Mistral
        "mistral-large" to 128_000,
        "mistral-medium" to 128_000,
        "mistral-small" to 128_000,
        "codestral" to 256_000,
        // Meta (via Groq / Together / Bedrock)
        "llama-3.3" to 128_000,
        "llama-3.1" to 128_000,
        "llama-3" to 128_000,
        // Alibaba
        "qwen2.5" to 128_000,
        "qwen3" to 256_000
    )

    /**
     * The known context window for one model id, or null when the model
     * is not in the table. Provider prefixes (`openai/…`) and dated
     * suffixes (`…-20250929`) are stripped by the prefix match.
     */
    fun knownWindow(modelId: String): Int? {
        val id = modelId.lowercase().substringAfterLast('/').trim()
        if (id.isEmpty()) return null
        var best: Map.Entry<String, Int>? = null
        for (entry in KNOWN_WINDOWS) {
            if (id.startsWith(entry.key) && (best == null || entry.key.length > best.key.length)) {
                best = entry
            }
        }
        return best?.value
    }
}
