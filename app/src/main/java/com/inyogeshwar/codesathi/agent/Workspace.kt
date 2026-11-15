package com.inyogeshwar.codesathi.agent

/**
 * Project sandbox (Phase 7, Step B3 — adapted from AgentMT's
 * `agent/tools/Workspace.kt`, the user's own code).
 *
 * AgentMT's workspace is a `java.io.File` root: every tool path is
 * resolved against it with `canonicalFile`, and a path that escapes
 * the root is refused. CodeSathi has no file root — the project folder
 * is a SAF tree URI, and [SafProject] already refuses `..` segments
 * when it walks the tree. So the sandbox here is the same rule,
 * expressed for relative SAF paths: the agent tools call
 * [ProjectWorkspace.validateProjectPath] before they touch a path,
 * and a path outside the project is refused with a plain message
 * instead of being resolved.
 *
 * The ignored-dirs set is AgentMT's: tool paths under one of these
 * first segments are refused too — an agent has no business reading
 * or writing a build cache or a `.git` directory through the file
 * tools (Git has its own screen, backed by JGit, not by these tools).
 *
 * Glob matching is NOT re-implemented here: the project's one glob
 * dialect is [globToRegex] in Permissions.kt (Step B2), and the glob
 * tool uses it for its optional `pattern` argument.
 */
object ProjectWorkspace {

    /** Directories the file tools never touch (AgentMT's set). */
    val IGNORED_DIRS = setOf(
        ".git", "node_modules", "build", ".gradle", ".idea",
        ".venv", "__pycache__", "dist", ".next", "target"
    )

    /**
     * Validate a tool path. Returns null when the path is inside the
     * project, or the plain refusal reason when it is not. A blank
     * path is allowed here — it means "the project root" for the glob
     * tool's `dir` argument, and the read / write / edit tools check
     * for a missing path themselves before they call this.
     *
     * Refused: absolute paths (`/etc/passwd`, `C:\...`), any `..`
     * segment, and any path whose first segment is ignored (see
     * [IGNORED_DIRS]). A leading `./` is stripped first, exactly as
     * AgentMT's `resolve` does.
     */
    fun validateProjectPath(path: String?): String? {
        val clean = (path ?: "").trim().removePrefix("./")
        if (clean.isBlank()) return null
        if (clean.startsWith("/") || clean.startsWith("\\") ||
            (clean.length >= 2 && clean[1] == ':' && clean[0].isLetter())
        ) {
            return "Path is outside the project: $path"
        }
        val parts = clean.split('/').filter { it.isNotEmpty() }
        if (parts.any { it == ".." }) {
            return "Path is outside the project: $path"
        }
        if (parts.firstOrNull() in IGNORED_DIRS) {
            return "That folder is not part of the project for file tools: ${parts.first()}"
        }
        return null
    }
}
