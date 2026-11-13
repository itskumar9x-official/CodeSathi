package com.inyogeshwar.codesathi.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Skills (Phase 2, Step 4): small Markdown files that teach the agent how
 * to do one job well — review code, write tests, explain a file.
 *
 * File format (kept deliberately simple, one skill per file):
 * ```
 * # Code Review
 * Description: Review a file for bugs, security issues and style.
 *
 * <instructions body — plain Markdown, any length>
 * ```
 * The file name (without ".md") is the skill's id — that is what the
 * model passes to the skill tool ("code-review"). The first "# ..." line
 * is the display name, the "Description:" line is the one-liner shown in
 * the Build system prompt and the Skills screen, and everything else is
 * the instructions the model follows when the skill is called.
 *
 * Storage: built-in skills ship under app/src/main/assets/skills/ and are
 * copied into the app-private folder getFilesDir()/skills/ on first use,
 * so the user can edit them. User-added skills are Markdown files in the
 * same folder. Enabled / disabled lives in DataStore (PreferencesStore),
 * not in the file — a file never carries its own on/off flag.
 * No skill is ever downloaded, and there is no marketplace or versioning.
 */
data class Skill(
    /** File name without ".md" — the id the model uses, e.g. "code-review". */
    val id: String,
    /** Display name from the "# ..." line, e.g. "Code Review". */
    val name: String,
    /** One line from the "Description:" line. */
    val description: String,
    /** The instructions body, without title and description lines. */
    val instructions: String,
    /** True when the skill shipped with the app (it can be disabled and
     *  reset, but not deleted). */
    val builtIn: Boolean,
    val fileName: String
)

/** The ids of the skills that ship with the app (assets/skills/*.md). */
val BUILTIN_SKILL_IDS = setOf("code-review", "write-tests", "explain-code")

/** URL-safe id from a name: "Code Review" → "code-review". */
fun slugifySkillId(name: String): String {
    val slug = name.lowercase()
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .split('-').filter { it.isNotEmpty() }.joinToString("-")
    return slug
}

/** True when [id] is a safe file-name slug: lowercase letters, digits, "-". */
fun isValidSkillId(id: String): Boolean =
    id.isNotBlank() && id.all { it.isLowerCase() && (it.isLetterOrDigit() || it == '-') } &&
        !id.startsWith('-') && !id.endsWith('-') && !id.contains("--")

/** Build the Markdown text of a skill from its three parts. */
fun buildSkillMarkdown(name: String, description: String, instructions: String): String =
    "# ${name.trim()}\nDescription: ${description.trim()}\n\n${instructions.trim()}\n"

/**
 * Parse one skill file's text. The title line and the description line
 * are taken out; the rest (leading/trailing blank lines trimmed) is the
 * instructions. A file without a title falls back to a display name
 * derived from its id ("code-review" → "Code Review").
 */
fun parseSkillMarkdown(fileName: String, text: String): Skill {
    val id = fileName.removeSuffix(".md")
    var name: String? = null
    var description = ""
    val body = StringBuilder()
    for (line in text.lines()) {
        when {
            line.startsWith("# ") && name == null -> name = line.removePrefix("# ").trim()
            line.startsWith("Description:") && description.isEmpty() ->
                description = line.removePrefix("Description:").trim()
            else -> body.appendLine(line)
        }
    }
    val displayName = name?.takeIf { it.isNotBlank() }
        ?: id.split('-').joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
    return Skill(
        id = id,
        name = displayName,
        description = description,
        instructions = body.toString().trim(),
        builtIn = id in BUILTIN_SKILL_IDS,
        fileName = fileName
    )
}

/**
 * Skills on disk: getFilesDir()/skills/*.md. All file work runs on
 * Dispatchers.IO — the ViewModel never touches these files directly.
 */
class SkillStore(private val context: Context) {

    companion object {
        const val SKILLS_DIR = "skills"
        const val ASSETS_DIR = "skills"
    }

    private val dir: File
        get() = File(context.filesDir, SKILLS_DIR)

