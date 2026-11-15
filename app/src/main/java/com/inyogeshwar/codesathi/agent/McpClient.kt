package com.inyogeshwar.codesathi.agent

import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Something went wrong talking to an MCP server. The message is written
 * for the user (shown in the MCP screen / chat), never a raw stack trace —
 * so it says what happened and what to check, not just an HTTP code.
 */
class McpException(message: String) : Exception(message)

/** One tool a server offers, from its tools/list reply. */
data class McpToolInfo(
    val name: String,
    val description: String,
    /** Raw inputSchema JSON, kept for display; arguments are passed as strings. */
    val inputSchemaJson: String = "{}"
)

/**
 * Minimal MCP client over streamable HTTP, using HttpURLConnection —
 * no new dependencies, same style as [LlmClient].
 *
 * Two transports:
 * - http(s) URLs: the MCP spec's streamable HTTP transport, plain
 *   JSON-RPC over POST.
 * - "termux:<command>" URLs (Phase 7, Step B4): the MCP server is a
 *   local command run inside the user's Termux, bridged to streamable
 *   HTTP on 127.0.0.1 by supergateway (npx) — see [TermuxMcp]. The
 *   server command itself never runs in this app: no Linux env exists
 *   in CodeSathi, so there is no "linux:" transport.
 *
 * The MCP spec's streamable HTTP transport is plain JSON-RPC over POST:
 * one request per call, `Content-Type: application/json`, and the server
 * answers with either a plain JSON object or an SSE stream (`data:` lines
 * carrying JSON-RPC messages). Both shapes are accepted here.
 *
 * Each call does initialize → tools/list or tools/call on one client
 * instance, so a server that hands out an `Mcp-Session-Id` header keeps
 * it for the follow-up call. Nothing is cached across instances: a fresh
 * client per tool call means no stale session can break the next one.
 *
 * Auth: when the server has a token, it is sent as
 * `Authorization: Bearer <token>`. The token itself is never logged.
 */
class McpClient(
    private val url: String,
    private val authToken: String? = null
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var nextId = 1
    private var sessionId: String? = null
    private var initialized = false
    /** Resolved http URL for a "termux:" server (see [TermuxMcp]). */
    private var resolvedUrl: String? = null

    /**
     * The http URL to POST to. For a plain server it is the configured
     * URL; for a "termux:" server it is the local supergateway URL
     * TermuxMcp.ensure started (and reuses it while it answers).
     */
    private suspend fun httpUrl(): String {
        if (!url.startsWith(TERMUX_PREFIX)) return url
        resolvedUrl?.let { return it }
        val resolved = TermuxMcp.ensure(url.removePrefix(TERMUX_PREFIX).trim())
        resolvedUrl = resolved
        return resolved
    }

    /** Ask the server what tools it offers. */
    suspend fun listTools(): List<McpToolInfo> {
        ensureInitialized()
        val result = rpc("tools/list", buildJsonObject {})
        val tools = result["tools"]?.jsonArray.orEmpty()
        return tools.mapNotNull { item ->
            val obj = runCatching { item.jsonObject }.getOrNull() ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val description = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val schema = obj["inputSchema"]?.toString() ?: "{}"
            McpToolInfo(name, description, schema)
        }
    }

    /**
     * Call one tool. Arguments arrive as strings (that is all the agent
     * loop passes); a value that is itself valid JSON — a number, true /
     * false, an object — is sent as that JSON value, everything else as
     * a string. Returns the joined text content of the reply.
     * Throws [McpException] when the server reports the call as an error.
     */
    suspend fun callTool(name: String, args: Map<String, String>): String {
        ensureInitialized()
        val params = buildJsonObject {
            put("name", name)
            put("arguments", buildJsonObject {
                args.forEach { (key, value) -> put(key, parseArgValue(value)) }
            })
        }
        val result = rpc("tools/call", params)
        val content = result["content"]?.jsonArray.orEmpty()
        val texts = content.mapNotNull { item ->
            val obj = runCatching { item.jsonObject }.getOrNull() ?: return@mapNotNull null
            if (obj["type"]?.jsonPrimitive?.contentOrNull == "text") {
                obj["text"]?.jsonPrimitive?.contentOrNull
            } else null
        }
        val joined = texts.joinToString("\n")
        val isError = result["isError"]?.jsonPrimitive?.contentOrNull == "true"
        if (isError) {
            throw McpException(
                if (joined.isNotBlank()) "MCP tool $name reported an error: $joined"
                else "MCP tool $name reported an error."
            )
        }
        return joined.ifBlank { result.toString() }
    }

    /** initialize handshake, then the notifications/initialized notice. */
    private suspend fun ensureInitialized() {
        if (initialized) return
        val params = buildJsonObject {
            put("protocolVersion", "2025-03-26")
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") {
                put("name", "CodeSathi")
                put("version", "1.0")
            }
        }
        rpc("initialize", params)
        // A notification has no id and no reply; if the server rejects it,
        // the handshake already succeeded, so a failure here is not fatal.
        try {
            post(buildJsonObject { put("jsonrpc", "2.0"); put("method", "notifications/initialized") }.toString())
        } catch (_: Exception) {
            // Best-effort only.
        }
        initialized = true
    }

    /** One JSON-RPC request → its "result" object. Throws [McpException]. */
    private suspend fun rpc(method: String, params: JsonObject?): JsonObject {
        val id = nextId++
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }.toString()
        val reply = parseResponse(post(body), method)
        reply["error"]?.jsonObject?.let { err ->
            val message = err["message"]?.jsonPrimitive?.contentOrNull ?: "unknown error"
            throw McpException("MCP server error ($method): $message")
        }
        return reply["result"]?.jsonObject
            ?: throw McpException("MCP server returned an unexpected response for $method.")
    }

    /** One POST. Short timeouts — a hung server must not hang the chat. */
    private suspend fun post(body: String): String = withContext(Dispatchers.IO) {
        val target = try {
            httpUrl()
        } catch (e: McpException) {
            throw e
        } catch (e: Exception) {
            throw McpException(e.message ?: "Could not start the MCP server.")
        }
        val conn = try {
            URL(target).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw McpException("Could not reach MCP server at $url — check the URL and your connection.")
        }
        conn.apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            setRequestProperty("User-Agent", "CodeSathi/1.0 (CodeSathi; Android)")
            if (!authToken.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $authToken")
            }
            sessionId?.let { setRequestProperty("Mcp-Session-Id", it) }
            doOutput = true
        }
        try {
            conn.outputStream.bufferedWriter().use { it.write(body) }
            val code = conn.responseCode
            conn.getHeaderField("Mcp-Session-Id")
                ?.takeIf { it.isNotBlank() }?.let { sessionId = it }
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) {
                throw McpException(
                    "MCP server error HTTP $code: ${text.take(300).ifBlank { "no details from the server" }}"
                )
            }
            text
        } catch (e: McpException) {
            throw e
        } catch (e: IOException) {
            throw McpException("Could not reach MCP server at $url — check the URL and your connection.")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Pull the JSON-RPC message out of a reply: either a plain JSON object,
     * or an SSE stream — `data:` lines, each one a JSON-RPC message.
     * The first message carrying "result" or "error" wins.
     */
    private fun parseResponse(body: String, method: String): JsonObject {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) {
            throw McpException(
                "MCP server returned an empty response for $method — it may not support streamable HTTP."
            )
        }
        val dataLines = trimmed.lines()
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
        val candidates = if (dataLines.isNotEmpty()) dataLines else listOf(trimmed)
        var lastProblem = "no result field"
        for (candidate in candidates) {
            if (candidate.isEmpty() || candidate == "[DONE]") continue
            try {
                val obj = json.parseToJsonElement(candidate).jsonObject
                if (obj.containsKey("result") || obj.containsKey("error")) return obj
            } catch (_: Exception) {
                lastProblem = "invalid JSON"
            }
        }
        throw McpException(
            "MCP server returned an unreadable response for $method ($lastProblem). " +
                "Check that the URL is an MCP server."
        )
    }

    /** A string argument that is itself valid JSON is sent as that value. */
    private fun parseArgValue(value: String): JsonElement {
        return try {
            json.parseToJsonElement(value.trim())
        } catch (_: Exception) {
            JsonPrimitive(value)
        }
    }

    companion object {
        /** A "termux:<command>" server URL runs the command in Termux. */
        const val TERMUX_PREFIX = "termux:"
    }
}

