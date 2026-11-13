package com.inyogeshwar.codesathi.agent

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * Phase 2, Step 6 — in-app terminal. User-facing screen only: the agent
 * has NO shell tool, and nothing here is wired into AgentRunner.
 *
 * A SAF tree URI is not a filesystem path, so the project folder is
 * resolved best-effort to a raw path for primary external storage
 * ("primary:AgentMT" → /storage/emulated/0/AgentMT). When that fails
 * the terminal falls back to the app's private files dir — never
 * silently running in the wrong folder.
 */

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
        return TerminalFolder.Unresolved(
            folderName,
            "Folder not found at ${dir.absolutePath} — it may have been moved or deleted.",
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
        val proc = ProcessBuilder("sh", "-c", command)
            .directory(workDir)
            .redirectErrorStream(true)
            .start()
        process = proc
        try {
            // Reading on the caller's IO thread: lines stream in as the
            // command prints them. When the process is killed, its output
            // stream closes and this loop ends.
            proc.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) onLine(line)
            }
            return proc.waitFor()
        } finally {
            process = null
            if (proc.isAlive) proc.destroy()
        }
    }
}
