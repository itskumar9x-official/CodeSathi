package com.inyogeshwar.codesathi.agent

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * One installed plugin, as stored in DataStore (PreferencesStore).
 * A plugin is a Claude Code layout folder — a manifest plus skills,
 * slash commands and MCP server configs — installed DISABLED, and it
 * NEVER executes code: hooks, scripts, sub-agents and local-command
 * MCP servers are recorded in [unsupported] and ignored, never run.
 * Skills, commands and MCP servers are the only things a plugin can
 * contribute; each of those is listed here so uninstall can remove
 * exactly what the plugin added.
 */
@Serializable
data class PluginRecord(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val source: String,
    val enabled: Boolean = false,
    /** Skill ids the plugin contributes (folder names under skills). */
    val skills: List<String> = emptyList(),
    /** Slash-command names the plugin contributes (without the slash). */
    val commands: List<String> = emptyList(),
    /** MCP server names the plugin contributes (from its .mcp.json). */
    val mcpServers: List<String> = emptyList(),
    /** Things the plugin asked for that CodeSathi never does. */
    val unsupported: List<String> = emptyList(),
    val installedAt: Long = 0L
)

/** The plugin manifest: .claude-plugin/plugin.json (or plugin.json). */
data class PluginManifest(
    val name: String,
    val version: String,
    val description: String,
    /** The raw "mcpServers" object, if the manifest carries one. */
    val mcpServersJson: JSONObject?
)

/** URL-safe plugin id from a name: "My Plugin!" becomes "my-plugin". */
fun pluginIdFor(name: String): String {
    val slug = name.lowercase()
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .split('-').filter { it.isNotEmpty() }.joinToString("-")
    return slug.ifBlank { "plugin" }
}

/**
 * Parse a plugin manifest. Throws [IllegalStateException] when the
 * JSON is unreadable or has no name — the install refuses instead of
 * guessing.
 */
fun parsePluginManifest(bytes: ByteArray): PluginManifest {
    val m = try {
        JSONObject(String(bytes))
    } catch (_: Exception) {
        throw IllegalStateException("plugin.json is not valid JSON.")
    }
    val name = m.optString("name").ifBlank {
        throw IllegalStateException("plugin.json has no name.")
    }
    return PluginManifest(
        name = name,
        version = m.optString("version", "0.0.0"),
        description = m.optString("description"),
        mcpServersJson = m.optJSONObject("mcpServers")
    )
}

/** One entry in a marketplace.json listing (name + path in the repo). */
data class MarketplaceItem(val name: String, val description: String, val path: String)

/**
 * Parse a marketplace.json body into its plugin entries. Only local
 * paths are returned — a marketplace entry pointing at a remote
 * source is skipped, because CodeSathi only installs from a repo or
 * a zip file it can read.
 */
fun parseMarketplaceJson(body: ByteArray, repoPath: String): List<MarketplaceItem> {
    val o = try {
        JSONObject(String(body))
    } catch (_: Exception) {
        return emptyList()
    }
    val arr = o.optJSONArray("plugins") ?: JSONArray()
    val out = ArrayList<MarketplaceItem>()
    for (i in 0 until arr.length()) {
        val p = arr.optJSONObject(i) ?: continue
        val src = p.opt("source") as? String ?: continue
        val path = src.removePrefix("./").trim('/').ifBlank { continue }
        val full = listOf(repoPath, path).filter { it.isNotBlank() }.joinToString("/")
        out += MarketplaceItem(p.optString("name"), p.optString("description"), full)
    }
    return out
}

/** One slash command a plugin contributes, ready to become a CustomCommand. */
data class PluginCommandDraft(val name: String, val description: String, val body: String)

/**
 * Convert a plugin command file to CodeSathi's slash-command shape.
 * The file is Markdown, optionally with YAML front matter carrying a
 * description. CodeSathi's expansion only knows "{input}" (Phase 3,
 * Step 1), so the Claude Code "$ARGUMENTS" placeholder becomes
 * "{input}" and the positional "$1" … "$9" placeholders are dropped —
 * a command that only works through positional arguments loses that
 * part, and the install note says so.
 */
fun convertPluginCommand(name: String, text: String): PluginCommandDraft {
    var body = text
    var description = ""
    if (text.startsWith("---")) {
        val end = text.indexOf("\n---", 3)
        if (end > 0) {
            text.substring(3, end).lines().forEach { line ->
                val i = line.indexOf(':')
                if (i > 0 && !line.startsWith(" ")) {
                    val key = line.substring(0, i).trim()
                    val value = line.substring(i + 1).trim().trim('"', '\'')
                    if (key == "description") description = value
                }
            }
            body = text.substring(end + 4).trimStart()
        }
    }
    if (description.isBlank()) {
        description = body.lines().firstOrNull { it.isNotBlank() }.orEmpty().take(160)
    }
    var converted = body.replace("\$ARGUMENTS", "{input}")
    for (i in 1..9) converted = converted.replace("\$$i", "")
    return PluginCommandDraft(pluginIdFor(name), description, converted.trim())
}

/** What one plugin install produced, for the ViewModel to record. */
data class PluginInstall(
    val record: PluginRecord,
    val commands: List<PluginCommandDraft>,
    /** (server name, http URL) pairs from the plugin's .mcp.json. */
    val mcpServers: List<Pair<String, String>>
)

/**
 * Installs plugins in the Claude Code plugin layout
 * (.claude-plugin/plugin.json plus skills, commands, .mcp.json).
 * Adapted from AgentMT's PluginManager to CodeSathi's storage: the
 * plugin registry lives in DataStore (PreferencesStore), not Room;
 * plugin skills are read from the plugin folder by SkillStore
 * (pluginDirs), plugin commands become CustomCommand records, and
 * plugin MCP servers become McpServer records — all disabled until
 * the user enables the plugin. No plugin code is ever executed:
 * hooks, scripts, sub-agents and local-command MCP servers are
 * recorded as unsupported and ignored.
 */
