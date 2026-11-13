package com.inyogeshwar.codesathi.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.inyogeshwar.codesathi.CodeSathiApp
import com.inyogeshwar.codesathi.agent.SafEntry
import com.inyogeshwar.codesathi.agent.SafProject
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
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Phase 3, Step 3 — editor state. Separate from AppViewModel, like the
 * terminal and Git: the editor's tabs and in-memory edits belong to
 * this screen, not to a chat session.
 *
 * - Folder context comes from the last-opened session
 *   (PreferencesStore.lastSessionId), falling back to the most recent
 *   session when there is no last one. Null when no session exists yet.
 * - The folder is resolved with the same [resolveTerminalFolder] as the
 *   terminal and Git (SAF tree URI → raw path, primary storage only).
 *   Like Git there is NO fallback folder: on an unresolved folder every
 *   editor action is refused and the screen says to re-choose the
 *   folder from Home.
 * - Tabs keep edits in memory with a dirty marker (edited text != text
 *   read from disk). Switching tabs never loses an edit; closing a
 *   dirty tab asks first.
 * - Saving NEVER writes a file directly. Save builds the same
 *   PlannedFile proposal the agent's writes use
 *   ([AppViewModel.proposeEditorSave]) and the file is only written
 *   when the user taps Approve on the shared diff card — in Plan mode
 *   saving is refused outright.
 * - Only text files open: binary files (by extension, and by a NUL
 *   byte in the first bytes) and non-UTF-8 files are refused with a
 *   plain message, never rendered as text.
 * - Folder tree (Phase 3, Step 5): subfolders expand lazily through
 *   the same `SafProject.listEntries` the agent's glob uses, with
 *   depth / per-folder / total-node caps; a folder's "+" button
 *   starts a new session from that subfolder.
 */
class EditorViewModel(private val app: CodeSathiApp) : ViewModel() {

    companion object {
        /** Files larger than this are refused with a plain message. */
        const val MAX_EDIT_BYTES = 1_048_576 // 1 MB
        /** How many leading bytes are sniffed for a NUL (binary) byte. */
        const val SNIFF_BYTES = 512

        /** Extensions that are binary by definition — refused without reading. */
        val BINARY_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "avif",
            "zip", "gz", "tgz", "tar", "xz", "7z", "rar",
            "apk", "aar", "jar", "class", "dex", "so",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "mp3", "mp4", "wav", "ogg", "webm", "mov",
            "ttf", "otf", "woff", "woff2", "eot",
            "db", "sqlite", "sqlite3", "bin", "dat"
        )

        /**
         * Folder-tree caps (Phase 3, Step 5): a folder expands at most
         * this many levels deep, at most this many entries are shown
         * per folder, and at most this many tree nodes are loaded in
         * total — so a huge project folder cannot freeze the UI.
         * Collapsing a folder drops its cached children.
         */
        const val MAX_TREE_DEPTH = 5
        const val MAX_CHILDREN_PER_DIR = 200
        const val MAX_TREE_NODES = 500

