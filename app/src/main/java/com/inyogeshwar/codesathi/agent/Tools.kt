package com.inyogeshwar.codesathi.agent

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v1 tool set: Read / Write / Edit / Glob / Grep, plus the `run` shell
 * tool (Phase 6, Step 1 — it reuses the terminal screen's
 * [TerminalRunner], there is no second shell executor).
 * Every tool works on the project folder the user picked with the
 * system folder picker (SAF tree URI).
 *
 * Safety core: [ToolContext.readOnly] (Plan mode) hard-disables Write/Edit
 * in code — [AgentRunner] never calls them when it is true, and the tools
 * themselves refuse as well. Nothing is written before the user approves.
 * The `run` tool refuses write-like commands in Plan mode too (see
 * [planModeRunRefusal]) — a heuristic screen, not a sandbox; see its KDoc.
 */
data class ToolContext(
    val readOnly: Boolean,
    val projectUri: Uri,
    /** The user-facing folder name, for messages. */
    val folderName: String
)

sealed class ToolResult {
    /**
     * [exitCode] is set only by the `run` tool (Phase 6, Step 1): the
     * shell's exit code, so the runner can count non-zero runs for the
     * auto-fix bound without parsing the result text. Every other tool
     * leaves it null.
     */
    data class Ok(val text: String, val exitCode: Int? = null) : ToolResult()
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

/** Commands whose first word plainly writes files, for the Plan-mode
 *  screen on the `run` tool (see [planModeRunRefusal]). */
private val RUN_WRITE_COMMANDS = setOf(
    "rm", "mv", "cp", "touch", "mkdir", "rmdir", "tee", "chmod", "chown",
    "ln", "install", "dd", "truncate", "unlink", "shred", "mkfifo", "mknod"
)

/**
 * Plan-mode screen for `run`: returns the refusal reason when [command]
 * plainly tries to write — a write command, `sed -i`, or an unquoted `>`
 * redirection — or null when it looks like a read-only command.
 *
 * Segments are split on `;`, `&&`, `||`, `|` and newlines outside quotes,
 * so `ls; rm -rf out` is caught on its second segment, and a `>` inside
 * quotes (`grep "a>b" file`) is not mistaken for a redirection (an
 * unquoted `>` IS a redirection in the shell, so refusing it is correct).
 *
 * This is a heuristic screen, NOT a sandbox: a command can still write
 * through an interpreter (`python3 -c ...`, `node -e ...`) or a tool this
 * list does not name, so Plan mode's hard guarantee stays what it always
 * was — the write / edit tools refuse, and no file proposal is ever
 * applied without Approve. The screen catches the ordinary cases
 * (`> file`, `rm`, `mkdir`, ...) so a plan turn cannot casually rewrite
 * the project through the shell.
 */
private fun planModeRunRefusal(command: String): String? {
    var inSingle = false
    var inDouble = false
    var escaped = false
    var segmentStart = 0
    // Check one segment (text between shell separators); [end] is exclusive.
    fun checkSegment(end: Int): String? {
        val segment = command.substring(segmentStart, end).trim()
        if (segment.isEmpty()) return null
        val words = segment.split(Regex("\\s+"))
        val firstWord = words.firstOrNull().orEmpty()
        if (firstWord in RUN_WRITE_COMMANDS) {
            return "Plan mode is read-only — \"$firstWord\" writes files. Switch to Build to change files."
        }
        if (firstWord == "sed" && words.drop(1).any {
                it == "-i" || (it.startsWith("-") && !it.startsWith("--") && 'i' in it)
            }) {
            return "Plan mode is read-only — \"sed -i\" writes files. Switch to Build to change files."
        }
        return null
    }
    var i = 0
    while (i < command.length) {
        val c = command[i]
        if (escaped) {
            escaped = false
            i++
            continue
        }
        when {
            c == '\\' && !inSingle -> escaped = true
            c == '\'' && !inDouble -> inSingle = !inSingle
            c == '"' && !inSingle -> inDouble = !inDouble
            !inSingle && !inDouble && c == '>' ->
                return "Plan mode is read-only — output redirection (\">\") writes files. Switch to Build to change files."
            !inSingle && !inDouble && (c == ';' || c == '\n' || c == '&' || c == '|') -> {
                checkSegment(i)?.let { return it }
                // Skip the second char of && / ||.
                if ((c == '&' || c == '|') && i + 1 < command.length && command[i + 1] == c) i++
                segmentStart = i + 1
            }
        }
        i++
    }
    return checkSegment(command.length)
}

/**
 * Phase 6, Step 1 — the agent's shell tool.
 *
 * Runs `sh -c <command>` in the session's project folder through the
 * SAME [TerminalRunner] the terminal screen uses — there is no second
 * shell executor. Returns the exit code plus the merged stdout + stderr,
 * capped at the same [TERMINAL_MAX_LINES] lines as the terminal screen.
 *
 * Gating: the same allow / ask / deny per-tool rule as every other tool
 * ([AgentRunner] consults `toolRule("run")` before calling it), default
 * "ask" (see `DEFAULT_TOOL_RULES` in PreferencesStore). A "deny" rule
 * refuses the call before anything runs.
 *
 * Plan mode: the tool stays available — running a command only reads its
 * output — but write-like commands are refused by [planModeRunRefusal],
 * exactly like the write / edit tools refuse in Plan mode. That screen
 * is a heuristic, not a sandbox (see its KDoc).
 *
 * Folder: the project folder is resolved with the same
 * [resolveTerminalFolder] as the terminal screen. Unlike the terminal
 * screen, an unresolved folder REFUSES the run instead of falling back
 * to the app-private files dir — the model must never believe it ran a
 * command in the project folder when it actually ran somewhere else.
 *
 * Honest limit: a SAF folder-picker grant gives the child shell NO
 * raw-filesystem access, so file-touching commands in the project
 * folder may print "Permission denied". That stderr is returned plainly
 * in the result (never hidden), with a note explaining why and what
 * fixes it — exactly like the terminal screen shows it.
 */
class RunTool(private val context: Context) : Tool {
    override val name = "run"
    override val description =
        "Run a shell command in the project folder (sh -c) and return its exit code and output. " +
            "Args: {\"command\": \"npm test\"}. In Plan mode write-like commands are refused."

