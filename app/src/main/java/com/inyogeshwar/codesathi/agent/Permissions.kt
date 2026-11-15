package com.inyogeshwar.codesathi.agent

/**
 * Tool permissions, Phase 7 Step B2 — adapted from AgentMT's
 * `PermissionEngine.kt` (the user's own code) to CodeSathi's stack.
 *
 * AgentMT kept its rules in Room and asked the user with a blocking
 * approval dialog (its `PendingApproval` + waiter). CodeSathi keeps its
 * rules in DataStore JSON (same pattern as the fallback chain and the
 * MCP servers — no Room change, no migration) and keeps CodeSathi's v1
 * meaning of "ask": the tool runs and is always shown as a tool card in
 * the chat. There is NO blocking ask dialog in CodeSathi — adding one
 * would change the default behaviour for every existing user, which
 * this step must not do. So the mode and the glob rules decide
 * allow / ask / deny, and the runner enforces them exactly the way it
 * enforces the per-tool rules today: "deny" refuses the call, anything
 * else runs it (writes still always need the diff-card Approve).
 *
 * Precedence, highest first (recorded in README/GOAL):
 *   1. An explicit per-tool rule the user stored (allow / ask / deny).
 *      A stored "deny" always wins — no mode or glob rule can override
 *      it. Only rules the user actually stored count here; the v1
 *      defaults (read/glob/grep allow, write/edit/run/skill ask) are
 *      NOT explicit rules, so they never block a glob rule or a mode.
 *   2. The first matching glob rule, in the order the user listed them
 *      (tool name + target pattern, e.g. read + a `docs/` pattern).
 *   3. The approval mode, by tool kind (see [ApprovalMode]).
 *
 * Plan mode still hard-refuses writes before any of this is consulted
 * ([ToolContext.readOnly] + the write/edit tools themselves), and a
 * file write is never applied without the diff-card Approve — not even
 * under [ApprovalMode.AUTO_ALL] or [ApprovalMode.AUTO_EDITS].
 */
enum class ApprovalMode(val label: String, val desc: String) {
    /** Every tool call asks first (runs, shown as a tool card). The default — behaves exactly like v1. */
    ASK_ALL("Ask for everything", "Every tool call asks first."),
    /** Reads run freely; writes, edits and commands ask first. */
    ASK_WRITES("Ask before changes", "Reads run freely. Writes, edits and commands ask first."),
    /** File edits run freely; commands and network still ask. Writes still need the diff-card Approve. */
    AUTO_EDITS("Auto-approve edits", "File edits run freely. Commands and network still ask."),
    /** Every tool runs without asking. Stored deny rules still apply. */
    AUTO_ALL("Auto-approve everything", "Every tool runs without asking. Deny rules still apply."),
}

/** The three outcomes a permission decision can have. */
enum class PermissionDecision { ALLOW, ASK, DENY }

/** What kind of tool this is, for the approval mode (AgentMT's ToolKind, trimmed to our tool set). */
enum class ToolKind {
    /** read / glob / grep — only reads the project. */
    READ,
    /** write / edit — changes files (still needs the diff-card Approve). */
    WRITE,
    /** run — executes a shell command. */
    EXECUTE,
    /** skill / mcp:* / anything unknown — network or model-driven. */
    OTHER
}

/** Kind of one tool by name. MCP tools (`mcp:<server>:<tool>`) are OTHER. */
fun toolKind(toolName: String): ToolKind = when (toolName) {
    "read", "glob", "grep" -> ToolKind.READ
    "write", "edit" -> ToolKind.WRITE
    "run", "background_process" -> ToolKind.EXECUTE
    else -> ToolKind.OTHER
}

/**
 * The target a glob rule matches against: the file path for read /
 * write / edit, the directory for glob, the query for grep, the command
 * for run, the skill name for skill. MCP tools have no meaningful
 * target, so only a `*` pattern matches them — a glob rule for an MCP
 * tool is written against its name instead (e.g. tool `mcp:*`).
 */
fun toolTarget(toolName: String, args: Map<String, String>): String = when (toolName) {
    "read", "write", "edit" -> args["path"].orEmpty()
    "glob" -> args["dir"].orEmpty()
    "grep" -> args["query"].orEmpty()
    "run" -> (args["command"] ?: args["cmd"]).orEmpty()
    "background_process" -> (args["command"] ?: args["action"]).orEmpty()
    "skill" -> args["name"].orEmpty()
    else -> ""
}

/**
 * One ordered glob rule (Phase 7, Step B2), stored as DataStore JSON.
 * [tool] is a tool name, `*` (every tool), or a glob over tool names
 * (e.g. `mcp:*` — every MCP tool). [pattern] is a glob over the target
 * (see [globToRegex]); blank means `*` (every target). [action] is
 * "allow" | "ask" | "deny". First matching rule wins.
 */
@kotlinx.serialization.Serializable
data class PermissionRule(
    val id: String,
    val tool: String,
    val pattern: String,
    val action: String
)