        fun factory(app: CodeSathiApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return EditorViewModel(app) as T
                }
            }
    }

    private val db = app.database
    private val prefs = app.preferencesStore

    /** True once the session list has been read — before that the screen
     *  shows "Loading…" instead of the no-folder message. */
    private val _sessionReady = MutableStateFlow(false)
    val sessionReady: StateFlow<Boolean> = _sessionReady

    /** The session the editor works on, or null when there is none yet. */
    val session: StateFlow<Session?> = combine(
        db.sessionDao().observeSessions(),
        prefs.lastSessionId
    ) { sessions, lastId ->
        sessions.firstOrNull { it.id == lastId } ?: sessions.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Which folder the editor works on — resolved when the session arrives. */
    var folder by mutableStateOf<TerminalFolder?>(null)
        private set

    /** Top-level entries of the project folder (files + folders). */
    var files by mutableStateOf<List<SafEntry>>(emptyList())
        private set

    // ---- Folder tree (Phase 3, Step 5) ----

    /** Relative dirs currently expanded in the tree ("" = the root,
     *  always expanded; the root's entries are [files]). */
    var expandedDirs by mutableStateOf<Set<String>>(setOf(""))
        private set

    /** Lazily loaded children per expanded dir (relative dir → entries).
     *  Folders first, then files, each alphabetical; capped at
     *  [MAX_CHILDREN_PER_DIR] per dir. */
    var treeChildren by mutableStateOf<Map<String, List<SafEntry>>>(emptyMap())
        private set

    /** Relative dirs whose children are being read right now. */
    var treeLoading by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Last tree error / cap note, cleared when the next action starts. */
    var treeError by mutableStateOf<String?>(null)
        private set

    /** Id of a session just created from a subfolder (FolderTree shows
     *  the result + an "Open session" button while this is set). */
    var createdSubfolderSessionId by mutableStateOf<String?>(null)
        private set

    /** The grant note for that session (from AppViewModel). */
    var subfolderNote by mutableStateOf<String?>(null)
        private set

    /** Open tabs, in open order. Edits live here, in memory only. */
    var tabs by mutableStateOf<List<EditorTab>>(emptyList())
        private set

    /** Path of the tab shown right now, or null when none is open. */
    var activePath by mutableStateOf<String?>(null)
        private set

    /** The tab shown right now, or null when none is open. */
    val activeTab: EditorTab?
        get() = tabs.firstOrNull { it.path == activePath }

    /** True when the active tab is an .html file (preview available). */
    val isHtmlActive: Boolean
        get() = activePath?.endsWith(".html", ignoreCase = true) == true

    /** Last success note, cleared when the next action starts. */
    var message by mutableStateOf<String?>(null)
        private set

    /** Last error, cleared when the next action starts. */
    var error by mutableStateOf<String?>(null)
        private set

    /** Path of a dirty tab the user asked to close — the screen shows the
     *  "Discard changes?" confirm while this is set. */
    var pendingClosePath by mutableStateOf<String?>(null)
        private set

    /** The editor save waiting on the diff card's Approve / Reject, if any. */
    var awaitingApproval by mutableStateOf<EditorSave?>(null)
        private set

    /** Bumped when the preview content changes (file opened / save
     *  approved) — the preview WebView reloads on this, never on edits. */
    var previewVersion by mutableStateOf(0)
        private set

    /** True while a file is being read from disk. */
    var isLoading by mutableStateOf(false)
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
                    files = emptyList()
                    return@collect
                }
                val key = "${s.id}|${s.projectUri}"
                if (key == folderKey) return@collect
                folderKey = key
                folder = resolveFolder(s)
                refreshFiles()
            }
        }
    }

    /** Re-read the top-level file list (after a save, or on Refresh).
     *  Subfolder listings are dropped too — they may be stale. */
    fun refreshFiles() {
        val s = session.value ?: return
        expandedDirs = setOf("")
        treeChildren = emptyMap()
        treeLoading = emptySet()
        treeError = null
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) {
                try {
                    SafProject(app, Uri.parse(s.projectUri)).listEntries()
                } catch (_: Exception) {
                    emptyList()
                }
            }
            // Files first, then folders, each alphabetical.
            files = entries.sortedWith(
                compareBy({ it.isDirectory }, { it.name.lowercase() })
            )
        }
    }

    /** Depth of a relative dir: "" is 0, "src" is 1, "src/main" is 2. */
    fun treeDepth(relativeDir: String): Int =
        relativeDir.trim('/').split('/').count { it.isNotBlank() }

    /** How many tree nodes are loaded right now (root + children). */
    private fun loadedTreeNodes(): Int =
        files.size + treeChildren.values.sumOf { it.size }

    /**
     * Expand / collapse a folder in the tree. Children load lazily on
     * the first expand (via the same `SafProject.listEntries` the
     * agent's glob uses); collapsing drops the cached children of
     * that folder and everything below it. The depth cap is enforced
     * here as well as in the UI, so a folder at [MAX_TREE_DEPTH]
     * cannot expand even if the UI is bypassed.
     */
    fun toggleTreeDir(relativeDir: String) {
        treeError = null
        if (relativeDir in expandedDirs) {
            // Collapse: drop this dir and every dir below it.
            val prefix = "$relativeDir/"
            expandedDirs = expandedDirs.filterNot { it == relativeDir || it.startsWith(prefix) }.toSet()
            treeChildren = treeChildren.filterKeys { it != relativeDir && !it.startsWith(prefix) }
            return
        }
        if (treeDepth(relativeDir) >= MAX_TREE_DEPTH) {
            treeError = "$relativeDir is ${MAX_TREE_DEPTH} levels deep — open it as its own session (+) instead."
            return
        }
        if (relativeDir in treeChildren) {
            expandedDirs = expandedDirs + relativeDir
            return
        }
        val s = session.value ?: return
        treeLoading = treeLoading + relativeDir
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) {
                try {
                    SafProject(app, Uri.parse(s.projectUri)).listEntries(relativeDir)
                } catch (_: Exception) {
                    null
                }
            }
            treeLoading = treeLoading - relativeDir
            if (entries == null) {
                treeError = "Could not list $relativeDir."
                return@launch
            }
            if (loadedTreeNodes() + entries.size > MAX_TREE_NODES) {
                treeError = "Too many files to show the whole tree (limit $MAX_TREE_NODES nodes) — " +
                    "open $relativeDir as its own session (+) to browse it alone."
                return@launch
            }
            val sorted = entries.sortedWith(
                compareBy({ it.isDirectory }, { it.name.lowercase() })
            )
            if (sorted.size > MAX_CHILDREN_PER_DIR) {
                treeError = "Showing the first $MAX_CHILDREN_PER_DIR entries of $relativeDir."
            }
            treeChildren = treeChildren + (relativeDir to sorted.take(MAX_CHILDREN_PER_DIR))
            expandedDirs = expandedDirs + relativeDir
        }
    }

    /**
     * Create a new session from a subfolder of the current project
     * (the "+" button on a folder row). The subfolder's DocumentFile
     * URI is turned into a proper SAF *tree* URI rooted at that
     * folder ([DocumentsContract.buildTreeDocumentUri]) — a plain
     * document URI would not work as a session's projectUri, because
     * `SafProject` opens its root with `DocumentFile.fromTreeUri`.
     * The session itself is created through
     * [AppViewModel.createSessionFromFolderUri], which reuses the
     * normal new-session path (persistable grant + [AppViewModel.createSession]).
     * On success [createdSubfolderSessionId] is set so the screen can
     * show the result (including the grant note) with an "Open session"
     * button; on failure [error] explains it and no session is made.
     */
    fun createSessionFromSubfolder(context: Context, appViewModel: AppViewModel, relativeDir: String) {
        val s = session.value ?: return
        error = null
        message = null
        viewModelScope.launch {
            val treeUri = withContext(Dispatchers.IO) { resolveSubfolderTreeUri(s, relativeDir) }
            if (treeUri == null) {
                error = "Could not open $relativeDir as a folder."
                return@launch
            }
            val name = relativeDir.substringAfterLast('/')
            val id = appViewModel.createSessionFromFolderUri(context, treeUri, name)
            if (id == null) {
                error = "Could not create a session from $relativeDir."
            } else {
                createdSubfolderSessionId = id
                subfolderNote = appViewModel.subfolderGrantNote
            }
        }
    }

    /** Dismiss the "session created" note in the tree. */
    fun consumeCreatedSubfolderSession() {
        createdSubfolderSessionId = null
        subfolderNote = null
    }

    /** SAF tree URI rooted at a subfolder, or null when it cannot be found. */
    private fun resolveSubfolderTreeUri(s: Session, relativeDir: String): Uri? {
        return try {
            val project = SafProject(app, Uri.parse(s.projectUri))
            val dir = project.find(relativeDir) ?: return null
            if (!dir.isDirectory) return null
            DocumentsContract.buildTreeDocumentUri(
                dir.uri.authority,
                DocumentsContract.getDocumentId(dir.uri)
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Open a file in a tab (or switch to it when already open).
     * Nested paths work: [SafProject.find] walks "sub/file.kt"
     * segment by segment, and the same path is used for reading,
     * saving and the tab key — verified by reading [SafProject]
     * (no on-device run here). Binary / non-UTF-8 / oversized
     * files are refused with a plain message and no tab is opened.
     */
    fun openFile(path: String) {
        if (tabs.any { it.path == path }) {
            activePath = path
            return
        }
        val s = session.value ?: return
        isLoading = true
        error = null
        message = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { readFileForEdit(s, path) }
            isLoading = false
            when (result) {
                is EditRead.Ok -> {
                    tabs = tabs + EditorTab(path, result.text, result.text)
                    activePath = path
                    previewVersion++
                }
                is EditRead.Refused -> error = result.reason
            }
        }
    }

    /** Switch to an already-open tab. */
    fun switchTab(path: String) {
        if (tabs.any { it.path == path }) activePath = path
    }

    /** Update the in-memory text of one tab (typing). Nothing is written. */
    fun updateTabContent(path: String, text: String) {
        tabs = tabs.map { if (it.path == path) it.copy(currentContent = text) else it }
    }

    /**
     * Ask to close a tab. A clean tab closes at once; a dirty tab sets
     * [pendingClosePath] so the screen asks "Discard changes?" first.
     * A tab whose save is waiting for approval cannot be closed until
     * that proposal is approved or rejected.
     */
    fun requestCloseTab(path: String) {
        val tab = tabs.firstOrNull { it.path == path } ?: return
        if (awaitingApproval?.path == path) {
            error = "A save for $path is waiting for approval — approve or reject it first."
            return
        }
        if (tab.isDirty) pendingClosePath = path
        else closeTab(path)
    }

    /** "Discard" on the close confirm: close the dirty tab, edits lost. */
    fun confirmCloseTab() {
        pendingClosePath?.let { closeTab(it) }
        pendingClosePath = null
    }

    fun dismissCloseConfirm() {
        pendingClosePath = null
    }

    private fun closeTab(path: String) {
        val wasActive = activePath == path
        tabs = tabs.filterNot { it.path == path }
        if (wasActive) activePath = tabs.lastOrNull()?.path
    }

    /**
     * Save the active tab through the diff-card flow: the edited text
     * becomes a PlannedFile proposal in [AppViewModel] and the file is
     * only written after Approve. Plan mode, an already-waiting
     * proposal, and a clean tab are all refused with a plain message.
     * When the disk already holds this exact text (e.g. the same save
     * was approved from the Chat screen), the tab is just marked clean.
     */
    fun saveActiveTab(context: Context, appViewModel: AppViewModel) {
        val tab = activeTab ?: return
        val s = session.value ?: return
        error = null
        message = null
        if (awaitingApproval != null) {
            error = "A save is already waiting for approval — approve or reject it first."
            return
        }
        if (!tab.isDirty) {
            message = "No changes to save in ${tab.path}."
            return
        }
        viewModelScope.launch {
            val onDisk = withContext(Dispatchers.IO) {
                try {
                    SafProject(app, Uri.parse(s.projectUri)).readText(tab.path)
                } catch (_: Exception) {
                    null
                }
            }
            if (onDisk == tab.currentContent) {
                markTabSaved(tab.path, tab.currentContent)
                message = "${tab.path} is already saved."
                return@launch
            }
            val err = appViewModel.proposeEditorSave(s, tab.path, tab.currentContent, context)
            if (err != null) {
                error = err
                return@launch
            }
            awaitingApproval = EditorSave(tab.path, tab.currentContent)
        }
    }

    /** Called when the diff card's Approve finished writing (or failed). */
    fun onSaveApplied(path: String, ok: Boolean) {
        val save = awaitingApproval?.takeIf { it.path == path }
        awaitingApproval = null
        if (save == null) return
        if (ok) {
            markTabSaved(path, save.content)
            previewVersion++
            message = "Saved $path."
            error = null
            refreshFiles()
        } else {
            error = "Could not write $path — nothing was saved."
        }
    }

    /** Called when the diff card's Reject was tapped for an editor save. */
    fun onSaveRejected() {
        awaitingApproval = null
        message = "Save rejected — nothing was written."
    }

    fun dismissMessage() {
        message = null
        error = null
    }

    private fun markTabSaved(path: String, content: String) {
        tabs = tabs.map { if (it.path == path) it.copy(originalContent = content) else it }
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

    /** Read a file for editing: bytes → binary sniff → strict UTF-8. */
    private fun readFileForEdit(s: Session, path: String): EditRead {
        val project = SafProject(app, Uri.parse(s.projectUri))
        val bytes = try {
            project.readBytes(path)
        } catch (_: Exception) {
            null
        } ?: return EditRead.Refused("Could not read $path.")
        if (bytes.size > MAX_EDIT_BYTES) {
            return EditRead.Refused(
                "$path is too large to edit here (${bytes.size / 1024} KB — limit 1 MB)."
            )
        }
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext in BINARY_EXTENSIONS) {
            return EditRead.Refused("$path looks like a binary file (.$ext) — it cannot be edited as text.")
        }
        if (bytes.take(SNIFF_BYTES).any { it == 0.toByte() }) {
            return EditRead.Refused("$path looks like a binary file — it cannot be edited as text.")
        }
        val text = decodeUtf8Strict(bytes)
            ?: return EditRead.Refused("$path is not UTF-8 text — it cannot be edited here.")
        return EditRead.Ok(text)
    }

    /** Strict UTF-8 decode: malformed input fails instead of silently
     *  replacing characters that a later save would then corrupt. */
    private fun decodeUtf8Strict(bytes: ByteArray): String? {
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    /** What [readFileForEdit] found: editable text, or why it refused. */
    private sealed class EditRead {
        data class Ok(val text: String) : EditRead()
        data class Refused(val reason: String) : EditRead()
    }
}

/** One open file in the editor. Edits live in memory until Save is approved. */
data class EditorTab(
    val path: String,
    val originalContent: String,
    val currentContent: String
) {
    /** True when the in-memory text differs from what was read from disk. */
    val isDirty: Boolean get() = currentContent != originalContent
}

/** An editor save waiting for the diff card's Approve / Reject. */
data class EditorSave(val path: String, val content: String)
