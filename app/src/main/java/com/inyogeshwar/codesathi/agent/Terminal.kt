package com.inyogeshwar.codesathi.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay

/**
 * Phase 2, Step 6 — in-app terminal, plus (Phase 6, Step 1) the shared
 * shell executor the agent's `run` tool uses ([TerminalRunner], in
 * Tools.kt). One executor, two callers: the terminal screen and the agent.
 *
 * A SAF tree URI is not a filesystem path, so the project folder is
 * resolved best-effort to a raw path for primary external storage
 * ("primary:AgentMT" → /storage/emulated/0/AgentMT). When that fails
 * the terminal falls back to the app's private files dir — never
 * silently running in the wrong folder. The agent's `run` tool does
 * NOT take that fallback: it refuses instead (see Tools.kt).
 */

/** Output cap shared by the terminal screen and the agent's `run` tool:
 *  only the last this-many lines of a command's output are kept. */
const val TERMINAL_MAX_LINES = 2000

/**
 * Phase 3, Step 4 — all-files access (MANAGE_EXTERNAL_STORAGE).
 *
 * A SAF tree grant lets the app read/write inside the picked folder
 * through the DocumentsProvider, but it gives the child shell and
 * JGit NO raw-filesystem access: `sh` and JGit work on real
 * `java.io.File` paths, so without this permission they can hit
 * "Permission denied" on the resolved project path even though the
 * folder exists. With all-files access granted, the same raw-path
 * code just works — nothing is re-implemented for it.
 *
 * Nothing here depends on the permission: when it is not granted,
 * everything keeps working exactly as before via SAF, and the
 * terminal / Git / editor only say the grant *may* help where that
 * is honestly true (see the "Folder not found" reason below). The
 * permission is never requested at runtime — it can only be granted
 * from system Settings ([openAllFilesAccessSettings], tap-only).
 */

