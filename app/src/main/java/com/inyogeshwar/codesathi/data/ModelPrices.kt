package com.inyogeshwar.codesathi.data

/**
 * Small bundled price table for cost estimates (Phase 5, Step 2).
 *
 * Prices are the providers' public list prices in US dollars per
 * million tokens, checked 2026-11-13 (see [PRICE_DATE]). Every cost the
 * app shows is an APPROXIMATE estimate ("~₹") — never a bill. Prices
 * change, providers bill cached input / long prompts / batch calls
 * differently, and gateways add their own fees, so the number on screen
 * is a guide for comparing models, not what the provider will charge.
 *
 * What is in the table, and what was verified where:
 * - Anthropic direct (claude-sonnet-4-5 $3/$15, claude-haiku-4-5 $1/$5,
 *   claude-opus-4-1 $15/$75): Anthropic's API pricing page
 *   (platform.claude.com/docs/en/about-claude/pricing), confirmed by
 *   several independent trackers in Nov 2026.
 * - OpenAI direct (gpt-5 $1.25/$10, gpt-5-mini $0.25/$2, gpt-4o
 *   $2.50/$10): OpenAI's API pricing page, confirmed by several
 *   independent trackers in Nov 2026. Short-context Standard rates;
 *   very long prompts bill higher on some models.
 * - Google Gemini direct (gemini-2-5-flash $0.30/$2.50,
 *   gemini-2-5-pro $1.25/$10 for prompts up to 200k tokens): the Gemini
 *   API pricing page (ai.google.dev/gemini-api/docs/pricing), Oct–Nov
 *   2026. Note: Google routes gemini-2.5-pro to gemini-3.8-flash and
 *   gemini-2.5-flash to gemini-3.5-flash-lite from 1 Dec 2026, billed
 *   at the new models' rates — the 2.5 prices here are the ones in
 *   effect on the price date.
 * - DeepSeek direct (deepseek-chat $0.28/$0.42, cache-miss input):
 *   DeepSeek's Models & Pricing page (api-docs.deepseek.com),
 *   checked 1 Nov 2026 and confirmed by LiteLLM on 13 Nov 2026.
 *   DeepSeek also runs peak / off-peak rates — the table uses the
 *   peak (higher) rate, so the estimate errs on the expensive side.
 * - OpenRouter (anthropic/claude-sonnet-4-5 $3/$15,
 *   deepseek/deepseek-chat $0.28/$0.42): OpenRouter passes through
 *   each provider's list price with no per-token markup, so the direct
 *   price applies. Its 5.5% fee is charged on credit purchases, not
 *   per request, so it is NOT in these numbers — real OpenRouter spend
 *   runs about 5.5% higher than the table.
 * - Free routes ($0): any model id ending in ":free" or "/free" is a
 *   gateway's free tier and bills $0 by definition
 *   (openrouter/free, tokenharbor deepseek-v4-flash:free, and the
 *   unorouter / orcarouter / clinepass free models in the catalog).
 *
 * What is deliberately NOT in the table (and shows "cost unknown"
 * instead of an invented price): TokenHarbor's paid models
 * (claude-sonnet-4-5, deepseek-v4-flash — a gateway whose markup could
 * not be verified), Groq, xAI, Mistral, Together, DeepSeek Reasoner
 * and every other model whose current list price was not confirmed
 * against a public pricing page on the price date.
 *
 * The rupee conversion uses a fixed approximate rate ([USD_TO_INR],
 * the market rate around the price date: $1 ≈ ₹94.20 at the 13 Nov
 * 2026 close). It is not a live rate and is shown as approximate.
 */
data class ModelPrice(
    val providerId: String,
    val modelId: String,
    /** US dollars per 1M input (prompt) tokens. */
    val inputUsdPerMillion: Double,
    /** US dollars per 1M output (completion) tokens. */
    val outputUsdPerMillion: Double
)

object ModelPrices {

    /** Date the prices in this table were last checked (YYYY-MM-DD). */
    const val PRICE_DATE = "2026-11-13"

    /** Fixed approximate rupees per US dollar, around the price date. */
    const val USD_TO_INR = 94.0

    val PRICES = listOf(
        // Anthropic direct — top pick.
        ModelPrice("anthropic", "claude-sonnet-4-5", 3.0, 15.0),
        ModelPrice("anthropic", "claude-haiku-4-5", 1.0, 5.0),
        ModelPrice("anthropic", "claude-opus-4-1", 15.0, 75.0),
        // OpenAI direct — top pick.
        ModelPrice("openai", "gpt-5", 1.25, 10.0),
        ModelPrice("openai", "gpt-5-mini", 0.25, 2.0),
        ModelPrice("openai", "gpt-4o", 2.5, 10.0),
        // Google Gemini direct — top pick.
        ModelPrice("google", "gemini-2-5-flash", 0.30, 2.50),
        ModelPrice("google", "gemini-2-5-pro", 1.25, 10.0),
        // DeepSeek direct.
        ModelPrice("deepseek", "deepseek-chat", 0.28, 0.42),
        // OpenRouter — pass-through of the direct list prices (see KDoc).
        ModelPrice("openrouter", "anthropic/claude-sonnet-4-5", 3.0, 15.0),
        ModelPrice("openrouter", "deepseek/deepseek-chat", 0.28, 0.42),
        // Free routes — $0 by definition.
        ModelPrice("openrouter", "openrouter/free", 0.0, 0.0),
        ModelPrice("tokenharbor", "deepseek-v4-flash:free", 0.0, 0.0),
        ModelPrice("unorouter", "deepseek-v4.1-flash-1017:free", 0.0, 0.0),
        ModelPrice("unorouter", "gemini-3.8-flash:free", 0.0, 0.0),
        ModelPrice("unorouter", "gpt-oss-120b:free", 0.0, 0.0),
        ModelPrice("orcarouter", "orcarouter/free", 0.0, 0.0),
        ModelPrice("clinepass", "baidu/ernie-5.5-flash-preview:free", 0.0, 0.0)
    )

    /** The table entry for one (provider, model), or null when unknown. */
    fun priceFor(providerId: String, modelId: String): ModelPrice? =
        PRICES.firstOrNull { it.providerId == providerId && it.modelId == modelId }

    /**
     * Approximate cost in US dollars for a token count, or null when
     * the model has no table entry (the UI shows "cost unknown").
     */
    fun approximateCostUsd(
        providerId: String,
        modelId: String,
        promptTokens: Long,
        completionTokens: Long
    ): Double? {
        val price = priceFor(providerId, modelId) ?: return null
        return (promptTokens * price.inputUsdPerMillion +
            completionTokens * price.outputUsdPerMillion) / 1_000_000.0
    }

    /**
     * Approximate cost in rupees, formatted with the "~₹" prefix so it
     * can never be read as an exact bill. Null when the model has no
     * table entry.
     */
    fun formatApproxInr(
        providerId: String,
        modelId: String,
        promptTokens: Long,
        completionTokens: Long
    ): String? {
        val usd = approximateCostUsd(providerId, modelId, promptTokens, completionTokens)
            ?: return null
        return formatApproxInr(usd)
    }

    /** Format a US-dollar amount as an approximate rupee figure ("~₹"). */
    fun formatApproxInr(usd: Double): String {
        val inr = usd * USD_TO_INR
        return when {
            inr < 1.0 -> "~₹%.2f".format(inr)
            inr < 100.0 -> "~₹%.1f".format(inr)
            else -> "~₹%.0f".format(inr)
        }
    }
}