    /** The shared executor — the same TerminalRunner the terminal screen uses. */
    private val runner = TerminalRunner()

    /** Kill the running command, if any — the chat Stop button calls this. */
    fun stop() {
        runner.stop()
    }

    override suspend fun run(args: Map<String, String>, ctx: ToolContext): ToolResult {
        val command = (args["command"] ?: args["cmd"])?.trim()
            ?: return ToolResult.Failed("run needs a command, e.g. {\"command\": \"ls\"}")
        if (command.isEmpty()) return ToolResult.Failed("run needs a non-empty command")
        if (ctx.readOnly) {
            planModeRunRefusal(command)?.let { return ToolResult.Failed(it) }
        }
        val folder = try {
            resolveTerminalFolder(context, ctx.projectUri, ctx.folderName, context.filesDir)
        } catch (e: Exception) {
            return ToolResult.Failed("Could not resolve the project folder: ${e.message}")
        }
        if (folder is TerminalFolder.Unresolved) {
            return ToolResult.Failed("Cannot run here: ${folder.reason}")
        }
        val lines = ArrayList<String>()
        var seenLines = 0
        val exitCode = try {
            withContext(Dispatchers.IO) {
                runner.run(command, folder.workDir) { line ->
                    seenLines++
                    // Keep the LAST lines, like the terminal screen: the
                    // error of a failing command is usually at the end.
                    lines += line
                    if (lines.size > TERMINAL_MAX_LINES) lines.removeAt(0)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ToolResult.Failed("Could not run command: ${e.message}")
        }
        val output = lines.joinToString("\n")
        val text = buildString {
            append("exit code: $exitCode\n")
            if (output.isNotEmpty()) append(output) else append("(no output)")
            if (seenLines > TERMINAL_MAX_LINES) {
                append("\n[Output capped at the last $TERMINAL_MAX_LINES lines.]")
            }
            if (output.contains("Permission denied", ignoreCase = true)) {
                append(
                    "\nNote: \"Permission denied\" above means the shell could not touch " +
                        "that file in the project folder — a folder-picker (SAF) grant gives " +
                        "the child shell no raw-filesystem access. Grant all-files access " +
                        "from Home → Storage access, or use the read / write tools instead, " +
                        "which work through the folder grant."
                )
            }
        }
        return ToolResult.Ok(text, exitCode)
    }
}

/** All v1 tools, wired to one project folder. The `run` tool reuses the
 *  terminal screen's [TerminalRunner] — there is no second shell executor. */
fun v1Tools(project: SafProject, context: Context): List<Tool> =
    listOf(
        ReadTool(project), GlobTool(project), GrepTool(project),
        WriteTool(project), EditTool(project), RunTool(context)
    )
