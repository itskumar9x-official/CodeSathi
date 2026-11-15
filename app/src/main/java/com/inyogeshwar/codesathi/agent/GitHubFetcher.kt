package com.inyogeshwar.codesathi.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Parsed reference like owner/repo[/path][@ref], from a GitHub or
 * skills.sh URL or shorthand. Adapted from AgentMT's GitHubFetcher
 * (RepoRef) to CodeSathi's HttpURLConnection stack — no OkHttp here.
 *
 * Accepted forms:
 * - https://github.com/owner/repo/tree/<ref>/<path> (or /blob/…/SKILL.md)
 * - https://skills.sh/owner/repo[/path] (or the "npx skills add …" line)
 * - owner/repo[/path][@ref] shorthand
 */
data class RepoRef(val owner: String, val repo: String, val path: String = "", val ref: String? = null) {
    val display get() = "$owner/$repo" + if (path.isNotEmpty()) "/$path" else ""

    companion object {
        fun parse(input: String): RepoRef? {
            var s = input.trim().removeSuffix("/").removeSuffix(".git")
            s = s.removePrefix("https://").removePrefix("http://").removePrefix("www.")
            var ref: String? = null
            when {
                s.startsWith("github.com/") -> {
                    s = s.removePrefix("github.com/")
                    val m = Regex("^([^/]+)/([^/]+)/(?:tree|blob)/([^/]+)/?(.*)$").find(s)
                    if (m != null) return RepoRef(
                        m.groupValues[1], m.groupValues[2],
                        m.groupValues[4].removeSuffix("/SKILL.md"), m.groupValues[3]
                    )
                }
                s.startsWith("skills.sh/") -> s = s.removePrefix("skills.sh/")
                s.startsWith("npx skills add ") -> s = s.removePrefix("npx skills add ").trim()
            }
            if ('@' in s) {
                ref = s.substringAfter('@')
                s = s.substringBefore('@')
            }
            val parts = s.split('/').filter { it.isNotBlank() }
            if (parts.size < 2) return null
            return RepoRef(parts[0], parts[1], parts.drop(2).joinToString("/"), ref)
        }
    }
}

data class RemoteEntry(val name: String, val path: String, val type: String, val downloadUrl: String?, val size: Long)

/**
 * Read-only GitHub contents API client (unauthenticated: 60 requests
 * per hour — a big plugin install can hit that, and the error says so
 * plainly; there is no token field in this app, so no token is sent).
 * Plain HttpURLConnection, same style as [LlmClient] and [McpClient].
 */
class GitHubFetcher {
    /** Plain blocking GET — callers already run on Dispatchers.IO. */
    private fun get(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "CodeSathi/1.2 (CodeSathi; Android)")
        }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.readBytes().orEmpty()
        conn.disconnect()
        if (code == 403 && String(body).contains("rate limit")) {
            throw IllegalStateException(
                "GitHub rate limit reached (unauthenticated: 60 requests per hour). Try again in an hour."
            )
        }
        if (code == 404) throw IllegalStateException("Not found on GitHub.")
        if (code !in 200..299) throw IllegalStateException("GitHub HTTP $code")
        body
    }

    /** One directory listing (or a single file, wrapped as one entry). */
    suspend fun list(r: RepoRef, path: String = r.path): List<RemoteEntry> = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/${r.owner}/${r.repo}/contents/${path.trim('/')}" +
            (r.ref?.let { "?ref=$it" } ?: "")
        val body = String(get(url))
        val arr = if (body.trimStart().startsWith("[")) JSONArray(body)
        else JSONArray().put(JSONObject(body))
        (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            RemoteEntry(
                it.optString("name"), it.optString("path"), it.optString("type"),
                it.optString("download_url").ifBlank { null }, it.optLong("size")
            )
        }
    }

    suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "CodeSathi/1.2 (CodeSathi; Android)")
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw IllegalStateException("Download failed: HTTP $code")
        }
        val bytes = conn.inputStream.readBytes()
        conn.disconnect()
        bytes
    }

    /**
     * Downloads every file under [path] (max 80 files, 4 MB total).
     * Keys are paths relative to [path].
     */
    suspend fun downloadTree(r: RepoRef, path: String): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        var total = 0L
        suspend fun walk(p: String) {
            for (e in list(r, p)) {
                if (out.size >= 80 || total > 4_000_000) return
                when (e.type) {
                    "dir" -> walk(e.path)
                    "file" -> e.downloadUrl?.let { u ->
                        val bytes = download(u)
                        total += bytes.size
                        out[e.path.removePrefix(path.trim('/')).trimStart('/')] = bytes
                    }
                }
            }
        }
        walk(path)
        return out
    }

    /**
     * Finds skill folders (folders that contain SKILL.md) at [r.path]
     * or one level below (for example skills/<name>).
     */
    suspend fun findSkills(r: RepoRef): List<RemoteEntry> {
        val top = list(r)
        if (top.any { it.name == "SKILL.md" }) {
            return listOf(
                RemoteEntry(
                    r.path.substringAfterLast('/').ifEmpty { r.repo },
                    r.path, "dir", null, 0
                )
            )
        }
        val found = ArrayList<RemoteEntry>()
        val dirs = top.filter { it.type == "dir" }
        val skillsDir = dirs.firstOrNull { it.name == "skills" }
        val scan = if (skillsDir != null) list(r, skillsDir.path).filter { it.type == "dir" } else dirs
        for (d in scan.take(40)) {
            val inside = runCatching { list(r, d.path) }.getOrDefault(emptyList())
            if (inside.any { it.name == "SKILL.md" }) found += d
        }
        return found
    }
}
/** One directory listing (or a single file, wrapped as one entry). */