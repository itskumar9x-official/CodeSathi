package com.inyogeshwar.codesathi.agent

import kotlinx.serialization.json.contentOrNull
import com.inyogeshwar.codesathi.data.AZURE_API_VERSION
import com.inyogeshwar.codesathi.data.KeyStore
import com.inyogeshwar.codesathi.data.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** One chat message in the provider's wire format. */
data class LlmMessage(
    val role: String,
    val content: String,
    /**
     * Images attached to this message (Phase 6, Step 2), already
     * downscaled and JPEG re-encoded by [ImageStore]. Empty for every
     * text-only message — and then the request body is byte-for-byte
     * what text-only turns sent before, so no provider sees a new
     * shape it did not ask for.
     */
    val images: List<LlmImage> = emptyList(),
    /**
     * PDFs attached to this message (Phase 7, Step B3), as raw bytes
     * base64-encoded (see [LlmPdf]). OpenAI-kind providers get a
     * `file` part and Anthropic-kind providers a `document` block
     * (see [openAiContentJson] / [anthropicContentJson]); Bedrock and
     * the on-device engine take no PDF part, so the ViewModel refuses
     * a PDF for those models before sending. Empty for every message
     * without a PDF — and then the request body is byte-for-byte what
     * turns without PDFs sent before.
     */
    val pdfs: List<LlmPdf> = emptyList(),
    /**
     * Tool calls the assistant made in this message (Bedrock only).
     * Bedrock's Converse API pairs each tool result with the toolUse id
     * it answers, so the ids must travel with the message. Every other
     * provider ignores these fields.
     */
    val toolUses: List<LlmToolCall>? = null,
    /**
     * Tool results this message carries (Bedrock only): one per toolUse
     * the previous assistant message made, in the same order.
     */
    val toolResults: List<ToolResultFeedback>? = null
)

/**
 * One attached image, already downscaled and JPEG re-encoded (see
 * [ImageStore]). [base64] is the JPEG bytes, base64-encoded, with no
 * line breaks; [mediaType] is always "image/jpeg" today — every picked
 * format becomes JPEG on disk, so every provider gets the same bytes.
 */
data class LlmImage(val base64: String, val mediaType: String = "image/jpeg")

/**
 * One attached PDF (Phase 7, Step B3). [base64] is the RAW PDF file
 * bytes, base64-encoded, with no line breaks — there is NO on-device
 * text extraction: no PDF library is in the app, so the model reads
 * the PDF itself. [fileName] is the file's display name, sent along
 * with the bytes so the model (and the user, in an error) can tell
 * which PDF a part is.
 */
data class LlmPdf(val base64: String, val fileName: String)

/** One tool call the model asked for (OpenAI `tool_calls` shape). */
data class LlmToolCall(
    val name: String,
    /** Raw JSON arguments object as a string (may be partial while streaming). */
    val argumentsJson: String,
    /**
     * The id the provider assigned to this call (Bedrock Converse
     * `toolUseId`, or the OpenAI tool-call id when present). Null for
     * fenced ```tool calls, which carry no id.
     */
    val id: String? = null
)

/**
 * One tool result fed back to the model (Bedrock Converse `toolResult`
 * block). [toolUseId] must equal the [LlmToolCall.id] it answers.
 */
data class ToolResultFeedback(
    val toolUseId: String,
    /** The tool's output text. */
    val content: String
)

/** Real token usage a provider reported for one turn. */
data class LlmUsage(val promptTokens: Int, val completionTokens: Int) {
    val totalTokens: Int get() = promptTokens + completionTokens
}

/** Events from one streamed chat turn. Tool calls arrive after the text. */
sealed interface ChatStreamEvent {
    data class Text(val chunk: String) : ChatStreamEvent
    data class ToolCall(val call: LlmToolCall) : ChatStreamEvent
    /**
     * Real token usage the provider reported for this turn (OpenAI SSE
     * `usage` object, Anthropic message_start / message_delta usage,
     * Bedrock Converse `usage`, or the `usage` object of a plain-JSON
     * reply). Emitted once, at the end of the turn, when the provider
     * sent one. Absent when the provider sent no usage — the caller
     * then falls back to the chars÷4 estimate and labels it estimated.
     */
    data class Usage(val usage: LlmUsage) : ChatStreamEvent
}