/**
 * Glob → Regex, adapted from AgentMT's `Workspace.globToRegex` (the
 * user's own code). Supports `**` (any characters, including `/`),
 * `*` (any characters except `/`), `?` (one character except `/`) and
 * `{a,b}` groups. A `**` followed by `/` also matches zero directories, so
 * `docs/` + `**` matches `docs/guide.md` and `docs/deep/guide.md`, and
 * a `**` pattern matches `README.md` at the root too.
 *
 * CodeSathi has no workspace glob of its own (the agent's glob tool
 * lists files, it does not match patterns), so this is the project's
 * only glob dialect — nothing else invents a second one.
 */
fun globToRegex(glob: String): Regex {
    val sb = StringBuilder("^")
    var i = 0
    var inGroup = false
    while (i < glob.length) {
        val c = glob[i]
        when {
            c == '*' && glob.getOrNull(i + 1) == '*' -> {
                if (glob.getOrNull(i + 2) == '/') {
                    sb.append("(?:.*/)?")
                    i += 2
                } else {
                    sb.append(".*")
                    i++
                }
            }
            c == '*' -> sb.append("[^/]*")
            c == '?' -> sb.append("[^/]")
            c == '{' -> {
                sb.append("(?:")
                inGroup = true
            }
            c == '}' && inGroup -> {
                sb.append(")")
                inGroup = false
            }
            c == ',' && inGroup -> sb.append("|")
            ".()+|^$@%\\[]".contains(c) -> sb.append("\\").append(c)
            else -> sb.append(c)
        }
        i++
    }
    return Regex(sb.append("$").toString())
}

object PermissionEngine {

    /**
     * Decide one tool call. [explicitRule] is the per-tool rule the
     * user stored for this tool, or null when they stored none (the v1
     * defaults are NOT passed here — see the file KDoc). [rules] are
     * the glob rules in the user's order; [mode] is the approval mode.
     * Returns the decision plus the glob rule that matched, or null
     * when the mode decided (the UI can say which decided it).
     */
    fun decide(
        tool: String,
        target: String,
        mode: ApprovalMode,
        explicitRule: String?,
        rules: List<PermissionRule>
    ): Pair<PermissionDecision, PermissionRule?> {
        // 1. Explicit per-tool rule — a stored deny always wins.
        when (explicitRule) {
            "allow" -> return PermissionDecision.ALLOW to null
            "deny" -> return PermissionDecision.DENY to null
            "ask" -> return PermissionDecision.ASK to null
        }
        // 2. First matching glob rule, in the user's order.
        for (rule in rules) {
            if (!toolMatches(rule.tool, tool)) continue
            if (!targetMatches(rule.pattern, target, toolKind(tool))) continue
            return when (rule.action) {
                "allow" -> PermissionDecision.ALLOW
                "deny" -> PermissionDecision.DENY
                else -> PermissionDecision.ASK
            } to rule
        }
        // 3. The approval mode, by tool kind.
        val decision = when (mode) {
            ApprovalMode.ASK_ALL -> PermissionDecision.ASK
            ApprovalMode.ASK_WRITES ->
                if (toolKind(tool) == ToolKind.READ) PermissionDecision.ALLOW
                else PermissionDecision.ASK
            ApprovalMode.AUTO_ALL -> PermissionDecision.ALLOW
            ApprovalMode.AUTO_EDITS ->
                if (toolKind(tool) == ToolKind.READ || toolKind(tool) == ToolKind.WRITE)
                    PermissionDecision.ALLOW
                else PermissionDecision.ASK
        }
        return decision to null
    }

    /**
     * Does a rule's tool field match this tool? `*` matches everything,
     * an exact name matches itself, and anything containing a glob
     * character (`*`, `?`, `{`) is matched as a glob — so `mcp:*`
     * matches every MCP tool.
     */
    fun toolMatches(ruleTool: String, tool: String): Boolean {
        if (ruleTool == "*" || ruleTool.equals(tool, ignoreCase = true)) return true
        if (ruleTool.any { it == '*' || it == '?' || it == '{' }) {
            return runCatching { globToRegex(ruleTool).matches(tool) }.getOrDefault(false)
        }
        return false
    }

    /**
     * Does a rule's pattern match this target? Blank, `*` and `**` match
     * everything. File targets (read / write) match as globs over the
     * path (a leading `./` is stripped, as in AgentMT); anything else
     * (commands, queries, skill names) matches as a `*`-wildcard
     * substring pattern, as in AgentMT.
     */
    fun targetMatches(pattern: String, target: String, kind: ToolKind): Boolean {
        if (pattern.isBlank() || pattern == "*" || pattern == "**") return true
        return if (kind == ToolKind.READ || kind == ToolKind.WRITE) {
            runCatching { globToRegex(pattern).matches(target.removePrefix("./")) }
                .getOrDefault(false)
        } else {
            Regex("^" + pattern.split("*").joinToString(".*") { Regex.escape(it) } + "$")
                .matches(target)
        }
    }
}