/** True on Android 11+ (API 30+), where the permission exists at all. */
fun isAllFilesAccessSupported(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

/**
 * True when the app currently holds all-files access. False on
 * Android 10 and older, and false until the user grants it in
 * system Settings on Android 11+.
 */
fun hasAllFilesAccess(): Boolean =
    isAllFilesAccessSupported() && Environment.isExternalStorageManager()

/**
 * Open the system screen where the user can grant (or revoke)
 * all-files access for THIS app. Call only from a tap — never
 * unprompted, never at startup. Falls back to the general
 * all-files-access list if this app's page cannot be opened.
 */
@Suppress("DEPRECATION")
fun openAllFilesAccessSettings(context: Context) {
    if (!isAllFilesAccessSupported()) return
    val appPage = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
        data = Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (appPage.resolveActivity(context.packageManager) != null) {
        context.startActivity(appPage)
        return
    }
    // General list as a fallback — only when it resolves, so a tap
    // here can never crash the app on a device without the screen.
    val listPage = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (listPage.resolveActivity(context.packageManager) != null) {
        context.startActivity(listPage)
    }
}

/** One line in the terminal history. */
enum class TerminalLineKind { COMMAND, OUTPUT, STATUS }

data class TerminalLine(val text: String, val kind: TerminalLineKind = TerminalLineKind.OUTPUT)

/** Which folder the terminal runs in — a real folder, or the fallback. */
sealed class TerminalFolder {
    /** The project folder, resolved to a raw path. */
    data class Resolved(val dir: File, val folderName: String) : TerminalFolder()

    /** Could not be resolved — [reason] is shown on screen, and the
     *  terminal runs in [fallbackDir] (the app's private files dir). */
    data class Unresolved(val folderName: String, val reason: String, val fallbackDir: File) : TerminalFolder()

    /** The directory commands actually run in. */
    val workDir: File
        get() = when (this) {
            is Resolved -> dir
            is Unresolved -> fallbackDir
        }
}

/**
 * Turn a SAF tree URI into a real folder path, best-effort.
 *
 * Only primary external storage can be resolved this way: the tree
 * document id looks like "primary:AgentMT". A folder on another volume,
 * a revoked grant, or a deleted folder cannot be resolved, and the
 * caller falls back to [fallbackDir] instead.
 */
@Suppress("DEPRECATION")
fun resolveTerminalFolder(
    context: Context,
    treeUri: Uri,
    folderName: String,
    fallbackDir: File
): TerminalFolder {
    // A revoked grant (or a deleted folder) means the folder is gone even
    // if its old path still looks plausible — check SAF first.
    if (!SafProject(context, treeUri).isAvailable()) {
        return TerminalFolder.Unresolved(
            folderName,
            "This folder is no longer available — its permission was revoked or it was deleted. " +
                "Re-choose it from the session on Home.",
            fallbackDir
        )
    }
    val docId = try {
        DocumentsContract.getTreeDocumentId(treeUri)
    } catch (_: Exception) {
        null
    } ?: return TerminalFolder.Unresolved(
        folderName,
        "This folder's address could not be read.",
        fallbackDir
    )
    val parts = docId.split(":", limit = 2)
    if (parts.size != 2) {
        return TerminalFolder.Unresolved(
            folderName,
            "Unexpected folder address \"$docId\".",
            fallbackDir
        )
    }
    val (volume, relative) = parts
    if (volume != "primary") {
        return TerminalFolder.Unresolved(
            folderName,
            "This folder is on \"$volume\", not the phone's main storage — " +
                "only main-storage folders can be used as a shell working directory.",
            fallbackDir
        )
    }
    val root = Environment.getExternalStorageDirectory()
    val dir = if (relative.isBlank()) root else File(root, relative)
    if (!dir.isDirectory) {
        // Without all-files access the app may be unable to SEE a
        // folder that really exists — that is the one unresolved case
        // where the grant can honestly help, so say so (hedged: the
        // folder may also genuinely have been moved or deleted).
        val hint = if (!hasAllFilesAccess()) {
            " Without all-files access the app may not be able to see " +
                "this folder even though it exists — see \"Storage access\" on Home."
        } else ""
        return TerminalFolder.Unresolved(
            folderName,
            "Folder not found at ${dir.absolutePath} — it may have been moved or deleted.$hint",
            fallbackDir
        )
    }
    return TerminalFolder.Resolved(dir, folderName)
}

/**
 * One shell command at a time. Runs `sh -c <command>` in [workDir] with
 * stdout and stderr merged, streaming each output line to [onLine] as it
 * arrives instead of waiting for the command to finish. Returns the
 * process exit code. [stop] kills the running process (Process.destroy).
 */
class TerminalRunner {
    @Volatile
    private var process: Process? = null

    /** Kill the running command, if any. */
    fun stop() {
        process?.destroy()
    }

    suspend fun run(command: String, workDir: File, onLine: (String) -> Unit): Int {
        val job = currentCoroutineContext()[Job]
        val proc = ProcessBuilder("sh", "-c", command)
            .directory(workDir)
            .redirectErrorStream(true)
            .start()
        process = proc
        try {
            // Reading on the caller's IO thread: lines stream in as the
            // command prints them. When the process is killed, its output
            // stream closes and this loop ends. A cancelled job (Stop)
            // throws out of the loop so the process is killed in `finally`
            // instead of running on after the caller gave up.
            proc.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (job?.isActive == false) throw CancellationException("Command stopped.")
                    onLine(line)
                }
            }
            // Wait without blocking the thread forever: poll so a
            // cancelled job can kill a quiet command (one printing
            // nothing) instead of hanging in waitFor(). A process that
            // already exited skips this loop entirely.
            while (proc.isAlive) {
                if (job?.isActive == false) throw CancellationException("Command stopped.")
                delay(100)
            }
            return proc.waitFor()
        } finally {
            process = null
            if (proc.isAlive) proc.destroy()
        }
    }
}