/**
 * Minimal LLM client over HttpURLConnection with SSE streaming.
 * Written for CodeSathi — no third-party HTTP library.
 *
 * - OpenAI-compatible providers: POST {baseUrl}/chat/completions, SSE.
 *   When the user set a reasoning effort for the model, it is sent as
 *   `reasoning_effort` ("low" | "medium" | "high"); unset means nothing
 *   is sent. Anthropic providers never get an effort field (see
 *   [requestBody]).
 * - Anthropic providers: POST {baseUrl}/messages with
 *   `anthropic-version: 2023-06-01` and `x-api-key`, SSE.
 * - Azure OpenAI providers (Phase 4): POST
 *   https://{resource}.openai.azure.com/openai/deployments/{deployment}/
 *   chat/completions?api-version=[AZURE_API_VERSION], with the `api-key`
 *   header (never Bearer). The deployment name is the model id the user
 *   picked; the resource name comes from [streamChatEvents]'s
 *   [azureResourceName] argument. A blank resource name throws before any
 *   request is made, so a placeholder host can never go out on the wire.
 * - AWS Bedrock providers (Phase 4): POST
 *   https://bedrock-runtime.{region}.amazonaws.com/model/{modelId}/converse
 *   with SigV4 signing ([BedrockSigner], hand-rolled — no AWS SDK) and
 *   the Converse API body (messages / system / inferenceConfig). The
 *   credentials come from [streamChatEvents]'s [awsCredentials] argument.
 *   Converse is NON-STREAMING here: the whole reply arrives at once and
 *   is emitted as a single [ChatStreamEvent.Text] (plus any
 *   [ChatStreamEvent.ToolCall] events) — see [emitBedrockConverse].
 *   The reasoning-effort picker does nothing for Bedrock (Converse has
 *   no effort field).
 * - Local providers (Ollama / LM Studio) work the same way with no key.
 * Streaming is best-effort: if the provider answers with plain JSON
 * instead of SSE, the single response is returned as one chunk, and any
 * `tool_calls` in it are surfaced as [ChatStreamEvent.ToolCall] events.
 * - On-device .task models (Phase 6, Step 5, kind "local"): no HTTP at
 *   all — the turn runs through [LocalLlmClient] (MediaPipe LLM
 *   Inference, tasks-genai 0.10.27, verified from Google's docs — see
 *   that file). The engine streams real text chunks and reports no
 *   token usage, so no [ChatStreamEvent.Usage] is emitted and the
 *   caller estimates (flagged estimated), like any provider that sends
 *   no usage. Tool calls come back as fenced ```tool blocks in the
 *   text, which the runner parses — the engine sends no native
 *   tool_calls.
 * - Real token usage (Phase 5, Step 2): when the provider reports it,
 *   the turn ends with a [ChatStreamEvent.Usage] — the OpenAI SSE
 *   `usage` object in the final chunk (requested with
 *   `stream_options.include_usage`), Anthropic's message_start /
 *   message_delta usage, Bedrock Converse `usage`, or the `usage`
 *   object of a plain-JSON reply. A provider that sends no usage
 *   simply yields no Usage event, and the caller estimates instead.
 * - Images (Phase 6, Step 2): a message with [LlmMessage.images] is
 *   sent in each provider's own vision shape — OpenAI `image_url`
 *   data URLs (also Azure, Google's OpenAI-compatible endpoint and
 *   other OpenAI-kind providers), Anthropic image content blocks
 *   (base64 + media_type), Bedrock Converse image blocks. A message
 *   with no images sends exactly the plain-string content it always
 *   did. Which models may receive images is decided before the
 *   request is built ([com.inyogeshwar.codesathi.data.supportsVision]);
 *   this client never has to refuse — it just renders the shape.
 */
/**
 * Minimal LLM client over HttpURLConnection with SSE streaming, plus the
 * on-device engine (Phase 6, Step 5) for kind "local" providers.
 * [local] is null only on the plain client path (the approve-only runner
 * never makes an LLM call); the ViewModel always wires a real one.
 */
