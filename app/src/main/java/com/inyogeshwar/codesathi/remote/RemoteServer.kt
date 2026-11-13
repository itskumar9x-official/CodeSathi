package com.inyogeshwar.codesathi.remote

import com.inyogeshwar.codesathi.agent.Plan
import com.inyogeshwar.codesathi.agent.PlannedFile
import com.inyogeshwar.codesathi.data.Message
import com.inyogeshwar.codesathi.data.Session
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Phase 2, Step 7 — Remote mode: a tiny local-network HTTP server so the
 * user can chat with the agent from another device on the same Wi-Fi.
 *
 * No server library is used: this is a minimal HTTP/1.1 server on
 * java.net.ServerSocket (about 200 lines). That keeps the app free of a
 * new dependency for four small routes, and every byte it serves is
 * visible here. com.sun.net.httpserver is NOT used — it is a JDK API,
 * not part of the Android runtime.
 *
 * LAN only, plain HTTP (no HTTPS), no streaming: POST /api/prompt runs
 * one agent turn and returns the final reply as JSON. Chat only — no
 * remote file browsing, no remote terminal, no remote approve.
 *
 * Fixed port [PORT] (8080), also shown on the Remote screen.
 * Auth: every /api/* call needs `Authorization: Bearer <password>`,
 * compared in constant time against the password in KeyStore
 * (EncryptedSharedPreferences). Wrong / missing password → 401.
 * The password is never logged and never appears in a URL.
 * The web page itself (GET /) needs no password, but every fetch it
 * makes sends the password the user types into the page.
 */
