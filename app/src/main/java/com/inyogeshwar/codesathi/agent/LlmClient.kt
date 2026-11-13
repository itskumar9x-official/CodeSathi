package com.inyogeshwar.codesathi.agent

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

/** Events from one streamed chat turn. Tool calls arrive after the text. */
sealed interface ChatStreamEvent {
    data class Text(val chunk: String) : ChatStreamEvent
    data class ToolCall(val call: LlmToolCall) : ChatStreamEvent
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
 */
class LlmClient {

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
                    body = err
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
     * [classifyLimitError] maps it to RATE — no new failure kind.
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
                        body = err
                    )
                }
                val raw = conn.inputStream.bufferedReader().readText()
                val parsed = parseBedrockConverse(raw)
                parsed.first?.let { collector.emit(ChatStreamEvent.Text(it)) }
                parsed.second.forEach { collector.emit(ChatStreamEvent.ToolCall(it)) }
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

    /** Parse a Converse response: (text, tool calls). */
    private fun parseBedrockConverse(body: String): Pair<String?, List<LlmToolCall>> {
        return try {
            val el = json.parseToJsonElement(body).jsonObject
            val content = el["output"]?.jsonObject
                ?.get("message")?.jsonObject
                ?.get("content")?.jsonArray
                ?: return null to emptyList()
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
            text to calls
        } catch (_: Exception) {
            null to emptyList()
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
            val role = escape(m.role)
            val content = escape(m.content)
            """{"role":"$role","content":"$content"}"""
        }
        return if (provider.kind == "anthropic") {
            val system = messages.firstOrNull { it.role == "system" }?.content
            val rest = messages.filter { it.role != "system" }
                .joinToString(",") { m -> """{"role":"${escape(m.role)}","content":"${escape(m.content)}"}""" }
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
            """{"model":"${escape(modelId)}","stream":true$effortPart,"messages":[$msgs]}"""
        }
    }

    companion object {
        /** The only effort values ever sent to a provider. */
        private val VALID_REASONING_EFFORTS = setOf("low", "medium", "high")
    }

    /**
     * Reads the response body. SSE `data:` lines yield text deltas (and
     * accumulate `tool_calls` fragments, emitted at the end). If the body
     * is plain JSON instead of SSE, it is parsed as one complete response.
     */
    private suspend fun emitAllEvents(
        conn: HttpURLConnection,
        collector: kotlinx.coroutines.flow.FlowCollector<ChatStreamEvent>
    ) {
        withContext(Dispatchers.IO) {
            val toolCalls = linkedMapOf<Int, ToolCallBuilder>()
            var sawSse = false
            val rawBody = StringBuilder()
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
            } else {
                toolCalls.values.mapNotNull { it.build() }
                    .forEach { collector.emit(ChatStreamEvent.ToolCall(it)) }
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

    /** Parse a complete (non-SSE) chat response: (text, tool calls). */
    private fun parseCompleteResponse(body: String): Pair<String?, List<LlmToolCall>> {
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
            text to calls
        } catch (_: Exception) {
            null to emptyList()
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
 */
class LlmException(
    message: String,
    val httpCode: Int? = null,
    val body: String? = null
) : Exception(message)