class PluginManager(private val context: Context, private val github: GitHubFetcher) {
    val root: File get() = File(context.filesDir, "plugins").apply { mkdirs() }

    /**
     * Lists plugins in a marketplace repo, or returns null when [ref]
     * points at a single plugin. A marketplace itself is never
     * installed whole — the caller asks the user to pick one plugin.
     */
    suspend fun marketplace(ref: RepoRef): List<MarketplaceItem>? {
        val top = github.list(ref)
        val pluginDir = runCatching {
            github.list(ref, "${ref.path}/.claude-plugin".trim('/'))
        }.getOrDefault(emptyList())
        val hasPluginJson = top.any { it.name == ".claude-plugin" } &&
            pluginDir.any { it.name == "plugin.json" }
        val mk = pluginDir.firstOrNull { it.name == "marketplace.json" }
        if (mk?.downloadUrl != null && !hasPluginJson) {
            return parseMarketplaceJson(github.download(mk.downloadUrl), ref.path)
        }
        return null
    }

    suspend fun installFromGitHub(ref: RepoRef): PluginInstall {
        val files = github.downloadTree(ref, ref.path)
        return install(files, "github:${ref.display}")
    }

    suspend fun installFromZip(uri: Uri): PluginInstall = withContext(Dispatchers.IO) {
        val files = LinkedHashMap<String, ByteArray>()
        var total = 0L
        context.contentResolver.openInputStream(uri)!!.use { input ->
            ZipInputStream(input).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory || e.name.contains("..")) continue
                    val bytes = z.readBytes()
                    total += bytes.size
                    if (total > 20_000_000) {
                        throw IllegalStateException("Plugin archive is larger than 20 MB.")
                    }
                    files[e.name] = bytes
                }
            }
        }
        // Strip a single top-level folder (GitHub "Download ZIP" style).
        val prefix = files.keys.map { it.substringBefore('/') }.distinct().singleOrNull()
            ?.takeIf { p -> files.keys.all { it.startsWith("$p/") } }
        val norm = if (prefix != null) files.mapKeys { it.key.removePrefix("$prefix/") } else files
        install(norm, "zip")
    }

    private suspend fun install(files: Map<String, ByteArray>, source: String): PluginInstall =
        withContext(Dispatchers.IO) {
            val manifestBytes = files[".claude-plugin/plugin.json"] ?: files["plugin.json"]
                ?: throw IllegalStateException(
                    "No .claude-plugin/plugin.json found. Is this a Claude Code plugin?"
                )
            val manifest = parsePluginManifest(manifestBytes)
            val id = pluginIdFor(manifest.name)
            val dir = File(root, id)
            if (dir.exists()) dir.deleteRecursively()
            dir.mkdirs()
            val skills = LinkedHashSet<String>()
            val commands = ArrayList<PluginCommandDraft>()
            val unsupported = LinkedHashSet<String>()
            for ((path, bytes) in files) {
                when {
                    path.startsWith("skills/") -> {
                        skills += path.removePrefix("skills/").substringBefore('/')
                        write(dir, path, bytes)
                    }
                    path.startsWith("commands/") && path.endsWith(".md") -> {
                        val name = path.removePrefix("commands/").removeSuffix(".md")
                        commands += convertPluginCommand(name, String(bytes))
                    }
                    path.startsWith("hooks/") -> unsupported += "hooks (never executed)"
                    path.startsWith("agents/") -> unsupported += "sub-agents"
                    path.startsWith("scripts/") || path.startsWith("bin/") ->
                        unsupported += "scripts (never executed)"
                    path == ".mcp.json" || path == ".claude-plugin/plugin.json" ||
                        path.endsWith("README.md") || path == "LICENSE" -> write(dir, path, bytes)
                }
            }
            val mcpJson = files[".mcp.json"]?.let { runCatching { JSONObject(String(it)) }.getOrNull() }
                ?: manifest.mcpServersJson?.let { JSONObject().put("mcpServers", it) }
            val mcp = ArrayList<Pair<String, String>>()
            mcpJson?.optJSONObject("mcpServers")?.let { servers ->
                servers.keys().forEach { key ->
                    val s = servers.getJSONObject(key)
                    val url = s.optString("url")
                    if (url.startsWith("http") && !url.contains("\${")) {
                        mcp += key to url
                    } else {
                        unsupported += "MCP server \"$key\" (" +
                            (if (s.has("command")) "local command" else "needs configuration") + ")"
                    }
                }
            }
            val record = PluginRecord(
                id = id, name = manifest.name, version = manifest.version,
                description = manifest.description, source = source, enabled = false,
                skills = skills.filter { it.isNotBlank() },
                commands = commands.map { it.name },
                mcpServers = mcp.map { it.first },
                unsupported = unsupported.toList(),
                installedAt = System.currentTimeMillis()
            )
            PluginInstall(record, commands, mcp)
        }

    /** Delete a plugin's folder. Records are removed by the caller. */
    fun uninstall(id: String) {
        File(root, pluginIdFor(id)).deleteRecursively()
    }

    /** (plugin name, folder) pairs for the enabled plugins, for SkillStore. */
    fun enabledDirs(plugins: List<PluginRecord>): List<Pair<String, File>> =
        plugins.filter { it.enabled }.map { it.name to File(root, it.id) }

    private fun write(dir: File, rel: String, bytes: ByteArray) {
        val f = File(dir, rel).canonicalFile
        if (!f.path.startsWith(dir.canonicalPath + File.separator)) return
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
    }
}
