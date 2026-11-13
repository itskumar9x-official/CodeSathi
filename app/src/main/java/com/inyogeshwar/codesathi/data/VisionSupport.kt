package com.inyogeshwar.codesathi.data

/**
 * Which (provider, model) pairs can see images — Phase 6, Step 2.
 *
 * Only pairs verified against the provider's own docs (checked
 * 2026-11-13) are listed. Everything else is treated as NOT supporting
 * vision and the turn is refused with a plain message instead of
 * sending a request the model cannot answer — a vision request to a
 * text-only model would either 400 or silently ignore the image, and
 * the user would never know which.
 *
 * Verified (2026-11-13):
 * - OpenAI (provider `openai`, kind `openai`): Chat Completions
 *   `image_url` with a base64 data URL. Vision families per OpenAI's
 *   "Images and vision" docs (developers.openai.com) and Azure's
 *   vision-model list: GPT-5 series, GPT-4.1 series, GPT-4.5, GPT-4o
 *   series, o-series reasoning models, GPT-4 Turbo.
 * - Azure OpenAI (provider `azure-openai`, kind `azure`): the same
 *   Chat Completions shape and the same vision families (Microsoft
 *   Learn, "How to use vision-enabled chat models"); the deployment
 *   name is the model id, so the same prefixes apply.
 * - Anthropic (provider `anthropic`, kind `anthropic`): Messages API
 *   image content blocks, base64 + media_type (JPEG/PNG/GIF/WebP).
 *   Every Claude 3+ model supports vision — the catalog's Claude
 *   Sonnet 4.5 / Haiku 4.5 / Opus 4.1 all do.
 * - Google Gemini (provider `google`, kind `openai`): the catalog
 *   points at Google's OpenAI-compatible endpoint
 *   (generativelanguage.googleapis.com/v1beta/openai), and Google's
 *   OpenAI-compatibility docs show `image_url` with a base64 data URL
 *   for image understanding — Gemini models are natively multimodal.
 * - AWS Bedrock (provider `bedrock`, kind `bedrock`): Converse API
 *   image blocks (`{"image":{"format":"jpeg","source":{"bytes":…}}}`);
 *   Claude 3.x/4.x support images, Titan / Llama / Mistral do not
 *   (AWS Converse docs, boto3 reference). Only `anthropic.claude*`
 *   model ids are allowed.
 * - On-device .task models (provider `ondevice`, kind `local`,
 *   Phase 6, Step 5): text-only. The wired models (Gemma-3 1B) take
 *   text; Gemma-3n multimodal support is not wired, so every local
 *   model refuses images instead of guessing.
 *
 * NOT verified, so refused: every other provider in the catalog —
 * OpenRouter, Groq, DeepSeek, xAI, Mistral, Together, Ollama, LM
 * Studio, TokenHarbor and the rest. Some of them route to models that
 * DO see images (OpenRouter can serve Claude or GPT-4o), but vision
 * was not verified against that provider's docs, and a gateway may
 * strip or reject the image part — so the app refuses plainly instead
 * of guessing. A provider moves to the verified list only after its
 * own docs are checked, never on a model name alone.
 */
fun supportsVision(provider: Provider, modelId: String): Boolean {
    val model = modelId.trim().lowercase()
    if (model.isEmpty()) return false
    return when (provider.kind) {
        "anthropic" -> model.startsWith("claude-")
        "bedrock" -> model.startsWith("anthropic.claude")
        "local" -> false
        "azure" -> OPENAI_VISION_PREFIXES.any { model.startsWith(it) }
        "openai" -> when (provider.id) {
            "openai" -> OPENAI_VISION_PREFIXES.any { model.startsWith(it) }
            // Google's catalog entry uses the OpenAI-compatible endpoint.
            "google" -> model.startsWith("gemini-")
            else -> false
        }
        else -> false
    }
}

/**
 * Vision-model prefixes for OpenAI-kind providers (OpenAI itself and
 * Azure OpenAI deployments). "gpt-4o" covers gpt-4o and gpt-4o-mini;
 * "gpt-5" covers gpt-5 and gpt-5-mini; "o1"/"o3"/"o4" cover the
 * o-series reasoning models.
 */
private val OPENAI_VISION_PREFIXES = listOf(
    "gpt-5", "gpt-4o", "gpt-4.1", "gpt-4.5", "gpt-4-turbo", "o1", "o3", "o4"
)

/**
 * Plain refusal shown when a message carries images but the current
 * model cannot see them — in the chat (before sending) and as the turn
 * error (if a fallback switch lands on a non-vision model). It names a
 * verified alternative instead of leaving the user guessing.
 */
fun visionRefusalMessage(provider: Provider, modelId: String): String =
    "This model (${provider.name} / $modelId) cannot see images — " +
        "vision isn't verified for it. Pick a vision model in Providers " +
        "(OpenAI GPT-4o / GPT-5, Anthropic Claude, Google Gemini 2.5, " +
        "Azure GPT-4o / GPT-5, or a Claude model on Bedrock), " +
        "or send the message without the image."
