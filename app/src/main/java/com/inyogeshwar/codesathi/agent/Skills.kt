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
 * same folder. Skills can also be installed from a GitHub URL or
 * skills.sh link (Phase 7, Step B4) — the SKILL.md is converted to this
 * same one-file format on install. Plugin skills (Phase 7, Step B4)
 * stay inside their plugin's folder and are read from there.
 * Enabled / disabled lives in DataStore (PreferencesStore),
 * not in the file — a file never carries its own on/off flag.
 * There is no marketplace or versioning.
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
    val fileName: String,
    /** Plugin this skill came from, if any — plugin skills are read-only
     *  here (enable / disable only; edit, delete and reset refuse). */
    val pluginId: String? = null,
    val pluginName: String? = null
)

/** The ids of the skills that ship with the app (assets/skills/ — one .md file each). */
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

/**
 * Parse a skill folder's SKILL.md (Claude Code format: YAML front
 * matter with name and description, then the instructions). Returns
 * (name, description, instructions). A file without front matter
 * falls back to the folder name and the first non-empty line.
 */
fun parseSkillFrontMatter(text: String, fallbackId: String): Triple<String, String, String> {
    var name = fallbackId
    var description = ""
    var body = text
    if (text.startsWith("---")) {
        val end = text.indexOf("\n---", 3)
        if (end > 0) {
            text.substring(3, end).lines().forEach { line ->
                val i = line.indexOf(':')
                if (i > 0 && !line.startsWith(" ")) {
                    val key = line.substring(0, i).trim()
                    val value = line.substring(i + 1).trim().trim('"', '\'')
                    if (key == "name" && value.isNotBlank()) name = value
                    if (key == "description") description = value
                }
            }
            body = text.substring(end + 4).trimStart()
        }
    }
    if (description.isBlank()) {
        description = body.lines().firstOrNull { it.isNotBlank() }.orEmpty().take(160)
    }
    return Triple(name, description, body.trim())
}

