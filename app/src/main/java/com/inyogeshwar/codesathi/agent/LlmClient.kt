package com.inyogeshwar.codesathi.agent

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
data class LlmMessage(val role: String, val content: String)

/** One tool call the model asked for (OpenAI `tool_calls` shape). */
data class LlmToolCall(
    val name: String,
    /** Raw JSON arguments object as a string (may be partial while streaming). */
    val argumentsJson: String
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
 * - Anthropic providers: POST {baseUrl}/messages with
 *   `anthropic-version: 2023-06-01` and `x-api-key`, SSE.
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
     */
    fun streamChat(
        provider: Provider,
        modelId: String,
        apiKey: String?,
        messages: List<LlmMessage>
    ): Flow<String> = flow {
        streamChatEvents(provider, modelId, apiKey, messages).collect { event ->
            if (event is ChatStreamEvent.Text) emit(event.chunk)
        }
    }

    /**
     * Stream one turn as text chunks plus any tool calls the model made.
     * Tool calls are emitted after the text of the turn, in call order.
     * Throws [LlmException] on HTTP errors.
     */
    fun streamChatEvents(
        provider: Provider,
        modelId: String,
        apiKey: String?,
        messages: List<LlmMessage>
    ): Flow<ChatStreamEvent> = flow {
        val url = URL(chatUrl(provider))
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
            } else if (!apiKey.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            doOutput = true
        }
        try {
            val body = requestBody(provider, modelId, messages)
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

    private fun chatUrl(provider: Provider): String {
        val base = provider.baseUrl.trimEnd('/')
        return if (provider.kind == "anthropic") "$base/messages" else "$base/chat/completions"
    }

    private fun requestBody(provider: Provider, modelId: String, messages: List<LlmMessage>): String {
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
            """{"model":"${escape(modelId)}","max_tokens":4096,"stream":true$systemPart,"messages":[$rest]}"""
        } else {
            """{"model":"${escape(modelId)}","stream":true,"messages":[$msgs]}"""
        }
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