class RemoteServer(
    private val handler: RemoteHandler,
    private val passwordProvider: () -> String?
) {

    /** What the server needs from the app: session lists, messages, turns. */
    interface RemoteHandler {
        suspend fun listSessions(): List<Session>
        suspend fun getSessionMessages(sessionId: String): List<Message>?
        suspend fun runTurn(sessionId: String, message: String): RemoteTurnResult
    }

    companion object {
        /** Fixed port for remote mode — shown on the Remote screen. */
        const val PORT = 8080

        /**
         * This phone's Wi-Fi (LAN) IPv4 address, e.g. "192.168.1.5".
         * Prefers a wlan interface; falls back to any site-local IPv4.
         * Null when the phone is not on Wi-Fi / has no LAN address.
         */
        fun wifiIpAddress(): String? {
            var fallback: String? = null
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
                for (iface in interfaces) {
                    if (!iface.isUp || iface.isLoopback) continue
                    for (address in iface.inetAddresses) {
                        if (address !is Inet4Address) continue
                        if (address.isLoopbackAddress) continue
                        val host = address.hostAddress ?: continue
                        if (!address.isSiteLocalAddress) continue
                        if (iface.name.startsWith("wlan")) return host
                        if (fallback == null) fallback = host
                    }
                }
            } catch (_: Exception) {
                return fallback
            }
            return fallback
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    var isRunning = false
        private set

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    /** Start listening. Returns false when the port is already in use. */
    fun start(): Boolean {
        synchronized(this) {
            if (isRunning) return true
            return try {
                val socket = ServerSocket(PORT, 50, InetAddress.getByName("0.0.0.0"))
                serverSocket = socket
                isRunning = true
                acceptThread = Thread({
                    while (isRunning) {
                        try {
                            val client = socket.accept()
                            Thread({ handleConnection(client) }, "codesathi-remote").start()
                        } catch (_: Exception) {
                            // Socket closed by stop() — the loop ends.
                            break
                        }
                    }
                }, "codesathi-remote-accept").also { it.isDaemon = true; it.start() }
                true
            } catch (_: Exception) {
                isRunning = false
                serverSocket = null
                false
            }
        }
    }

    /** Stop listening and drop every open connection's socket. */
    fun stop() {
        synchronized(this) {
            isRunning = false
            try {
                serverSocket?.close()
            } catch (_: Exception) {
                // Already closed — nothing to do.
            }
            serverSocket = null
            acceptThread = null
        }
    }

    // ---- One connection ----

    private fun handleConnection(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                sendJson(output, 400, json.encodeToString(ErrorDto("Bad request.")))
                return
            }
            val method = parts[0].uppercase()
            val rawPath = parts[1].substringBefore("?")
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] =
                        line.substring(colon + 1).trim()
                }
            }
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val bodyBytes = if (contentLength > 0) readExact(input, contentLength) else ByteArray(0)
            dispatch(method, rawPath, headers, bodyBytes, output)
        } catch (_: Exception) {
            // A dropped / malformed connection is not worth crashing over.
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
                // Already closed.
            }
        }
    }

    private fun dispatch(
        method: String,
        rawPath: String,
        headers: Map<String, String>,
        body: ByteArray,
        output: OutputStream
    ) {
        // The web page itself is public; everything under /api/* needs the
        // password, and so does every fetch the page makes.
        if (method == "GET" && rawPath == "/") {
            sendHtml(output, WEB_PAGE)
            return
        }
        if (!rawPath.startsWith("/api/")) {
            sendJson(output, 404, json.encodeToString(ErrorDto("Not found.")))
            return
        }
        val password = passwordProvider()
        if (password.isNullOrBlank()) {
            sendJson(output, 503, json.encodeToString(ErrorDto("Remote password is not set — set one on the phone first.")))
            return
        }
        if (!isAuthorised(headers["authorization"], password)) {
            sendJson(output, 401, json.encodeToString(ErrorDto("Wrong password.")))
            return
        }
        when {
            method == "GET" && rawPath == "/api/sessions" -> {
                val sessions = runBlocking { handler.listSessions() }
                sendJson(output, 200, json.encodeToString(sessions.map { it.toDto() }))
            }
            method == "GET" && rawPath.startsWith("/api/session/") -> {
                val id = rawPath.removePrefix("/api/session/").decodePathSegment()
                if (id.isBlank()) {
                    sendJson(output, 400, json.encodeToString(ErrorDto("Missing session id.")))
                    return
                }
                val messages = runBlocking { handler.getSessionMessages(id) }
                if (messages == null) {
                    sendJson(output, 404, json.encodeToString(ErrorDto("Unknown session.")))
                } else {
                    sendJson(output, 200, json.encodeToString(messages.map { it.toDto() }))
                }
            }
            method == "POST" && rawPath == "/api/prompt" -> {
                val request = try {
                    json.decodeFromString<PromptRequest>(body.decodeToString())
                } catch (_: Exception) {
                    sendJson(output, 400, json.encodeToString(ErrorDto("Send JSON {\"sessionId\": \"…\", \"message\": \"…\"}.")))
                    return
                }
                if (request.sessionId.isBlank() || request.message.isBlank()) {
                    sendJson(output, 400, json.encodeToString(ErrorDto("sessionId and message must not be empty.")))
                    return
                }
                // A turn can take a while (LLM + tools) — no socket timeout
                // while it runs; the connection stays open until it finishes.
                val result = runBlocking { handler.runTurn(request.sessionId, request.message) }
                sendJson(output, 200, json.encodeToString(result))
            }
            else -> sendJson(output, 404, json.encodeToString(ErrorDto("Not found.")))
        }
    }

    /** Constant-time check, so timing cannot leak how much matched. */
    private fun isAuthorised(header: String?, password: String): Boolean {
        if (header == null) return false
        if (!header.startsWith("Bearer ")) return false
        val supplied = header.removePrefix("Bearer ").trim()
        if (supplied.isEmpty()) return false
        return MessageDigest.isEqual(
            supplied.toByteArray(Charsets.UTF_8),
            password.toByteArray(Charsets.UTF_8)
        )
    }

    private fun String.decodePathSegment(): String = try {
        URLDecoder.decode(this, "UTF-8")
    } catch (_: Exception) {
        this
    }

    // ---- Small HTTP helpers (raw bytes, no chunked encoding) ----

    /** Read one CRLF-terminated line, byte by byte. Null on end of stream. */
    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte == -1) return if (bytes.size() == 0) null else bytes.toString("UTF-8")
            if (byte == '\n'.code) break
            if (byte != '\r'.code) bytes.write(byte)
        }
        return bytes.toString("UTF-8")
    }

    private fun readExact(input: BufferedInputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(bytes, read, length - read)
            if (n == -1) break
            read += n
        }
        return if (read == length) bytes else bytes.copyOf(read)
    }

    private fun sendJson(output: OutputStream, status: Int, bodyJson: String) {
        send(output, status, "application/json; charset=utf-8", bodyJson.toByteArray(Charsets.UTF_8))
    }

    private fun sendHtml(output: OutputStream, html: String) {
        send(output, 200, "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8))
    }

    private fun send(output: OutputStream, status: Int, contentType: String, body: ByteArray) {
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            503 -> "Service Unavailable"
            else -> "OK"
        }
        val head = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.UTF_8))
        output.write(body)
        output.flush()
    }
}

/** One agent turn, as the remote page (and any API client) sees it. */
@Serializable
data class RemoteTurnResult(
    /** The assistant's reply text (empty when the turn failed). */
    val reply: String,
    /** One line per tool message the turn wrote to the chat. */
    val tools: List<String> = emptyList(),
    /** Error message when the turn did not run / failed, else null. */
    val error: String? = null,
    /**
     * A file change the turn proposed. NOT applied — approve or reject it
     * on the phone's diff card. Null when the turn proposed nothing.
     */
    val proposal: PlannedFile? = null,
    /** The plan card of a Plan-mode turn, if the turn made one. */
    val plan: Plan? = null,
    /** One-line notice when the turn switched to another key / model. */
    val fallbackNotice: String? = null
)

@Serializable
data class RemoteSessionDto(
    val id: String,
    val title: String,
    val folderName: String,
    val providerId: String,
    val modelId: String,
    val agentMode: String,
    val updatedAt: Long
)

