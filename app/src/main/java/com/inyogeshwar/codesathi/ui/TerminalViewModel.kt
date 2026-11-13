package com.inyogeshwar.codesathi.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.inyogeshwar.codesathi.CodeSathiApp
import com.inyogeshwar.codesathi.agent.TerminalFolder
import com.inyogeshwar.codesathi.agent.TerminalLine
import com.inyogeshwar.codesathi.agent.TerminalLineKind
import com.inyogeshwar.codesathi.agent.TerminalRunner
import com.inyogeshwar.codesathi.agent.resolveTerminalFolder
import com.inyogeshwar.codesathi.data.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 2, Step 6 — terminal state. Separate from AppViewModel: the
 * terminal is a user-facing screen only, and its running process and
 * output history belong to this screen, not to a chat session.
 *
 * - Folder context comes from the last-opened session
 *   (PreferencesStore.lastSessionId), falling back to the most recent
 *   session when there is no last one. Null when no session exists yet.
 * - One command at a time; Stop kills the running process.
 * - Output keeps only the last [MAX_LINES] lines, so a huge output
 *   cannot grow memory without bound.
 */
class TerminalViewModel(private val app: CodeSathiApp) : ViewModel() {

    companion object {
        /** Keep only the last ~2000 lines. */
        const val MAX_LINES = 2000
        /** Past commands kept for tap-to-re-run (most recent first). */
        const val MAX_HISTORY = 20

        fun factory(app: CodeSathiApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return TerminalViewModel(app) as T
                }
            }
    }

    private val db = app.database
    private val prefs = app.preferencesStore
    private val runner = TerminalRunner()
    private var runJob: Job? = null
    /** Set on Stop, read when the killed process exits. Main thread only. */
    private var stoppedByUser = false
    /** "sessionId|projectUri" the current [folder] was resolved from —
     *  re-resolve when the session's folder changes. */
    private var folderKey: String? = null

    /** True once the session list has been read — before that the screen
     *  shows "Loading…" instead of the no-folder message. */
    private val _sessionReady = MutableStateFlow(false)
    val sessionReady: StateFlow<Boolean> = _sessionReady

    /** The session the terminal runs in, or null when there is none yet. */
    val session: StateFlow<Session?> = combine(
        db.sessionDao().observeSessionsByRecency(),
        prefs.lastSessionId
    ) { sessions, lastId ->
        sessions.firstOrNull { it.id == lastId } ?: sessions.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Which folder commands run in — resolved when the session arrives. */
    var folder by mutableStateOf<TerminalFolder?>(null)
        private set

    /** Scrollable output history (commands, output, exit codes). */
    private val _lines = MutableStateFlow<List<TerminalLine>>(emptyList())
    val lines: StateFlow<List<TerminalLine>> = _lines

    /** True while a command is running. */
    var isRunning by mutableStateOf(false)
        private set

    /** Past commands, most recent first — tap one to re-run it. */
    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<String>> = _history

    init {
        viewModelScope.launch {
            session.collect { s ->
                _sessionReady.value = true
                if (s == null) {
                    folder = null
                    folderKey = null
                    return@collect
                }
                val key = "${s.id}|${s.projectUri}"
                if (key == folderKey) return@collect
                folderKey = key
                folder = resolveFolder(s)
                // Announce where commands run, so the history itself shows
                // it even before the first command.
                val resolved = folder
                appendLine(
                    TerminalLine("Working directory: ${resolved?.workDir?.absolutePath}", TerminalLineKind.STATUS)
                )
                if (resolved is TerminalFolder.Unresolved) {
                    appendLine(TerminalLine(resolved.reason, TerminalLineKind.STATUS))
                }
            }
        }
    }

    override fun onCleared() {
        // Kill a running command first: that closes its output stream,
        // which unblocks the reader, then the job can be cancelled.
        runner.stop()
        runJob?.cancel()
        super.onCleared()
    }

    /** Run one command. Ignored when blank, when no folder is set, or
     *  while another command is still running. */
    fun runCommand(command: String) {
        val trimmed = command.trim()
        val target = folder ?: return
        if (trimmed.isEmpty() || isRunning) return
        isRunning = true
        stoppedByUser = false
        appendLine(TerminalLine("\$ $trimmed", TerminalLineKind.COMMAND))
        addToHistory(trimmed)
        runJob = viewModelScope.launch {
            try {
                val exitCode = withContext(Dispatchers.IO) {
                    runner.run(trimmed, target.workDir) { line ->
                        appendLine(TerminalLine(line))
                    }
                }
                if (stoppedByUser) {
                    appendLine(
                        TerminalLine("Stopped by user (exit code: $exitCode).", TerminalLineKind.STATUS)
                    )
                } else {
                    appendLine(TerminalLine("exit code: $exitCode", TerminalLineKind.STATUS))
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    appendLine(
                        TerminalLine("Could not run command: ${e.message}", TerminalLineKind.STATUS)
                    )
                }
            } finally {
                isRunning = false
            }
        }
    }

    /** Stop the running command (Process.destroy). */
    fun stopCommand() {
        if (!isRunning) return
        stoppedByUser = true
        runner.stop()
    }

    /** Clear the output history (the folder card still shows where
     *  commands run). */
    fun clearOutput() {
        _lines.value = emptyList()
    }

    private fun resolveFolder(s: Session): TerminalFolder {
        return try {
            resolveTerminalFolder(app, Uri.parse(s.projectUri), s.folderName, app.filesDir)
        } catch (_: Exception) {
            TerminalFolder.Unresolved(
                s.folderName,
                "This folder's address could not be read.",
                app.filesDir
            )
        }
    }

    /** Append one line, dropping the oldest lines past [MAX_LINES]. */
    private fun appendLine(line: TerminalLine) {
        _lines.update { current ->
            (current + line).takeLast(MAX_LINES)
        }
    }

    private fun addToHistory(command: String) {
        _history.value = (listOf(command) + _history.value.filterNot { it == command })
            .take(MAX_HISTORY)
    }
}