/** Result of installing skills from a GitHub repo. */
data class SkillInstallResult(
    val installed: List<String>,
    val skipped: List<String>,
    val error: String?
)

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
 * Skills on disk: getFilesDir()/skills/ (one .md file per skill). All file work runs on
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
     * (plugin name, plugin folder) pairs for the enabled plugins, set
     * by the ViewModel from the plugin registry. Plugin skills live
     * inside those folders (skills/<name>/SKILL.md) and are read from
     * there — never copied into the skills folder, so uninstalling a
     * plugin removes its skills at once and a plugin update is just a
     * reinstall.
     */
    @Volatile
    var pluginDirs: List<Pair<String, File>> = emptyList()

    /** Ids of the skills that come from an enabled plugin right now. */
    private fun pluginSkillIds(): Set<String> {
        val ids = HashSet<String>()
        for ((_, pluginDir) in pluginDirs) {
            File(pluginDir, "skills").listFiles()
                ?.filter { it.isDirectory && File(it, "SKILL.md").isFile }
                ?.forEach { ids += pluginIdFor(it.name) }
        }
        return ids
    }

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

    /** All skills on disk, built-ins first, then user-added, each by name.
     *  Plugin skills (from enabled plugins) are listed last, under their
     *  plugin's name; a user skill with the same id wins over a plugin
     *  skill, so a plugin can never shadow a skill the user wrote. */
    suspend fun listSkills(): List<Skill> = withContext(Dispatchers.IO) {
        val files = dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".md") }
            ?.sortedBy { it.name }
            .orEmpty()
        val out = files.mapNotNull { file ->
            try {
                parseSkillMarkdown(file.name, file.readText())
            } catch (_: Exception) {
                null
            }
        }.toMutableList()
        val knownIds = out.map { it.id }.toHashSet()
        for ((pluginName, pluginDir) in pluginDirs) {
            File(pluginDir, "skills").listFiles()
                ?.filter { it.isDirectory }
                ?.sortedBy { it.name }
                ?.forEach { skillDir ->
                    val skillFile = File(skillDir, "SKILL.md")
                    if (!skillFile.isFile) return@forEach
                    val id = pluginIdFor(skillDir.name)
                    if (id in knownIds) return@forEach
                    try {
                        val (name, description, instructions) =
                            parseSkillFrontMatter(skillFile.readText(), id)
                        out += Skill(
                            id = id, name = name, description = description,
                            instructions = instructions, builtIn = false,
                            fileName = "SKILL.md",
                            pluginId = pluginIdFor(pluginName), pluginName = pluginName
                        )
                        knownIds += id
                    } catch (_: Exception) {
                        // One bad plugin skill must not hide the rest.
                    }
                }
        }
        out.sortedWith(compareBy({ !it.builtIn }, { it.name.lowercase() }))
    }

    /** One skill by id, or null when there is no such file. Plugin
     *  skills are found by their folder name under an enabled plugin. */
    suspend fun loadSkill(id: String): Skill? = withContext(Dispatchers.IO) {
        if (!isValidSkillId(id)) return@withContext null
        val file = File(dir, "$id.md")
        if (file.isFile) {
            try {
                return@withContext parseSkillMarkdown(file.name, file.readText())
            } catch (_: Exception) {
                return@withContext null
            }
        }
        for ((pluginName, pluginDir) in pluginDirs) {
            val skillFile = File(File(pluginDir, "skills"), "$id/SKILL.md")
            if (!skillFile.isFile) continue
            return@withContext try {
                val (name, description, instructions) =
                    parseSkillFrontMatter(skillFile.readText(), id)
                Skill(
                    id = id, name = name, description = description,
                    instructions = instructions, builtIn = false,
                    fileName = "SKILL.md",
                    pluginId = pluginIdFor(pluginName), pluginName = pluginName
                )
            } catch (_: Exception) {
                null
            }
        }
        null
    }

    /**
     * Install skills from a GitHub URL or skills.sh link (or an
     * owner/repo shorthand). Each skill folder found becomes one
     * plain CodeSathi skill file in the skills folder — the front
     * matter becomes the "# Name" and "Description:" lines, the rest
     * becomes the instructions. Supporting files beside SKILL.md are
     * NOT installed: CodeSathi skills are single Markdown files, and
     * a skill that needs its extra files will say so in its text.
     * An id that already exists is skipped, never overwritten.
     */
    suspend fun installFromGitHub(ref: RepoRef, fetcher: GitHubFetcher): SkillInstallResult =
        withContext(Dispatchers.IO) {
            val found = try {
                fetcher.findSkills(ref)
            } catch (e: Exception) {
                return@withContext SkillInstallResult(
                    emptyList(), emptyList(),
                    e.message ?: "Could not reach GitHub."
                )
            }
            if (found.isEmpty()) {
                return@withContext SkillInstallResult(
                    emptyList(), emptyList(),
                    "No skill (no SKILL.md) found at ${ref.display}."
                )
            }
            val installed = ArrayList<String>()
            val skipped = ArrayList<String>()
            for (entry in found) {
                val entries = try {
                    fetcher.list(ref, entry.path)
                } catch (_: Exception) {
                    continue
                }
                val skillFile = entries.firstOrNull { it.name == "SKILL.md" }
                    ?: continue
                val text = try {
                    String(fetcher.download(skillFile.downloadUrl ?: continue))
                } catch (_: Exception) {
                    continue
                }
                val id = pluginIdFor(entry.name)
                if (!isValidSkillId(id) || File(dir, "$id.md").exists()) {
                    skipped += id
                    continue
                }
                val (name, description, instructions) = parseSkillFrontMatter(text, id)
                dir.mkdirs()
                try {
                    File(dir, "$id.md").writeText(
                        buildSkillMarkdown(name, description, instructions)
                    )
                    installed += id
                } catch (_: Exception) {
                    skipped += id
                }
            }
            SkillInstallResult(installed, skipped, null)
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

    /** Overwrite a skill's Markdown. False when the id is bad, the
     *  skill comes from a plugin (edit it in the plugin instead), or
     *  the write fails. */
    suspend fun saveSkill(id: String, markdown: String): Boolean = withContext(Dispatchers.IO) {
        if (!isValidSkillId(id) || markdown.isBlank()) return@withContext false
        if (id in pluginSkillIds()) return@withContext false
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
     * reset them instead) — returns false for those — and neither can
     * plugin skills (uninstall the plugin instead).
     */
    suspend fun deleteSkill(id: String): Boolean = withContext(Dispatchers.IO) {
        if (id in BUILTIN_SKILL_IDS) return@withContext false
        if (id in pluginSkillIds()) return@withContext false
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