@Serializable
data class RemoteMessageDto(
    val role: String,
    val text: String,
    val createdAt: Long
)

@Serializable
data class PromptRequest(val sessionId: String, val message: String)

@Serializable
data class ErrorDto(val error: String)

private fun Session.toDto() = RemoteSessionDto(
    id = id,
    title = title,
    folderName = folderName,
    providerId = providerId,
    modelId = modelId,
    agentMode = agentMode,
    updatedAt = updatedAt
)

private fun Message.toDto() = RemoteMessageDto(
    role = role,
    text = text,
    createdAt = createdAt
)

/**
 * The remote web page: plain HTML, no frameworks, small inline JS with
 * fetch. The password is typed into the page and kept only in a JS
 * variable for this page load — it is never stored, never put in a URL,
 * and sent only as an Authorization header.
 */
private val WEB_PAGE = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>CodeSathi Remote</title>
<style>
body { font-family: sans-serif; max-width: 720px; margin: 0 auto; padding: 16px; }
#chat { border: 1px solid #ccc; height: 50vh; overflow-y: auto; padding: 8px; white-space: pre-wrap; }
.user { color: #1565c0; } .assistant { color: #222; } .tool { color: #666; font-size: 0.9em; }
input, select, button { font-size: 1em; margin: 4px 0; padding: 8px; }
#msg { width: 70%; } #err { color: #c62828; }
</style>
</head>
<body>
<h1>CodeSathi Remote</h1>
<p>Chat with the agent on your phone, from this device. Same Wi-Fi only.</p>
<label>Password <input id="pw" type="password" autocomplete="off" placeholder="Remote password"></label>
<button onclick="loadSessions()">Connect</button>
<p id="err"></p>
<label>Session <select id="sessions" onchange="loadMessages()"></select></label>
<div id="chat"></div>
<p>
<input id="msg" type="text" placeholder="Ask the agent…" onkeydown="if (event.key === 'Enter') send()">
<button onclick="send()">Send</button>
</p>
<p id="note"></p>
<script>
var pw = "";
function headers() { return { "Content-Type": "application/json", "Authorization": "Bearer " + pw }; }
function showErr(t) { document.getElementById("err").textContent = t || ""; }
function addLine(role, text) {
  var div = document.createElement("div");
  div.className = role;
  div.textContent = role + ": " + text;
  document.getElementById("chat").appendChild(div);
  document.getElementById("chat").scrollTop = document.getElementById("chat").scrollHeight;
}
function loadSessions() {
  pw = document.getElementById("pw").value;
  if (!pw) { showErr("Type the remote password first."); return; }
  showErr("");
  fetch("/api/sessions", { headers: headers() }).then(function (r) {
    if (r.status === 401) { showErr("Wrong password."); return []; }
    if (!r.ok) { showErr("Could not load sessions (" + r.status + ")."); return []; }
    return r.json();
  }).then(function (list) {
    var sel = document.getElementById("sessions");
    sel.innerHTML = "";
    list.forEach(function (s) {
      var opt = document.createElement("option");
      opt.value = s.id;
      opt.textContent = s.title + " (" + s.providerId + " / " + s.modelId + ", " + s.agentMode + ")";
      sel.appendChild(opt);
    });
    if (list.length === 0) showErr("No sessions yet — create one in the app first.");
    loadMessages();
  }).catch(function () { showErr("Could not reach the phone. Same Wi-Fi?"); });
}
function loadMessages() {
  var id = document.getElementById("sessions").value;
  if (!id) return;
  fetch("/api/session/" + encodeURIComponent(id), { headers: headers() }).then(function (r) {
    return r.json();
  }).then(function (list) {
    document.getElementById("chat").innerHTML = "";
    list.forEach(function (m) { addLine(m.role, m.text); });
  });
}
function send() {
  var id = document.getElementById("sessions").value;
  var box = document.getElementById("msg");
  var text = box.value.trim();
  if (!id || !text) return;
  box.value = "";
  addLine("user", text);
  document.getElementById("note").textContent = "Thinking…";
  fetch("/api/prompt", {
    method: "POST",
    headers: headers(),
    body: JSON.stringify({ sessionId: id, message: text })
  }).then(function (r) { return r.json(); }).then(function (res) {
    document.getElementById("note").textContent = "";
    if (res.error) { showErr(res.error); return; }
    (res.tools || []).forEach(function (t) { addLine("tool", t); });
    if (res.reply) addLine("assistant", res.reply);
    if (res.plan) addLine("assistant", "Plan ready — see the plan card in the app.");
    if (res.proposal) {
      document.getElementById("note").textContent =
        "File change proposed for " + res.proposal.path + " — approve or reject it on the phone. Nothing was written.";
    }
    if (res.fallbackNotice) addLine("tool", res.fallbackNotice);
  }).catch(function () { document.getElementById("note").textContent = ""; showErr("Request failed — is the phone still on?"); });
}
</script>
</body>
</html>
""".trimIndent()
