package com.inyogeshwar.codesathi.agent

import android.content.Context

/**
 * Project templates (Phase 6, Step 3): starter files for a new session.
 *
 * A template is a small list of files that ship with the app under
 * app assets, in the templates folder — one subfolder per template,
 * named after the template id. The files are plain text the app
 * itself wrote; a template never carries code copied from anywhere
 * else, and it never carries a secret — there is nothing to put a key
 * in here, and no template file may contain one.
 *
 * A template never writes by itself. Choosing one in the New Session
 * flow only queues its files as [PlannedFile] proposals, one at a
 * time, through the same diff-card Approve flow the agent's writes
 * and the editor's saves use — nothing reaches the disk until the
 * user taps Approve on that card (see [com.inyogeshwar.codesathi.ui.AppViewModel]).
 * A template with no files (blank) queues nothing at all.
 */

/** One file of a template: its path in the new session's folder, and
 *  the asset file it is read from. */
data class TemplateFile(
    /** Path inside the project folder, e.g. "index.html". */
    val path: String,
    /** Asset path, e.g. "templates/portfolio/index.html". */
    val assetPath: String
)

/** One template on the New Session screen. */
data class ProjectTemplate(
    /** Stable id, e.g. "portfolio". Shown nowhere; used to look it up. */
    val id: String,
    /** Display name on the picker. */
    val name: String,
    /** One line under the name, saying what the template starts. */
    val description: String,
    /** The files it creates, in the order they are proposed. */
    val files: List<TemplateFile>
)

/** The id of the "no template" choice — an empty folder, exactly as
 *  new sessions worked before templates existed. */
const val BLANK_TEMPLATE_ID = "blank"

/**
 * The templates that ship with the app, in picker order. Blank first,
 * so the default choice changes nothing about the old flow.
 */
val BUILT_IN_TEMPLATES: List<ProjectTemplate> = listOf(
    ProjectTemplate(
        id = BLANK_TEMPLATE_ID,
        name = "Blank",
        description = "Empty folder — start from scratch, exactly as before.",
        files = emptyList()
    ),
    ProjectTemplate(
        id = "portfolio",
        name = "Portfolio website",
        description = "A single-page portfolio site, ready to edit and preview — the most common first project.",
        files = listOf(TemplateFile("index.html", "templates/portfolio/index.html"))
    ),
    ProjectTemplate(
        id = "python-script",
        name = "Python script",
        description = "A small main.py starter — run it, then grow it into your script.",
        files = listOf(TemplateFile("main.py", "templates/python-script/main.py"))
    ),
    ProjectTemplate(
        id = "notes",
        name = "Markdown notes",
        description = "A NOTES.md starter with headings for today, ideas and tasks.",
        files = listOf(TemplateFile("NOTES.md", "templates/notes/NOTES.md"))
    )
)

/** Look a template up by id, or null when the id is unknown. */
fun templateById(id: String): ProjectTemplate? =
    BUILT_IN_TEMPLATES.firstOrNull { it.id == id }

/**
 * Read a template's files from assets as (path, content) pairs, in
 * the template's file order. A blank template reads nothing and
 * returns an empty list. Returns null when any file cannot be read —
 * the caller then prepares no proposal and says so in the session,
 * instead of creating a half-empty template.
 *
 * Callers run this off the main thread (the asset files are small,
 * but asset reads are still IO).
 */
fun loadTemplateFiles(
    context: Context,
    template: ProjectTemplate
): List<Pair<String, String>>? {
    val loaded = ArrayList<Pair<String, String>>()
    for (file in template.files) {
        try {
            val text = context.assets.open(file.assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
            loaded += file.path to text
        } catch (_: Exception) {
            return null
        }
    }
    return loaded
}
