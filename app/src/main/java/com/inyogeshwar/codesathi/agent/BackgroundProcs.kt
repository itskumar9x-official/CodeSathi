package com.inyogeshwar.codesathi.agent

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Background processes (Phase 7, Step B3 — adapted from AgentMT's
 * `BackgroundProcs` + `BackgroundProcessTool` in `OtherTools.kt`, the
 * user's own code).
 *
 * The agent's `run` tool (Phase 6, Step 1) is foreground-only: it
 * waits for the command to finish. A dev server or a watcher never
 * finishes, so it needs a background process instead: start it, and
 * list / read its output / stop it later with the
 * `background_process` tool. AgentMT kept no cap on concurrent
 * processes; CodeSathi caps them at [MAX_CONCURRENT] (3) — a phone
 * should not accumulate forgotten servers, and the refusal names the
 * cap so the model stops one first.
 *
 * Execution reuses the same shell as the foreground tools (`sh -c`,
 * same work-dir resolution, same SAF honesty note on the terminal
 * screen): there is no second shell executor. Output goes to one log
 * file per process under `getFilesDir()/bg/`, exactly like AgentMT's
 * per-process log file, and is read back from the tail of that file —
 * so output survives the process exiting, and reading it never blocks
 * on a live process.
 *
 * Lifecycle: processes live in this process-wide object, so a
 * background server keeps running while the user is on another
 * screen, and survives a chat turn ending. The chat Stop button
 * ([AppViewModel.stopRun]) kills them all, and so does leaving the
 * app process — a server started here never outlives CodeSathi
 * itself. A process that already exited stays in the list as
 * "exited N" until the app restarts; its log file stays readable.
 */
data class BgProcessInfo(
    val id: Int,
    val command: String,
    val running: Boolean,
    val exitCode: Int?
)

object BackgroundProcs {

    /** At most this many processes running at once (AgentMT had no cap). */
    const val MAX_CONCURRENT = 3

    private class Bg(val id: Int, val command: String, val proc: Process, val log: File)

    private val all = ConcurrentHashMap<Int, Bg>()
    private val next = AtomicInteger(1)

    /** Start outcome: the new process id, or the plain refusal reason. */
    sealed class StartResult {
        data class Started(val id: Int) : StartResult()
        data class Refused(val reason: String) : StartResult()
    }

    private fun bgDir(filesDir: File): File =
        File(filesDir, "bg").apply { if (!exists()) mkdirs() }

    /** How many processes are currently alive. */
    fun runningCount(): Int = all.values.count { it.proc.isAlive }

    /**
     * Start [command] in [workDir] (`sh -c`), writing merged
     * stdout + stderr to its log file. Refuses when [MAX_CONCURRENT]
     * processes are already running — stop one first.
     */
    fun start(filesDir: File, command: String, workDir: File): StartResult {
        if (runningCount() >= MAX_CONCURRENT) {
            return StartResult.Refused(
                "Already $MAX_CONCURRENT background processes running " +
                    "(the limit) — stop one with background_process first."
            )
        }
        val id = next.getAndIncrement()
        val log = File(bgDir(filesDir), "bg_$id.log")
        return try {
            val proc = ProcessBuilder("sh", "-c", command)
                .directory(workDir)
                .redirectErrorStream(true)
                .redirectOutput(log)
                .start()
            all[id] = Bg(id, command, proc, log)
            StartResult.Started(id)
        } catch (e: Exception) {
            StartResult.Refused("Could not start background process: ${e.message}")
        }
    }

    /** The last [chars] of one process's log, or "" when there is none. */
    fun tail(id: Int, chars: Int): String {
        val log = all[id]?.log ?: return ""
        return runCatching {
            RandomAccessFile(log, "r").use { r ->
                val start = (r.length() - chars).coerceAtLeast(0)
                r.seek(start)
                val bytes = ByteArray((r.length() - start).toInt())
                r.readFully(bytes)
                String(bytes, Charsets.UTF_8)
            }
        }.getOrDefault("")
    }

    /** One line per process, newest last — the `list` action's text. */
    fun list(): String = all.values.sortedBy { it.id }.joinToString("\n") { b ->
        "#${b.id} ${if (b.proc.isAlive) "running" else "exited ${b.proc.exitValue()}"}: ${b.command.take(100)}"
    }.ifEmpty { "No background processes." }

    /** Snapshot for the terminal screen: id, command, state, exit code. */
    fun snapshot(): List<BgProcessInfo> = all.values.sortedBy { it.id }.map { b ->
        BgProcessInfo(
            id = b.id,
            command = b.command,
            running = b.proc.isAlive,
            exitCode = if (b.proc.isAlive) null else runCatching { b.proc.exitValue() }.getOrNull()
        )
    }

    /** Stop one process (destroy, then force after 2 s). False when there is no such id. */
    fun stop(id: Int): Boolean {
        val bg = all[id] ?: return false
        bg.proc.destroy()
        if (!bg.proc.waitFor(2, TimeUnit.SECONDS)) bg.proc.destroyForcibly()
        return true
    }

    /** Stop every background process — the chat Stop button calls this. */
    fun stopAll() {
        all.keys.toList().forEach { stop(it) }
    }
}

/**
 * The agent's `background_process` tool.
 *
 * Actions (args: `action`, plus `command` for start, `id` for
 * output / stop):
 * - `start`: start a long-running command in the background and
 *   return its id at once (no waiting — the first output may still
 *   be empty; read it with `output`). Refused in Plan mode: a dev
 *   server is a side effect, and Plan is read-only. Refused when the
 *   project folder cannot be resolved, exactly like the `run` tool.
 * - `list`: every background process and its state.
 * - `output`: the recent log of one process, plus the list.
 * - `stop`: stop one process.
 *
 * Gating: kind EXECUTE (see [toolKind]), same allow / ask / deny
 * rule as `run`, default "ask" (see `DEFAULT_TOOL_RULES` in
 * PreferencesStore). The tool's target for a glob rule is its
 * command (start) or its action (list / output / stop) — see
 * [toolTarget].
 */
class BackgroundProcessTool(private val context: Context) : Tool {
    override val name = "background_process"
    override val description =
        "Start a long-running command in the background (a dev server, a watcher) " +
            "and manage it. Args: {\"action\": \"start\", \"command\": \"npm run dev\"} " +
            "starts one and returns its id; {\"action\": \"list\"}, " +
            "{\"action\": \"output\", \"id\": 1} and {\"action\": \"stop\", \"id\": 1} manage it. " +
            "At most ${BackgroundProcs.MAX_CONCURRENT} may run at once. Not available in Plan mode."

    override suspend fun run(args: Map<String, String>, ctx: ToolContext): ToolResult {
        return when (args["action"]?.trim()?.lowercase().orEmpty()) {
            "start" -> start(args, ctx)
            "output" -> {
                val id = args["id"]?.toIntOrNull()
                    ?: return ToolResult.Failed("background_process output needs an id, e.g. {\"action\": \"output\", \"id\": 1}")
                ToolResult.Ok(
                    BackgroundProcs.tail(id, 8000).ifBlank { "(no output yet)" } +
                        "\n\n" + BackgroundProcs.list()
                )
            }
            "stop" -> {
                val id = args["id"]?.toIntOrNull()
                    ?: return ToolResult.Failed("background_process stop needs an id, e.g. {\"action\": \"stop\", \"id\": 1}")
                if (BackgroundProcs.stop(id)) ToolResult.Ok("Stopped #$id.")
                else ToolResult.Failed("No background process #$id.")
            }
            else -> ToolResult.Ok(BackgroundProcs.list())
        }
    }

    private fun start(args: Map<String, String>, ctx: ToolContext): ToolResult {
        if (ctx.readOnly) {
            return ToolResult.Failed(
                "Plan mode is read-only — background processes start in Build mode."
            )
        }
        val command = args["command"]?.trim()
            ?: return ToolResult.Failed("background_process start needs a command, e.g. {\"action\": \"start\", \"command\": \"npm run dev\"}")
        if (command.isEmpty()) {
            return ToolResult.Failed("background_process start needs a non-empty command")
        }
        val folder = try {
            resolveTerminalFolder(context, ctx.projectUri, ctx.folderName, context.filesDir)
        } catch (e: Exception) {
            return ToolResult.Failed("Could not resolve the project folder: ${e.message}")
        }
        if (folder is TerminalFolder.Unresolved) {
            return ToolResult.Failed("Cannot start a background process here: ${folder.reason}")
        }
        return when (val started = BackgroundProcs.start(context.filesDir, command, folder.workDir)) {
            is BackgroundProcs.StartResult.Started -> ToolResult.Ok(
                "Started background process #${started.id} (running).\n" +
                    "Use background_process (output / stop) with id ${started.id} to manage it."
            )
            is BackgroundProcs.StartResult.Refused -> ToolResult.Failed(started.reason)
        }
    }
}
