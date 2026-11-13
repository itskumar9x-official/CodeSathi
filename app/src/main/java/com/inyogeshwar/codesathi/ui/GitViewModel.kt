package com.inyogeshwar.codesathi.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.inyogeshwar.codesathi.CodeSathiApp
import com.inyogeshwar.codesathi.agent.GitCredentials
import com.inyogeshwar.codesathi.agent.GitRepo
import com.inyogeshwar.codesathi.agent.GitRepoInfo
import com.inyogeshwar.codesathi.agent.TerminalFolder
import com.inyogeshwar.codesathi.agent.resolveTerminalFolder
import com.inyogeshwar.codesathi.data.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 2, Step 8 — Git state. Separate from AppViewModel, like the
 * terminal: Git belongs to this screen, not to a chat session.
 *
 * - Folder context comes from the last-opened session
 *   (PreferencesStore.lastSessionId), falling back to the most recent
 *   session when there is no last one. Null when no session exists yet.
 * - The folder is resolved with the same [resolveTerminalFolder] as the
 *   terminal (SAF tree URI → raw path, primary storage only). Unlike
 *   the terminal, there is NO fallback folder here: Git needs a real
 *   path, so on an unresolved folder every Git action is refused and
 *   the screen says to re-choose the folder from Home.
 * - All Git work runs on Dispatchers.IO; one operation at a time
 *   ([isBusy]); the result or the error is shown plainly on screen.
 */
class GitViewModel(private val app: CodeSathiApp) : ViewModel() {

