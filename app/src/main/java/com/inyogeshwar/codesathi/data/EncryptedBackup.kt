package com.inyogeshwar.codesathi.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Encrypted backup (Phase 7, Step B3 — adapted from AgentMT's
 * `WorkspaceBackup.kt`, the user's own code).
 *
 * The plain sessions-only backup ([encodeBackup], format version 1)
 * stays exactly as it is and stays the default: one JSON file, no
 * keys, no credentials, no usage records. The encrypted export is a
 * separate, opt-in file — a ZIP with two entries:
 *
 * - `codesathi-backup.json` — byte-for-byte the same sessions JSON
 *   the plain export writes (same format, same rules).
 * - `secrets.enc` — the secrets JSON ([KeyStore.exportSecretsJson])
 *   encrypted with [BackupCrypto] (PBKDF2-HMAC-SHA256, 210,000
 *   iterations, AES-256-GCM, magic "CSS1"). It is always written in
 *   an encrypted export, even when there are no secrets to carry —
 *   then it holds an empty JSON object, and the result says so.
 *
 * Import is two-phase, like AgentMT's preview: the file is read and
 * counted FIRST (sessions, messages, whether it carries secrets),
 * and nothing is applied until the user confirms. When the backup
 * carries secrets, the passphrase is asked in the preview and the
 * secrets are decrypted BEFORE any session is written — a wrong
 * passphrase stops the whole import with nothing changed. Restoring
 * secrets never overwrites a credential that is already on the
 * device (see [KeyStore.importSecretsJson]).
 *
 * The passphrase is never stored: it is typed at export and at
 * import and held only in memory for that one operation. The ZIP is
 * read from memory (the whole file is capped at [MAX_BACKUP_BYTES],
 * 10 MB, like the plain backup) — no temp file is left behind.
 */

/** Entry holding the sessions JSON inside an encrypted backup. */
const val ENCRYPTED_BACKUP_SESSIONS_ENTRY = "codesathi-backup.json"

/** Entry holding the encrypted secrets inside an encrypted backup. */
const val ENCRYPTED_BACKUP_SECRETS_ENTRY = "secrets.enc"

/** What the import preview shows, before anything is applied. */
data class EncryptedBackupPreview(
    val sessionCount: Int,
    val messageCount: Int,
    val hasSecrets: Boolean,
    val exportedAt: Long
)

/** Build the encrypted backup file: sessions JSON + encrypted secrets. */
fun writeEncryptedBackupZip(
    sessionsJson: String,
    secretsJson: String,
    passphrase: String
): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out.buffered()).use { zip ->
        zip.putNextEntry(ZipEntry(ENCRYPTED_BACKUP_SESSIONS_ENTRY))
        zip.write(sessionsJson.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        zip.putNextEntry(ZipEntry(ENCRYPTED_BACKUP_SECRETS_ENTRY))
        zip.write(BackupCrypto.encrypt(secretsJson.toByteArray(Charsets.UTF_8), passphrase))
        zip.closeEntry()
    }
    return out.toByteArray()
}

/** True when the bytes look like a ZIP file (the encrypted backup). */
fun isEncryptedBackup(bytes: ByteArray): Boolean =
    bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
        bytes[2] == 3.toByte() && bytes[3] == 4.toByte()

/** Read every entry of the ZIP into memory, keyed by entry name. */
private fun readZipEntries(bytes: ByteArray): Map<String, ByteArray> {
    val entries = LinkedHashMap<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }
    return entries
}

/** The sessions JSON inside an encrypted backup, or null when it is missing. */
fun readSessionsJsonFromEncryptedBackup(bytes: ByteArray): String? {
    val entries = runCatching { readZipEntries(bytes) }.getOrNull() ?: return null
    return entries[ENCRYPTED_BACKUP_SESSIONS_ENTRY]?.toString(Charsets.UTF_8)
}

/**
 * Preview an encrypted backup without changing anything: how many
 * sessions and messages it holds, and whether it carries encrypted
 * secrets. Null when the file is not a readable CodeSathi encrypted
 * backup at all. The secrets themselves are NOT decrypted here — the
 * preview cannot say how many there are until the passphrase is
 * entered, and it says so on screen.
 */
fun readEncryptedBackupPreview(bytes: ByteArray): EncryptedBackupPreview? {
    val entries = runCatching { readZipEntries(bytes) }.getOrNull() ?: return null
    val sessionsText = entries[ENCRYPTED_BACKUP_SESSIONS_ENTRY]?.toString(Charsets.UTF_8)
        ?: return null
    val backup = when (val parsed = parseBackup(sessionsText)) {
        is BackupParseResult.Ok -> parsed.backup
        is BackupParseResult.Error -> return null
    }
    return EncryptedBackupPreview(
        sessionCount = backup.sessions.size,
        messageCount = backup.sessions.sumOf { it.messages.size },
        hasSecrets = entries.containsKey(ENCRYPTED_BACKUP_SECRETS_ENTRY),
        exportedAt = backup.exportedAt
    )
}

/**
 * Decrypt the secrets JSON inside an encrypted backup. Throws
 * [BackupCrypto.WrongPassphrase] for a wrong passphrase, and
 * [IllegalArgumentException] when the secrets entry is missing or
 * damaged.
 */
fun decryptSecretsFromEncryptedBackup(bytes: ByteArray, passphrase: String): String {
    val entries = readZipEntries(bytes)
    val blob = entries[ENCRYPTED_BACKUP_SECRETS_ENTRY]
        ?: throw IllegalArgumentException("This backup carries no encrypted secrets.")
    return BackupCrypto.decrypt(blob, passphrase).toString(Charsets.UTF_8)
}
