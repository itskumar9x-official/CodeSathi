package com.inyogeshwar.codesathi.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Session backup and restore (Phase 5, Step 3).
 *
 * A backup is one JSON file holding Room sessions only: each session,
 * its messages and its compaction summary. Nothing else is ever in the
 * file — no API keys, no provider credentials, no MCP tokens, no remote
 * password, no Git credential, no AWS credential and no usage or cost
 * records. Usage is re-derived from new turns after a restore, so
 * exporting it would only leak history for no benefit.
 *
 * The format is versioned: [SessionBackup.formatVersion] starts at 1.
 * A file whose version is not 1, or that is not valid backup JSON at
 * all, is refused whole — import writes nothing for that file.
 * Unknown fields inside a version-1 file are ignored, so a file
 * written by a newer app that only adds fields still imports here.
 *
 * Import rule, per session: a session whose id already exists in Room
 * is SKIPPED, never overwritten. A session that fails validation is
 * skipped too, with its reason in the result. Validation happens
 * before any write for that session, so a skipped session can never
 * leave a half-imported session behind; a write that still fails
 * mid-session is cleaned up by deleting what was just inserted.
 *
 * Folder grants: the backup stores the session's project-folder URI
 * string ([Session.projectUri]), so an imported session still points
 * at its folder — but a SAF persistable grant is per device and per
 * install, and it is NOT in the file. On the same device and install
 * the grant usually still holds and the session just works. On a new
 * device, after reinstalling, or after the grant was revoked, the
 * imported session shows "Folder unavailable" and the user re-chooses
 * the folder from Home, exactly like any session whose folder was
 * lost. The backup never pretends the grant travels with the file.
 */

/** The only backup format version this build writes or reads. */
const val BACKUP_FORMAT_VERSION = 1

/** Largest backup file the importer will read (10 MB — a text file of sessions). */
const val MAX_BACKUP_BYTES = 10 * 1024 * 1024

/** One message inside a backed-up session. Room ids are not stored:
 *  messages are re-inserted on import and get fresh ids. */
@Serializable
data class BackupMessage(
    val role: String,
    val text: String,
    val toolJson: String? = null,
    val createdAt: Long
)

/** One session inside a backup, with its messages and summary inline. */
@Serializable
data class BackupSession(
    val id: String,
    val title: String,
    val folderName: String,
    val projectUri: String,
    val providerId: String,
    val modelId: String,
    val agentMode: String,
    val createdAt: Long,
    val updatedAt: Long,
    val status: String = "active",
    /** Pinned state (Phase 5, Step 4). Defaults to false, so backups
     *  written before pinning existed still import fine. */
    val pinned: Boolean = false,
    val messages: List<BackupMessage> = emptyList(),
    val summaryMarkdown: String? = null,
    val summaryModelId: String? = null,
    val summaryCreatedAt: Long? = null
)

/** The whole backup file. [app] has no default on purpose: a file
 *  without it is not a CodeSathi backup and must be refused. */
@Serializable
data class SessionBackup(
    val formatVersion: Int,
    val app: String,
    val exportedAt: Long,
    val sessions: List<BackupSession>
)

/** One session plus the Room rows that belong to it, for encoding. */
data class SessionBackupData(
    val session: Session,
    val messages: List<Message>,
    val summary: SessionSummary?
)

/** Outcome of one import, for the plain summary shown on Home. */
data class BackupImportResult(
    val imported: Int,
    val skipped: Int,
    /** One line per skipped session, e.g. "My project — already exists". */
    val skippedReasons: List<String>
)

private val backupJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
}

/** Encode sessions to the backup file text (format version 1). */
fun encodeBackup(data: List<SessionBackupData>): String {
    val backup = SessionBackup(
        formatVersion = BACKUP_FORMAT_VERSION,
        app = "CodeSathi",
        exportedAt = System.currentTimeMillis(),
        sessions = data.map { (session, messages, summary) ->
            BackupSession(
                id = session.id,
                title = session.title,
                folderName = session.folderName,
                projectUri = session.projectUri,
                providerId = session.providerId,
                modelId = session.modelId,
                agentMode = session.agentMode,
                createdAt = session.createdAt,
                updatedAt = session.updatedAt,
                status = session.status,
                pinned = session.pinned,
                messages = messages.map { message ->
                    BackupMessage(
                        role = message.role,
                        text = message.text,
                        toolJson = message.toolJson,
                        createdAt = message.createdAt
                    )
                },
                summaryMarkdown = summary?.summaryMarkdown,
                summaryModelId = summary?.modelId,
                summaryCreatedAt = summary?.createdAt
            )
        }
    )
    return backupJson.encodeToString(SessionBackup.serializer(), backup)
}

/** Result of parsing backup text: either a usable backup or a plain error. */
sealed class BackupParseResult {
    data class Ok(val backup: SessionBackup) : BackupParseResult()
    data class Error(val message: String) : BackupParseResult()
}

/**
 * Parse backup text. Any structural problem — malformed JSON, a
 * missing app marker, a wrong format version — returns [Error] and
 * the caller must import nothing. Per-session problems are NOT
 * errors here; they are found by [validateBackupSession] during
 * import and skipped one by one.
 */
fun parseBackup(text: String): BackupParseResult {
    val backup = try {
        backupJson.decodeFromString(SessionBackup.serializer(), text)
    } catch (e: SerializationException) {
        return BackupParseResult.Error(
            "That file is not a CodeSathi backup — nothing was imported."
        )
    } catch (e: IllegalArgumentException) {
        return BackupParseResult.Error(
            "That file is not a CodeSathi backup — nothing was imported."
        )
    }
    if (backup.app != "CodeSathi") {
        return BackupParseResult.Error(
            "That file is not a CodeSathi backup — nothing was imported."
        )
    }
    if (backup.formatVersion != BACKUP_FORMAT_VERSION) {
        return BackupParseResult.Error(
            "Backup format version ${backup.formatVersion} is not supported " +
                "(this app reads version $BACKUP_FORMAT_VERSION) — nothing was imported."
        )
    }
    return BackupParseResult.Ok(backup)
}

/**
 * Validate one backed-up session before anything is written for it.
 * Returns null when the session is importable, or the plain reason
 * it must be skipped. Deliberately strict about the fields the app
 * needs to open the session (id, folder, provider, model, mode) and
 * lenient about the rest — timestamps are used as stored.
 */
fun validateBackupSession(session: BackupSession): String? {
    if (session.id.isBlank()) return "missing session id"
    if (session.title.isBlank()) return "missing title"
    if (session.folderName.isBlank()) return "missing folder name"
    if (session.projectUri.isBlank()) return "missing folder"
    if (session.providerId.isBlank()) return "missing provider"
    if (session.modelId.isBlank()) return "missing model"
    if (session.agentMode != "plan" && session.agentMode != "build") {
        return "unknown mode “${session.agentMode}”"
    }
    return null
}

/** A validated backup session as a Room [Session]. */
fun BackupSession.toSession(): Session = Session(
    id = id,
    title = title,
    folderName = folderName,
    projectUri = projectUri,
    providerId = providerId,
    modelId = modelId,
    agentMode = agentMode,
    createdAt = createdAt,
    updatedAt = updatedAt,
    status = status,
    pinned = pinned
)