/**
 * Runs a stdio MCP server inside the user's Termux behind supergateway
 * (npx, MIT), which serves it as streamable HTTP on
 * 127.0.0.1:<port>/mcp. Started once; reused while it answers.
 * Adapted from AgentMT's TermuxMcp to CodeSathi's HttpURLConnection
 * stack (no OkHttp) and TermuxBridge.
 *
 * Needs Termux installed and allowed (see TermuxBridge), plus nodejs
 * in Termux (`pkg install nodejs`) so `npx` exists, and a network
 * connection the first time so npx can fetch supergateway. The first
 * start can take up to 2 minutes; later calls reuse the running
 * server. The server command runs inside Termux, not in this app —
 * it sees Termux's home and shared storage, never this app's private
 * folder. If Termux is not set up, every call fails with a plain
 * message and the HTTP MCP servers keep working as before.
 */
object TermuxMcp {
    /** One port per command, stable across calls (18100–18899). */
    fun port(command: String) = 18100 + Math.floorMod(command.hashCode(), 800)

    private fun alive(port: Int): Boolean = runCatching {
        val conn = URL("http://127.0.0.1:$port/healthz").openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "GET"
            connectTimeout = 3_000
            readTimeout = 3_000
        }
        val ok = conn.responseCode in 200..299
        conn.disconnect()
        ok
    }.getOrDefault(false)

    suspend fun ensure(command: String): String {
        if (command.isBlank()) throw McpException("A termux: MCP server needs a command.")
        val port = port(command)
        val url = "http://127.0.0.1:$port/mcp"
        if (alive(port)) return url
        if (!TermuxBridge.ready()) {
            throw McpException(
                "Termux is not connected — install Termux, allow external apps, " +
                    "and grant CodeSathi the Termux permission (see the Terminal screen)."
            )
        }
        val log = "~/.codesathi/mcp-$port.log"
        val start = "mkdir -p ~/.codesathi; command -v npx >/dev/null || " +
            "{ echo 'npx missing: run pkg install nodejs'; exit 3; }; " +
            "nohup npx -y supergateway@4 --stdio ${TermuxBridge.shellQuote(command)} " +
            "--outputTransport streamableHttp --port $port --healthEndpoint /healthz " +
            "> $log 2>&1 & echo started"
        val r = TermuxBridge.run(start, null, 20_000)
            ?: throw McpException("Termux did not answer.")
        if (!r.ok) throw McpException(r.text().take(400))
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            if (alive(port)) return url
            kotlinx.coroutines.delay(1500)
        }
        val tail = TermuxBridge.run("tail -c 800 $log", null, 10_000)?.stdout.orEmpty()
        throw McpException("MCP server in Termux did not start in 2 min. Log:\n$tail")
    }
}