    companion object {
        /** Shown whenever Git cannot run because the folder is unresolved. */
        const val UNRESOLVED_MESSAGE =
            "Git needs a real folder path, so it cannot run on an unresolved folder. " +
                "Go back to Home and open the session to re-choose its folder."

        fun factory(app: CodeSathiApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return GitViewModel(app) as T
                }
            }
    }

    private val db = app.database
    private val prefs = app.preferencesStore
    private val keys = app.keyStore

    /** True once the session list has been read — before that the screen
     *  shows "Loading…" instead of the no-folder message. */
    private val _sessionReady = MutableStateFlow(false)
    val sessionReady: StateFlow<Boolean> = _sessionReady

    /** The session Git runs in, or null when there is none yet. */
    val session: StateFlow<Session?> = combine(
        db.sessionDao().observeSessionsByRecency(),
        prefs.lastSessionId
    ) { sessions, lastId ->
        sessions.firstOrNull { it.id == lastId } ?: sessions.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Which folder Git runs in — resolved when the session arrives. */
    var folder by mutableStateOf<TerminalFolder?>(null)
        private set

    /** Branch / branches / remote / status, or null when not a repo. */
    var repoInfo by mutableStateOf<GitRepoInfo?>(null)
        private set

    /** True when the folder is a Git working tree. */
    var isRepo by mutableStateOf(false)
        private set

    /** True while a Git operation is running. */
    var isBusy by mutableStateOf(false)
        private set

    /** Last success message, cleared when the next operation starts. */
    var message by mutableStateOf<String?>(null)
        private set

    /** Last error message, cleared when the next operation starts. */
    var error by mutableStateOf<String?>(null)
        private set

    /** The saved remote credential (values never leave KeyStore). */
    var hasCredential by mutableStateOf(keys.hasGitCredential())
        private set
    var credentialUsername by mutableStateOf<String?>(keys.getGitUsername())
        private set
    var credentialMasked by mutableStateOf<String?>(keys.gitTokenMasked())
        private set

    /** "sessionId|projectUri" the current [folder] was resolved from —
     *  re-resolve when the session's folder changes. */
    private var folderKey: String? = null

    init {
        viewModelScope.launch {
            session.collect { s ->
                _sessionReady.value = true
                if (s == null) {
                    folder = null
                    folderKey = null
                    repoInfo = null
                    isRepo = false
                    return@collect
                }
                val key = "${s.id}|${s.projectUri}"
                if (key == folderKey) return@collect
                folderKey = key
                folder = resolveFolder(s)
                refresh()
            }
        }
    }

    /** Re-read branch / status from disk. Ignored while an operation runs. */
    fun refresh() {
        if (isBusy) return
        viewModelScope.launch { load() }
    }

    /** Stage everything (new + changed + deleted). */
    fun stageAll() = runGit { repo ->
        repo.stageAll()
        "Staged all changes."
    }

    /** Stage one file ([wasDeleted] when it is missing from the disk). */
    fun stageFile(path: String, wasDeleted: Boolean) = runGit { repo ->
        repo.stageFile(path, wasDeleted)
        "Staged $path."
    }

    /** Take one file out of the index. */
    fun unstageFile(path: String) = runGit { repo ->
        repo.unstageFile(path)
        "Unstaged $path."
    }

    /** Commit what is staged. Blank message is refused on screen. */
    fun commit(messageText: String) {
        val text = messageText.trim()
        if (text.isEmpty()) {
            error = "Type a commit message first."
            return
        }
        runGit { repo ->
            val name = keys.getGitUsername()?.takeIf { it.isNotBlank() } ?: "CodeSathi"
            val email = if ("@" in name) name else "codesathi@localhost"
            "Committed ${repo.commit(text, name, email)}."
        }
    }

    /** Push the current branch (credential needed for private remotes). */
    fun push() = runGit { repo -> repo.push(gitCredentials()) }

    /** Pull the current branch (fetch + merge). */
    fun pull() = runGit { repo -> repo.pull(gitCredentials()) }

    /** Switch branch. */
    fun checkout(branch: String) = runGit { repo ->
        repo.checkout(branch)
        "Switched to $branch."
    }

    /**
     * Clone [url] into a subfolder of the project folder, named after
     * the repo. The clone is NOT the current session's folder — open it
     * as a new session from Home to work in it.
     */
    fun cloneRepo(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            error = "Type a repo URL first (https://…)."
            return
        }
        val target = folder
        if (target !is TerminalFolder.Resolved) {
            error = UNRESOLVED_MESSAGE
            return
        }
        if (isBusy) return
        isBusy = true
        message = null
        error = null
        viewModelScope.launch {
            try {
                val dir = withContext(Dispatchers.IO) {
                    GitRepo.clone(trimmed, target.dir, gitCredentials())
                }
                message = "Cloned into ${dir.absolutePath}. " +
                    "Open it as a new session from Home to work in it."
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    error = e.message ?: "Clone failed."
                }
            } finally {
                isBusy = false
            }
        }
    }

    /** Save the remote credential (KeyStore only). False when blank. */
    fun saveCredential(username: String, token: String): Boolean {
        val ok = keys.saveGitCredential(username, token)
        if (ok) {
            hasCredential = true
            credentialUsername = keys.getGitUsername()
            credentialMasked = keys.gitTokenMasked()
            message = "Git credential saved (stored encrypted on this device only)."
            error = null
        } else {
            error = "Type both a username and a token."
        }
        return ok
    }

    /** Delete the saved remote credential. */
    fun removeCredential() {
        keys.removeGitCredential()
        hasCredential = false
        credentialUsername = null
        credentialMasked = null
        message = "Git credential removed."
        error = null
    }

    private fun gitCredentials(): GitCredentials? {
        val user = keys.getGitUsername()
        val token = keys.getGitToken()
        return if (user != null && token != null) GitCredentials(user, token) else null
    }

    /** The repo for the resolved folder, or null (with [error] set) when
     *  the folder cannot be resolved — Git has no fallback folder. */
    private fun currentRepo(): GitRepo? {
        val target = folder
        if (target !is TerminalFolder.Resolved) {
            error = UNRESOLVED_MESSAGE
            return null
        }
        return GitRepo(target.dir)
    }

    /** One Git operation: guard, run on IO, show the result, reload status. */
    private fun runGit(block: suspend (GitRepo) -> String) {
        val repo = currentRepo() ?: return
        if (isBusy) return
        isBusy = true
        message = null
        error = null
        viewModelScope.launch {
            try {
                val done = withContext(Dispatchers.IO) { block(repo) }
                message = done
                load()
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    error = e.message ?: "Git failed."
                }
            } finally {
                isBusy = false
            }
        }
    }

    /** Read branch / status from disk into [repoInfo] / [isRepo]. */
    private suspend fun load() {
        val target = folder ?: return
        if (target !is TerminalFolder.Resolved) {
            repoInfo = null
            isRepo = false
            return
        }
        val info = withContext(Dispatchers.IO) { GitRepo(target.dir).load() }
        isRepo = info != null
        repoInfo = info
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
}