    /**
     * Copy the built-in skills from assets the first time they are needed.
     * Only files that are not already there are copied, so a user's edits
     * to a built-in skill are never overwritten by this.
     */
    suspend fun ensureSeeded() = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val assets = context.assets.list(ASSETS_DIR).orEmpty()
        for (file in assets) {
            if (!file.endsWith(".md")) continue
            val target = File(dir, file)
            if (target.exists()) continue
            try {
                context.assets.open("$ASSETS_DIR/$file").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: Exception) {
                // One bad asset must not stop the others from seeding.
            }
        }
    }

    /** All skills on disk, built-ins first, then user-added, each by name. */
    suspend fun listSkills(): List<Skill> = withContext(Dispatchers.IO) {
        val files = dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".md") }
            ?.sortedBy { it.name }
            .orEmpty()
        files.mapNotNull { file ->
            try {
                parseSkillMarkdown(file.name, file.readText())
            } catch (_: Exception) {
                null
            }
        }.sortedWith(compareBy({ !it.builtIn }, { it.name.lowercase() }))
    }

    /** One skill by id, or null when there is no such file. */
    suspend fun loadSkill(id: String): Skill? = withContext(Dispatchers.IO) {
        if (!isValidSkillId(id)) return@withContext null
        val file = File(dir, "$id.md")
        if (!file.isFile) return@withContext null
        try {
            parseSkillMarkdown(file.name, file.readText())
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Create a skill from its three parts. Returns the new id, or null
     * when the name is blank or a skill with that id already exists.
     */
    suspend fun createSkill(name: String, description: String, instructions: String): String? =
        withContext(Dispatchers.IO) {
            val id = slugifySkillId(name)
            if (id.isBlank() || !isValidSkillId(id)) return@withContext null
            if (File(dir, "$id.md").exists()) return@withContext null
            dir.mkdirs()
            return@withContext try {
                File(dir, "$id.md").writeText(buildSkillMarkdown(name, description, instructions))
                id
            } catch (_: Exception) {
                null
            }
        }

    /** Overwrite a skill's Markdown. False when the id is bad or the write fails. */
    suspend fun saveSkill(id: String, markdown: String): Boolean = withContext(Dispatchers.IO) {
        if (!isValidSkillId(id) || markdown.isBlank()) return@withContext false
        return@withContext try {
            dir.mkdirs()
            File(dir, "$id.md").writeText(markdown)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Delete a user-added skill. Built-ins cannot be deleted (disable or
     * reset them instead) — returns false for those.
     */
    suspend fun deleteSkill(id: String): Boolean = withContext(Dispatchers.IO) {
        if (id in BUILTIN_SKILL_IDS) return@withContext false
        val file = File(dir, "$id.md")
        if (!file.isFile) return@withContext false
        file.delete()
    }

    /**
     * Restore an edited built-in skill to the version that shipped with
     * the app. False when it is not a built-in or the asset is missing.
     */
    suspend fun resetSkill(id: String): Boolean = withContext(Dispatchers.IO) {
        if (id !in BUILTIN_SKILL_IDS) return@withContext false
        return@withContext try {
            context.assets.open("$ASSETS_DIR/$id.md").use { input ->
                dir.mkdirs()
                File(dir, "$id.md").outputStream().use { output -> input.copyTo(output) }
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * The "skill" tool, as a normal [Tool] — so [AgentRunner] dispatches it
 * and gates it with the same allow / ask / deny rules as the built-ins,
 * with no special-casing in the runner.
 *
 * Calling it with a skill name loads that skill's instructions and
 * returns them as the tool result, so the model then follows them for
 * the task at hand. A skill never writes files by itself: anything it
 * asks the model to change still goes through write / edit, which pause
 * for the user's Approve on the diff card. Plan mode never calls tools,
 * so the read-only flag does not apply here.
 */
class SkillTool(
    private val store: SkillStore,
    private val isEnabled: (String) -> Boolean = { true }
) : Tool {
    override val name = "skill"
    override val description =
        "Load a skill's instructions and follow them for the task. " +
            "Args: {\"name\": \"code-review\"}."

    override suspend fun run(args: Map<String, String>, ctx: ToolContext): ToolResult {
        val name = args["name"] ?: args["skill"]
            ?: return ToolResult.Failed("skill needs a name, e.g. {\"name\": \"code-review\"}")
        val skill = store.loadSkill(name.trim())
            ?: return ToolResult.Failed("No skill named \"$name\". Ask the user which skill to use, or check the Skills screen.")
        if (!isEnabled(skill.id)) {
            return ToolResult.Failed("Skill \"${skill.id}\" is disabled. Enable it on the Skills screen first.")
        }
        if (skill.instructions.isBlank()) {
            return ToolResult.Failed("Skill \"${skill.id}\" has no instructions yet. Edit it on the Skills screen first.")
        }
        return ToolResult.Ok(
            "Skill \"${skill.id}\" (${skill.name})" +
                (if (skill.description.isNotBlank()) " — ${skill.description}" else "") +
                "\nFollow these instructions for this task:\n\n${skill.instructions}"
        )
    }
}

/** The skill tool, for the agent's tool list. The tool itself refuses
 *  unknown or disabled skills with a clear message. */
fun skillTools(store: SkillStore, isEnabled: (String) -> Boolean): List<Tool> =
    listOf(SkillTool(store, isEnabled))