class LlmClient(private val local: LocalLlmClient? = null) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Stream the assistant's reply as text chunks. Throws on HTTP errors —
     * callers must catch and show the error, never fake a reply.
     * Tool calls in the reply are ignored here; use [streamChatEvents]
     * when the caller needs them.
     * [reasoningEffort] is "low" | "medium" | "high" when the user set an
     * effort for this model, null for the default (nothing is sent).
     */
    fun streamChat(
        provider: Provider,
        modelId: String,
        apiKey: String?,
        messages: List<LlmMessage>,
        reasoningEffort: String? = null,
        azureResourceName: String? = null,
        awsCredentials: KeyStore.AwsCredentials? = null
    ): Flow<String> = flow {
        streamChatEvents(provider, modelId, apiKey, messages, reasoningEffort, azureResourceName, awsCredentials).collect { event ->
            if (event is ChatStreamEvent.Text) emit(event.chunk)
        }
    }

    /**
     * Stream one turn as text chunks plus any tool calls the model made.
     * Tool calls are emitted after the text of the turn, in call order.
     * Throws [LlmException] on HTTP errors.
     * Bedrock turns are non-streaming (see the class KDoc): the reply
     * arrives whole and is emitted as one text chunk.
     */
    fun streamChatEvents(
        provider: Provider,
        modelId: String,
        apiKey: String?,
        messages: List<LlmMessage>,
        reasoningEffort: String? = null,
        azureResourceName: String? = null,
        awsCredentials: KeyStore.AwsCredentials? = null
    ): Flow<ChatStreamEvent> = flow {
        if (provider.kind == "local") {
            // On-device .task model (Phase 6, Step 5): no HTTP, no key —
            // the MediaPipe engine streams the reply itself. A missing
            // client here would mean the ViewModel forgot to wire it, so
            // fail plainly instead of sending a request nowhere.
            val localClient = local
                ?: throw LlmException("On-device model support is not initialised.")
            localClient.streamEvents(messages).collect { emit(it) }
            return@flow
        }
        if (provider.kind == "bedrock") {
            emitBedrockConverse(provider, modelId, messages, awsCredentials, this)
            return@flow
        }
        val url = URL(chatUrl(provider, modelId, azureResourceName))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 120_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("User-Agent", "CodeSathi/1.0 (CodeSathi; Android)")
            if (provider.kind == "anthropic") {
                setRequestProperty("anthropic-version", "2023-06-01")
                setRequestProperty("x-api-key", apiKey.orEmpty())
            } else if (provider.kind == "azure") {
                // Azure API keys go in the `api-key` header, never Bearer.
                if (!apiKey.isNullOrBlank()) setRequestProperty("api-key", apiKey)
            } else if (!apiKey.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            doOutput = true
        }
        try {
            val body = requestBody(provider, modelId, messages, reasoningEffort)
            conn.outputStream.bufferedWriter().use { it.write(body) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                throw LlmException(
                    "Provider error HTTP $code: ${err.take(300)}",
                    httpCode = code,
                    body = err,
                    retryAfterMs = retryAfterMs(conn)
                )
            }
            emitAllEvents(conn, this)
        } finally {
            conn.disconnect()
        }
    }

    private fun chatUrl(provider: Provider, modelId: String, azureResourceName: String?): String {
        val base = provider.baseUrl.trimEnd('/')
        return when (provider.kind) {
            "anthropic" -> "$base/messages"
            "azure" -> {
                // The resource name is the only thing that can build this
                // URL. A blank name throws here — before any request is
                // made — so a URL with a placeholder host can never go out.
                val resource = azureResourceName?.trim()?.takeIf { it.isNotBlank() }
                    ?: throw LlmException("Azure resource name is not set. Set it in Providers → Azure OpenAI first.")
                "https://$resource.openai.azure.com/openai/deployments/$modelId/chat/completions?api-version=$AZURE_API_VERSION"
            }
            else -> "$base/chat/completions"
        }
    }

    /**
     * One Bedrock Converse turn (Phase 4). Non-streaming: the whole reply
     * is read, then emitted as one text chunk plus any tool calls — the
     * same [ChatStreamEvent] shape the streaming providers use, so the
     * agent loop, fallback and compaction need no Bedrock special case.
     * The request is SigV4-signed ([BedrockSigner]); a missing credential
     * throws before any request is made. A 429 from Bedrock (throttling)
     * carries the code through [LlmException], so the existing
     * [classifyFailure] maps it to RATE_LIMIT — no new failure kind.
     */
    private suspend fun emitBedrockConverse(
        provider: Provider,
        modelId: String,
        messages: List<LlmMessage>,
        awsCredentials: KeyStore.AwsCredentials?,
        collector: kotlinx.coroutines.flow.FlowCollector<ChatStreamEvent>
    ) {
        withContext(Dispatchers.IO) {
            val creds = awsCredentials
                ?: throw LlmException("AWS credentials are not set. Add your access key id, secret access key and region in Providers → AWS Bedrock first.")
            val region = creds.region.trim().ifBlank { "us-east-1" }
            val host = "bedrock-runtime.$region.amazonaws.com"
            val path = BedrockSigner.conversePath(modelId)
            val body = bedrockRequestBody(messages)
            val signed = BedrockSigner.sign(creds.copy(region = region), host, path, body)
            val conn = (URL("https://$host$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 30_000
                readTimeout = 120_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "CodeSathi/1.0 (CodeSathi; Android)")
                setRequestProperty("X-Amz-Date", signed.amzDate)
                setRequestProperty("X-Amz-Content-Sha256", signed.payloadHash)
                setRequestProperty("Authorization", signed.authorization)
                doOutput = true
            }
            try {
                conn.outputStream.bufferedWriter().use { it.write(body) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                    throw LlmException(
                        "Bedrock error HTTP $code: ${err.take(300)}",
                        httpCode = code,
                        body = err,
                        retryAfterMs = retryAfterMs(conn)
                    )
                }
                val raw = conn.inputStream.bufferedReader().readText()
                val parsed = parseBedrockConverse(raw)
                parsed.text?.let { collector.emit(ChatStreamEvent.Text(it)) }
                parsed.calls.forEach { collector.emit(ChatStreamEvent.ToolCall(it)) }
                // Bedrock Converse DOES report usage (usage.inputTokens /
                // outputTokens) — use it when present, exactly like the
                // streaming providers. A response without it falls back
                // to the estimate in the runner, like any other provider.
                parsed.usage?.let { collector.emit(ChatStreamEvent.Usage(it)) }
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * The Converse request body: {"messages":[…],"system":[…],
     * "inferenceConfig":{"maxTokens":4096}}. The model id is NOT in the
     * body — it is in the URL path. Roles: "system" messages become the
     * top-level `system` list; "assistant" stays assistant; anything else
     * ("user", and the legacy "tool" feedback message) becomes a user
     * message. An assistant message with [LlmMessage.toolUses] carries a
     * `toolUse` block per call; a user message with
     * [LlmMessage.toolResults] carries a `toolResult` block per result,
     * each paired by toolUseId. A toolUse without an id is skipped —
     * Bedrock cannot answer a result for it, and the plain-text feedback
     * still carries the outcome (see AgentRunner).
     */
    private fun bedrockRequestBody(messages: List<LlmMessage>): String {
        val system = messages.firstOrNull { it.role == "system" }?.content
        val systemPart = if (system != null) """, "system":[{"text":"${escape(system)}"}]""" else ""
        val rest = messages.filter { it.role != "system" }.joinToString(",") { m ->
            val role = if (m.role == "assistant") "assistant" else "user"
            val blocks = StringBuilder()
            if (m.content.isNotBlank()) blocks.append("""{"text":"${escape(m.content)}"}""")
            // Images (Phase 6, Step 2): Converse image blocks fit the
            // existing content array — {"image":{"format":"jpeg",
            // "source":{"bytes":"<base64>"}}}. The format is "jpeg"
            // (not "image/jpeg") and the bytes are a base64 string in
            // the REST JSON body. Only Claude models on Bedrock take
            // images (see VisionSupport); other models are refused
            // before the request is built, so an unsupported model
            // never sees an image block here.
            m.images.forEach { img ->
                if (blocks.isNotEmpty()) blocks.append(",")
                val format = when (img.mediaType) {
                    "image/png" -> "png"
                    "image/gif" -> "gif"
                    "image/webp" -> "webp"
                    else -> "jpeg"
                }
                blocks.append(
                    """{"image":{"format":"$format","source":{"bytes":"${img.base64}"}}}"""
                )
            }
            m.toolUses?.forEach { call ->
                val id = call.id ?: return@forEach
                if (blocks.isNotEmpty()) blocks.append(",")
                blocks.append(
                    """{"toolUse":{"toolUseId":"${escape(id)}","name":"${escape(call.name)}","input":${safeJsonObject(call.argumentsJson)}}}"""
                )
            }
            m.toolResults?.forEach { result ->
                if (blocks.isNotEmpty()) blocks.append(",")
                blocks.append(
                    """{"toolResult":{"toolUseId":"${escape(result.toolUseId)}","content":{"text":"${escape(result.content)}"}}}"""
                )
            }
            """{"role":"$role","content":[${blocks}]}"""
        }
        return """{"messages":[$rest],"inferenceConfig":{"maxTokens":4096}$systemPart}"""
    }

    /** One parsed Converse reply: text, tool calls, and reported usage (when present). */
    private data class BedrockReply(
        val text: String?,
        val calls: List<LlmToolCall>,
        val usage: LlmUsage?
    )

    /** Parse a Converse response: (text, tool calls, usage). */
    private fun parseBedrockConverse(body: String): BedrockReply {
        return try {
            val el = json.parseToJsonElement(body).jsonObject
            val content = el["output"]?.jsonObject
                ?.get("message")?.jsonObject
                ?.get("content")?.jsonArray
                ?: return BedrockReply(null, emptyList(), null)
            var text: String? = null
            val calls = mutableListOf<LlmToolCall>()
            for (block in content) {
                val obj = block.jsonObject
                obj["text"]?.jsonPrimitive?.contentOrNull?.let { t ->
                    text = if (text == null) t else "$text$t"
                }
                obj["toolUse"]?.jsonObject?.let { tu ->
                    val id = tu["toolUseId"]?.jsonPrimitive?.contentOrNull ?: return@let
                    val name = tu["name"]?.jsonPrimitive?.contentOrNull ?: return@let
                    val input = tu["input"]?.jsonObject
                    calls += LlmToolCall(name, input?.toString() ?: "{}", id)
                }
            }
            // Converse usage: {"inputTokens":…,"outputTokens":…,"totalTokens":…}.
            val usageObj = el["usage"]?.jsonObject
            val usage = usageObj?.let {
                val prompt = it["inputTokens"]?.jsonPrimitive?.content?.toIntOrNull()
                val completion = it["outputTokens"]?.jsonPrimitive?.content?.toIntOrNull()
                if (prompt != null && completion != null) LlmUsage(prompt, completion) else null
            }
            BedrockReply(text, calls, usage)
        } catch (_: Exception) {
            BedrockReply(null, emptyList(), null)
        }
    }

    /**
     * A JSON object string, or "{}" when the input is not a JSON object.
     * The Converse body must stay valid JSON even if a model's tool
     * arguments arrived malformed.
     */
    private fun safeJsonObject(raw: String): String = try {
        json.parseToJsonElement(raw).jsonObject
        raw
    } catch (_: Exception) {
        "{}"
    }

    /**
     * Build the request body. [reasoningEffort] is sent ONLY when set
     * ("low" | "medium" | "high"): to OpenAI-kind providers as
     * `reasoning_effort` in /chat/completions, and to Anthropic-kind
     * providers as `output_config.effort` in /messages — verified
     * against Anthropic's own docs (platform.claude.com/docs/en/
     * build-with-claude/effort, Nov 2026): the field is
     * `output_config.effort`, levels low/medium/high/xhigh/max. Only
     * low/medium/high are ever sent (the picker offers those).
     * Anthropic lists effort for Claude 4.5+ / 5.x models; an older
     * model that does not support it answers 400. A model that does
     * not support the field answers 400, which surfaces through the
     * normal [LlmException] path — no new error handling. Note:
     * Anthropic recommends a large max_tokens at high effort; this
     * client still sends max_tokens 4096.
     * Bedrock never reaches this function — it has its own body
     * ([bedrockRequestBody]) and no effort field.
     */
    private fun requestBody(
        provider: Provider,
        modelId: String,
        messages: List<LlmMessage>,
        reasoningEffort: String? = null
    ): String {
        val msgs = messages.joinToString(",") { m ->
            """{"role":"${escape(m.role)}","content":${openAiContentJson(m)}}"""
        }
        return if (provider.kind == "anthropic") {
            val system = messages.firstOrNull { it.role == "system" }?.content
            val rest = messages.filter { it.role != "system" }
                .joinToString(",") { m -> """{"role":"${escape(m.role)}","content":${anthropicContentJson(m)}}""" }
            val systemPart = if (system != null) ",\"system\":\"${escape(system)}\"" else ""
            // Anthropic effort (verified, see KDoc above): only a valid,
            // user-set effort is sent; "default" (null) sends nothing.
            val effortPart = if (reasoningEffort in VALID_REASONING_EFFORTS) {
                ",\"output_config\":{\"effort\":\"$reasoningEffort\"}"
            } else ""
            """{"model":"${escape(modelId)}","max_tokens":4096,"stream":true$systemPart$effortPart,"messages":[$rest]}"""
        } else {
            // Only a valid, user-set effort is sent; "default" (null) and
            // any unexpected value send nothing, so the request is exactly
            // what an unset model would have sent.
            val effortPart = if (reasoningEffort in VALID_REASONING_EFFORTS) {
                ",\"reasoning_effort\":\"$reasoningEffort\""
            } else ""
            // stream_options.include_usage asks the provider to send its
            // real token counts in the final SSE chunk (OpenAI's documented
            // way — without it, /chat/completions never reports usage).
            // Most OpenAI-compatible servers accept the field; a provider
            // that 400s on unknown fields surfaces its error through the
            // normal LlmException path instead of silently losing usage.
            """{"model":"${escape(modelId)}","stream":true,"stream_options":{"include_usage":true}$effortPart,"messages":[$msgs]}"""
        }
    }

    /**
     * OpenAI-style message content (Phase 6, Step 2; PDFs Phase 7,
     * Step B3): a plain string when the message has no images and no
     * PDFs — exactly what text-only turns sent before, so no provider
     * sees a new shape it did not ask for — otherwise a content list
     * with the text part first, one `image_url` part per image, and
     * one `file` part per PDF
     * (`{"type":"file","file":{"filename":"…","file_data":
     * "data:application/pdf;base64,…"}}` — AgentMT's OpenAI shape).
     * Azure and Google's OpenAI-compatible endpoint take the same shape.
     */
    private fun openAiContentJson(m: LlmMessage): String {
        if (m.images.isEmpty() && m.pdfs.isEmpty()) return "\"${escape(m.content)}\""
        val parts = StringBuilder()
        if (m.content.isNotBlank()) {
            parts.append("""{"type":"text","text":"${escape(m.content)}"}""")
        }
        m.images.forEach { img ->
            if (parts.isNotEmpty()) parts.append(",")
            parts.append(
                """{"type":"image_url","image_url":{"url":"data:${img.mediaType};base64,${img.base64}"}}"""
            )
        }
        m.pdfs.forEach { pdf ->
            if (parts.isNotEmpty()) parts.append(",")
            parts.append(
                """{"type":"file","file":{"filename":"${escape(pdf.fileName)}","file_data":"data:application/pdf;base64,${pdf.base64}"}}"""
            )
        }
        return "[$parts]"
    }

    /**
     * Anthropic-style message content (Phase 6, Step 2; PDFs Phase 7,
     * Step B3): a plain string with no images and no PDFs, otherwise
     * a content list with the image blocks FIRST and the text after
     * them — Anthropic's docs recommend images before the text that
     * asks about them. One block per image:
     * `{"type":"image","source":{"type":"base64","media_type":
     * "image/jpeg","data":"…"}}`. PDFs go between the images and the
     * text as `document` blocks
     * (`{"type":"document","source":{"type":"base64","media_type":
     * "application/pdf","data":"…"}}` — AgentMT's Anthropic shape).
     */
    private fun anthropicContentJson(m: LlmMessage): String {
        if (m.images.isEmpty() && m.pdfs.isEmpty()) return "\"${escape(m.content)}\""
        val parts = StringBuilder()
        m.images.forEach { img ->
            if (parts.isNotEmpty()) parts.append(",")
            parts.append(
                """{"type":"image","source":{"type":"base64","media_type":"${img.mediaType}","data":"${img.base64}"}}"""
            )
        }
        m.pdfs.forEach { pdf ->
            if (parts.isNotEmpty()) parts.append(",")
            parts.append(
                """{"type":"document","source":{"type":"base64","media_type":"application/pdf","data":"${pdf.base64}"}}"""
            )
        }
        if (m.content.isNotBlank()) {
            if (parts.isNotEmpty()) parts.append(",")
            parts.append("""{"type":"text","text":"${escape(m.content)}"}""")
        }
        return "[$parts]"
    }

    companion object {
        /** The only effort values ever sent to a provider. */
        private val VALID_REASONING_EFFORTS = setOf("low", "medium", "high")
    }

    /**
     * Reads the response body. SSE `data:` lines yield text deltas (and
     * accumulate `tool_calls` fragments, emitted at the end). If the body
     * is plain JSON instead of SSE, it is parsed as one complete response.
     * Real token usage is collected along the way — the OpenAI `usage`
     * object in the final chunk, Anthropic's message_start (input) and
     * message_delta (output) usage — and emitted once as
     * [ChatStreamEvent.Usage] at the end, when the provider sent any.
     */
    private suspend fun emitAllEvents(
        conn: HttpURLConnection,
        collector: kotlinx.coroutines.flow.FlowCollector<ChatStreamEvent>
    ) {
        withContext(Dispatchers.IO) {
            val toolCalls = linkedMapOf<Int, ToolCallBuilder>()
            var sawSse = false
            val rawBody = StringBuilder()
            // Real usage reported by this turn, when the provider sends it.
            var openAiUsage: LlmUsage? = null
            var anthropicInput: Int? = null
            var anthropicOutput: Int? = null
            BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val raw = line!!.trim()
                    if (raw.startsWith("data:")) {
                        sawSse = true
                        val data = raw.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        extractDelta(data)?.let { collector.emit(ChatStreamEvent.Text(it)) }
                        accumulateToolCalls(data, toolCalls)
                        extractOpenAiUsage(data)?.let { openAiUsage = it }
                        extractAnthropicUsage(data)?.let { (input, output) ->
                            if (input != null) anthropicInput = input
                            if (output != null) anthropicOutput = output
                        }
                    } else if (raw.isNotEmpty()) {
                        rawBody.append(raw)
                    }
                }
            }
            if (!sawSse && rawBody.isNotEmpty()) {
                // Plain-JSON (non-streaming) reply.
                val parsed = parseCompleteResponse(rawBody.toString())
                parsed.first?.let { collector.emit(ChatStreamEvent.Text(it)) }
                parsed.second.forEach { collector.emit(ChatStreamEvent.ToolCall(it)) }
                parsed.third?.let { collector.emit(ChatStreamEvent.Usage(it)) }
            } else {
                toolCalls.values.mapNotNull { it.build() }
                    .forEach { collector.emit(ChatStreamEvent.ToolCall(it)) }
                // The usage object, when the provider sent one: OpenAI's
                // final chunk carries both counts; Anthropic splits them
                // across message_start (input) and message_delta (output).
                val usage = openAiUsage
                    ?: if (anthropicInput != null || anthropicOutput != null) {
                        LlmUsage(anthropicInput ?: 0, anthropicOutput ?: 0)
                    } else null
                usage?.let { collector.emit(ChatStreamEvent.Usage(it)) }
            }
        }
    }

    private class ToolCallBuilder {
        var name: String? = null
        val args = StringBuilder()
        fun build(): LlmToolCall? {
            val n = name?.takeIf { it.isNotBlank() } ?: return null
            return LlmToolCall(n, args.toString())
        }
    }

    /** Merge one SSE chunk's `tool_calls` delta fragments into [acc]. */
    private fun accumulateToolCalls(data: String, acc: MutableMap<Int, ToolCallBuilder>) {
        try {
            val el = json.parseToJsonElement(data).jsonObject
            val calls = el["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("delta")
                ?.jsonObject?.get("tool_calls")?.jsonArray ?: return
            for (call in calls) {
                val obj = call.jsonObject
                val index = obj["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val builder = acc.getOrPut(index) { ToolCallBuilder() }
                val function = obj["function"]?.jsonObject ?: continue
                function["name"]?.jsonPrimitive?.contentOrNull?.let { builder.name = it }
                function["arguments"]?.jsonPrimitive?.contentOrNull?.let { builder.args.append(it) }
            }
        } catch (_: Exception) {
            // A chunk that is not tool-call JSON is simply not a tool call.
        }
    }

    /** Parse a complete (non-SSE) chat response: (text, tool calls, usage). */
    private fun parseCompleteResponse(body: String): Triple<String?, List<LlmToolCall>, LlmUsage?> {
        return try {
            val el = json.parseToJsonElement(body).jsonObject
            val message = el["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")?.jsonObject
                ?: el["content"]?.jsonArray?.firstOrNull()?.jsonObject
            val text = message?.get("content")?.jsonPrimitive?.contentOrNull
                ?: el["content"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
            val calls = message?.get("tool_calls")?.jsonArray?.mapNotNull { item ->
                val fn = item.jsonObject["function"]?.jsonObject ?: return@mapNotNull null
                val name = fn["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                LlmToolCall(name, fn["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}")
            }.orEmpty()
            // Usage in a complete reply: OpenAI's prompt/completion
            // tokens, or Anthropic's input/output tokens — whichever the
            // provider sent. Null when it sent none.
            val usageObj = el["usage"]?.jsonObject
            val usage = usageObj?.let {
                val prompt = it["prompt_tokens"]?.jsonPrimitive?.content?.toIntOrNull()
                    ?: it["input_tokens"]?.jsonPrimitive?.content?.toIntOrNull()
                val completion = it["completion_tokens"]?.jsonPrimitive?.content?.toIntOrNull()
                    ?: it["output_tokens"]?.jsonPrimitive?.content?.toIntOrNull()
                if (prompt != null && completion != null) LlmUsage(prompt, completion) else null
            }
            Triple(text, calls, usage)
        } catch (_: Exception) {
            Triple(null, emptyList(), null)
        }
    }

    /**
     * The OpenAI `usage` object of one SSE chunk (the final chunk, when
     * `stream_options.include_usage` was requested): prompt_tokens and
     * completion_tokens. Null for every other chunk.
     */
    private fun extractOpenAiUsage(data: String): LlmUsage? {
        return try {
            val usage = json.parseToJsonElement(data).jsonObject["usage"]?.jsonObject ?: return null
            val prompt = usage["prompt_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
            val completion = usage["completion_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
            LlmUsage(prompt, completion)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Anthropic usage in one SSE event: message_start carries
     * message.usage.input_tokens, message_delta carries
     * usage.output_tokens. Returns (input, output) with nulls for the
     * half this event does not carry, or null when the event is not a
     * usage event at all.
     */
    private fun extractAnthropicUsage(data: String): Pair<Int?, Int?>? {
        return try {
            val el = json.parseToJsonElement(data).jsonObject
            when (el["type"]?.jsonPrimitive?.contentOrNull) {
                "message_start" -> {
                    val input = el["message"]?.jsonObject?.get("usage")?.jsonObject
                        ?.get("input_tokens")?.jsonPrimitive?.content?.toIntOrNull()
                    if (input != null) input to null else null
                }
                "message_delta" -> {
                    val output = el["usage"]?.jsonObject
                        ?.get("output_tokens")?.jsonPrimitive?.content?.toIntOrNull()
                    if (output != null) null to output else null
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Pull the text delta out of one SSE JSON chunk (OpenAI or Anthropic shape). */
    private fun extractDelta(data: String): String? {
        return try {
            val el = json.parseToJsonElement(data).jsonObject
            el["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("delta")
                ?.jsonObject?.get("content")
                ?.jsonPrimitive?.content
                ?: el["delta"]?.jsonObject?.get("text")?.jsonPrimitive?.content
        } catch (_: Exception) {
            null
        }
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
}

/**
 * An HTTP failure from the provider. [httpCode] and [body] are kept so the
 * fallback engine can tell a limit error (402 / quota / 429) from any other
 * failure — the message alone is not enough. The body is the provider's
 * error text (never an API key: keys are only ever sent, never returned).
 * [retryAfterMs] is the wait the provider asked for, parsed from its
 * `Retry-After` / `X-RateLimit-Reset` headers when it sent one (Phase 7,
 * Step B1) — a rate limit then cools down for exactly that long instead
 * of the default 60 s. Null when the provider sent no such header.
 */
class LlmException(
    message: String,
    val httpCode: Int? = null,
    val body: String? = null,
    val retryAfterMs: Long? = null
) : Exception(message)

/**
 * The wait a provider asked for, from its `Retry-After` /
 * `X-RateLimit-Reset` headers (see [parseRetryAfterMs]). Read before
 * the connection is closed — headers stay available until disconnect.
 */
private fun retryAfterMs(conn: HttpURLConnection): Long? = parseRetryAfterMs(
    conn.getHeaderField("Retry-After"),
    conn.getHeaderField("X-RateLimit-Reset")
)
