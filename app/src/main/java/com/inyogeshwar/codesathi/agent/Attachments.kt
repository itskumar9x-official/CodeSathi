package com.inyogeshwar.codesathi.agent

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.inyogeshwar.codesathi.data.Provider
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * File attachments for chat — PDF + plain text (Phase 7, Step B3 —
 * adapted from AgentMT's `agent/attachments/Attachments.kt`, the
 * user's own code). CodeSathi already had images only
 * ([ImageStore], Phase 6 Step 2); images stay exactly there, and
 * this store handles everything else.
 *
 * A picked file is copied into app-private storage
 * (`getFilesDir()/attachments/`, same pattern as the images) and
 * Room stores only its record ([Message.attachmentsJson]) — never
 * bytes, never a content URI. The 25 MB cap is AgentMT's, and so is
 * the text cap: a text file is inlined into the prompt at most
 * [MAX_TEXT_CHARS] characters, wrapped in an attached-file block.
 *
 * What PDF support actually does — read carefully, no text
 * extraction exists anywhere here:
 * - A PDF is stored as-is and sent to the model as raw PDF bytes,
 *   base64-encoded: as a `file` part for OpenAI-kind providers
 *   (AgentMT's shape, from its OpenAI client) and as a `document`
 *   block for Anthropic-kind providers (AgentMT's shape, from its
 *   Anthropic client). See [LlmClient] — the request-body builders
 *   carry those two shapes.
 * - No PDF text is ever extracted on-device: there is no PDF library
 *   in the app, so the model reads the PDF itself (or fails to, and
 *   says so). A PDF attached to a model that takes neither shape
 *   (local on-device model, Bedrock) is refused before sending, with
 *   a plain message — it is never silently dropped.
 * - A binary file that is neither text nor PDF nor image is stored
 *   and shown, but never sent: the model only gets a note naming it.
 *
 * Only the 2 most recent user messages resend binaries on each turn
 * (AgentMT's rule — adopted here for images too, see
 * [AgentRunner.runBuild]'s `resendBinariesFor`). Older messages keep
 * their text attachments inlined (text is cheap) but their images
 * and PDFs become a short "[shared earlier]" note instead of
 * re-sending megabytes of base64 every turn. Before this step the
 * runner re-sent every history image on every turn.
 *
 * The file picker can return an image too; those are skipped here
 * with a plain note — images go through the image button and
 * [ImageStore] (downscaled + JPEG re-encoded), never through this
 * store as raw bytes.
 */

/** One attached file, as stored in [Message.attachmentsJson]. */
@Serializable
data class AttachmentRecord(
    val name: String,
    val mime: String,
    val path: String,
    val size: Long,
    /** "image" | "text" | "pdf" | "binary" — see [AttachmentStore.kindOf]. */
    val kind: String
)

/** What one message's attachments become for the model. */
data class AttachmentMaterial(
    /** Text-file contents inlined + notes for files not re-sent. */
    val extraText: String,
    /** PDFs to send as binary parts (only when [binary] was true). */
    val pdfs: List<LlmPdf>
)

/** True when this provider can receive a PDF as a binary part. */
fun supportsPdfUpload(provider: Provider): Boolean =
    provider.kind in setOf("openai", "anthropic", "azure")

object AttachmentStore {

    /** Largest file that may be attached (AgentMT's cap). */
    const val MAX_FILE_BYTES = 25L * 1024 * 1024

    /** At most this many files on one message (same cap as images). */
    const val MAX_ATTACHMENTS_PER_MESSAGE = 3

    /** Longest text file inlined into the prompt (AgentMT's cap). */
    const val MAX_TEXT_CHARS = 120_000

    private const val DIR_NAME = "attachments"

    /** File paths this store ever writes: "attachments/att_<8 hex>-<safe name>". */
    private val PATH = Regex("""attachments/att_[0-9a-f]{8}-[A-Za-z0-9._ \-]+""")

    private val TEXT_EXT = setOf(
        "txt", "md", "markdown", "kt", "kts", "java", "js", "mjs", "cjs", "ts", "tsx", "jsx",
        "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cs", "swift", "dart", "php",
        "html", "htm", "css", "scss", "json", "xml", "yaml", "yml", "toml", "ini", "gradle",
        "properties", "sh", "bat", "ps1", "sql", "csv", "tsv", "log", "env", "gitignore",
        "dockerfile", "vue", "svelte", "lua", "r", "tex", "srt", "vtt"
    )

    fun kindOf(name: String, mime: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            mime.startsWith("image/") && ext != "svg" -> "image"
            mime == "application/pdf" || ext == "pdf" -> "pdf"
            mime.startsWith("text/") || ext in TEXT_EXT ||
                mime in setOf("application/json", "application/xml", "application/javascript") -> "text"
            else -> "binary"
        }
    }

    fun dir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    /**
     * Copy a picked document into app-private storage. Throws
     * [IllegalArgumentException] when the file is larger than
     * [MAX_FILE_BYTES] — the caller shows the plain message and
     * never crashes. The stored copy is deleted again when the size
     * check fails after copying (an unknown size from the picker).
     */
    fun import(context: Context, uri: Uri): AttachmentRecord {
        var name = "file"
        var size = -1L
        context.contentResolver.query(uri, null, null, null, null)?.use { cur ->
            if (cur.moveToFirst()) {
                cur.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }
                    ?.let { name = cur.getString(it) ?: name }
                cur.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }
                    ?.let { size = cur.getLong(it) }
            }
        }
        if (size > MAX_FILE_BYTES) throw IllegalArgumentException("$name is larger than 25 MB")
        val mime = context.contentResolver.getType(uri)
            ?: android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80)
        val rel = "attachments/att_${UUID.randomUUID().toString().take(8)}-$safe"
        val out = File(context.filesDir, rel)
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        } ?: throw IllegalArgumentException("Could not read $name")
        if (out.length() > MAX_FILE_BYTES) {
            out.delete()
            throw IllegalArgumentException("$name is larger than 25 MB")
        }
        return AttachmentRecord(name, mime, rel, out.length(), kindOf(name, mime))
    }

    /** The stored file for a record, or null when the path is not one this store wrote. */
    fun file(context: Context, record: AttachmentRecord): File? {
        if (!PATH.matches(record.path)) return null
        return File(context.filesDir, record.path).takeIf { it.isFile }
    }

    /**
     * Builds what the model sees for one message's attachments
     * (AgentMT's `materialize`): text files are inlined in the
     * prompt, PDFs become binary parts — only when [binary] is true,
     * so older turns don't resend large payloads — and anything not
     * sent becomes a short note in the text instead.
     */
    fun materialize(
        context: Context,
        list: List<AttachmentRecord>,
        binary: Boolean
    ): AttachmentMaterial {
        val text = StringBuilder()
        val pdfs = ArrayList<LlmPdf>()
        for (a in list) {
            val f = file(context, a)
            if (f == null || !f.exists()) {
                text.append("\n\n[Attachment ${a.name} is missing on this device]")
                continue
            }
            when (a.kind) {
                "text" -> {
                    val body = runCatching { f.readText() }.getOrDefault("")
                    val lang = a.name.substringAfterLast('.', "")
                    text.append("\n\n<attached_file name=\"${a.name}\">\n```$lang\n")
                        .append(body.take(MAX_TEXT_CHARS))
                        .append(if (body.length > MAX_TEXT_CHARS) "\n… (truncated)" else "")
                        .append("\n```\n</attached_file>")
                }
                "image" -> text.append("\n\n[Image ${a.name} was shared earlier]")
                "pdf" -> if (binary) {
                    runCatching { f.readBytes() }.getOrNull()?.let { bytes ->
                        pdfs += LlmPdf(
                            Base64.encodeToString(bytes, Base64.NO_WRAP), a.name
                        )
                    } ?: text.append("\n\n[PDF ${a.name} could not be read]")
                } else {
                    text.append("\n\n[PDF ${a.name} was shared earlier]")
                }
                else -> text.append(
                    "\n\n[Attached file ${a.name} (${a.mime}, ${a.size / 1024} KB) — " +
                        "binary content not readable by the model.]"
                )
            }
        }
        return AttachmentMaterial(text.toString(), pdfs)
    }

    /** Delete one stored attachment; missing files and bad paths are no-ops. */
    fun delete(context: Context, record: AttachmentRecord) {
        try {
            file(context, record)?.delete()
        } catch (_: Exception) {
            // Best effort — a leftover app-private file is harmless.
        }
    }

    /** Delete several stored attachments (session delete, staged-file discard). */
    fun deleteAll(context: Context, records: List<AttachmentRecord>) {
        records.forEach { delete(context, it) }
    }

    /** Human size for a chip: "12 KB", "3.1 MB". */
    fun displaySize(size: Long): String = when {
        size >= 1024 * 1024 -> "%.1f MB".format(size / (1024.0 * 1024.0))
        size >= 1024 -> "${size / 1024} KB"
        else -> "$size B"
    }
}

private val attachmentsJson = Json { ignoreUnknownKeys = true }

/** Encode attachment records for [Message.attachmentsJson]. */
fun encodeAttachments(records: List<AttachmentRecord>): String =
    attachmentsJson.encodeToString(ListSerializer(AttachmentRecord.serializer()), records)

/** Decode [Message.attachmentsJson]; corrupt or missing values mean "no attachments", never a crash. */
fun decodeAttachments(json: String?): List<AttachmentRecord> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        attachmentsJson.decodeFromString(ListSerializer(AttachmentRecord.serializer()), json)
            .filter { it.name.isNotBlank() && it.path.isNotBlank() }
    } catch (_: Exception) {
        emptyList()
    }
}
