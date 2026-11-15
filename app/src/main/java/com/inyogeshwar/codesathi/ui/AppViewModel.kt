package com.inyogeshwar.codesathi.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.inyogeshwar.codesathi.CodeSathiApp
import com.inyogeshwar.codesathi.agent.AgentEvent
import com.inyogeshwar.codesathi.agent.AgentRunner
import com.inyogeshwar.codesathi.agent.AttachmentMaterial
import com.inyogeshwar.codesathi.agent.AttachmentRecord
import com.inyogeshwar.codesathi.agent.AttachmentStore
import com.inyogeshwar.codesathi.agent.BackgroundProcs
import com.inyogeshwar.codesathi.agent.BLANK_TEMPLATE_ID
import com.inyogeshwar.codesathi.agent.Compaction
import com.inyogeshwar.codesathi.agent.FallbackEngine
import com.inyogeshwar.codesathi.agent.ModelRouter
import com.inyogeshwar.codesathi.agent.PermissionDecision
import com.inyogeshwar.codesathi.agent.PermissionEngine
import com.inyogeshwar.codesathi.agent.RoutingMode
import com.inyogeshwar.codesathi.agent.ImageStore
import com.inyogeshwar.codesathi.agent.LimitKind
import com.inyogeshwar.codesathi.agent.LlmClient
import com.inyogeshwar.codesathi.agent.LlmImage
import com.inyogeshwar.codesathi.agent.LlmMessage
import com.inyogeshwar.codesathi.agent.LlmPdf
import com.inyogeshwar.codesathi.agent.LocalLlmClient
import com.inyogeshwar.codesathi.agent.loadTemplateFiles
import com.inyogeshwar.codesathi.agent.decodeAttachments
import com.inyogeshwar.codesathi.agent.decodeImageNames
import com.inyogeshwar.codesathi.agent.encodeAttachments
import com.inyogeshwar.codesathi.agent.encodeImageNames
import com.inyogeshwar.codesathi.agent.supportsPdfUpload
import com.inyogeshwar.codesathi.core.CrashReporter
import com.inyogeshwar.codesathi.data.BackupCrypto
import com.inyogeshwar.codesathi.data.EncryptedBackupPreview
import com.inyogeshwar.codesathi.data.RunEntity
import com.inyogeshwar.codesathi.data.decryptSecretsFromEncryptedBackup
import com.inyogeshwar.codesathi.data.isEncryptedBackup
import com.inyogeshwar.codesathi.data.readEncryptedBackupPreview
import com.inyogeshwar.codesathi.data.readSessionsJsonFromEncryptedBackup
import com.inyogeshwar.codesathi.data.writeEncryptedBackupZip
import com.inyogeshwar.codesathi.agent.GitHubFetcher
import com.inyogeshwar.codesathi.agent.McpClient
import com.inyogeshwar.codesathi.agent.McpToolInfo
import com.inyogeshwar.codesathi.agent.PlannedFile
import com.inyogeshwar.codesathi.agent.Plan
import com.inyogeshwar.codesathi.agent.PluginManager
import com.inyogeshwar.codesathi.agent.PluginRecord
import com.inyogeshwar.codesathi.agent.RepoRef
import com.inyogeshwar.codesathi.agent.RunTool
import com.inyogeshwar.codesathi.agent.SafProject
import com.inyogeshwar.codesathi.agent.Skill
import com.inyogeshwar.codesathi.agent.SkillStore
import com.inyogeshwar.codesathi.agent.VoiceSpeaker
import com.inyogeshwar.codesathi.agent.Tool
import com.inyogeshwar.codesathi.agent.ToolContext
import com.inyogeshwar.codesathi.agent.mcpToolsFor
import com.inyogeshwar.codesathi.agent.templateById
import com.inyogeshwar.codesathi.agent.toolTarget
import com.inyogeshwar.codesathi.agent.v1Tools
import com.inyogeshwar.codesathi.data.CustomCommand
import com.inyogeshwar.codesathi.data.FallbackEntry
import com.inyogeshwar.codesathi.data.KeyHealth
import com.inyogeshwar.codesathi.data.FavouriteModel
import com.inyogeshwar.codesathi.data.KeyStore
import com.inyogeshwar.codesathi.data.McpServer
import com.inyogeshwar.codesathi.data.BackupImportResult
import com.inyogeshwar.codesathi.data.BackupParseResult
import com.inyogeshwar.codesathi.data.MAX_BACKUP_BYTES
import com.inyogeshwar.codesathi.data.Message
import com.inyogeshwar.codesathi.agent.PermissionRule
import com.inyogeshwar.codesathi.data.SessionBackup
import com.inyogeshwar.codesathi.data.SessionBackupData
import com.inyogeshwar.codesathi.data.SyncMergeResult
import com.inyogeshwar.codesathi.data.encodeBackup
import com.inyogeshwar.codesathi.data.parseBackup
import com.inyogeshwar.codesathi.data.toSession
import com.inyogeshwar.codesathi.data.validateBackupSession
import com.inyogeshwar.codesathi.data.PreferencesStore
import com.inyogeshwar.codesathi.data.Provider
import com.inyogeshwar.codesathi.data.Session
import com.inyogeshwar.codesathi.data.SessionSummary
import com.inyogeshwar.codesathi.data.TokenCounter
import com.inyogeshwar.codesathi.data.supportsVision
import com.inyogeshwar.codesathi.data.visionRefusalMessage
import com.inyogeshwar.codesathi.data.UsageBySessionModel
import com.inyogeshwar.codesathi.data.UsageRecord
import com.inyogeshwar.codesathi.data.UsageTotals
import com.inyogeshwar.codesathi.remote.RemoteTurnResult
import com.inyogeshwar.codesathi.remote.SyncClient
import com.inyogeshwar.codesathi.remote.SyncException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Single ViewModel for the v1 app.
 * Holds sessions, the picked folder, the active plan / pending file proposal,
 * and the running agent job (Stop cancels it).
 */
class AppViewModel(private val app: CodeSathiApp) : ViewModel() {

    private val db = app.database
    private val prefs = app.preferencesStore
    private val keys = app.keyStore
    val providerRepository = app.providerRepository

    /** Multi-key + single-provider fallback engine (Phase 2, Step 1). */
    val fallbackEngine = FallbackEngine(providerRepository, keys, prefs)

    /** Routing profiles + per-key health (Phase 7, Step B1). */
    val modelRouter = ModelRouter(fallbackEngine, prefs, keys)

    var onboarded by mutableStateOf(false)
        private set
    /**
     * v1.2: true once the stored onboarded flag has been read, so the
     * first screen is chosen from the real value (onboarding is shown
     * once) instead of the initial default.
     */
    var onboardingChecked by mutableStateOf(false)
        private set

    val sessions: StateFlow<List<Session>> =
        db.sessionDao().observeSessions()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favourites: StateFlow<List<FavouriteModel>> =
        db.favouriteDao().observeFavourites()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---- Session organisation (Phase 5, Step 4) ----

    /**
     * Search text typed on Home. The Home list reads [filteredSessions],
     * not [sessions]: a blank query shows every session (the same
     * pinned-first order), a typed query switches the Room query to the
     * case-insensitive title LIKE search. Clearing the field restores
     * the full list. The query lives here (not in the screen) so the
     * list survives a recomposition and the DAO call is the single
     * source of the filtered list.
     */
    var sessionSearchQuery by mutableStateOf("")
        @JvmName("setSessionSearchQueryState") private set
    private val sessionSearchQueryFlow = MutableStateFlow("")

    val filteredSessions: StateFlow<List<Session>> = sessionSearchQueryFlow
        .flatMapLatest { query ->
            val trimmed = query.trim()
            if (trimmed.isEmpty()) db.sessionDao().observeSessions()
            else db.sessionDao().searchSessions(escapeLike(trimmed))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setSessionSearchQuery(query: String) {
        sessionSearchQuery = query
        sessionSearchQueryFlow.value = query
    }

    /**
     * Escape the LIKE wildcards in a search query, so "%" and "_" typed
     * by the user match those literal characters in a title instead of
     * matching anything. The backslash is escaped first, and the DAO
     * query declares ESCAPE '\'.
     */
    private fun escapeLike(text: String): String =
        text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** Pin or unpin a session. Stored in Room, so it survives a restart. */
    fun setSessionPinned(sessionId: String, pinned: Boolean) {
        viewModelScope.launch { db.sessionDao().setPinned(sessionId, pinned) }
    }

    /**
     * Rename a session. Returns false when the title is blank after
     * trimming — nothing is written and the old title stays. The
     * dialog shows a plain message in that case.
     */
    fun renameSession(sessionId: String, title: String): Boolean {
        val clean = title.trim()
        if (clean.isEmpty()) return false
        viewModelScope.launch { db.sessionDao().setTitle(sessionId, clean) }
        return true
    }

    /**
     * Delete a session and everything that belongs to it — messages,
     * compaction summary and usage rows, in one Room transaction
     * ([SessionDao.deleteSessionAndData]), plus its attached-image
     * files (Phase 6, Step 2): image bytes are app-private files, not
     * Room rows, so they are deleted here or they would be orphaned
     * when the messages go. Deleting the session that
     * is open, or the last session, never crashes: the active-session
     * pointer and the last-session preference are cleared, and the
     * terminal / Git / editor screens fall back to the most recent
     * remaining session (or show "no session yet" when none is left).
     * A chat screen left open on the deleted session already shows
     * "Session not found." with a Back button.
     */
    fun deleteSession(sessionId: String) {
        // Deleting the session a template proposal belongs to drops
        // the proposal and the rest of its queue — a stale proposal
        // must never resurface in another session's chat.
        if (pendingTemplateSessionId == sessionId) {
            pendingProposal = null
            pendingProposalOld = null
            pendingTemplateFiles = emptyList()
            pendingProposalIsTemplate = false
            pendingTemplateSessionId = null
        }
        viewModelScope.launch {
            val messages = db.messageDao().getMessages(sessionId)
            val imageNames = messages.flatMap { decodeImageNames(it.imagesJson) }
            val attachments = messages.flatMap { decodeAttachments(it.attachmentsJson) }
            db.sessionDao().deleteSessionAndData(sessionId)
            withContext(Dispatchers.IO) {
                ImageStore.deleteAll(app, imageNames)
                AttachmentStore.deleteAll(app, attachments)
            }
            if (activeSessionId == sessionId) activeSessionId = null
            if (prefs.lastSessionId.first() == sessionId) prefs.setLastSession(null)
        }
    }

    // ---- Crash report (Phase 7, Step B3) ----

    /** The last crash report, shown on Home until dismissed. */
    var pendingCrash by mutableStateOf<String?>(null)
        private set

    /** Dismiss the crash card: the file is deleted too, so it never shows again. */
    fun dismissCrashReport() {
        pendingCrash = null
        CrashReporter.clear(app)
    }

    // ---- Runs history (Phase 7, Step B3) ----

    /** Every recorded agent turn, newest first. */
    val runs: StateFlow<List<RunEntity>> =
        db.runDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Clear finished runs from the history (a running / waiting run is never cleared). */
    fun clearFinishedRuns() {
        viewModelScope.launch { db.runDao().clearFinished() }
    }

    /**
     * The session messages written while [run] was active, for the
     * run detail timeline. A run's own messages are not linked by id
     * — the window is the run's start / end time, so the assistant
     * message written just before the run finished is included too.
     */
    suspend fun runMessages(run: RunEntity): List<Message> {
        val all = db.messageDao().getMessages(run.sessionId)
        val end = run.endedAt ?: Long.MAX_VALUE
        return all.filter { it.createdAt in run.startedAt..end }
    }

    /** Counts accumulated for one run while its turn is in flight. */
    private class RunTracker(val runId: String) {
        var steps = 0
        var toolCalls = 0
        val changedFiles = linkedSetOf<String>()
        var inputTokens = 0L
        var outputTokens = 0L
        val routeLines = mutableListOf<String>()
        var error: String? = null
    }

    /** Title for a run row: the user's message, or the last user message for a resume. */
    private fun runTitle(userMessage: String, history: List<Message>): String {
        val raw = when {
            userMessage.startsWith("User approved") ||
                userMessage.startsWith(AgentRunner.CONTINUE_TEXT) ->
                history.lastOrNull { it.role == "user" }?.text.orEmpty()
            else -> userMessage
        }
        val clean = raw.replace('\n', ' ').trim()
        return if (clean.isEmpty()) "Agent run" else clean.take(80)
    }

    // ---- Usage & cost (Phase 5, Step 2) ----

    /** Grand totals over every recorded turn (prompt + completion). */
    val usageTotals: StateFlow<UsageTotals> =
        db.usageDao().observeTotals()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UsageTotals())

    /**
     * Usage summed per (session, provider, model). The dashboard groups
     * these rows by model and by session, and prices them with
     * [com.inyogeshwar.codesathi.data.ModelPrices] — totals are always
     * derived from stored rows, never kept only in memory.
     */
    val usageBySessionModel: StateFlow<List<UsageBySessionModel>> =
        db.usageDao().observeBySessionModel()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---- Session backup & restore (Phase 5, Step 3) ----

    /** One-line note from the last backup / restore, for Home. */
    var backupNote by mutableStateOf<String?>(null)
        private set
    /** One-line error from the last backup / restore, for Home. */
    var backupError by mutableStateOf<String?>(null)
        private set

    fun dismissBackupMessage() {
        backupNote = null
        backupError = null
    }

    /**
     * Every session (with its messages and compaction summary) as
     * backup data, ready to encode. Shared by the file export below
     * and by multi-device sync (Phase 6, Step 4): the server's
     * export endpoint and the push both send exactly this, so there
     * is one backup format, not two.
     */
    private suspend fun buildBackupData(): List<SessionBackupData> {
        val sessions = db.sessionDao().observeSessions().first()
        return sessions.map { session ->
            SessionBackupData(
                session = session,
                messages = db.messageDao().getMessages(session.id),
                summary = db.summaryDao().get(session.id)
            )
        }
    }

    /** The backup data above, encoded as formatVersion 1 JSON text. */
    private suspend fun buildBackupText(): String = encodeBackup(buildBackupData())

