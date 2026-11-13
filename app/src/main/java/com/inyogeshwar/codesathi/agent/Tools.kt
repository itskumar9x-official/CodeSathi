package com.inyogeshwar.codesathi.agent

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.FileNotFoundException

/**
 * v1 tool set: Read / Write / Edit / Glob / Grep only.
 * No shell execution in v1 (Phase 2). Every tool works on the project folder
 * the user picked with the system folder picker (SAF tree URI).
 *
 * Safety core: [ToolContext.readOnly] (Plan mode) hard-disables Write/Edit
 * in code — [AgentRunner] never calls them when it is true, and the tools
 * themselves refuse as well. Nothing is written before the user approves.
 */
data class ToolContext(
    val readOnly: Boolean,
    val projectUri: Uri,
    /** The user-facing folder name, for messages. */
    val folderName: String
)

sealed class ToolResult {
    data class Ok(val text: String) : ToolResult()
    data class Failed(val reason: String) : ToolResult()
}

interface Tool {
    val name: String
    val description: String
    suspend fun run(args: Map<String, String>, ctx: ToolContext): ToolResult
}

/** File access through the SAF tree the user granted. */
class SafProject(private val context: Context, private val treeUri: Uri) {

    val root: DocumentFile?
        get() = DocumentFile.fromTreeUri(context, treeUri)

    /** True when the folder is still reachable (grant intact, folder exists). */
    fun isAvailable(): Boolean {
        return try {
            root?.exists() == true && root?.canRead() == true
        } catch (_: SecurityException) {
            false
        }
    }

    /** Find a file under the root by relative path ("index.html", "src/App.kt"). */
    fun find(relativePath: String): DocumentFile? {
        var current = root ?: return null
        for (part in relativePath.trim('/').split('/')) {
            if (part.isBlank() || part == ".") continue
            if (part == "..") return null // never escape the project folder
            current = current.findFile(part) ?: return null
        }
        return current
    }

    fun readText(relativePath: String): String? {
        val file = find(relativePath) ?: return null
        return context.contentResolver.openInputStream(file.uri)
            ?.bufferedReader().use { it?.readText() }
    }

    /** Raw bytes of a file, or null when it cannot be read. The editor
     *  uses this to sniff binary files before trying to decode text. */
    fun readBytes(relativePath: String): ByteArray? {
        val file = find(relativePath) ?: return null
        return context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
    }

    /** Creates parent folders as needed, then writes (or overwrites) the file. */
    fun writeText(relativePath: String, content: String): Boolean {
        val parts = relativePath.trim('/').split('/').filter { it.isNotBlank() }
        if (parts.isEmpty() || parts.any { it == ".." }) return false
        var dir = root ?: return false
        for (part in parts.dropLast(1)) {
            dir = dir.findFile(part)?.takeIf { it.isDirectory }
                ?: dir.createDirectory(part)
                ?: return false
        }
        val name = parts.last()
        val existing = dir.findFile(name)
        val file = if (existing != null && existing.isFile) {
            existing
        } else {
            existing?.delete()
            dir.createFile("text/plain", name) ?: return false
        }
        return try {
            context.contentResolver.openOutputStream(file.uri, "wt")
                ?.bufferedWriter().use { it?.write(content) }
            true
        } catch (_: FileNotFoundException) {
            false
        }
    }

    fun listFiles(relativeDir: String = ""): List<String> {
        val dir = if (relativeDir.isBlank()) root else find(relativeDir)
        return dir?.listFiles().orEmpty().mapNotNull { it.name }
    }

    /** Top-level entries of a folder, with their kind — the editor's file
     *  list. Directories are listed but cannot be opened (a fuller tree
     *  browser is Phase 3, Step 5). */
    fun listEntries(relativeDir: String = ""): List<SafEntry> {
        val dir = if (relativeDir.isBlank()) root else find(relativeDir)
        return dir?.listFiles().orEmpty().mapNotNull { file ->
            file.name?.let { SafEntry(it, file.isDirectory) }
        }
    }
}

/** One entry in a project folder listing (see [SafProject.listEntries]). */
data class SafEntry(val name: String, val isDirectory: Boolean)

class ReadTool(private val project: SafProject) : Tool {
    override val name = "read"
    override val description = "Read a file in the project folder."
    override suspend fun run(args: Map<String, String>, ctx: ToolContext): ToolResult {
        val path = args["path"] ?: return ToolResult.Failed("read needs a path")
        val text = project.readText(path) ?: return ToolResult.Failed("File not found: $path")
        return ToolResult.Ok(text)
    }
}

class GlobTool(private val project: SafProject) : Tool {
    override val name = "glob"
    override val description = "List files in the project folder."
    override suspend fun run(args: Map<String, String>, ctx: ToolContext): ToolResult {
        return ToolResult.Ok(project.listFiles(args["dir"].orEmpty()).joinToString("\n"))
    }
}

class GrepTool(private val project: SafProject) : Tool {
    override val name = "grep"
    override val description = "Search file contents for a text snippet (top-level files only in v1)."
    override suspend fun run(args: Map<String, String>, ctx: ToolContext): ToolResult {
        val needle = args["query"] ?: return ToolResult.Failed("grep needs a query")
        val hits = project.listFiles().mapNotNull { name ->
            val text = project.readText(name) ?: return@mapNotNull null
            if (text.contains(needle)) name else null
        }
        return ToolResult.Ok(if (hits.isEmpty()) "No matches for \"$needle\"." else hits.joinToString("\n"))
    }
}

class WriteTool(private val project: SafProject) : Tool {
    override val name = "write"
    override val description = "Create or overwrite a file. Disabled in Plan mode."
    override suspend fun run(args: Map<String, String>, ctx: ToolContext): ToolResult {
        if (ctx.readOnly) return ToolResult.Failed("Plan mode is read-only — writes are disabled.")
        val path = args["path"] ?: return ToolResult.Failed("write needs a path")
        val content = args["content"] ?: return ToolResult.Failed("write needs content")
        return if (project.writeText(path, content)) ToolResult.Ok("Wrote $path (${content.length} chars).")
        else ToolResult.Failed("Could not write $path.")
    }
}

class EditTool(private val project: SafProject) : Tool {
    override val name = "edit"
    override val description = "Replace one exact snippet in a file. Disabled in Plan mode."
    override suspend fun run(args: Map<String, String>, ctx: ToolContext): ToolResult {
        if (ctx.readOnly) return ToolResult.Failed("Plan mode is read-only — edits are disabled.")
        val path = args["path"] ?: return ToolResult.Failed("edit needs a path")
        val old = args["old"] ?: return ToolResult.Failed("edit needs the old snippet")
        val new = args["new"] ?: return ToolResult.Failed("edit needs the new snippet")
        val text = project.readText(path) ?: return ToolResult.Failed("File not found: $path")
        if (!text.contains(old)) return ToolResult.Failed("Snippet not found in $path.")
        return if (project.writeText(path, text.replace(old, new, ignoreCase = false))) {
            ToolResult.Ok("Edited $path.")
        } else {
            ToolResult.Failed("Could not write $path.")
        }
    }
}

/** All v1 tools, wired to one project folder. */
fun v1Tools(project: SafProject): List<Tool> =
    listOf(ReadTool(project), GlobTool(project), GrepTool(project), WriteTool(project), EditTool(project))
