package com.inyogeshwar.codesathi.remote

import com.inyogeshwar.codesathi.data.MAX_BACKUP_BYTES
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json

/**
 * Multi-device sync client (Phase 6, Step 4).
 *
 * Talks to the OTHER device's remote server — the same hand-rolled
 * HTTP server Remote mode runs, on the same port, with the same
 * Bearer password auth. No new dependency: plain HttpURLConnection,
 * like the LLM client. The password is sent only as an Authorization
 * header, never in a URL and never logged.
 *
 * Two calls, both in the backup format (formatVersion 1):
 * [pullExport] fetches the other device's sessions, [pushImport]
 * sends this device's sessions for the other side to merge. The merge
 * itself (newest updatedAt wins) happens on the receiving side, so a
 * pull and a push are the same shape in both directions.
 *
 * Every failure is a [SyncException] with a plain message the Sync
 * screen shows as-is — a refused connection, a wrong password and a
 * missing password on the other device each get their own wording.
 * This client never runs on the main thread: the ViewModel calls it
 * from Dispatchers.IO.
 */
object SyncClient {

    private val json = Json { ignoreUnknownKeys = true }

    /** Pull the other device's sessions as backup-format JSON text. */
    fun pullExport(host: String, port: Int, password: String): String {
        val bytes = call(host, port, "/api/sync/export", password, "GET", null)
        return String(bytes, Charsets.UTF_8)
    }

    /** Push this device's backup text to the other device; returns what it merged. */
    fun pushImport(host: String, port: Int, password: String, backupText: String): SyncImportResponse {
        val bytes = call(
            host, port, "/api/sync/import", password, "POST",
            backupText.toByteArray(Charsets.UTF_8)
        )
        return try {
            json.decodeFromString<SyncImportResponse>(String(bytes, Charsets.UTF_8))
        } catch (_: Exception) {
            throw SyncException(
                "The other device answered, but its reply could not be read — " +
                    "is it running the same CodeSathi version?"
            )
        }
    }

    /**
     * Clean up what the user typed as the other device's address:
     * drop a pasted "http://" or "https://" prefix, any path after
     * the host, and a trailing ":port" (the Port field governs the
     * port — and the server only ever listens on 8080 anyway, so a
     * pasted Remote-screen URL always carries that same port). An
     * IPv6 address (it still contains ":") is refused plainly — the
     * URL form here only builds IPv4 addresses, and the Remote
     * screen shows an IPv4 address anyway.
     */
    fun normaliseHost(raw: String): String {
        var host = raw.trim()
        if (host.startsWith("http://")) host = host.removePrefix("http://")
        if (host.startsWith("https://")) host = host.removePrefix("https://")
        host = host.substringBefore("/").trim().trimEnd('/')
        if (host.isBlank()) {
            throw SyncException("Type the other device's IP address — it is shown on its Remote screen.")
        }
        // A pasted URL carries its port ("192.168.1.5:8080") — drop a
        // trailing :digits suffix; anything with a ":" left is IPv6.
        val colon = host.lastIndexOf(':')
        if (colon > 0 && host.substring(colon + 1).all { it.isDigit() }) {
            host = host.substring(0, colon)
        }
        if (host.contains(':')) {
            throw SyncException(
                "Use an IPv4 address (like 192.168.1.5) — IPv6 addresses are not supported for sync yet."
            )
        }
        if (host.any { it.isWhitespace() }) {
            throw SyncException("That address has a space in it — type just the IP address.")
        }
        return host
    }

    /** Blank means the remote port (8080); anything else must be 1–65535. */
    fun parsePort(raw: String): Int {
        val text = raw.trim()
        if (text.isBlank()) return RemoteServer.PORT
        val port = text.toIntOrNull()
            ?: throw SyncException("Port must be a number from 1 to 65535.")
        if (port !in 1..65_535) {
            throw SyncException("Port must be a number from 1 to 65535.")
        }
        return port
    }

    private fun call(
        host: String,
        port: Int,
        path: String,
        password: String,
        method: String,
        body: ByteArray?
    ): ByteArray {
        val connection = try {
            (URL("http://$host:$port$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("Authorization", "Bearer $password")
                if (body != null) {
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    doOutput = true
                    setFixedLengthStreamingMode(body.size)
                }
            }
        } catch (e: IOException) {
            throw SyncException(
                "Could not reach the other device — is Remote mode on there, " +
                    "and are both devices on the same Wi-Fi?"
            )
        }
        try {
            if (body != null) {
                try {
                    connection.outputStream.use { it.write(body) }
                } catch (e: IOException) {
                    throw SyncException(
                        "Could not reach the other device — is Remote mode on there, " +
                            "and are both devices on the same Wi-Fi?"
                    )
                }
            }
            val status = try {
                connection.responseCode
            } catch (e: IOException) {
                throw SyncException(
                    "Could not reach the other device — is Remote mode on there, " +
                        "and are both devices on the same Wi-Fi?"
                )
            }
            // The server's error body is JSON {"error": "…"} — surface
            // its plain message instead of inventing one, when it parses.
            if (status == 401) {
                throw SyncException(
                    "Wrong password — it must match the other device's Remote password."
                )
            }
            if (status == 503) {
                throw SyncException(
                    "The other device has no Remote password set — set one on its Remote screen first."
                )
            }
            if (status !in 200..299) {
                val detail = readErrorMessage(connection)
                throw SyncException(
                    if (detail != null) "The other device refused the sync: $detail"
                    else "The other device answered $status — is Remote mode on there?"
                )
            }
            return readCapped(connection)
        } finally {
            connection.disconnect()
        }
    }

    /** Read the reply, refusing anything over the 10 MB backup cap. */
    private fun readCapped(connection: HttpURLConnection): ByteArray {
        val out = ByteArrayOutputStream()
        try {
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n == -1) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_BACKUP_BYTES) {
                        throw SyncException(
                            "The other device sent more than 10 MB — sync refuses " +
                                "backups over 10 MB, like a file restore."
                        )
                    }
                }
            }
        } catch (e: SyncException) {
            throw e
        } catch (e: IOException) {
            throw SyncException(
                "The connection to the other device broke mid-sync — try again."
            )
        }
        return out.toByteArray()
    }

    /** The server's plain error text, or null when it cannot be read. */
    private fun readErrorMessage(connection: HttpURLConnection): String? {
        return try {
            val bytes = connection.errorStream?.readBytes() ?: return null
            val text = String(bytes, Charsets.UTF_8)
            json.decodeFromString<ErrorDto>(text).error
        } catch (_: Exception) {
            null
        }
    }
}

/** A sync failure with a plain message, shown as-is on the Sync screen. */
class SyncException(message: String) : Exception(message)