    /**
     * Write every session (with its messages and compaction summary)
     * to the file the user picked with the system file picker.
     * Sessions only — no API keys, credentials, MCP tokens or usage
     * records are ever in the file (see SessionBackup.kt).
     */
    fun exportSessions(context: Context, uri: Uri) {
        viewModelScope.launch {
            backupNote = null
            backupError = null
            try {
                val data = buildBackupData()
                val text = encodeBackup(data)
                val written = context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                    true
                } ?: false
                if (!written) {
                    backupError = "Could not write the backup file — try another location."
                } else {
                    backupNote = "Backup saved — ${data.size} session" +
                        (if (data.size == 1) "" else "s") +
                        ". It holds sessions only: no API keys, no usage records."
                }
            } catch (e: Exception) {
                backupError = "Backup failed: ${e.message ?: "could not write the file."}"
            }
        }
    }

    /**
     * Restore sessions from a backup file the user picked with the
     * system file picker.
     *
     * All-or-nothing per FILE for structural problems: malformed JSON
     * or a wrong format version imports nothing. Per SESSION after
     * that: a session whose id already exists is skipped (never
     * overwritten), an invalid session is skipped with its reason,
     * and the rest are imported with fresh message ids. A write that
     * still fails mid-session is cleaned up, so no half-imported
     * session is left behind. The result is a plain summary on Home.
     *
     * An imported session keeps its folder URI string, but the SAF
     * grant does not travel with the file — on a new device or after
     * a reinstall the session shows "Folder unavailable" until the
     * folder is re-chosen from Home.
     */
    fun importSessions(context: Context, uri: Uri) {
        viewModelScope.launch {
            backupNote = null
            backupError = null
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null) {
                    backupError = "Could not open that file — nothing was imported."
                    return@launch
                }
                if (bytes.size > MAX_BACKUP_BYTES) {
                    backupError = "That backup file is too large (over 10 MB) — nothing was imported."
                    return@launch
                }
                // Encrypted backup (Phase 7, Step B3): a ZIP with the
                // sessions JSON + an encrypted secrets entry. It is
                // NOT imported here — the preview is shown first
                // (sessions / messages / whether it carries secrets)
                // and the user confirms, entering the passphrase when
                // the backup carries secrets.
                if (isEncryptedBackup(bytes)) {
                    val preview = readEncryptedBackupPreview(bytes)
                    if (preview == null) {
                        backupError = "That file is not a CodeSathi backup — nothing was imported."
                        return@launch
                    }
                    pendingEncryptedImportBytes = bytes
                    encryptedImportPreview = preview
                    return@launch
                }
                val text = String(bytes, Charsets.UTF_8)
                val backup = when (val parsed = parseBackup(text)) {
                    is BackupParseResult.Error -> {
                        backupError = parsed.message
                        return@launch
                    }
                    is BackupParseResult.Ok -> parsed.backup
                }
                val result = importBackupSessions(backup.sessions)
                backupNote = importSummaryNote(result)
            } catch (e: Exception) {
                backupError = "Restore failed: ${e.message ?: "could not read the file."}"
            }
        }
    }

    /** The plain import summary, shared by the JSON and the encrypted-ZIP paths. */
    private fun importSummaryNote(result: BackupImportResult): String = buildString {
        append("${result.imported} imported, ${result.skipped} skipped")
        if (result.skippedReasons.isNotEmpty()) {
            append(" — ")
            append(result.skippedReasons.take(5).joinToString("; "))
            if (result.skippedReasons.size > 5) {
                append("; and ${result.skippedReasons.size - 5} more")
            }
        }
        append(".")
        if (result.imported > 0) {
            append(" Restored sessions keep their folder, but the folder " +
                "permission may need re-choosing on a new device.")
        }
    }

    // ---- Encrypted backup (Phase 7, Step B3) ----

    /**
     * The preview of an encrypted backup waiting for the user's
     * confirm (see [importSessions]): sessions / messages / whether
     * it carries secrets. Null when no encrypted import is pending.
     */
    var encryptedImportPreview by mutableStateOf<EncryptedBackupPreview?>(null)
        private set

    /** The encrypted backup bytes behind the preview above. */
    private var pendingEncryptedImportBytes: ByteArray? = null

    /** Dismiss the encrypted-import preview without importing anything. */
    fun dismissEncryptedImportPreview() {
        encryptedImportPreview = null
        pendingEncryptedImportBytes = null
    }

    /**
     * Write the encrypted backup: the same sessions JSON the plain
     * export writes, plus the device's secrets encrypted with
     * [passphrase], in one ZIP file. The passphrase needs at least 8
     * characters — a short passphrase is refused with a plain message
     * instead of writing a weakly protected file. The passphrase is
     * never stored: it lives only in this call's argument. The result
     * says how many secrets were carried (0 when the device holds
     * none — the file still carries an encrypted empty object).
     */
    fun exportEncryptedSessions(context: Context, uri: Uri, passphrase: String) {
        viewModelScope.launch {
            backupNote = null
            backupError = null
            if (passphrase.length < 8) {
                backupError = "Choose a passphrase of at least 8 characters — it protects your API keys."
                return@launch
            }
            try {
                val data = buildBackupData()
                val sessionsJson = encodeBackup(data)
                val sessionCount = data.size
                val mcpIds = prefs.mcpServers.first().map { it.id }
                val secrets = keys.exportSecretsJson(mcpIds)
                val bytes = writeEncryptedBackupZip(sessionsJson, secrets.json, passphrase)
                val written = context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(bytes)
                    true
                } ?: false
                if (!written) {
                    backupError = "Could not write the backup file — try another location."
                } else {
                    backupNote = "Encrypted backup saved — $sessionCount session" +
                        (if (sessionCount == 1) "" else "s") +
                        ", ${secrets.entries} secret" +
                        (if (secrets.entries == 1) "" else "s") +
                        " encrypted with your passphrase. The passphrase is not stored anywhere — " +
                        "you will need it to restore the keys."
                }
            } catch (e: Exception) {
                backupError = "Encrypted backup failed: ${e.message ?: "could not write the file."}"
            }
        }
    }

    /**
     * Apply the encrypted import the user previewed (see
     * [encryptedImportPreview]). When the backup carries secrets,
     * the passphrase is required: the secrets are decrypted FIRST,
     * so a wrong passphrase stops the whole import before any
     * session is written (AgentMT's order). Restoring secrets never
     * overwrites a credential already on the device (see
     * [KeyStore.importSecretsJson]). When the backup carries no
     * secrets, the passphrase is ignored and only sessions import.
     */
    fun confirmEncryptedImport(passphrase: String?) {
        val bytes = pendingEncryptedImportBytes ?: return
        val preview = encryptedImportPreview ?: return
        viewModelScope.launch {
            backupNote = null
            backupError = null
            try {
                var restoredSecrets = 0
                if (preview.hasSecrets) {
                    val pass = passphrase.orEmpty()
                    if (pass.isEmpty()) {
                        backupError = "Enter the passphrase this backup was encrypted with — the keys cannot be restored without it."
                        return@launch
                    }
                    val secretsJson = try {
                        decryptSecretsFromEncryptedBackup(bytes, pass)
                    } catch (e: BackupCrypto.WrongPassphrase) {
                        backupError = "Wrong passphrase — nothing was imported."
                        return@launch
                    } catch (e: IllegalArgumentException) {
                        backupError = "That backup's secrets are damaged — nothing was imported."
                        return@launch
                    }
                    restoredSecrets = keys.importSecretsJson(secretsJson)
                }
                val sessionsText = readSessionsJsonFromEncryptedBackup(bytes)
                    ?: run {
                        backupError = "That file is not a CodeSathi backup — nothing was imported."
                        return@launch
                    }
                val backup = when (val parsed = parseBackup(sessionsText)) {
                    is BackupParseResult.Error -> {
                        backupError = parsed.message
                        return@launch
                    }
                    is BackupParseResult.Ok -> parsed.backup
                }
                val result = importBackupSessions(backup.sessions)
                backupNote = importSummaryNote(result) +
                    if (preview.hasSecrets) {
                        " $restoredSecrets secret" +
                            (if (restoredSecrets == 1) "" else "s") +
                            " restored (ones already on this device were kept)."
                    } else {
                        " No secrets were in this backup — add your API keys again in Providers."
                    }
            } catch (e: Exception) {
                backupError = "Restore failed: ${e.message ?: "could not read the file."}"
            } finally {
                encryptedImportPreview = null
                pendingEncryptedImportBytes = null
            }
        }
    }

    /**
     * The import itself, separated from the file reading so the rules
     * are testable in one place: validate first, skip duplicates and
     * invalid sessions, insert the rest, clean up on a failed write.
     */
    private suspend fun importBackupSessions(
        entries: List<com.inyogeshwar.codesathi.data.BackupSession>
    ): BackupImportResult {
        var imported = 0
        val skipped = mutableListOf<String>()
        val seenIds = mutableSetOf<String>()
        for (entry in entries) {
            val invalid = validateBackupSession(entry)
            if (invalid != null) {
                skipped += "“${entry.title.ifBlank { entry.id.ifBlank { "unnamed" } }}” — $invalid"
                continue
            }
            if (entry.id in seenIds || db.sessionDao().getSession(entry.id) != null) {
                skipped += "“${entry.title}” — already exists"
                continue
            }
            seenIds += entry.id
            try {
                db.sessionDao().insert(entry.toSession())
                val insertedIds = entry.messages.map { message ->
                    db.messageDao().insert(
                        Message(
                            sessionId = entry.id,
                            role = message.role,
                            text = message.text,
                            toolJson = message.toolJson,
                            createdAt = message.createdAt
                        )
                    )
                }
                if (entry.summaryMarkdown != null) {
                    db.summaryDao().save(
                        SessionSummary(
                            sessionId = entry.id,
                            summaryMarkdown = entry.summaryMarkdown,
                            // Old message ids are gone (fresh ids above),
                            // so the summary covers up to the last
                            // imported message instead.
                            coversUpToMessageId = insertedIds.lastOrNull() ?: 0L,
                            modelId = entry.summaryModelId.orEmpty(),
                            createdAt = entry.summaryCreatedAt ?: System.currentTimeMillis()
                        )
                    )
                }
                imported++
            } catch (e: Exception) {
                // Never leave a half-imported session behind.
                db.messageDao().deleteForSession(entry.id)
                db.sessionDao().deleteById(entry.id)
                skipped += "“${entry.title}” — could not be saved (${e.message ?: "write failed"})"
            }
        }
        return BackupImportResult(imported, skipped.size, skipped)
    }

    // ---- Multi-device sync (Phase 6, Step 4) ----

    /** One-line result from the last sync, for the Sync screen. */
    var syncResult by mutableStateOf<String?>(null)
        private set
    /** One-line error from the last sync, for the Sync screen. */
    var syncError by mutableStateOf<String?>(null)
        private set
    /** True while a sync is running — the Sync button is disabled then. */
    var syncBusy by mutableStateOf(false)
        private set

    fun dismissSyncMessage() {
        syncResult = null
        syncError = null
    }

    /**
     * This device's Remote password, for prefilling the Sync screen —
     * the password the OTHER device checks is its own Remote password,
     * and most users set the same one on both devices. Null when none
     * is set here. The value is only ever put in a masked password
     * field and sent as an Authorization header, never logged.
     */
    fun remotePassword(): String? = keys.getRemotePassword()

    /** Backup text of every session, for the server's sync-export endpoint. */
    suspend fun exportBackupText(): String = buildBackupText()

    /**
     * Merge a backup received from another device. Same format and
     * validation as a file restore, different rule for a session that
     * already exists: newest `updatedAt` wins — the incoming copy
     * replaces the local one (session row, messages and summary
     * together) instead of being skipped, so the two devices converge.
     * An equal timestamp keeps the local copy ("already up to date").
     *
     * Each session is replaced inside one Room transaction, so a
     * failed write rolls back to the old session instead of leaving
     * a half-replaced one. A session that fails is skipped with its
     * reason; the rest still merge.
     */
    suspend fun mergeSyncedSessions(backup: SessionBackup): SyncMergeResult {
        var added = 0
        var updated = 0
        val skipped = mutableListOf<String>()
        val seenIds = mutableSetOf<String>()
        for (entry in backup.sessions) {
            val invalid = validateBackupSession(entry)
            if (invalid != null) {
                skipped += "“${entry.title.ifBlank { entry.id.ifBlank { "unnamed" } }}” — $invalid"
                continue
            }
            if (entry.id in seenIds) {
                skipped += "“${entry.title}” — sent twice in one sync"
                continue
            }
            seenIds += entry.id
            val existing = db.sessionDao().getSession(entry.id)
            if (existing != null && entry.updatedAt <= existing.updatedAt) {
                skipped += "“${entry.title}” — already up to date"
                continue
            }
            val isUpdate = existing != null
            try {
                db.withTransaction {
                    if (isUpdate) {
                        db.messageDao().deleteForSession(entry.id)
                        db.sessionDao().deleteSummaryForSession(entry.id)
                    }
                    // ABORT insert for a new session (never overwrite by
                    // accident); REPLACE upsert for an update, whose row
                    // is still there because only its messages were deleted.
                    if (isUpdate) {
                        db.sessionDao().upsert(entry.toSession())
                    } else {
                        db.sessionDao().insert(entry.toSession())
                    }
                    val insertedIds = entry.messages.map { message ->
                        db.messageDao().insert(
                            Message(
                                sessionId = entry.id,
                                role = message.role,
                                text = message.text,
                                toolJson = message.toolJson,
                                createdAt = message.createdAt
                            )
                        )
                    }
                    if (entry.summaryMarkdown != null) {
                        db.summaryDao().save(
                            SessionSummary(
                                sessionId = entry.id,
                                summaryMarkdown = entry.summaryMarkdown,
                                // Old message ids are gone (fresh ids
                                // above), so the summary covers up to
                                // the last imported message instead.
                                coversUpToMessageId = insertedIds.lastOrNull() ?: 0L,
                                modelId = entry.summaryModelId.orEmpty(),
                                createdAt = entry.summaryCreatedAt ?: System.currentTimeMillis()
                            )
                        )
                    }
                }
                if (isUpdate) updated++ else added++
            } catch (e: Exception) {
                skipped += "“${entry.title}” — could not be saved (${e.message ?: "write failed"})"
            }
        }
        return SyncMergeResult(added, updated, skipped.size, skipped)
    }

    /**
     * Sync with another device running CodeSathi: pull its sessions,
     * push this device's, or both (pull first, then push, so one
     * round converges both sides on the newest copy of each session).
     *
     * The other device must have Remote mode ON — sync talks to its
     * remote server on the same port with the same Bearer password.
     * The password typed here is the OTHER device's Remote password.
     *
     * Sessions only, exactly like a file backup: no API keys, no
     * provider credentials, no MCP tokens, no usage records ever
     * leave the device. A sync refuses to start while a turn is
     * running or a file change waits for approval, so a merge can
     * never land in the middle of a turn.
     */
    fun syncWithDevice(hostRaw: String, portRaw: String, password: String, push: Boolean, pull: Boolean) {
        viewModelScope.launch {
            syncResult = null
            syncError = null
            if (syncBusy) return@launch
            if (isRunning) {
                syncError = "Wait for the current turn to finish, then sync."
                return@launch
            }
            if (pendingProposal != null) {
                syncError = "A file change is waiting for approval — approve or reject it first, then sync."
                return@launch
            }
            if (!push && !pull) {
                syncError = "Choose Push, Pull, or both."
                return@launch
            }
            val host = try {
                SyncClient.normaliseHost(hostRaw)
            } catch (e: SyncException) {
                syncError = e.message
                return@launch
            }
            val port = try {
                SyncClient.parsePort(portRaw)
            } catch (e: SyncException) {
                syncError = e.message
                return@launch
            }
            if (password.isBlank()) {
                syncError = "Type the other device's Remote password."
                return@launch
            }
            val remotePassword = password.trim()
            syncBusy = true
            try {
                val parts = mutableListOf<String>()
                var pulledAny = false
                val extraReasons = mutableListOf<String>()
                if (pull) {
                    val text = withContext(Dispatchers.IO) {
                        SyncClient.pullExport(host, port, remotePassword)
                    }
                    val backup = when (val parsed = parseBackup(text)) {
                        is BackupParseResult.Error -> {
                            syncError = parsed.message
                            return@launch
                        }
                        is BackupParseResult.Ok -> parsed.backup
                    }
                    val merged = withContext(Dispatchers.IO) { mergeSyncedSessions(backup) }
                    pulledAny = merged.added + merged.updated > 0
                    extraReasons += merged.skippedReasons.filter { !it.endsWith("already up to date") }
                    parts += "Pulled ${merged.added + merged.updated} session" +
                        (if (merged.added + merged.updated == 1) "" else "s") +
                        " (${merged.added} new, ${merged.updated} updated)" +
                        if (merged.skipped > 0) ", ${merged.skipped} skipped" else ""
                }
                if (push) {
                    val text = withContext(Dispatchers.IO) { buildBackupText() }
                    val response = withContext(Dispatchers.IO) {
                        SyncClient.pushImport(host, port, remotePassword, text)
                    }
                    extraReasons += response.reasons.filter { !it.endsWith("already up to date") }
                    val pushed = response.added + response.updated
                    parts += "Pushed $pushed session" +
                        (if (pushed == 1) "" else "s") +
                        " (${response.added} new, ${response.updated} updated)" +
                        if (response.skipped > 0) ", ${response.skipped} skipped" else ""
                }
                syncResult = buildString {
                    append(parts.joinToString(". "))
                    append(".")
                    val shown = extraReasons.take(3)
                    if (shown.isNotEmpty()) {
                        append(" Skipped: ")
                        append(shown.joinToString("; "))
                        append(".")
                    }
                    if (pulledAny) {
                        append(" Pulled sessions keep their folder, but the folder " +
                            "permission may need re-choosing on a new device.")
                    }
                }
            } catch (e: SyncException) {
                syncError = e.message
            } catch (e: Exception) {
                syncError = "Sync failed: ${e.message ?: "could not reach the other device."}"
            } finally {
                syncBusy = false
            }
        }
    }

    /** Folder picked in the New Session flow (or re-choose flow). */
    var pickedFolderUri by mutableStateOf<Uri?>(null)
        private set
    var pickedFolderName by mutableStateOf("")
        private set

    /** Plan card currently shown in the chat screen, if any. */
    var currentPlan by mutableStateOf<Plan?>(null)
        private set

    /** File change awaiting Approve / Reject, with the old content for the diff. */
    var pendingProposal by mutableStateOf<PlannedFile?>(null)
        private set
    var pendingProposalOld by mutableStateOf<String?>(null)
        private set

    // ---- Project templates (Phase 6, Step 3) ----

    /**
     * The template picked on the New Session screen, if the user picked
     * one. "blank" is the default — an empty folder, exactly as before.
     * It is consumed once by [createSession] (which resets it), so a
     * template picked and then abandoned never leaks into a later
     * session created another way (e.g. from a subfolder in the editor).
     */
    var selectedTemplateId by mutableStateOf(BLANK_TEMPLATE_ID)
        private set

    /** Pick a template on the New Session screen. Unknown ids fall back to blank. */
    fun selectTemplate(id: String) {
        selectedTemplateId = templateById(id)?.id ?: BLANK_TEMPLATE_ID
    }

    /**
     * Template files still waiting to be proposed, in the template's
     * file order. Only the first file is ever in [pendingProposal] at
     * once — approving it queues the next one (see [approveProposal]),
     * so a multi-file template still goes through the one-proposal-at-
     * a-time diff flow. Rejecting a template file, or a failed write,
     * clears the rest of the queue.
     *
     * [TemplateQueueEntry.oldContent] is the file's current text when
     * it already exists in the folder (empty / missing files have
     * null): an existing file is proposed as an "edit", so the diff
     * card shows the old text and the write only replaces when the
     * disk still holds it — exactly like [proposeEditorSave]. A
     * template Approve can never silently overwrite a file the user
     * cannot see in the diff.
     */
    private var pendingTemplateFiles: List<TemplateQueueEntry> = emptyList()

    /** One template file waiting its turn, with its current on-disk text if it exists. */
    private data class TemplateQueueEntry(
        val path: String,
        val content: String,
        val oldContent: String?
    )

    /** Build the diff-card proposal for one queued template file. */
    private fun queueTemplateEntry(entry: TemplateQueueEntry) {
        pendingProposal = if (entry.oldContent != null) {
            PlannedFile(path = entry.path, action = "edit", oldSnippet = entry.oldContent, newSnippet = entry.content)
        } else {
            PlannedFile(path = entry.path, action = "create", newContent = entry.content)
        }
        pendingProposalOld = entry.oldContent
    }

    /**
     * The session a pending template proposal belongs to, or null when
     * the pending proposal is not a template file. Template proposals
     * are scoped to their session (see [shouldShowProposalFor]) — a
     * template chosen for session A must never show its diff card in
     * session B's chat. Agent and editor proposals carry no session
     * id and show wherever the card is, as before.
     */
    var pendingTemplateSessionId by mutableStateOf<String?>(null)
        private set

    /** True while [pendingProposal] is a template file, not an agent or editor change. */
    private var pendingProposalIsTemplate: Boolean = false

    /**
     * True when the pending diff card belongs on [sessionId]'s screens.
     * Template proposals show only in their own session's chat / editor;
     * every other proposal (agent, editor) shows wherever the card is.
     */
    fun shouldShowProposalFor(sessionId: String): Boolean =
        pendingTemplateSessionId == null || pendingTemplateSessionId == sessionId

    /**
     * Queue a template's files for approval in [sessionId]'s chat.
     * Nothing is written here — the first file becomes [pendingProposal]
     * (a "create", or an "edit" when [oldContents] holds that file's
     * current text, so the diff card shows what Approve would replace),
     * and the rest wait in [pendingTemplateFiles] until each Approve
     * queues the next one. A tool message in the session says what was
     * chosen and that nothing is written until Approve is tapped, so
     * the card never appears unexplained. When another proposal is
     * already waiting, the template is not queued at all (one approval
     * at a time) and the message says so instead.
     */
    private suspend fun queueTemplateProposal(
        sessionId: String,
        templateName: String,
        files: List<Pair<String, String>>,
        oldContents: Map<String, String>
    ) {
        if (files.isEmpty()) return
        if (pendingProposal != null) {
            db.messageDao().insert(
                Message(
                    sessionId = sessionId,
                    role = "tool",
                    text = "Template “$templateName” was chosen, but another file change " +
                        "is already waiting for approval, so the template was not " +
                        "prepared. Approve or reject that change first, then start " +
                        "a new session with the template. Nothing was written.",
                    createdAt = System.currentTimeMillis()
                )
            )
            return
        }
        val entries = files.map { (path, content) ->
            TemplateQueueEntry(path, content, oldContents[path])
        }
        pendingTemplateFiles = entries.drop(1)
        pendingTemplateSessionId = sessionId
        pendingProposalIsTemplate = true
        val first = entries.first()
        queueTemplateEntry(first)
        val more = if (entries.size > 1) " (${entries.size - 1} more file${if (entries.size > 2) "s" else ""} after this one)" else ""
        val existsNote = if (first.oldContent != null) {
            " It already exists in this folder — approving replaces it, and the diff shows the old text."
        } else ""
        db.messageDao().insert(
            Message(
                sessionId = sessionId,
                role = "tool",
                text = "Template “$templateName” chosen — ${first.path} is waiting for " +
                    "approval below$more.$existsNote Nothing is written until you tap Approve.",
                createdAt = System.currentTimeMillis()
            )
        )
    }

    private var runningState by mutableStateOf(false)
    /**
     * True while an agent turn runs. v1.2: going from false to true also
     * stamps [runStartedAt], so the chat's working indicator can show the
     * real elapsed seconds of the current run.
     */
    var isRunning: Boolean
        get() = runningState
        private set(value) {
            if (value && !runningState) runStartedAt = System.currentTimeMillis()
            runningState = value
        }
    /** Wall-clock start (ms) of the current/last run; 0 before the first run. */
    var runStartedAt by mutableStateOf(0L)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set
    var summarisedBanner by mutableStateOf(false)
        private set
    var lastSummary by mutableStateOf<SessionSummary?>(null)
        private set

    /** The user's ordered fallback list (1st, 2nd, 3rd …). */
    val fallbackChain: StateFlow<List<FallbackEntry>> =
        prefs.fallbackChain.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
        )

    /** "providerId/modelId" values whose every key is exhausted right now. */
    var exhaustedModelKeys by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Plain "why + when" line per fully-blocked "providerId/modelId" (Phase 7, Step B1). */
    var exhaustedLabels by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** Bumped whenever keys change, so the provider screen re-reads them. */
    var keysVersion by mutableStateOf(0)
        private set

    /** One-line notice after a fallback switch ("Switched to … — quota reached"). */
    var fallbackNotice by mutableStateOf<String?>(null)
        private set

    /** Fetched model lists, keyed by provider id (fresh or cached). */
    var fetchedModels by mutableStateOf<Map<String, List<String>>>(emptyMap())
        private set
    /** Provider whose model list is being refreshed right now, if any. */
    var fetchingProviderId by mutableStateOf<String?>(null)
        private set
    /** Last fetch error per provider id — shown next to the cached list. */
    var fetchErrors by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** Per-tool permission rules (allow / ask / deny), from DataStore. */
    val toolRules: StateFlow<Map<String, String>> =
        prefs.toolRules.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            PreferencesStore.DEFAULT_TOOL_RULES
        )

    /** Approval mode (Phase 7, Step B2), from DataStore. "ask_all" by default — behaves exactly like v1. */
    val approvalMode: StateFlow<String> =
        prefs.approvalMode.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000), "ask_all"
        )

    /** Ordered glob permission rules (Phase 7, Step B2), from DataStore. */
    val permissionRules: StateFlow<List<PermissionRule>> =
        prefs.permissionRules.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
        )

    /** True when the last turn ended interrupted (error, limit, or Stop)
     *  and can be continued with [continueTurn]. Cleared when a new
     *  message is sent or the turn is continued. */
    var interruptedTurn by mutableStateOf(false)
        private set

    /** MCP servers (Phase 2, Step 3), from DataStore. */
    val mcpServers: StateFlow<List<McpServer>> =
        prefs.mcpServers.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
        )

    /** Last fetched tools/list per server id — the MCP screen reads this. */
    var mcpServerTools by mutableStateOf<Map<String, List<McpToolInfo>>>(emptyMap())
        private set
    /** Server ids whose tool list is being fetched right now. */
    var mcpLoadingServers by mutableStateOf<Set<String>>(emptySet())
        private set
    /** Last fetch error per server id — shown next to that server. */
    var mcpErrors by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** Voice output (Phase 2, Step 5): one TextToSpeech instance for the app. */
    val voiceSpeaker = VoiceSpeaker(app)

    /** Voice-output toggle, from DataStore. Off by default — never speaks unless on. */
    val voiceOutputEnabled: StateFlow<Boolean> =
        prefs.voiceOutputEnabled.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000), false
        )

    /** Flip the voice-output toggle; turning it off stops any speech. */
    fun setVoiceOutputEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setVoiceOutputEnabled(enabled)
            if (!enabled) voiceSpeaker.stop()
        }
    }

    // ---- Remote mode (Phase 2, Step 7) ----

    /** Remote server on/off, from DataStore. Off by default. */
    val remoteEnabled: StateFlow<Boolean> =
        prefs.remoteEnabled.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000), false
        )

    fun setRemoteEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setRemoteEnabled(enabled) }
    }

    /** True when a remote password is saved (EncryptedSharedPreferences). */
    fun hasRemotePassword(): Boolean = keys.hasRemotePassword()

    /**
     * Save (or change) the remote password. Returns false when it is
     * shorter than 4 characters. The value is stored encrypted only —
     * it is never logged and never shown in full.
     */
    fun setRemotePassword(password: String): Boolean = keys.saveRemotePassword(password)

    /** Messages of one session for the remote server, or null if unknown. */
    suspend fun remoteSessionMessages(sessionId: String): List<Message>? {
        if (db.sessionDao().getSession(sessionId) == null) return null
        return db.messageDao().getMessages(sessionId)
    }

    /**
     * One agent turn driven by a remote device. This is the same turn the
     * chat screen runs — [runAgentTurn] with the session's own provider,
     * model, project, tool rules, MCP tools, skills and fallback — so a
     * remote turn behaves exactly like an in-app turn, including
     * compaction and fallback switches.
     *
     * Differences from [sendMessage]: it is a suspend function that
     * returns the reply (the HTTP server has no UI to watch), and it
     * refuses to start while another turn is running or while a file
     * change is waiting for approval on the phone.
     *
     * A turn that proposes a file change does NOT apply it: the proposal
     * is left in [pendingProposal] for the phone's diff card, and is
     * returned in the result so the remote page can say "waiting for
     * approval on the phone". Approve / Reject still happen on the phone
     * only — there is no remote approve.
     */
    suspend fun runRemoteTurn(sessionId: String, message: String, context: Context): RemoteTurnResult {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) {
            return RemoteTurnResult(reply = "", error = "Message is empty.")
        }
        if (isRunning) {
            return RemoteTurnResult(
                reply = "",
                error = "Another turn is already running — wait for it to finish, then try again."
            )
        }
        if (pendingProposal != null) {
            return RemoteTurnResult(
                reply = "",
                error = "A file change is waiting for approval on the phone — approve or reject it first."
            )
        }
        val session = db.sessionDao().getSession(sessionId)
            ?: return RemoteTurnResult(reply = "", error = "Unknown session.")
        val provider = provider(session.providerId)
            ?: return RemoteTurnResult(reply = "", error = "Unknown provider ${session.providerId}.")
        val apiKey = keys.getKey(session.providerId)
        missingCredentialMessage(provider, apiKey)?.let { message ->
            return RemoteTurnResult(reply = "", error = message)
        }
        isRunning = true
        lastError = null
        fallbackNotice = null
        currentPlan = null
        val beforeIds = db.messageDao().getMessages(sessionId).map { it.id }.toSet()
        try {
            db.messageDao().insert(
                Message(sessionId = session.id, role = "user", text = trimmed, createdAt = System.currentTimeMillis())
            )
            db.sessionDao().touch(session.id, System.currentTimeMillis())
            val history = db.messageDao().getMessages(sessionId)
            // Slash command (Phase 3, Step 1): the stored message stays
            // as the user typed it; only the text sent to the model is
            // expanded. Works in Plan and Build alike.
            val expanded = expandCommand(trimmed)
            runAgentTurn(session, provider, apiKey, history, expanded, context)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = e.message ?: "Agent run failed."
        } finally {
            isRunning = false
        }
        // What the turn produced: new tool messages, the assistant reply
        // (runAgentTurn stores it as one message), and any plan/proposal.
        val after = db.messageDao().getMessages(sessionId)
        val tools = after
            .filter { it.id !in beforeIds && it.role == "tool" }
            .map { it.text }
        val reply = after.filter { it.role == "assistant" }.lastOrNull()?.text.orEmpty()
        return RemoteTurnResult(
            reply = reply,
            tools = tools,
            error = lastError,
            proposal = pendingProposal,
            plan = currentPlan,
            fallbackNotice = fallbackNotice
        )
    }

    override fun onCleared() {
        localLlm.close()
        voiceSpeaker.shutdown()
        super.onCleared()
    }

    // ---- Skills (Phase 2, Step 4) ----

    /** Skills on disk: getFilesDir()/skills/ — one .md file per skill (built-ins + user-added). */
    val skillStore = SkillStore(app)

    /** All skills, built-ins first. Refreshed after every add / edit / delete. */
    var skills by mutableStateOf<List<Skill>>(emptyList())
        private set

    /** Skill id → enabled, from DataStore. No entry means enabled. */
    val skillEnabled: StateFlow<Map<String, Boolean>> =
        prefs.skillEnabled.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap()
        )

    /** One-line note / error from the last skills action, for the Skills screen. */
    var skillNote by mutableStateOf<String?>(null)
        private set
    var skillError by mutableStateOf<String?>(null)
        private set

    fun setToolRule(toolName: String, rule: String) {
        viewModelScope.launch { prefs.setToolRule(toolName, rule) }
    }

    // ---- Approval mode + glob permission rules (Phase 7, Step B2) ----

    fun setApprovalMode(mode: String) {
        viewModelScope.launch { prefs.setApprovalMode(mode) }
    }

    fun addPermissionRule(rule: PermissionRule) {
        viewModelScope.launch { prefs.addPermissionRule(rule) }
    }

    fun removePermissionRule(ruleId: String) {
        viewModelScope.launch { prefs.removePermissionRule(ruleId) }
    }

    fun movePermissionRule(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch { prefs.movePermissionRule(fromIndex, toIndex) }
    }

    private var runJob: Job? = null
    /**
     * The `run` tool of the turn in progress, if any (Phase 6, Step 1).
     * [stopRun] kills its shell process as well as cancelling the job.
     * Set in [runAgentTurn], cleared when the turn's events are done
     * (including on cancellation, via try/finally there).
     */
    private var activeRunTool: RunTool? = null
    /** On-device .task model engine (Phase 6, Step 5), wired into [llm]. */
    private val localLlm = LocalLlmClient(app)
    private val llm = LlmClient(local = localLlm)

    /** Display name of the picked .task model file, or null when none is picked. */
    var localModelName by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launch {
            prefs.onboarded.collect { onboarded = it; onboardingChecked = true }
        }
        // Runs (Phase 7, Step B3): a run left "running" or "waiting"
        // by a killed process is orphaned — mark it cancelled on
        // start, so it never stays "running" forever.
        viewModelScope.launch {
            runCatching { db.runDao().cancelOrphaned(System.currentTimeMillis()) }
        }
        // Crash report (Phase 7, Step B3): shown on Home until the
        // user dismisses it.
        pendingCrash = CrashReporter.pending(app)
        // On-device model file (Phase 6, Step 5): keep the picked file's
        // display name in state for the provider screen.
        viewModelScope.launch {
            prefs.localModelName.collect { localModelName = it }
        }
        // Seed the pre-filled (disabled) Exa MCP entry once.
        viewModelScope.launch { prefs.ensureDefaultServers() }
        // Seed the two built-in example slash commands once.
        viewModelScope.launch { prefs.ensureDefaultCommands() }
        // Seed the built-in skills into getFilesDir()/skills/ once.
        viewModelScope.launch {
            skillStore.ensureSeeded()
            refreshSkills()
        }
        // Plugins (Phase 7, Step B4): point the skill store at the
        // enabled plugins' folders, so their skills are listed.
        viewModelScope.launch {
            prefs.plugins.collect { syncPluginSkillDirs(it) }
        }
        refreshExhausted()
    }

    // ---- Onboarding / folder picking (Step 1) ----

    /**
     * Called with the URI the system folder picker returned.
     * Takes a persistable grant so the folder survives app restarts.
     * Returns the folder's display name, or null if the URI is unusable.
     */
    fun onFolderPicked(context: Context, uri: Uri): String? {
        return try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            val name = DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment.orEmpty()
            pickedFolderUri = uri
            pickedFolderName = name
            name
        } catch (_: SecurityException) {
            null
        }
    }

    fun clearPickedFolder() {
        pickedFolderUri = null
        pickedFolderName = ""
    }

    fun completeOnboarding() {
        viewModelScope.launch { prefs.setOnboarded(true) }
    }

    /**
     * Create a session in the picked folder, with the default model and
     * Plan mode. [templateId] is the template picked on the New Session
     * screen (blank by default — an empty folder, exactly as before);
     * the editor's subfolder flow passes nothing, so it always gets
     * blank. The choice is consumed once here and [selectedTemplateId]
     * is reset, so a later session starts blank again.
     *
     * A template's files are never written here. When [context] is
     * given and the template has files, they are read from assets off
     * the main thread and handed to [queueTemplateProposal], which
     * turns the first file into a diff-card proposal — the file only
     * reaches the disk when the user taps Approve in the chat, exactly
     * like an agent write or an editor save. When a template file
     * cannot be read, no proposal is queued and the session says so.
     */
    fun createSession(title: String, context: Context? = null, templateId: String = BLANK_TEMPLATE_ID): String? {
        val uri = pickedFolderUri ?: return null
        val id = UUID.randomUUID().toString()
        val providerId = defaultProviderPair().first
        val modelId = defaultProviderPair().second
        val session = Session(
            id = id,
            title = title.ifBlank { pickedFolderName },
            folderName = pickedFolderName,
            projectUri = uri.toString(),
            providerId = providerId,
            modelId = modelId,
            agentMode = "plan",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        val template = templateById(templateId) ?: templateById(BLANK_TEMPLATE_ID)!!
        selectedTemplateId = BLANK_TEMPLATE_ID
        viewModelScope.launch {
            db.sessionDao().upsert(session)
            prefs.setLastSession(id)
            clearPickedFolder()
            if (template.files.isNotEmpty() && context != null) {
                // Read the template files from assets and, for each one,
                // its current text in the folder when it already exists
                // — both off the main thread. An existing file is
                // proposed as an "edit" (see queueTemplateProposal), so
                // Approve can never silently overwrite it; an empty file
                // counts as missing, because an "edit" with an empty old
                // snippet would corrupt the file (same guard as
                // proposeEditorSave).
                val (files, oldContents) = withContext(Dispatchers.IO) {
                    val loaded = loadTemplateFiles(context, template)
                    val olds = loaded?.let { list ->
                        val project = SafProject(context, Uri.parse(session.projectUri))
                        list.mapNotNull { (path, _) ->
                            project.readText(path)?.takeIf { it.isNotEmpty() }?.let { path to it }
                        }.toMap()
                    }
                    loaded to olds
                }
                if (files == null) {
                    db.messageDao().insert(
                        Message(
                            sessionId = id,
                            role = "tool",
                            text = "Template “${template.name}” could not be loaded — " +
                                "its files were not prepared. Nothing was written.",
                            createdAt = System.currentTimeMillis()
                        )
                    )
                } else {
                    queueTemplateProposal(id, template.name, files, oldContents.orEmpty())
                }
            }
        }
        return id
    }

    /** Outcome note for the last [createSessionFromFolderUri] call:
     *  whether the subfolder's persistable grant was saved, or — when
     *  it could not be — that the session may stop working after a
     *  restart. The editor shows this note instead of pretending. */
    var subfolderGrantNote by mutableStateOf<String?>(null)
        private set

    /**
     * Create a session from a folder URI the app did not get from the
     * system picker (Phase 3, Step 5 — "new session from a subfolder"
     * in the editor tree). Reuses the normal new-session path: the URI
     * goes through [onFolderPicked] (persistable grant) and then
     * [createSession], so the session gets the default model, Plan
     * mode, and becomes the last session exactly like a picked folder.
     * Returns the new session id, or null when no session was made.
     *
     * Honest limit: taking a persistable grant on a subfolder tree URI
     * can fail (the grant lives on the parent tree) — when it does,
     * the session is still created from the URI, which works right now
     * under the parent folder's grant, and [subfolderGrantNote] says
     * plainly that the permission may not survive a restart, so the
     * user can re-choose the folder from Home if it ever stops working.
     */
    fun createSessionFromFolderUri(context: Context, treeUri: Uri, name: String): String? {
        val picked = try {
            onFolderPicked(context, treeUri)
        } catch (_: Exception) {
            null
        }
        subfolderGrantNote = if (picked != null) {
            "Session “$name” created — the folder permission was saved, so it survives restarts."
        } else {
            "Session “$name” created, but its folder permission could not be saved — " +
                "it may stop working after a restart. Re-choose the folder from Home if it does."
        }
        if (picked == null) {
            // No persistable grant, but the tree URI is usable right now
            // under the parent folder's grant: record it directly so the
            // session is created instead of failing outright.
            pickedFolderUri = treeUri
            pickedFolderName = name
        }
        return createSession(name, context)
    }

    private var cachedDefault: Pair<String, String>? = null

    private fun defaultProviderPair(): Pair<String, String> {
        // v1 default: first top pick. The user picks a model in the provider
        // screen before chatting; this is only the prefilled default.
        val top = providerRepository.topPicks().firstOrNull()
        return cachedDefault ?: (top?.let { it.first.id to it.second } ?: ("openrouter" to "openrouter/free"))
            .also { cachedDefault = it }
    }

    /** Flip a session between Plan and Build. Never writes files by itself. */
    fun setMode(session: Session, mode: String) {
        if (mode != "plan" && mode != "build") return
        viewModelScope.launch {
            db.sessionDao().setMode(session.id, mode)
            db.sessionDao().touch(session.id, System.currentTimeMillis())
        }
    }

    fun setDefaultModel(providerId: String, modelId: String) {
        cachedDefault = providerId to modelId
        viewModelScope.launch { prefs.setDefaultModel(providerId, modelId) }
    }

    // ---- Folder availability ----

    fun isFolderAvailable(context: Context, session: Session): Boolean {
        return try {
            SafProject(context, Uri.parse(session.projectUri)).isAvailable()
        } catch (_: Exception) {
            false
        }
    }

    fun rechooseFolder(context: Context, session: Session, uri: Uri) {
        val name = onFolderPicked(context, uri) ?: return
        viewModelScope.launch {
            db.sessionDao().upsert(session.copy(projectUri = uri.toString(), folderName = name))
        }
    }

    // ---- Providers / keys / favourites ----

    fun provider(id: String): Provider? = providerRepository.provider(id)

    fun hasApiKey(providerId: String): Boolean = keys.hasKey(providerId)

    /** Masked keys for one provider, for the provider screen. */
    fun apiKeys(providerId: String): List<KeyStore.KeyDisplay> = keys.listKeys(providerId)

    /** Add another key for a provider (stored encrypted, never logged). */
    fun addApiKey(providerId: String, apiKey: String) {
        if (keys.addKey(providerId, apiKey) != null) {
            keysVersion++
            refreshExhausted()
        }
    }

    /** Delete one key (by its stable id) from a provider. */
    fun removeApiKey(providerId: String, keyId: String) {
        keys.removeKey(providerId, keyId)
        keysVersion++
        refreshExhausted()
    }

    // ---- Azure OpenAI resource name (Phase 4) ----

    /** "providerId" → resource name, from DataStore. */
    val azureResourceNames: StateFlow<Map<String, String>> =
        prefs.azureResourceNames.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap()
        )

    /**
     * Save the Azure resource name for one provider. Returns false when
     * the name does not match Azure's naming rules (nothing is stored);
     * the screen shows a plain note in that case. The DataStore write
     * itself happens in the scope.
     */
    fun saveAzureResourceName(providerId: String, name: String): Boolean {
        if (!PreferencesStore.isValidAzureResourceName(name.trim().lowercase())) return false
        viewModelScope.launch { prefs.setAzureResourceName(providerId, name) }
        return true
    }

    // ---- AWS Bedrock credentials (Phase 4) ----

    /** Masked AWS access key id for the Bedrock provider card, or null when unset. */
    fun awsAccessKeyIdMasked(): String? = keys.awsAccessKeyIdMasked()

    /** Stored AWS region for the Bedrock provider card (us-east-1 when unset). */
    fun awsRegion(): String = keys.getAwsRegion()

    fun hasAwsCredential(): Boolean = keys.hasAwsCredential()

    /**
     * Save the AWS credential set for Bedrock. Returns false when the
     * access key id or the secret is blank (nothing is stored); the
     * screen shows a plain note in that case.
     */
    fun saveAwsCredential(accessKeyId: String, secretAccessKey: String, region: String): Boolean {
        if (!keys.saveAwsCredential(accessKeyId, secretAccessKey, region)) return false
        keysVersion++
        return true
    }

    /** Delete the AWS credential set for Bedrock. */
    fun removeAwsCredential() {
        keys.removeAwsCredential()
        keysVersion++
    }

    // ---- Reasoning effort per model (Phase 3, Step 2) ----

    /** "providerId/modelId" → "low" | "medium" | "high", from DataStore. */
    val reasoningEfforts: StateFlow<Map<String, String>> =
        prefs.reasoningEfforts.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap()
        )

    /** Set the effort for one model; null goes back to "default" (unset). */
    fun setReasoningEffort(providerId: String, modelId: String, effort: String?) {
        viewModelScope.launch { prefs.setReasoningEffort(providerId, modelId, effort) }
    }

    /** The stored effort for one model, or null when it is unset (default). */
    suspend fun reasoningEffort(providerId: String, modelId: String): String? =
        prefs.reasoningEffort(providerId, modelId)

    // ---- Fallback chain (Phase 2, Step 1) ----

    fun addFallbackEntry(providerId: String, modelId: String) {
        viewModelScope.launch { prefs.addFallbackEntry(FallbackEntry(providerId, modelId)) }
    }

    fun removeFallbackEntry(providerId: String, modelId: String) {
        viewModelScope.launch { prefs.removeFallbackEntry(providerId, modelId) }
    }

    fun moveFallbackEntryUp(index: Int) {
        viewModelScope.launch { prefs.moveFallbackEntry(index, index - 1) }
    }

    fun moveFallbackEntryDown(index: Int) {
        viewModelScope.launch { prefs.moveFallbackEntry(index, index + 1) }
    }

    fun refreshExhausted() {
        viewModelScope.launch {
            exhaustedModelKeys = fallbackEngine.exhaustedModelKeys()
            exhaustedLabels = fallbackEngine.exhaustedLabels()
        }
    }

    // ---- Routing mode + key health (Phase 7, Step B1) ----

    /** "pinned" (stay on the session's model) or "fallback" (walk the chain). */
    val routingMode: StateFlow<String> =
        prefs.routingMode.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000), "fallback"
        )

    /** Per-key health, keyed by "providerId|modelId|keyId". */
    val keyHealth: StateFlow<Map<String, KeyHealth>> =
        prefs.keyHealth.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap()
        )

    fun setRoutingMode(mode: String) {
        viewModelScope.launch { prefs.setRoutingMode(mode) }
    }

    /**
     * Reset every limit flag at once ("Reset limit flags" on the
     * Providers screen): all cooldowns and invalid flags are lifted.
     * The next turn retries the first key / model again.
     */
    fun resetAllKeyLimits() {
        viewModelScope.launch {
            modelRouter.resetAllLimits()
            refreshExhausted()
        }
    }

    /** Reset one credential (one provider + model + key). */
    fun resetKeyLimit(providerId: String, modelId: String, keyId: String) {
        viewModelScope.launch {
            modelRouter.resetCredential(providerId, modelId, keyId)
            refreshExhausted()
        }
    }

    fun dismissFallbackNotice() {
        fallbackNotice = null
    }

    // ---- MCP servers (Phase 2, Step 3) ----

    /**
     * Add a server. Returns false when the name is blank or the URL is
     * not http(s) — or a "termux:<command>" URL, which runs a stdio MCP
     * server inside the user's Termux (Phase 7, Step B4). The token,
     * when given, is stored encrypted in KeyStore only — the server
     * record in DataStore only notes that one exists.
     */
    fun addMcpServer(name: String, url: String, token: String): Boolean {
        val cleanName = name.trim()
        val cleanUrl = url.trim()
        if (cleanName.isEmpty()) return false
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://") &&
            !cleanUrl.startsWith("termux:")) return false
        val id = slugify(cleanName) + "-" + UUID.randomUUID().toString().take(8)
        val hasToken = token.isNotBlank() && keys.saveMcpToken(id, token)
        val server = McpServer(id = id, name = cleanName, url = cleanUrl, enabled = true, hasToken = hasToken)
        viewModelScope.launch {
            prefs.addMcpServer(server)
            refreshMcpTools(server)
        }
        return true
    }

    /** Delete a server: its token and its tools' permission rules go too.
     *  A server a plugin added cannot be deleted here — uninstall the
     *  plugin instead, so the plugin record and the server never drift apart. */
    fun removeMcpServer(serverId: String) {
        if (mcpServers.value.firstOrNull { it.id == serverId }?.pluginId != null) return
        keys.removeMcpToken(serverId)
        mcpServerTools = mcpServerTools - serverId
        mcpErrors = mcpErrors - serverId
        viewModelScope.launch {
            prefs.removeMcpServer(serverId)
            prefs.removeToolRules("mcp:$serverId:")
        }
    }

    fun setMcpServerEnabled(serverId: String, enabled: Boolean) {
        viewModelScope.launch { prefs.setMcpServerEnabled(serverId, enabled) }
    }

    /** Masked token for one server, for the MCP screen ("abc…wxyz"). */
    fun mcpTokenMasked(serverId: String): String? = keys.mcpTokenMasked(serverId)

    /** Fetch a server's tool list (initialize + tools/list). */
    fun refreshMcpTools(server: McpServer) {
        viewModelScope.launch {
            mcpLoadingServers = mcpLoadingServers + server.id
            try {
                val tools = McpClient(server.url, keys.getMcpToken(server.id)).listTools()
                mcpServerTools = mcpServerTools + (server.id to tools)
                mcpErrors = mcpErrors - server.id
            } catch (e: Exception) {
                mcpErrors = mcpErrors + (server.id to (e.message ?: "Could not reach this server."))
            } finally {
                mcpLoadingServers = mcpLoadingServers - server.id
            }
        }
    }

    /** Tools the agent can call right now: enabled servers only. */
    fun mcpTools(): List<Tool> {
        return mcpServers.value.filter { it.enabled }.flatMap { server ->
            mcpToolsFor(server, mcpServerTools[server.id].orEmpty()) { keys.getMcpToken(server.id) }
        }
    }

    /**
     * Before a chat turn, fetch tool lists for enabled servers that have
     * none yet (and no recorded error). A server that is down does not
     * block the turn — its error is recorded and the chat runs without it.
     */
    private suspend fun ensureMcpToolsLoaded() {
        for (server in mcpServers.value) {
            if (!server.enabled) continue
            if (mcpServerTools.containsKey(server.id)) continue
            if (mcpErrors.containsKey(server.id)) continue
            try {
                val tools = McpClient(server.url, keys.getMcpToken(server.id)).listTools()
                mcpServerTools = mcpServerTools + (server.id to tools)
            } catch (e: Exception) {
                mcpErrors = mcpErrors + (server.id to (e.message ?: "Could not reach this server."))
            }
        }
    }

    /** URL-safe slug for a server id: "My Server" → "my-server". */
    private fun slugify(name: String): String {
        val slug = name.lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .split('-').filter { it.isNotEmpty() }.joinToString("-")
        return slug.ifBlank { "server" }
    }

    // ---- Skills (Phase 2, Step 4) ----

    /** Re-read the skills folder into [skills]. */
    fun refreshSkills() {
        viewModelScope.launch { skills = skillStore.listSkills() }
    }

    /** Skills the agent may use right now: enabled only (no entry = enabled). */
    fun enabledSkills(): List<Skill> =
        skills.filter { skillEnabled.value[it.id] ?: true }

    /** The skill tool for this turn, gated to the enabled skills. Always
     *  present: the tool itself refuses unknown / disabled skills with a
     *  clear message, so the model never gets a bare "Unknown tool". */
    fun skillTools(): List<Tool> {
        val enabled = enabledSkills().map { it.id }.toSet()
        // Fully qualified: this function shares the name with the
        // top-level skillTools() builder in the agent package.
        return com.inyogeshwar.codesathi.agent.skillTools(skillStore) { it in enabled }
    }

    fun setSkillEnabled(skillId: String, enabled: Boolean) {
        viewModelScope.launch { prefs.setSkillEnabled(skillId, enabled) }
    }

    /** Add a user skill. The name becomes the id ("Code Review" → "code-review"). */
    fun createSkill(name: String, description: String, instructions: String) {
        if (name.isBlank() || instructions.isBlank()) {
            skillError = "Give the skill a name and some instructions."
            skillNote = null
            return
        }
        viewModelScope.launch {
            val id = skillStore.createSkill(name.trim(), description.trim(), instructions.trim())
            if (id == null) {
                skillError = "A skill named “${name.trim()}” already exists — pick another name."
                skillNote = null
            } else {
                skillNote = "Skill “$id” added and enabled."
                skillError = null
                refreshSkills()
            }
        }
    }

    /** Overwrite a skill's Markdown (view / edit on the Skills screen). */
    fun saveSkill(skillId: String, markdown: String) {
        viewModelScope.launch {
            if (skillStore.saveSkill(skillId, markdown)) {
                skillNote = "Skill “$skillId” saved."
                skillError = null
                refreshSkills()
            } else {
                skillError = "Could not save “$skillId” — check the text and try again."
                skillNote = null
            }
        }
    }

    /** Delete a user-added skill (built-ins cannot be deleted). */
    fun deleteSkill(skillId: String) {
        viewModelScope.launch {
            if (skillStore.deleteSkill(skillId)) {
                prefs.removeSkillEnabled(skillId)
                skillNote = "Skill “$skillId” deleted."
                skillError = null
                refreshSkills()
            } else {
                skillError = "Built-in skills cannot be deleted — disable or reset them instead."
                skillNote = null
            }
        }
    }

    /** Restore an edited built-in skill to the version shipped with the app. */
    fun resetSkill(skillId: String) {
        viewModelScope.launch {
            if (skillStore.resetSkill(skillId)) {
                skillNote = "Skill “$skillId” reset to the built-in version."
                skillError = null
                refreshSkills()
            } else {
                skillError = "Could not reset “$skillId”."
                skillNote = null
            }
        }
    }

    fun dismissSkillMessage() {
        skillNote = null
        skillError = null
    }

    // ---- Plugins + skill install (Phase 7, Step B4) ----

    /** Installed plugins, from DataStore. New plugins are disabled. */
    val plugins: StateFlow<List<PluginRecord>> =
        prefs.plugins.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
        )

    /** Termux backend for the agent's `run` tool, from DataStore. Off by default. */
    val termuxEnabled: StateFlow<Boolean> =
        prefs.termuxEnabled.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000), false
        )

    fun setTermuxEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setTermuxEnabled(enabled) }
    }

    private val pluginManager = PluginManager(app, GitHubFetcher())
    private val githubFetcher = GitHubFetcher()

    /** Point the skill store at the enabled plugins' folders, then
     *  re-list skills. Called whenever the plugin list changes. */
    private fun syncPluginSkillDirs(plugins: List<PluginRecord>) {
        skillStore.pluginDirs = pluginManager.enabledDirs(plugins)
        refreshSkills()
    }

    /** Install a skill from a GitHub URL, skills.sh link or shorthand.
     *  The result is reported through the Skills screen's note / error. */
    fun installSkillFromUrl(url: String) {
        val ref = RepoRef.parse(url)
        if (ref == null) {
            skillError = "That does not look like a GitHub repo or skills.sh link — " +
                "try owner/repo or a full github.com URL."
            skillNote = null
            return
        }
        viewModelScope.launch {
            val result = skillStore.installFromGitHub(ref, githubFetcher)
            if (result.error != null) {
                skillError = result.error
                skillNote = null
            } else if (result.installed.isEmpty()) {
                skillError = "Nothing new installed" +
                    if (result.skipped.isNotEmpty()) " — ${result.skipped.joinToString()} already exists." else "."
                skillNote = null
            } else {
                skillNote = "Skill installed: ${result.installed.joinToString()}." +
                    if (result.skipped.isNotEmpty()) " Skipped (already exists): ${result.skipped.joinToString()}." else ""
                skillError = null
            }
            refreshSkills()
        }
    }

    /** Install a plugin from a GitHub repo URL or shorthand. A
     *  marketplace URL is refused with the plugin names, so the user
     *  can pick one plugin's own repo instead of installing a whole
     *  marketplace at once. */
    fun installPluginFromGitHub(url: String) {
        val ref = RepoRef.parse(url)
        if (ref == null) {
            skillError = "That does not look like a GitHub repo URL — try owner/repo or a full github.com URL."
            skillNote = null
            return
        }
        viewModelScope.launch {
            try {
                val marketplace = pluginManager.marketplace(ref)
                if (marketplace != null) {
                    skillError = "That is a marketplace with ${marketplace.size} plugins " +
                        "(${marketplace.take(5).joinToString { it.name }}). " +
                        "Install one plugin's own repo instead."
                    skillNote = null
                    return@launch
                }
                applyPluginInstall(pluginManager.installFromGitHub(ref))
            } catch (e: Exception) {
                skillError = e.message ?: "Could not install that plugin."
                skillNote = null
            }
        }
    }

    /** Install a plugin from a zip file the user picked. */
    fun installPluginFromZip(uri: Uri) {
        viewModelScope.launch {
            try {
                applyPluginInstall(pluginManager.installFromZip(uri))
            } catch (e: Exception) {
                skillError = e.message ?: "Could not install that plugin."
                skillNote = null
            }
        }
    }

    /**
     * Record one installed plugin: its registry entry (disabled), its
     * slash commands as CustomCommand records (a name that already
     * exists is skipped, never overwritten), and its http MCP servers
     * as McpServer records (disabled, like the plugin). Its skills
     * need no record — SkillStore reads them from the plugin folder.
     */
    private suspend fun applyPluginInstall(install: com.inyogeshwar.codesathi.agent.PluginInstall) {
        val record = install.record
        val existingNames = prefs.customCommands.first().map { it.name }.toHashSet()
        val keptCommands = ArrayList<String>()
        val skippedCommands = ArrayList<String>()
        for (draft in install.commands) {
            if (draft.name in existingNames) {
                skippedCommands += draft.name
                continue
            }
            prefs.addCustomCommand(
                CustomCommand(
                    id = "plugin-${record.id}-${draft.name}",
                    name = draft.name, prompt = draft.body,
                    description = draft.description, enabled = false,
                    pluginId = record.id
                )
            )
            keptCommands += draft.name
        }
        for ((serverName, serverUrl) in install.mcpServers) {
            prefs.addMcpServer(
                McpServer(
                    id = "plugin-${record.id}-" + slugify(serverName) + "-" +
                        UUID.randomUUID().toString().take(8),
                    name = "${record.name} · $serverName",
                    url = serverUrl, enabled = false, hasToken = false,
                    pluginId = record.id
                )
            )
        }
        prefs.addPlugin(record.copy(commands = keptCommands))
        syncPluginSkillDirs(prefs.plugins.first())
        val note = buildString {
            append("Plugin “${record.name}” installed — disabled. ")
            append("Enable it below to use its ${record.skills.size} skill(s), " +
                "${keptCommands.size} command(s) and ${install.mcpServers.size} MCP server(s).")
            if (skippedCommands.isNotEmpty()) {
                append(" Skipped commands (name already exists): ${skippedCommands.joinToString()}.")
            }
            if (record.unsupported.isNotEmpty()) {
                append(" Not included: ${record.unsupported.joinToString()}.")
            }
        }
        skillNote = note
        skillError = null
    }

    /** Enable / disable a plugin, and its commands + MCP servers with it. */
    fun setPluginEnabled(plugin: PluginRecord, enabled: Boolean) {
        viewModelScope.launch {
            prefs.setPluginEnabled(plugin.id, enabled)
            prefs.setCustomCommandsEnabledForPlugin(plugin.id, enabled)
            prefs.setMcpServersEnabledForPlugin(plugin.id, enabled)
            syncPluginSkillDirs(prefs.plugins.first())
            skillNote = if (enabled) "Plugin “${plugin.name}” enabled." else "Plugin “${plugin.name}” disabled."
            skillError = null
        }
    }

    /** Uninstall a plugin: its folder, commands, MCP servers and record.
     *  Its skills vanish with the folder — SkillStore reads them from
     *  there, so nothing else has to be deleted. */
    fun uninstallPlugin(plugin: PluginRecord) {
        viewModelScope.launch {
            prefs.removeCustomCommandsForPlugin(plugin.id)
            prefs.removeMcpServersForPlugin(plugin.id)
            prefs.removePlugin(plugin.id)
            pluginManager.uninstall(plugin.id)
            syncPluginSkillDirs(prefs.plugins.first())
            skillNote = "Plugin “${plugin.name}” uninstalled."
            skillError = null
        }
    }

    // ---- Custom commands (Phase 3, Step 1) ----

    /** User-defined slash commands, from DataStore. */
    val customCommands: StateFlow<List<CustomCommand>> =
        prefs.customCommands.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
        )

    /** One-line note / error from the last commands action, for the Commands screen. */
    var commandNote by mutableStateOf<String?>(null)
        private set
    var commandError by mutableStateOf<String?>(null)
        private set

    /** A command name is lowercase letters, digits and hyphens only. */
    private fun isValidCommandName(name: String): Boolean =
        name.isNotEmpty() && name.all { it == '-' || it.isDigit() || it.isLowerCase() }

    /** Add a command. The name becomes the id; duplicates and bad names are refused.
     *  Returns true when the command was accepted (false = validation error set). */
    fun createCommand(name: String, description: String, prompt: String): Boolean {
        val cleanName = name.trim().lowercase()
        if (!isValidCommandName(cleanName)) {
            commandError = "Command names use lowercase letters, digits and hyphens only (e.g. review-code)."
            commandNote = null
            return false
        }
        if (prompt.isBlank()) {
            commandError = "Give the command a prompt template."
            commandNote = null
            return false
        }
        if (customCommands.value.any { it.name == cleanName }) {
            commandError = "A command named “/$cleanName” already exists — pick another name."
            commandNote = null
            return false
        }
        val command = CustomCommand(
            id = cleanName + "-" + UUID.randomUUID().toString().take(8),
            name = cleanName,
            prompt = prompt.trim(),
            description = description.trim(),
            enabled = true
        )
        viewModelScope.launch {
            prefs.addCustomCommand(command)
            commandNote = "Command “/$cleanName” added and enabled."
            commandError = null
        }
        return true
    }

    /** Save an edited command (name, description, prompt). Renames are
     *  refused when the new name is invalid or already taken by another command.
     *  Returns true when the edit was accepted (false = validation error set). */
    fun saveCommand(command: CustomCommand): Boolean {
        val cleanName = command.name.trim().lowercase()
        if (!isValidCommandName(cleanName)) {
            commandError = "Command names use lowercase letters, digits and hyphens only (e.g. review-code)."
            commandNote = null
            return false
        }
        if (command.prompt.isBlank()) {
            commandError = "Give the command a prompt template."
            commandNote = null
            return false
        }
        if (customCommands.value.any { it.id != command.id && it.name == cleanName }) {
            commandError = "A command named “/$cleanName” already exists — pick another name."
            commandNote = null
            return false
        }
        viewModelScope.launch {
            prefs.updateCustomCommand(command.copy(name = cleanName))
            commandNote = "Command “/$cleanName” saved."
            commandError = null
        }
        return true
    }

    fun deleteCommand(commandId: String) {
        viewModelScope.launch {
            prefs.removeCustomCommand(commandId)
            commandNote = "Command deleted."
            commandError = null
        }
    }

    fun setCommandEnabled(commandId: String, enabled: Boolean) {
        viewModelScope.launch { prefs.setCustomCommandEnabled(commandId, enabled) }
    }

    fun dismissCommandMessage() {
        commandNote = null
        commandError = null
    }

    /**
     * Expand a chat message that starts with "/name".
     *
     * - "/name some text" → the command's prompt, with "{input}" replaced
     *   by "some text" when the prompt contains it, otherwise the prompt
     *   followed by the text on a new line. "/name" alone → the prompt.
     * - Unknown names, disabled commands, and messages not starting with
     *   "/" pass through unchanged — the chat never breaks on a slash.
     * The raw message is what gets stored in the chat history; only the
     * text sent to the model is expanded.
     */
    suspend fun expandCommand(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("/")) return trimmed
        val afterSlash = trimmed.removePrefix("/")
        val name = afterSlash.substringBefore(" ").lowercase()
        if (name.isEmpty()) return trimmed
        val args = afterSlash.substringAfter(" ", "").trim()
        val command = prefs.customCommands.first()
            .firstOrNull { it.enabled && it.name == name } ?: return trimmed
        return if ("{input}" in command.prompt) {
            command.prompt.replace("{input}", args)
        } else if (args.isNotEmpty()) {
            "${command.prompt}\n\n$args"
        } else {
            command.prompt
        }
    }

    fun toggleFavourite(providerId: String, modelId: String) {
        val key = "$providerId/$modelId"
        viewModelScope.launch {
            val existing = db.favouriteDao().get(key)
            if (existing == null) {
                db.favouriteDao().star(FavouriteModel(key, providerId, modelId, System.currentTimeMillis()))
            } else {
                db.favouriteDao().unstar(key)
            }
        }
    }

    // ---- Model fetching (GET {baseUrl}/models, cached in Room) ----

    /** Load the cached model list for a provider into picker state. */
    fun loadCachedModels(provider: Provider) {
        viewModelScope.launch {
            val cached = providerRepository.cachedModels(provider.id)
            if (cached.isNotEmpty()) {
                fetchedModels = fetchedModels + (provider.id to cached)
            }
        }
    }

    /** Refresh from the network; on failure the cached list stays visible. */
    fun refreshModels(provider: Provider) {
        viewModelScope.launch {
            fetchingProviderId = provider.id
            try {
                val result = providerRepository.refreshModels(
                    provider,
                    keys.getKey(provider.id),
                    azureResourceName = if (provider.kind == "azure") prefs.azureResourceName(provider.id) else null
                )
                if (result.models.isNotEmpty()) {
                    fetchedModels = fetchedModels + (provider.id to result.models)
                }
                fetchErrors = if (result.error != null) {
                    fetchErrors + (provider.id to result.error)
                } else {
                    fetchErrors - provider.id
                }
            } finally {
                fetchingProviderId = null
            }
        }
    }

    /** Use a model id typed by hand (when fetch is unavailable). */
    fun useManualModel(providerId: String, modelId: String): Boolean {
        val trimmed = modelId.trim()
        if (trimmed.isEmpty()) return false
        return useModel(providerId, trimmed)
    }

    // ---- On-device .task model file (Phase 6, Step 5) ----

    /**
     * Called with the URI the system file picker returned for a .task
     * model file. Only ".task" files are accepted — a GGUF or
     * safetensors file is not a MediaPipe model and would fail at load.
     * Takes a persistable READ grant (the file is only ever read — the
     * engine copies it into app-private storage), stores the URI +
     * display name, drops any loaded engine so the next turn loads the
     * new file, and makes it the default model (and the open session's
     * model, when a chat is open) via [useModel]. Returns an error
     * message, or null on success.
     */
    fun setLocalModelFile(context: Context, uri: Uri): String? {
        val name = displayNameOf(context, uri) ?: uri.lastPathSegment.orEmpty()
        if (!name.endsWith(".task", ignoreCase = true)) {
            return "That is not a .task model file — pick the .task file you downloaded (for example gemma-3-1b-it-int4.task)."
        }
        return try {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            viewModelScope.launch {
                prefs.setLocalModel(uri.toString(), name)
                localLlm.onModelChanged()
                useModel(LocalLlmClient.LOCAL_PROVIDER_ID, name)
            }
            null
        } catch (_: SecurityException) {
            "Could not keep access to that file — pick it again."
        }
    }

    /** Forget the picked .task file; the cached copy and engine are dropped too. */
    fun removeLocalModelFile() {
        viewModelScope.launch {
            prefs.clearLocalModel()
            localLlm.onModelChanged()
        }
    }

    /** Display name of a SAF document URI, or null when it cannot be read. */
    private fun displayNameOf(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---- Attached images (Phase 6, Step 2) ----

    /**
     * Images staged for the next message: file names in
     * getFilesDir()/images/, already downscaled + JPEG re-encoded by
     * [ImageStore]. At most [ImageStore.MAX_IMAGES_PER_MESSAGE]; the
     * chat screen shows them as thumbnails above the input. They become
     * part of the message on send; tapping × deletes the staged file.
     * A staged image the user never sends stays app-private until it is
     * sent or removed — it is never sent on its own.
     */
    var pendingImages by mutableStateOf<List<String>>(emptyList())
        private set

    /** One-line note from the last image staging (too many, unreadable). */
    var imageNote by mutableStateOf<String?>(null)
        private set

    fun dismissImageNote() {
        imageNote = null
    }

    /**
     * Stage images picked with the system picker (SAF image picker).
     * Each one is downscaled + JPEG re-encoded into app-private storage
     * before it is shown — a raw phone photo is far too large to base64
     * into a request. Images past the 3-per-message cap are skipped
     * with a plain note; an unreadable image is skipped the same way.
     * Nothing here sends anything.
     */
    fun addPendingImages(context: Context, uris: List<Uri>) {
        viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                uris.map { uri -> ImageStore.saveImage(context, uri) }
            }
            val saved = results.filterNotNull()
            val failed = results.size - saved.size
            val room = ImageStore.MAX_IMAGES_PER_MESSAGE - pendingImages.size
            val kept = saved.take(room.coerceAtLeast(0))
            val overCap = saved.size - kept.size
            if (kept.isNotEmpty()) pendingImages = pendingImages + kept
            imageNote = when {
                kept.isEmpty() && failed > 0 ->
                    "Could not read ${if (failed == 1) "that image" else "those $failed images"} — nothing was attached."
                overCap > 0 ->
                    "Only ${ImageStore.MAX_IMAGES_PER_MESSAGE} images per message — " +
                        "${if (overCap == 1) "1 image was" else "$overCap images were"} skipped."
                failed > 0 ->
                    "${if (failed == 1) "1 image" else "$failed images"} could not be read and " +
                        "${if (failed == 1) "was" else "were"} skipped."
                else -> null
            }
        }
    }

    /** Remove one staged image (× on its thumbnail): the file is deleted too. */
    fun removePendingImage(fileName: String) {
        pendingImages = pendingImages - fileName
        viewModelScope.launch(Dispatchers.IO) { ImageStore.delete(app, fileName) }
    }

    /**
     * The images of one stored message, as base64 for the request body.
     * A file that is missing (deleted, or a message restored from a
     * backup — backups never carry images) is skipped silently, so an
     * old message never breaks a turn; the text still goes out.
     */
    private suspend fun loadImagesForMessage(message: Message): List<LlmImage> =
        loadImagesForNames(decodeImageNames(message.imagesJson))

    /** The same, from file names directly (the just-staged message). */
    private suspend fun loadImagesForNames(fileNames: List<String>): List<LlmImage> =
        withContext(Dispatchers.IO) {
            fileNames.mapNotNull { name ->
                ImageStore.loadBase64(app, name)?.let { LlmImage(it) }
            }
        }

    // ---- Attached files: PDF + text (Phase 7, Step B3) ----

    /**
     * Files staged for the next message: records in
     * getFilesDir()/attachments/, copied by [AttachmentStore] at pick
     * time. At most [AttachmentStore.MAX_ATTACHMENTS_PER_MESSAGE];
     * the chat screen shows them as chips above the input. They
     * become part of the message on send; tapping × deletes the
     * staged file. A staged file the user never sends stays
     * app-private until it is sent or removed — it is never sent on
     * its own. Images are NOT staged here: they go through the image
     * button and [ImageStore] (see [pendingImages]).
     */
    var pendingAttachments by mutableStateOf<List<AttachmentRecord>>(emptyList())
        private set

    /** One-line note from the last file staging (too many, too large, image). */
    var attachmentNote by mutableStateOf<String?>(null)
        private set

    fun dismissAttachmentNote() {
        attachmentNote = null
    }

    /**
     * Stage files picked with the system picker. Each one is copied
     * into app-private storage before it is shown — nothing is read
     * from the picked URI again later. Files past the per-message cap
     * are skipped with a plain note; an unreadable or over-25 MB file
     * is skipped the same way. An image picked here is skipped too
     * (its file is deleted again at once) with a note pointing at the
     * image button — images must go through [ImageStore] so they are
     * downscaled before sending. Nothing here sends anything.
     */
    fun addPendingAttachments(context: Context, uris: List<Uri>) {
        viewModelScope.launch {
            val imported = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching { AttachmentStore.import(context, uri) }.getOrNull()
                }
            }
            val failed = uris.size - imported.size
            val (images, files) = imported.partition { it.kind == "image" }
            if (images.isNotEmpty()) {
                withContext(Dispatchers.IO) { AttachmentStore.deleteAll(context, images) }
            }
            val room = AttachmentStore.MAX_ATTACHMENTS_PER_MESSAGE - pendingAttachments.size
            val kept = files.take(room.coerceAtLeast(0))
            val overCap = files.size - kept.size
            if (kept.isNotEmpty()) pendingAttachments = pendingAttachments + kept
            attachmentNote = when {
                kept.isEmpty() && failed > 0 ->
                    "Could not read ${if (failed == 1) "that file" else "those $failed files"} — nothing was attached."
                images.isNotEmpty() ->
                    "Images go through the image button, not the file button — " +
                        "${if (images.size == 1) "that image was" else "${images.size} images were"} skipped."
                overCap > 0 ->
                    "Only ${AttachmentStore.MAX_ATTACHMENTS_PER_MESSAGE} files per message — " +
                        "${if (overCap == 1) "1 file was" else "$overCap files were"} skipped."
                failed > 0 ->
                    "${if (failed == 1) "1 file" else "$failed files"} could not be read (over 25 MB?) and " +
                        "${if (failed == 1) "was" else "were"} skipped."
                else -> null
            }
        }
    }

    /** Remove one staged file (× on its chip): the file is deleted too. */
    fun removePendingAttachment(record: AttachmentRecord) {
        pendingAttachments = pendingAttachments - record
        viewModelScope.launch(Dispatchers.IO) { AttachmentStore.delete(app, record) }
    }

    /**
     * One stored message's attachments, as the model sees them.
     * Text files are inlined; PDFs become binary parts only when
     * [binary] is true (the 2-most-recent rule — see
     * [AgentRunner.runBuild]'s `resendBinariesFor`).
     */
    private suspend fun loadAttachmentsForMessage(
        message: Message,
        binary: Boolean
    ): AttachmentMaterial = withContext(Dispatchers.IO) {
        AttachmentStore.materialize(app, decodeAttachments(message.attachmentsJson), binary)
    }

    // ---- Chat ----

    private val messageFlows = mutableMapOf<String, StateFlow<List<Message>>>()

    fun messages(sessionId: String): StateFlow<List<Message>> =
        messageFlows.getOrPut(sessionId) {
            db.messageDao().observeMessages(sessionId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        }

    fun tokenEstimate(messages: List<Message>): Int = TokenCounter.estimateTokens(messages)

    fun stopRun() {
        // Kill a running shell command too (Phase 6, Step 1): cancelling
        // the job alone would leave a quiet `run` command blocked in its
        // output read. Destroying the process closes its stream, which
        // lets the cancelled turn unwind.
        activeRunTool?.stop()
        // Background processes (Phase 7, Step B3): Stop kills them too
        // — a dev server started by the agent must not keep running
        // after the user stopped the turn.
        BackgroundProcs.stopAll()
        runJob?.cancel()
        isRunning = false
        // A stopped turn is an interrupted turn (Phase 7, Step B2): the
        // partial text stays as shown, and Continue can pick the turn
        // up where it stopped.
        interruptedTurn = true
    }

    fun dismissPlan() {
        currentPlan = null
    }

    /** Draft text for the chat input when the user taps "Edit plan". */
    var planEditDraft by mutableStateOf<String?>(null)
        private set

    /** "Edit plan": close the card and prefill the goal in the chat box
     *  so the user can rephrase it and send a new plan request. */
    fun editPlan(plan: Plan) {
        planEditDraft = plan.goal
        currentPlan = null
    }

    fun consumeEditDraft() {
        planEditDraft = null
    }

    /** Session currently open in the chat screen, if any. */
    var activeSessionId by mutableStateOf<String?>(null)
        private set

    fun setActiveSession(id: String?) {
        activeSessionId = id
    }

    /**
     * Use a model: sets it as the default for new sessions, and — when a
     * chat session is open — switches that session to it too. Returns true
     * when a session was switched.
     */
    fun useModel(providerId: String, modelId: String): Boolean {
        setDefaultModel(providerId, modelId)
        val sessionId = activeSessionId ?: return false
        viewModelScope.launch {
            db.sessionDao().setModel(sessionId, providerId, modelId)
            db.sessionDao().touch(sessionId, System.currentTimeMillis())
        }
        return true
    }

    /** Approve switches the session to Build mode. The user then sends the
     *  next message to start building — the runner never writes on its own. */
    fun approvePlanAndSwitchToBuild(session: Session) {
        currentPlan = null
        viewModelScope.launch {
            db.sessionDao().setMode(session.id, "build")
            db.messageDao().insert(
                Message(
                    sessionId = session.id,
                    role = "tool",
                    text = "Plan approved — Build mode is on. Send a message to start building, one file at a time.",
                    createdAt = System.currentTimeMillis()
                )
            )
            db.sessionDao().touch(session.id, System.currentTimeMillis())
        }
    }

    /**
     * Editor save (Phase 3, Step 3): turn the editor's text into the SAME
     * [PlannedFile] proposal the agent's writes use, so the file is only
     * written when the user taps Approve on the diff card — there is no
     * second write path. Returns an error message, or null when the
     * proposal is ready and waiting for approval.
     *
     * - Plan mode refuses: the editor never writes in Plan mode.
     * - A proposal already waiting (agent's or editor's) refuses: one
     *   approval at a time.
     * - "edit" carries the whole old file as [PlannedFile.oldSnippet]
     *   and the whole new text as [PlannedFile.newSnippet], so
     *   [AgentRunner.applyFileProposal] replaces the file only when the
     *   disk still holds that old text — an agent change made meanwhile
     *   fails the write instead of being silently overwritten.
     * - An empty / missing file on disk becomes "create" (full overwrite),
     *   because an "edit" with an empty old snippet would corrupt the
     *   file (`String.replace("", …)` inserts at every position).
     */
    fun proposeEditorSave(session: Session, path: String, newContent: String, context: Context): String? {
        if (session.agentMode == "plan") {
            return "Plan mode is read-only — switch to Build in Chat to save changes."
        }
        if (pendingProposal != null) {
            return "Another file change is waiting for approval — approve or reject it first."
        }
        val project = SafProject(context, Uri.parse(session.projectUri))
        val old = project.readText(path)
        pendingProposal = if (old.isNullOrEmpty()) {
            PlannedFile(path = path, action = "create", newContent = newContent)
        } else {
            PlannedFile(path = path, action = "edit", oldSnippet = old, newSnippet = newContent)
        }
        pendingProposalOld = old
        return null
    }

    /**
     * Approve the pending proposal and write the file (agent's,
     * editor's or template's — all three are the same [PlannedFile]).
     *
     * [resumeAgent] is true for agent proposals: after a verified write
     * the Build loop resumes with a continuation message. Editor saves
     * pass false — there is no agent turn to resume. [onApplied] reports
     * whether the write succeeded, so the editor can clear its dirty
     * marker (or keep it and show the error).
     *
     * Template proposals never resume the agent: there is no turn to
     * resume — the user only approved a starter file. Instead, an
     * approved template file queues the template's NEXT file as the
     * next proposal (still through the diff card), until the template
     * is done. A failed template write clears the rest of the queue.
     */
    fun approveProposal(
        session: Session,
        context: Context,
        resumeAgent: Boolean = true,
        onApplied: ((Boolean) -> Unit)? = null
    ) {
        val proposal = pendingProposal ?: return
        val wasTemplate = pendingProposalIsTemplate
        pendingProposal = null
        pendingProposalOld = null
        val project = SafProject(context, Uri.parse(session.projectUri))
        val runner = AgentRunner(llm, v1Tools(project, context))
        viewModelScope.launch {
            val event = runner.applyFileProposal(proposal, project)
            val text = when (event) {
                is AgentEvent.Verified -> event.message
                is AgentEvent.Error -> event.message
                else -> "Done."
            }
            db.messageDao().insert(
                Message(sessionId = session.id, role = "tool", text = text, createdAt = System.currentTimeMillis())
            )
            db.sessionDao().touch(session.id, System.currentTimeMillis())
            // The loop paused for approval — resume it so the agent keeps
            // working toward the goal instead of stopping after one file.
            // Editor saves (resumeAgent = false) have no turn to resume.
            if (event is AgentEvent.Verified) {
                onApplied?.invoke(true)
                if (wasTemplate) {
                    // Next template file, if any — still a diff-card
                    // proposal, never a direct write. When the template
                    // is done, clear the template state.
                    val next = pendingTemplateFiles.firstOrNull()
                    if (next != null) {
                        pendingTemplateFiles = pendingTemplateFiles.drop(1)
                        queueTemplateEntry(next)
                    } else {
                        pendingTemplateFiles = emptyList()
                        pendingProposalIsTemplate = false
                        pendingTemplateSessionId = null
                    }
                } else if (resumeAgent) {
                    resumeBuild(
                        session,
                        context,
                        "User approved and applied the change to ${proposal.path}. " +
                            "Continue working toward the goal; use tools as needed and finish with a short summary."
                    )
                }
            } else {
                onApplied?.invoke(false)
                // A failed write ends the template: a stale queue must
                // never resurface as a proposal for a later session.
                if (wasTemplate) {
                    pendingTemplateFiles = emptyList()
                    pendingProposalIsTemplate = false
                    pendingTemplateSessionId = null
                }
            }
        }
    }

    fun rejectProposal(session: Session) {
        val proposal = pendingProposal ?: return
        val wasTemplate = pendingProposalIsTemplate
        pendingProposal = null
        pendingProposalOld = null
        // Rejecting a template file ends the template — the rest of its
        // files are skipped, never proposed on their own.
        pendingTemplateFiles = emptyList()
        pendingProposalIsTemplate = false
        pendingTemplateSessionId = null
        viewModelScope.launch {
            db.messageDao().insert(
                Message(
                    sessionId = session.id,
                    role = "tool",
                    text = if (wasTemplate) {
                        "Template file ${proposal.path} rejected — nothing was written, " +
                            "and the rest of the template was skipped."
                    } else {
                        "Change to ${proposal.path} rejected — nothing was written."
                    },
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * Send a chat message (with any staged images). Returns false when
     * nothing was sent — empty text, a turn already running, no key, or
     * (Phase 6, Step 2) images attached to a model that cannot see them
     * — so the chat screen keeps the input text instead of clearing it.
     * The vision refusal happens here, before anything is stored or
     * sent; the runner checks again per attempt, so a fallback switch
     * to a text-only model is refused there too.
     *
     * [context] is required: file tools run against the picked SAF folder.
     */
    fun sendMessage(session: Session, text: String, context: Context): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || isRunning) return false
        val provider = provider(session.providerId)
        val apiKey = keys.getKey(session.providerId)
        if (provider == null) {
            lastError = "Unknown provider ${session.providerId}. Pick a model in Providers."
            return false
        }
        if (pendingImages.isNotEmpty() && !supportsVision(provider, session.modelId)) {
            lastError = visionRefusalMessage(provider, session.modelId)
            return false
        }
        // PDFs (Phase 7, Step B3): only OpenAI / Anthropic-kind models
        // receive a PDF as a binary part. A PDF for a local or Bedrock
        // model is refused here, before anything is stored or sent —
        // it is never silently dropped. Text files go to any model.
        if (pendingAttachments.any { it.kind == "pdf" } && !supportsPdfUpload(provider)) {
            lastError = "This model cannot receive PDFs — pick an OpenAI, Anthropic or Azure model, " +
                "or remove the PDF before sending."
            return false
        }
        missingCredentialMessage(provider, apiKey)?.let { message ->
            lastError = message
            return false
        }
        // The staged images become this message's images; clear the
        // staging list now so a second send cannot re-attach them.
        val attached = pendingImages.toList()
        pendingImages = emptyList()
        // The staged files become this message's attachments, same rule.
        val attachedFiles = pendingAttachments.toList()
        pendingAttachments = emptyList()
        isRunning = true
        lastError = null
        fallbackNotice = null
        interruptedTurn = false
        runJob = viewModelScope.launch {
            try {
                // Insert the user message first, in this coroutine, so the
                // history read below always includes it.
                db.messageDao().insert(
                    Message(
                        sessionId = session.id,
                        role = "user",
                        text = trimmed,
                        imagesJson = if (attached.isEmpty()) null else encodeImageNames(attached),
                        attachmentsJson = if (attachedFiles.isEmpty()) null else encodeAttachments(attachedFiles),
                        createdAt = System.currentTimeMillis()
                    )
                )
                db.sessionDao().touch(session.id, System.currentTimeMillis())
                val history = db.messageDao().getMessages(session.id)
                // Slash command (Phase 3, Step 1): the stored message stays
                // as the user typed it; only the text sent to the model is
                // expanded. Works in Plan and Build alike.
                val expanded = expandCommand(trimmed)
                // Images for this turn (Phase 6, Step 2): the just-saved
                // files as base64; history messages' images are resolved
                // inside runAgentTurn through imagesFor.
                val userImages = loadImagesForNames(attached)
                // Files for this turn (Phase 7, Step B3): text files are
                // inlined into the text sent to the model (never stored
                // in the message — the stored text stays as typed);
                // PDFs go as binary parts. History messages' files are
                // resolved inside runAgentTurn through attachmentsFor.
                val userMaterial = withContext(Dispatchers.IO) {
                    AttachmentStore.materialize(app, attachedFiles, binary = true)
                }
                runAgentTurn(
                    session, provider, apiKey, history, expanded, context,
                    userImages, userMaterial.pdfs, userMaterial.extraText
                )
                // A turn that ended with an error left the work half
                // done (Phase 7, Step B2) — offer Continue in the chat.
                if (lastError != null) interruptedTurn = true
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    lastError = e.message ?: "Agent run failed."
                    interruptedTurn = true
                }
            } finally {
                isRunning = false
            }
        }
        return true
    }

    /**
     * Continue an interrupted turn (Phase 7, Step B2 — AgentMT's
     * `resume` / CONTINUE_TEXT, adapted). After an error, a limit or a
     * Stop, the turn resumes from the last finished step instead of
     * resending the last message from scratch: the history already
     * holds every finished tool result and the partial reply, and the
     * model is sent [AgentRunner.CONTINUE_TEXT] as the next user
     * message. The CONTINUE_TEXT itself is NOT stored as a user
     * message — the chat shows no extra bubble (same pattern as the
     * post-approval resume in [resumeBuild], whose continuation is
     * likewise only in the text sent to the model). The mid-stream
     * partial text stays as shown and is never replayed (Step B1's
     * rule). No-ops while a turn runs or when no credential is set.
     *
     * Plan mode is the one exception: [AgentRunner.runPlan] ignores
     * the history and only sends the goal, so continuing a plan with
     * bare CONTINUE_TEXT would lose the goal — the original goal (the
     * last user message) is appended to the continue text for plans.
     * A plan has no tool steps to resume, so this is a re-ask of the
     * same goal with the "continue" instruction, not a fresh turn.
     */
    fun continueTurn(session: Session, context: Context) {
        if (isRunning) return
        val provider = provider(session.providerId) ?: run {
            lastError = "Unknown provider ${session.providerId}. Pick a model in Providers."
            return
        }
        val apiKey = keys.getKey(session.providerId)
        missingCredentialMessage(provider, apiKey)?.let { message ->
            lastError = message
            return
        }
        interruptedTurn = false
        isRunning = true
        lastError = null
        fallbackNotice = null
        runJob = viewModelScope.launch {
            try {
                val history = db.messageDao().getMessages(session.id)
                val message = if (session.agentMode == "plan") {
                    val goal = history.lastOrNull { it.role == "user" }?.text.orEmpty()
                    AgentRunner.CONTINUE_TEXT +
                        if (goal.isNotBlank()) "\n\nOriginal goal: $goal" else ""
                } else {
                    AgentRunner.CONTINUE_TEXT
                }
                runAgentTurn(session, provider, apiKey, history, message, context)
                if (lastError != null) interruptedTurn = true
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    lastError = e.message ?: "Agent run failed."
                    interruptedTurn = true
                }
            } finally {
                isRunning = false
            }
        }
    }

    /**
     * One agent turn (Plan or Build). Shared by [sendMessage] and the
     * post-approval resume in [resumeBuild].
     */
    /**
     * Null when this provider can be called; otherwise the plain message
     * to show instead of starting a turn. Bedrock (Phase 4) has no API
     * key — it needs the AWS credential set; every other remote provider
     * needs at least one key; local providers need nothing.
     */
    private fun missingCredentialMessage(provider: Provider, apiKey: String?): String? {
        if (provider.local) return null
        if (provider.kind == "bedrock") {
            return if (keys.hasAwsCredential()) null
            else "Add your AWS access key id, secret access key and region first (Providers → AWS Bedrock)."
        }
        return if (apiKey.isNullOrBlank()) "Add a provider API key first (Providers → ${provider.name})." else null
    }

    private suspend fun runAgentTurn(
        session: Session,
        provider: Provider,
        apiKey: String?,
        history: List<Message>,
        userMessage: String,
        context: Context,
        /** Images attached to this message (Phase 6, Step 2). */
        userImages: List<LlmImage> = emptyList(),
        /** PDFs attached to this message (Phase 7, Step B3). */
        userPdfs: List<LlmPdf> = emptyList(),
        /**
         * Text attachments of this message, already materialized
         * (Phase 7, Step B3): appended to the text sent to the model,
         * never stored in the message.
         */
        userAttachmentText: String = ""
    ) {
        // Azure (Phase 4): without a resource name there is no URL to
        // call — stop here with a plain message instead of sending a
        // request to a placeholder host. (LlmClient throws too, as a
        // second layer, if any other caller forgets this check.)
        if (provider.kind == "azure" && prefs.azureResourceName(provider.id).isNullOrBlank()) {
            lastError = "Set your Azure resource name first (Providers → Azure OpenAI)."
            return
        }
        // On-device model (Phase 6, Step 5): without a .task file there
        // is no engine to call — stop here with a plain message, like
        // the Azure guard above. (LocalLlmClient throws too, as a
        // second layer, if any other caller forgets this check.)
        if (provider.kind == "local" && !localLlm.hasModelFile()) {
            lastError = "Pick a .task model file first (Providers → On-device model (.task))."
            return
        }
        // Runs (Phase 7, Step B3): one row per turn. Created here, so
        // a turn that fails, pauses for approval or is stopped still
        // leaves a row; the guard refusals above create none.
        val runId = UUID.randomUUID().toString()
        val tracker = RunTracker(runId)
        db.runDao().insert(
            RunEntity(
                id = runId,
                sessionId = session.id,
                title = runTitle(userMessage, history),
                status = "running",
                startedAt = System.currentTimeMillis(),
                model = "${provider.id}/${session.modelId}"
            )
        )
        // Only the 2 most recent user messages resend their binaries
        // (Phase 7, Step B3 — AgentMT's rule): the ids below are the
        // history messages whose images / PDFs go out this turn.
        val binaryMessageIds = history.filter { msg ->
            msg.role == "user" && (
                decodeImageNames(msg.imagesJson).isNotEmpty() ||
                    decodeAttachments(msg.attachmentsJson).any { it.kind == "image" || it.kind == "pdf" }
                )
        }.takeLast(2).map { it.id }.toSet()
        // The text sent to the model is the user's text plus this
        // message's inlined text attachments (Phase 7, Step B3). The
        // stored message keeps only the text as typed.
        val fullUserMessage = userMessage + userAttachmentText
        // The on-device engine's whole context window is 4,096 tokens
        // (LocalLlmClient.CONTEXT_TOKENS) — compaction thresholds and
        // the prune step are measured against that, not the 128k remote
        // default, so a long local session compacts early instead of
        // overflowing the engine.
        //
        // Remote models (Phase 7, Step B2): the window is capped at the
        // SMALLEST known window among the candidate models for the turn
        // — the session's model plus the fallback chain (those are the
        // models a turn may actually switch to). A 64k model in the
        // chain pulls the 60% prune / 75% summarise thresholds down to
        // that window; when no candidate's window is known the fixed
        // 128k default is used, exactly as before.
        val contextTokens = if (provider.kind == "local") LocalLlmClient.CONTEXT_TOKENS
        else Compaction.effectiveContextTokens(session.modelId, prefs.fallbackChain.first())
        val project = SafProject(context, Uri.parse(session.projectUri))
        // MCP tools for this turn: fetch any missing enabled-server lists
        // first, then hand the built-ins + MCP tools + skill tool to the
        // same runner. The enabled skills are also listed in the Build
        // system prompt, so the model knows what it can call. The
        // built-ins include the `run` shell tool (Phase 6, Step 1).
        ensureMcpToolsLoaded()
        val tools = v1Tools(project, context, useTermux = { termuxEnabled.value }) + mcpTools() + skillTools()
        activeRunTool = tools.filterIsInstance<RunTool>().firstOrNull()
        // Tool decisions (Phase 7, Step B2): read once per turn, like the
        // routing mode — the stored per-tool rule wins, then the first
        // matching glob rule, then the approval mode (see
        // PermissionEngine). The runner gets the target-aware function;
        // without it the plain per-tool rule would be the only input.
        val approvalModeEnum = prefs.approvalModeEnum()
        val globRules = prefs.permissionRules.first()
        val storedRules = prefs.storedToolRules()
        val toolDecision: (String, Map<String, String>) -> String = { name, args ->
            when (
                PermissionEngine.decide(
                    name, toolTarget(name, args), approvalModeEnum,
                    storedRules[name], globRules
                ).first
            ) {
                PermissionDecision.ALLOW -> "allow"
                PermissionDecision.DENY -> "deny"
                PermissionDecision.ASK -> "ask"
            }
        }
        val runner = AgentRunner(
            llm, tools, fallbackEngine,
            // Router (Phase 7, Step B1): per-kind cooldowns, health
            // ledger, pinned vs fallback mode (read fresh per turn).
            router = modelRouter,
            routingMode = if (prefs.routingMode.first() == "pinned") RoutingMode.PINNED else RoutingMode.FALLBACK,
            // Reasoning effort (Phase 3, Step 2): looked up per attempt
            // inside the runner, for the model actually being called.
            effortFor = { providerId, modelId -> prefs.reasoningEffort(providerId, modelId) },
            // Azure resource name (Phase 4): same per-attempt lookup.
            azureResourceFor = { providerId -> prefs.azureResourceName(providerId) },
            // AWS credentials (Phase 4): same per-attempt lookup.
            awsCredentialsFor = { keys.awsCredentials() },
            // Target-aware tool decisions (Phase 7, Step B2).
            toolDecision = toolDecision,
            // Harder compaction on "context too long" (Phase 7, Step B2):
            // re-summarise with a 5k kept tail and let the runner retry
            // the step once with the new summary.
            onContextTooLong = { compactHarder(session, provider, apiKey, history) }
        )
        val toolContext = ToolContext(
            readOnly = session.agentMode == "plan",
            projectUri = Uri.parse(session.projectUri),
            folderName = session.folderName
        )
        val rules = prefs.toolRules.first()
        val toolRule: (String) -> String = { name -> rules[name] ?: "ask" }
        val assistantText = StringBuilder()
        // Compaction (Build only): summarise first when the estimate is
        // over 75% of the context window; the summary is sent, not the
        // full old history. Nothing is dropped from Room.
        val summary = if (session.agentMode == "build") {
            maybeCompact(session, provider, apiKey, history, contextTokens)
        } else {
            null
        }
        val flow = if (session.agentMode == "plan") {
            runner.runPlan(
                provider, session.modelId, apiKey, history, fullUserMessage, project,
                session.folderName, userImages = userImages, userPdfs = userPdfs
            )
        } else {
            runner.runBuild(
                provider, session.modelId, apiKey, history, fullUserMessage, project,
                toolContext, toolRule, summary, contextTokens = contextTokens, skills = enabledSkills(),
                // Images (Phase 6, Step 2): this message's images, plus
                // every history message's images, resolved fresh per
                // turn so the model re-sees old screenshots with the
                // history. Remote turns pass none (no image input).
                userImages = userImages,
                // PDFs + text files (Phase 7, Step B3): this message's
                // PDFs, plus history messages' files through
                // attachmentsFor — binaries only for the 2 most recent
                // user messages (see binaryMessageIds above).
                userPdfs = userPdfs,
                imagesFor = { msg -> loadImagesForMessage(msg) },
                attachmentsFor = { msg, binary -> loadAttachmentsForMessage(msg, binary) },
                resendBinariesFor = { msg -> msg.id in binaryMessageIds }
            )
        }
        val turnFinishedCleanly = try {
            collectRunEvents(session, flow, assistantText, tracker)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Stopped (Phase 7, Step B3): the run is cancelled, not
            // left "running" — the orphan rule on next launch is only
            // the backstop, for a process killed outright.
            finishRun(runId, tracker, "cancelled")
            throw e
        } finally {
            // Clear even when the turn is stopped mid-command, so a later
            // Stop can never kill a command from an old turn.
            activeRunTool = null
        }
        refreshExhausted()
        if (assistantText.isNotBlank()) {
            db.messageDao().insert(
                Message(sessionId = session.id, role = "assistant", text = assistantText.toString(), createdAt = System.currentTimeMillis())
            )
        }
        db.sessionDao().touch(session.id, System.currentTimeMillis())
        // Voice output (Phase 2, Step 5): only when the toggle is ON and
        // the turn finished cleanly. A turn that ended in an error, or
        // paused for a file approval, stays silent.
        if (turnFinishedCleanly && voiceOutputEnabled.value) {
            if (assistantText.isNotBlank()) voiceSpeaker.speakReply(assistantText.toString())
            if (session.agentMode == "build") voiceSpeaker.speakAnnouncement()
        }
        // Close the run (Phase 7, Step B3): "waiting" when the turn
        // paused for a file approval, "failed" on an error, otherwise
        // "succeeded" when it finished cleanly.
        finishRun(
            runId, tracker,
            when {
                tracker.error != null -> "failed"
                pendingProposal != null -> "waiting"
                turnFinishedCleanly -> "succeeded"
                else -> "failed"
            }
        )
    }

    /** Write a run's final state; never throws (a failed write must not fail the turn). */
    private suspend fun finishRun(runId: String, tracker: RunTracker, status: String) {
        runCatching {
            db.runDao().finish(
                runId, status, System.currentTimeMillis(),
                tracker.steps, tracker.toolCalls,
                tracker.changedFiles.joinToString("\n"),
                tracker.error, tracker.inputTokens, tracker.outputTokens,
                tracker.routeLines.joinToString("\n")
            )
        }
    }

    /** Continue a Build turn that paused for a file-approval. */
    private fun resumeBuild(session: Session, context: Context, continuation: String) {
        if (isRunning) return
        val provider = provider(session.providerId) ?: return
        val apiKey = keys.getKey(session.providerId)
        if (missingCredentialMessage(provider, apiKey) != null) return
        isRunning = true
        runJob = viewModelScope.launch {
            try {
                val history = db.messageDao().getMessages(session.id)
                runAgentTurn(session, provider, apiKey, history, continuation, context)
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    lastError = e.message ?: "Agent run failed."
                }
            } finally {
                isRunning = false
            }
        }
    }

    /**
     * Persist tool results / verification messages and track plan/proposals.
     * Returns true only when the flow ended with [AgentEvent.Done] and no
     * error and no file proposal: a turn that failed, or paused for a
     * diff-card approval, is not a finished turn (voice stays silent).
     */
    private suspend fun collectRunEvents(
        session: Session,
        flow: Flow<AgentEvent>,
        assistantText: StringBuilder,
        /** Counts for the run row (Phase 7, Step B3); null when the caller records no run. */
        tracker: RunTracker? = null
    ): Boolean {
        var hadError = false
        var hadProposal = false
        var finishedCleanly = false
        flow.collect { event ->
            when (event) {
                is AgentEvent.TextChunk -> assistantText.append(event.text)
                is AgentEvent.PlanReady -> currentPlan = event.plan
                is AgentEvent.FileProposal -> {
                    hadProposal = true
                    tracker?.changedFiles?.add(event.file.path)
                    if (pendingProposalIsTemplate) {
                        // The agent proposed a change while a template
                        // file was waiting for approval. There is only
                        // one proposal slot, so the agent's proposal
                        // takes it and the template file is set aside —
                        // never written, never silently kept queued.
                        // The session says so plainly.
                        val setAside = pendingProposal?.path
                        pendingTemplateFiles = emptyList()
                        pendingProposalIsTemplate = false
                        pendingTemplateSessionId = null
                        if (setAside != null) {
                            db.messageDao().insert(
                                Message(
                                    sessionId = session.id,
                                    role = "tool",
                                    text = "Template file “$setAside” was waiting for approval, " +
                                        "but the agent proposed a change first — the template " +
                                        "file was set aside and nothing was written. Ask the " +
                                        "agent to create it, or start a new session with the template.",
                                    createdAt = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                    pendingProposal = event.file
                    pendingProposalOld = event.oldContent
                }
                is AgentEvent.ToolRan -> {
                    tracker?.toolCalls = (tracker?.toolCalls ?: 0) + 1
                    db.messageDao().insert(
                        Message(
                            sessionId = session.id,
                            role = "tool",
                            text = "[${event.tool}] ${event.result}",
                            createdAt = System.currentTimeMillis()
                        )
                    )
                }
                is AgentEvent.Error -> {
                    hadError = true
                    lastError = event.message
                    if (tracker?.error == null) tracker?.error = event.message
                }
                is AgentEvent.FallbackSwitched -> {
                    val toName = provider(event.toProviderId)?.name ?: event.toProviderId
                    // The failure reason (Phase 7, Step B1): "rate
                    // limited (429) on …", "out of credit on …" — the
                    // old quota/rate wording is the fallback when the
                    // event carries no classification.
                    val reason = event.failure?.let { f ->
                        "${f.label}${f.code?.let { " ($it)" } ?: ""} on ${event.fromProviderId} / ${event.fromModelId}"
                    } ?: when (event.kind) {
                        LimitKind.QUOTA -> "quota reached on ${event.fromProviderId} / ${event.fromModelId}"
                        LimitKind.RATE -> "rate limit on ${event.fromProviderId} / ${event.fromModelId}"
                        null -> "failed on ${event.fromProviderId} / ${event.fromModelId}"
                    }
                    val text = "Switched to $toName / ${event.toModelId} — $reason."
                    tracker?.routeLines?.add(
                        "${event.fromProviderId}/${event.fromModelId} → " +
                            "${event.toProviderId}/${event.toModelId} ($reason)"
                    )
                    fallbackNotice = text
                    db.messageDao().insert(
                        Message(sessionId = session.id, role = "tool", text = text, createdAt = System.currentTimeMillis())
                    )
                    refreshExhausted()
                }
                is AgentEvent.Verified -> {
                    db.messageDao().insert(
                        Message(sessionId = session.id, role = "tool", text = event.message, createdAt = System.currentTimeMillis())
                    )
                }
                is AgentEvent.Notice -> {
                    // An informational note (Phase 7, Step B2 — e.g. a
                    // "context too long" retry): kept as a tool message
                    // like the fallback notice, and it does not end the
                    // turn or count as an error.
                    db.messageDao().insert(
                        Message(sessionId = session.id, role = "tool", text = event.message, createdAt = System.currentTimeMillis())
                    )
                }
                is AgentEvent.TurnUsage -> {
                    // One row per model turn (Phase 5, Step 2). Real
                    // counts when the provider reported them, the
                    // chars÷4 estimate (flagged) when it did not.
                    tracker?.steps = (tracker?.steps ?: 0) + 1
                    tracker?.let {
                        it.inputTokens += event.usage.promptTokens.toLong()
                        it.outputTokens += event.usage.completionTokens.toLong()
                    }
                    db.usageDao().insert(
                        UsageRecord(
                            sessionId = session.id,
                            providerId = event.providerId,
                            modelId = event.modelId,
                            promptTokens = event.usage.promptTokens.toLong(),
                            completionTokens = event.usage.completionTokens.toLong(),
                            estimated = event.estimated,
                            createdAt = System.currentTimeMillis()
                        )
                    )
                }
                is AgentEvent.Done -> finishedCleanly = !hadError && !hadProposal
                else -> Unit
            }
        }
        return finishedCleanly
    }

    // ---- Compaction (Step 5) ----

    /**
     * Manual "Summarise chat": ask the session's model for a structured
     * summary first; if the model call fails (or no key is set), fall back
     * to the offline local summary. Either way the full history stays in
     * Room — a summary only changes what is sent to the model.
     */
    fun summariseChat(session: Session) {
        viewModelScope.launch {
            val history = db.messageDao().getMessages(session.id)
            if (history.size < 2) {
                lastError = "Not enough messages to summarise yet."
                return@launch
            }
            val provider = provider(session.providerId)
            val apiKey = keys.getKey(session.providerId)
            val saved = summariseWithModel(session, provider, apiKey, history)
                ?: saveLocalSummary(session, history)
            lastSummary = saved
            summarisedBanner = true
        }
    }

    /**
     * Auto-compaction check before a Build turn: when the token estimate
     * is over 75% of the context window, produce a summary (model first,
     * local fallback) and return its Markdown so the runner sends the
     * summary + recent tail instead of the full history. Returns null
     * when no compaction is needed.
     */
    private suspend fun maybeCompact(
        session: Session,
        provider: Provider,
        apiKey: String?,
        history: List<Message>,
        /** Context window the thresholds are measured against (4,096 for local). */
        contextTokens: Int = Compaction.DEFAULT_CONTEXT_TOKENS
    ): String? {
        val estimate = TokenCounter.estimateTokens(history)
        if (!Compaction.needsSummary(estimate, contextTokens)) return null
        val already = db.summaryDao().get(session.id)
        val (covered, _) = Compaction.splitForSummary(history)
        val lastCovered = covered.lastOrNull()?.id ?: 0L
        // Reuse the saved summary when it already covers these messages.
        if (already != null && already.coversUpToMessageId >= lastCovered) {
            lastSummary = already
            summarisedBanner = true
            return already.summaryMarkdown
        }
        val saved = summariseWithModel(session, provider, apiKey, history)
            ?: saveLocalSummary(session, history)
        lastSummary = saved
        summarisedBanner = true
        return saved.summaryMarkdown
    }

    /**
     * Harder compaction for a "context too long" failure (Phase 7,
     * Step B2 — AgentMT's "summarise harder once and redo this step").
     * Re-summarises with only the newest [Compaction.HARD_KEEP_TOKENS]
     * (5k) kept verbatim, so the summary covers strictly more of the
     * history than the normal 15k-tail summary did, and returns the new
     * summary Markdown for the runner to retry the step with. Returns
     * null when there is nothing more to cover (no messages outside the
     * 5k tail, or the saved summary already covers them) — the runner
     * then falls through to the normal next-candidate path instead of
     * retrying with an identical prompt. The model summary is tried
     * first and the offline local summary is the fallback, exactly as
     * in [maybeCompact]; the saved summary is replaced (REPLACE), so
     * the next turn measures against the harder one.
     */
    private suspend fun compactHarder(
        session: Session,
        provider: Provider,
        apiKey: String?,
        history: List<Message>
    ): String? {
        val (covered, _) = Compaction.splitForSummary(history, Compaction.HARD_KEEP_TOKENS)
        if (covered.isEmpty()) return null
        val lastCovered = covered.lastOrNull()?.id ?: 0L
        val already = db.summaryDao().get(session.id)
        if (already != null && already.coversUpToMessageId >= lastCovered) return null
        val saved = summariseWithModel(session, provider, apiKey, history, Compaction.HARD_KEEP_TOKENS)
            ?: saveLocalSummary(session, history, Compaction.HARD_KEEP_TOKENS)
        lastSummary = saved
        summarisedBanner = true
        return saved.summaryMarkdown
    }

    /** Ask the model for a structured summary; null when unavailable. */
    private suspend fun summariseWithModel(
        session: Session,
        provider: Provider?,
        apiKey: String?,
        history: List<Message>,
        /** Tokens kept verbatim beside the summary (5k on the harder retry). */
        keepTokens: Int = Compaction.KEEP_TOKENS
    ): SessionSummary? {
        if (provider == null) return null
        // Azure without a resource name cannot be called — fall through
        // to the local summary instead of failing the turn.
        if (provider.kind == "azure" && prefs.azureResourceName(provider.id).isNullOrBlank()) return null
        // Bedrock without its credential set cannot be called either.
        if (provider.kind == "bedrock" && !keys.hasAwsCredential()) return null
        // On-device model without a .task file cannot summarise — fall
        // through to the local summary instead of attempting a slow
        // engine load that would fail.
        if (provider.kind == "local" && !localLlm.hasModelFile()) return null
        if (!provider.local && provider.kind != "bedrock" && apiKey.isNullOrBlank()) return null
        val (covered, _) = Compaction.splitForSummary(history, keepTokens)
        if (covered.isEmpty()) return null
        val coveredText = covered.joinToString("\n\n") { "${it.role}: ${it.text.take(4000)}" }
        val reply = StringBuilder()
        return try {
            llm.streamChat(
                provider,
                session.modelId,
                apiKey,
                listOf(LlmMessage("user", Compaction.summaryPrompt(coveredText))),
                azureResourceName = if (provider.kind == "azure") prefs.azureResourceName(provider.id) else null,
                awsCredentials = if (provider.kind == "bedrock") keys.awsCredentials() else null
            ).collect { reply.append(it) }
            val text = reply.toString().trim()
            if (text.isBlank()) return null
            val saved = SessionSummary(
                sessionId = session.id,
                summaryMarkdown = text,
                coversUpToMessageId = covered.last().id,
                modelId = session.modelId,
                createdAt = System.currentTimeMillis()
            )
            db.summaryDao().save(saved)
            saved
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // Stop must cancel, never fall through to the local summary.
        } catch (_: Exception) {
            null
        }
    }

    /** Offline fallback summary, saved the same way as a model summary. */
    private suspend fun saveLocalSummary(
        session: Session,
        history: List<Message>,
        /** Tokens kept verbatim beside the summary (5k on the harder retry). */
        keepTokens: Int = Compaction.KEEP_TOKENS
    ): SessionSummary {
        val (covered, _) = Compaction.splitForSummary(history, keepTokens)
        val saved = SessionSummary(
            sessionId = session.id,
            summaryMarkdown = Compaction.buildLocalSummary(covered, session.folderName),
            coversUpToMessageId = covered.lastOrNull()?.id ?: 0L,
            modelId = session.modelId,
            createdAt = System.currentTimeMillis()
        )
        db.summaryDao().save(saved)
        return saved
    }

    fun dismissSummaryBanner() {
        summarisedBanner = false
    }

    suspend fun loadSummary(sessionId: String): SessionSummary? = db.summaryDao().get(sessionId)

    companion object {
        fun factory(app: CodeSathiApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AppViewModel(app) as T
                }
            }
    }
}
