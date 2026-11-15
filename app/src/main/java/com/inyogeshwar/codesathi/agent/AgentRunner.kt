package com.inyogeshwar.codesathi.agent

import com.inyogeshwar.codesathi.data.KeyStore
import com.inyogeshwar.codesathi.data.Message
import com.inyogeshwar.codesathi.data.Provider
import com.inyogeshwar.codesathi.data.TokenCounter
import com.inyogeshwar.codesathi.data.supportsVision
import com.inyogeshwar.codesathi.data.visionRefusalMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One file change the agent proposes. Shown as a diff card; nothing is
 *  written until the user taps Approve on that card. */
@Serializable
data class PlannedFile(
    val path: String,
    /** "create" | "edit" */
    val action: String,
    val newContent: String? = null,
    val oldSnippet: String? = null,
    val newSnippet: String? = null
)

/** The plan card shown at the end of a Plan-mode turn. */
@Serializable
data class Plan(
    val goal: String,
    val files: List<PlannedFile> = emptyList(),
    val steps: List<String> = emptyList(),
    val risks: List<String> = emptyList()
)

/** Events the chat screen renders. */
sealed class AgentEvent {
    data class TextChunk(val text: String) : AgentEvent()
    data class PlanReady(val plan: Plan) : AgentEvent()
    data class FileProposal(val file: PlannedFile, val oldContent: String?) : AgentEvent()
    data class ToolRan(val tool: String, val summary: String, val result: String = "") : AgentEvent()
    data class Verified(val message: String) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
    /**
     * One model turn hit a failure and switched to another key or fallback
     * model. [kind] is why the previous key stopped (quota vs rate limit)
     * when the failure was a limit error, null otherwise; [failure] is
     * the full classification (see [Failure]) — the chat notice uses its
     * label ("rate limited", "out of credit", "server error" …) so the
     * user sees WHY the turn switched, not just that it did.
     */
    data class FallbackSwitched(
        val fromProviderId: String,
        val fromModelId: String,
        val toProviderId: String,
        val toModelId: String,
        val kind: LimitKind? = null,
        val failure: Failure? = null
    ) : AgentEvent()
    /**
     * One model turn's token usage (Phase 5, Step 2). [usage] is the
     * provider's real reported counts when it sent any; [estimated] is
     * true when it did not and the counts are the chars÷4 estimate —
     * the dashboard shows estimated rows as estimates, never as real.
     * One event per model turn (a Build message with several tool steps
     * emits one per step), tagged with the provider + model that
     * actually answered that turn (after any fallback switch).
     */
    data class TurnUsage(
        val usage: LlmUsage,
        val providerId: String,
        val modelId: String,
        val estimated: Boolean
    ) : AgentEvent()
    /**
     * A plain informational note for the chat (Phase 7, Step B2 — e.g.
     * a "context too long" retry). The ViewModel shows it as a tool
     * message; it is not an error and does not end the turn.
     */
    data class Notice(val message: String) : AgentEvent()
    object Done : AgentEvent()
}

/** One tool call, from the model's native `tool_calls` or a ```tool block. */
private data class ParsedToolCall(
    val name: String,
    val args: Map<String, String>,
    /**
     * The provider-assigned id of this call (Bedrock Converse toolUseId).
     * Null for fenced ```tool calls, which carry no id — and for native
     * calls from providers that assign none.
     */
    val id: String? = null
)

/**
 * The v1 agent loop.
 *
 * Plan mode: the model is asked for a structured plan (JSON). The plan is
 * shown, never executed. Write/Edit tools are hard-disabled ([ToolContext.readOnly]).
 *
 * Build mode: a real tool-calling loop, bounded to [MAX_STEPS] model turns
 * per user message.
 *  - Each turn the model may call tools, either with native OpenAI-style
 *    `tool_calls` or with fenced blocks:
 *    ```tool
 *    {"tool":"read","args":{"path":"index.html"}}
 *    ```
 *  - Read-only tools (read / glob / grep) run at once and their results
 *    are fed back to the model for the next turn.
 *  - A write / edit call never executes directly: the runner emits a
 *    [AgentEvent.FileProposal] and STOPS — the UI shows the diff card and
 *    only calls [applyFileProposal] after the user approves. The ViewModel
 *    then resumes the loop with a continuation message.
 *  - Per-tool permission rules ([toolRule]) gate every call: "deny" means
 *    the call is refused and the model is told so; "allow" runs a read-only
 *    tool without asking; "ask" runs it too, but the run is always visible
 *    as a tool card in the chat. Writes are never auto-applied: even with
 *    rule "allow", a write still needs the diff-card Approve.
 *
 * Light verification after a write: file exists, non-empty, and (for .html)
 * contains a matching <html> tag. Full diagnostics is Phase 2.
 *
 * Fallback (Phase 2, Step 1; routing rework Phase 7, Step B1): when
 * [router] is set, each model turn tries the router's ordered
 * candidates — other keys of the same provider + model first, then the
 * user's fallback chain (fallback mode), or only the session model's
 * keys (pinned mode). A failed attempt is classified by
 * [FailureClassifier] (429 rate limit vs daily quota vs 402 / out of
 * credit vs auth vs context-too-long vs 5xx vs capability mismatch vs
 * network) and the key cools down for that kind's cooldown (rate 60 s,
 * daily quota 1 h, out of credit 6 h, server 20 s, network 15 s) —
 * NOT until midnight any more. The turn retries with the next
 * candidate and emits [AgentEvent.FallbackSwitched] with the failure
 * reason, so the chat can say what changed and why. Bounded, the way
 * AgentMT's RouteRunner bounds it: at most [RouteBounds.MAX_ROUTE_ATTEMPTS]
 * attempts and [RouteBounds.PRE_FIRST_TOKEN_DEADLINE_MS] before the
 * first token per model turn. A turn is only retried when nothing was
 * emitted yet — if a stream fails halfway, the partial reply stays
 * exactly as shown and the turn stops ([MidStreamException]: a
 * half-streamed answer is never replayed on another model; the user
 * sends a message to continue). Non-retryable errors fail the turn
 * as before. Without a router the old fallback-only path is kept
 * (limit errors only), so the approve-only runner is unaffected.
 * Reasoning effort (Phase 3, Step 2): each attempt looks up the effort
 * for its OWN provider + model, so a fallback switch sends the new
 * model's effort (or nothing, when that model is at "default") —
 * the first attempt's effort is never reused for another model.
 *
 * Usage (Phase 5, Step 2): every successful model turn emits one
 * [AgentEvent.TurnUsage] — the provider's real reported counts when it
 * sent any ([ChatStreamEvent.Usage]), otherwise the chars÷4 estimate
 * over the prompt sent and the text received, flagged estimated. The
 * ViewModel persists each one, so totals are always derivable from
 * stored rows and never only in memory.
 *
 * MCP tools (Phase 2, Step 3): tools whose names start with "mcp:" are
 * listed in the Build system prompt and dispatched through the same
 * loop and the same [toolRule] gating as the built-ins — no
 * special-casing. A denied MCP tool is refused the same way.
 *
 * Skills (Phase 2, Step 4): the "skill" tool loads one skill's
 * instructions (Markdown in getFilesDir()/skills/) and returns them as
 * its result, so the model then follows them for the task. Enabled
 * skills are listed by name in the Build system prompt, exactly like
 * the MCP tools. A skill never writes files by itself — anything it
 * asks for still goes through write / edit and the diff-card Approve.
 *
 * Agent Run (Phase 6, Step 1): the "run" tool executes a shell command
 * in the project folder through the terminal screen's TerminalRunner
 * and returns its exit code + merged output. It is gated by the same
 * allow / ask / deny rule as every other tool (default "ask"). After a
 * Build turn, a non-zero run consumes one of [MAX_FIX_ATTEMPTS] fix
 * attempts for that turn; once all 3 are used, further `run` calls in
 * the turn are refused without executing, so the loop can never run
 * unbounded. A successful (exit 0) run consumes nothing.
 *
 * Images (Phase 6, Step 2): a user message may carry attached images
 * ([LlmMessage.images], downscaled + JPEG re-encoded by ImageStore).
 * History messages keep theirs through [imagesFor], so the model
 * re-sees an old screenshot when the turn re-sends the history,
 * exactly like it re-reads the old text. Before any attempt is tried,
 * [visionAttempts] drops attempts whose model cannot see images —
 * a vision request must never reach a text-only model, even through a
 * fallback switch; when no attempt can see images the turn is refused
 * with a plain message. The per-turn usage estimate adds
 * [IMAGE_TOKENS_ESTIMATE] per image (only when the provider reports
 * no usage — real counts already include image tokens).
 *
 * Attachments (Phase 7, Step B3): a user message may also carry PDF
 * and text files ([LlmMessage.pdfs], [attachmentsFor]). Text files
 * are inlined into the prompt every turn (text is cheap); images and
 * PDFs are binary payloads, so only the 2 most recent user messages
 * resend them ([resendBinariesFor] — AgentMT's rule, adopted here for
 * images too: before this step every history image was re-sent on
 * every turn). Older messages get a short "[shared earlier]" note
 * instead. A PDF is raw bytes, base64-encoded — no text is extracted
 * on-device — and only OpenAI / Anthropic-kind models receive one
 * (the ViewModel refuses a PDF for local and Bedrock models).
 * Known quirk, pre-existing for images: the newest user message is
 * sent twice in Build — once as the last history message (with its
 * images / attachments resolved through [imagesFor] /
 * [attachmentsFor]) and once as the new user message (with
 * [userImages] / [userPdfs]) — so its binaries go out twice in one
 * turn. Deliberately left as-is: the raw text + expanded text both
 * reaching the model is the established slash-command behaviour, and
 * changing it here would change every Build prompt.
 *
 * Permissions (Phase 7, Step B2): [toolDecision] replaces the plain
 * per-tool rule when the ViewModel supplies it — the stored per-tool
 * rule wins, then the first matching glob rule, then the approval mode
 * (see [PermissionEngine]). "Ask" still means "run and show as a tool
 * card" (there is no blocking ask dialog in CodeSathi); only "deny"
 * refuses a call. Writes always need the diff-card Approve.
 *
 * Compaction (Phase 7, Step B2): the context window a turn is measured
 * against is capped at the smallest known window among the session
 * model + fallback chain ([Compaction.effectiveContextTokens]); a
 * "context too long" failure with nothing emitted yet compacts harder
 * once ([onContextTooLong]) and retries the same step instead of
 * failing the turn. The kept tail never splits a tool call/result pair
 * ([Compaction.splitForSummary]).
 *
 * Resume (Phase 7, Step B2): [CONTINUE_TEXT] is the message the
 * ViewModel sends to pick a turn up where it stopped — the history
 * already holds every finished step and tool result, so nothing is
 * resent from scratch and the partial text is never replayed.
 */
class AgentRunner(
    private val llm: LlmClient,
    private val tools: List<Tool>,
    private val fallback: FallbackEngine? = null,
    /**
     * Router (Phase 7, Step B1): builds the ordered candidates
     * (pinned vs fallback mode), records per-key health and applies
     * the per-kind cooldowns. When null, the runner falls back to the
     * plain [fallback] engine with limit-only retries (the old path).
     */
    private val router: ModelRouter? = null,
    /**
     * Pinned routing (Phase 7, Step B1): [RoutingMode.PINNED] tries only
     * the session model's keys — the fallback chain is not walked.
     * Read fresh per turn by the ViewModel from the routing-mode setting.
     */
    private val routingMode: RoutingMode = RoutingMode.FALLBACK,
    /**
     * Reasoning effort for one (provider, model), or null when the user
     * left it at "default". Looked up fresh for EVERY attempt — a
     * fallback switch to another model must send that model's effort,
     * never the first attempt's. Null means no effort is ever sent
     * (the plain client path, e.g. the approve-only runner).
     */
    private val effortFor: (suspend (providerId: String, modelId: String) -> String?)? = null,
    /**
     * Azure OpenAI resource name for one provider id (Phase 4). Looked up
     * fresh for EVERY attempt, like [effortFor] — a fallback switch to
     * Azure must use Azure's resource name, never another provider's.
     * Null means the plain client path (no Azure provider in the run).
     */
    private val azureResourceFor: (suspend (providerId: String) -> String?)? = null,
    /**
     * AWS credentials for Bedrock (Phase 4). Looked up fresh for EVERY
     * attempt, like [effortFor] — only Bedrock attempts use it; every
     * other kind passes null and is unaffected. Null means the plain
     * client path (no Bedrock provider in the run).
     */
    private val awsCredentialsFor: (suspend () -> KeyStore.AwsCredentials?)? = null,
    /**
     * Target-aware tool decision (Phase 7, Step B2): (tool name, args)
     * -> "allow" | "ask" | "deny", decided by [PermissionEngine] from
     * the stored per-tool rule, the glob rules and the approval mode.
     * When set it replaces [toolRule] for Build turns — the decision
     * function knows the call's target (file path / command), which
     * the glob rules need. When null, [toolRule] is used exactly as
     * before (the approve-only runner and older callers).
     */
    private val toolDecision: ((String, Map<String, String>) -> String)? = null,
    /**
     * Harder compaction for a "context too long" failure (Phase 7,
     * Step B2 — AgentMT's "summarise harder once and redo this step"):
     * called once per Build step, returns the new summary Markdown, or
     * null when nothing more could be compacted. The step is then
     * retried once with the new summary instead of failing the turn.
     * Null means no retry (the old next-candidate / error path runs).
     */
    private val onContextTooLong: (suspend () -> String?)? = null
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val planSystemPrompt = """
        You are CodeSathi, an on-device coding assistant. You are in PLAN mode:
        read-only. Never write files. Answer with a JSON plan only, in this shape:
        {"goal":"...","files":[{"path":"index.html","action":"create","newContent":"..."}],
         "steps":["..."],"risks":["..."]}
        Keep file contents complete and runnable. Do not add any text outside the JSON.
    """.trimIndent()

    /**
     * Build-mode system prompt. [mcpTools] are the enabled MCP tools
     * (namespaced "mcp:<server>:<tool>") and [skills] are the enabled
     * skills — both are listed here so the model knows it can call
     * them, exactly like the built-in tools.
     */
    private fun buildSystemPrompt(mcpTools: List<Tool>, skills: List<Skill> = emptyList()): String {
        val base = """
        You are CodeSathi, an on-device coding assistant in BUILD mode.
        You have these tools — call them with a fenced block, one tool per block:
        ```tool
        {"tool":"read","args":{"path":"index.html"}}
        ```
        ```tool
        {"tool":"glob","args":{"dir":""}}
        ```
        ```tool
        {"tool":"grep","args":{"query":"TODO"}}
        ```
        ```tool
        {"tool":"write","args":{"path":"index.html","content":"<!DOCTYPE html>..."}}
        ```
        ```tool
        {"tool":"edit","args":{"path":"index.html","old":"<title>Old</title>","new":"<title>New</title>"}}
        ```
        ```tool
        {"tool":"skill","args":{"name":"code-review"}}
        ```
        ```tool
        {"tool":"run","args":{"command":"npm test"}}
        ```
        ```tool
        {"tool":"background_process","args":{"action":"start","command":"npm run dev"}}
        ```
        Use read / glob / grep first to see the project before changing it.
        To change a file, call write (new file) or edit (change a snippet).
        Use run to execute a command in the project folder — run tests, a
        build, or a small script — and read its exit code and output. A
        command that exits non-zero means something failed: fix the code
        and run it again. You have at most 3 commands that exit non-zero
        per turn; after the third, run is refused for the rest of the turn,
        so make each re-run count. A "Permission denied" in the output
        means the shell cannot touch that file (the folder grant gives the
        shell no raw-filesystem access) — say so and use the read / write
        tools instead. Use background_process to start a long-running
        command (a dev server, a watcher) instead of run — run waits for
        the command to finish, background_process returns at once with
        an id; list it, read its output, or stop it with the same tool.
        At most 3 background processes may run at once.
        The user approves every file change before it is applied, so propose
        one file at a time. When the work is done, finish with a short plain
        text summary and no more tool calls. Do not invent files that were
        not asked for.
        A skill is a named way of doing one job well: call the skill tool
        with the skill's name and it returns that skill's instructions —
        follow them for the task at hand, then continue with the other
        tools. A skill never writes files by itself; any file change it
        asks for still goes through write / edit and needs the user's
        Approve. When the user's request matches a skill below, use it.
    """.trimIndent()
        var prompt = base
        if (skills.isNotEmpty()) {
            val listed = skills.joinToString("\n") { "- ${it.id}: ${it.description}" }
            prompt += "\n\nEnabled skills — call the skill tool with the skill's name to use one:\n$listed"
        }
        if (mcpTools.isNotEmpty()) {
            val listed = mcpTools.joinToString("\n") { "- ${it.name}: ${it.description}" }
            prompt += "\n\nYou also have these MCP tools from connected servers. Call them " +
                "exactly like the built-in tools, one fenced ```tool block per call, " +
                "with string arguments:\n$listed"
        }
        return prompt
    }

    companion object {
        /** Max model turns per user message — the Stop button cancels sooner. */
        const val MAX_STEPS = 10

        /**
         * Resume text (Phase 7, Step B2 — AgentMT's CONTINUE_TEXT,
         * adapted). Sent as the next user message after an error, a
         * limit or a stop: the history already holds every finished
         * step and tool result, so the model picks up where it stopped
         * instead of starting the last message over from scratch. The
         * mid-stream partial text stays as shown and is never replayed
         * (Step B1's rule). The ViewModel sends this WITHOUT storing
         * it as a user message — the chat shows no extra bubble, the
         * model still gets the instruction as the latest user message.
         */
        const val CONTINUE_TEXT = "Continue from where you stopped. " +
            "Earlier steps and tool results above are done; do not repeat them. " +
            "Check the last results, then carry on with the remaining work."

        /**
         * Max commands that may exit non-zero in one Build turn (Phase 6,
         * Step 1 — auto-fix bound). Each non-zero `run` result consumes
         * one attempt; once all 3 are used, further `run` calls in the
         * turn are refused without executing. The loop can never run
         * unbounded, and a successful (exit 0) run consumes nothing.
         */
        const val MAX_FIX_ATTEMPTS = 3

        /**
         * Rough token cost of one attached image for the chars÷4
         * estimate (Phase 6, Step 2): a 1568 px image is roughly
         * 1,300–2,000 vision tokens depending on the provider, so 1,500
         * is the middle. Only used when the provider reports no usage —
         * real counts, when sent, already include the image tokens.
         */
        const val IMAGE_TOKENS_ESTIMATE = 1_500
    }

    /**
     * Attempts that may actually be tried for this turn (Phase 6,
     * Step 2): when the messages carry images, only attempts whose
     * model can see images ([supportsVision]) survive — a vision
     * request must never go to a text-only model, even through a
     * fallback switch. With no images the list is unchanged.
     */
    private fun visionAttempts(
        attempts: List<FallbackAttempt>,
        messages: List<LlmMessage>
    ): List<FallbackAttempt> {
        if (messages.none { it.images.isNotEmpty() }) return attempts
        return attempts.filter { supportsVision(it.provider, it.modelId) }
    }

    fun runPlan(
        provider: Provider,
        modelId: String,
        apiKey: String?,
        history: List<Message>,
        userGoal: String,
        project: SafProject,
        folderName: String,
        /** Images attached to this message (Phase 6, Step 2). */
        userImages: List<LlmImage> = emptyList(),
        /** PDFs attached to this message (Phase 7, Step B3). */
        userPdfs: List<LlmPdf> = emptyList()
    ): Flow<AgentEvent> = flow {
        val files = try {
            project.listFiles().take(30).joinToString(", ")
        } catch (e: Exception) {
            "(could not list folder: ${e.message})"
        }
        val messages = listOf(
            LlmMessage("system", planSystemPrompt),
            LlmMessage(
                "user",
                "Project folder: $folderName. Existing files: $files\n\nGoal: $userGoal",
                images = userImages,
                pdfs = userPdfs
            )
        )
        val rawAttempts = when {
            router != null -> router.route(provider, modelId, routingMode)
            fallback != null -> fallback.attemptsFor(provider, modelId)
            else -> listOf(FallbackAttempt(provider, modelId, apiKey, null))
        }
        if (rawAttempts.isEmpty()) {
            emit(AgentEvent.Error("All keys and fallback models are cooling down or unavailable right now. Try again shortly, or reset the limit flags in Providers."))
            return@flow
        }
        // Vision (Phase 6, Step 2): with images attached, only
        // attempts whose model can see images may run. An empty list
        // here means every attempt was filtered out — the plain refusal
        // names a verified vision model instead of sending a request
        // the model cannot answer.
        val attempts = visionAttempts(rawAttempts, messages)
        if (attempts.isEmpty()) {
            emit(AgentEvent.Error(visionRefusalMessage(provider, modelId)))
            return@flow
        }
        var fullText: String? = null
        var lastLimitError: Exception? = null
        var lastFailure: Failure? = null
        var stoppedReason: String? = null
        // The turn that answered, and the usage it reported (Phase 5,
        // Step 2): set only by the successful attempt, so a failed
        // attempt's partial stream can never be recorded.
        var usedProviderId = provider.id
        var usedModelId = modelId
        var reportedUsage: LlmUsage? = null
        // Bounded attempts + pre-first-token deadline (Phase 7, Step B1,
        // RouteBounds): at most 4 attempts, 180 s before the first token.
        val turnStartMs = System.currentTimeMillis()
        for ((index, attempt) in attempts.withIndex()) {
            if (index >= RouteBounds.MAX_ROUTE_ATTEMPTS) {
                stoppedReason = "Tried ${RouteBounds.MAX_ROUTE_ATTEMPTS} models/keys and none could answer."
                break
            }
            if (index > 0 && System.currentTimeMillis() - turnStartMs > RouteBounds.PRE_FIRST_TOKEN_DEADLINE_MS) {
                stoppedReason = "Stopped after ${RouteBounds.PRE_FIRST_TOKEN_DEADLINE_MS / 1000} s without a first token."
                break
            }
            val full = StringBuilder()
            reportedUsage = null
            val attemptStartMs = System.currentTimeMillis()
            try {
                // Effort is looked up for THIS attempt's model, so a
                // fallback switch sends the new model's effort (or none).
                val effort = effortFor?.invoke(attempt.provider.id, attempt.modelId)
                // Azure resource, looked up the same way — only for Azure
                // attempts; every other kind passes null and is unaffected.
                val azureResource = if (attempt.provider.kind == "azure") {
                    azureResourceFor?.invoke(attempt.provider.id)
                } else null
                // AWS credentials, looked up the same way — only for
                // Bedrock attempts; every other kind passes null.
                val awsCreds = if (attempt.provider.kind == "bedrock") {
                    awsCredentialsFor?.invoke()
                } else null
                llm.streamChatEvents(attempt.provider, attempt.modelId, attempt.apiKey, messages, effort, azureResource, awsCreds).collect { event ->
                    when (event) {
                        is ChatStreamEvent.Text -> {
                            full.append(event.chunk)
                            emit(AgentEvent.TextChunk(event.chunk))
                        }
                        is ChatStreamEvent.Usage -> reportedUsage = event.usage
                        is ChatStreamEvent.ToolCall -> Unit // Plans make no tool calls.
                    }
                }
                fullText = full.toString()
                usedProviderId = attempt.provider.id
                usedModelId = attempt.modelId
                router?.recordSuccess(
                    attempt,
                    System.currentTimeMillis() - attemptStartMs,
                    reportedUsage?.promptTokens?.toLong() ?: 0,
                    reportedUsage?.completionTokens?.toLong() ?: 0
                )
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failure = classifyFailure(e)
                lastFailure = failure
                if (full.isNotEmpty()) {
                    // Mid-stream failure (Phase 7, Step B1): text was
                    // already shown, so the turn must NOT retry on
                    // another model — the half-streamed answer would be
                    // replayed. The partial reply stays as shown; the
                    // user sends a message to continue.
                    val mid = MidStreamException(failure, e)
                    emit(AgentEvent.Error("The reply stopped halfway (${mid.failure.label}) — the part above is kept. Send a message to continue."))
                    return@flow
                }
                val next = attempts.getOrNull(index + 1)
                val canRetry = failure.fallback && (router != null || fallback != null) &&
                    next != null && index + 1 < RouteBounds.MAX_ROUTE_ATTEMPTS
                if (canRetry) {
                    if (router != null) {
                        router.recordFailure(attempt, failure)
                    } else {
                        failure.limitKind()?.let { fallback?.markExhausted(attempt, it) }
                    }
                    lastLimitError = e
                    emit(
                        AgentEvent.FallbackSwitched(
                            attempt.provider.id, attempt.modelId,
                            next.provider.id, next.modelId,
                            kind = failure.limitKind(), failure = failure
                        )
                    )
                    // A server error or timeout gets a short backoff
                    // before the next candidate (AgentMT: 400 ms,
                    // doubling to 4 s).
                    if (failure.kind == FailureKind.SERVER || failure.kind == FailureKind.TIMEOUT) {
                        delay((400L shl index.coerceAtMost(4)).coerceAtMost(4000))
                    }
                    continue
                }
                emit(AgentEvent.Error("Plan failed: ${e.message}"))
                return@flow
            }
        }
        val full = fullText
        if (full == null) {
            val reason = stoppedReason
                ?: "Plan failed: ${lastLimitError?.message ?: lastFailure?.message ?: "all keys and fallback models are cooling down."}"
            emit(AgentEvent.Error(reason))
            return@flow
        }
        // Usage for this turn (Phase 5, Step 2): the provider's real
        // counts when it sent any, otherwise the chars÷4 estimate over
        // the prompt sent and the reply received. Recorded even when
        // the reply is not a valid plan — the tokens were still spent.
        val realUsage = reportedUsage
        emit(
            AgentEvent.TurnUsage(
                realUsage ?: estimateUsage(messages, full),
                usedProviderId, usedModelId, realUsage == null
            )
        )
        val plan = parsePlan(full)
        if (plan != null) emit(AgentEvent.PlanReady(plan))
        else emit(AgentEvent.Error("The model did not return a plan in the expected format. Try again or rephrase the goal."))
        emit(AgentEvent.Done)
    }

    fun runBuild(
        provider: Provider,
        modelId: String,
        apiKey: String?,
        history: List<Message>,
        userMessage: String,
        project: SafProject,
        toolContext: ToolContext,
        toolRule: (String) -> String = { "ask" },
        summaryMarkdown: String? = null,
        contextTokens: Int = Compaction.DEFAULT_CONTEXT_TOKENS,
        skills: List<Skill> = emptyList(),
        /** Images attached to this message (Phase 6, Step 2). */
        userImages: List<LlmImage> = emptyList(),
        /** PDFs attached to this message (Phase 7, Step B3). */
        userPdfs: List<LlmPdf> = emptyList(),
        /**
         * Images attached to a HISTORY message (Phase 6, Step 2):
         * looked up fresh per turn, so an old message's images are
         * re-sent with the history exactly like its text. Null when the
         * caller has no images to resolve (plain text path).
         */
        imagesFor: (suspend (Message) -> List<LlmImage>)? = null,
        /**
         * Attachments of a HISTORY message (Phase 7, Step B3): the
         * message's text files inlined + its PDFs as binary parts.
         * [binary] is true only for the 2 most recent user messages
         * (see [resendBinariesFor]) — with false, PDFs become a short
         * "[shared earlier]" note and only the text is inlined.
         * Null when the caller has no attachments to resolve.
         */
        attachmentsFor: (suspend (Message, Boolean) -> AttachmentMaterial)? = null,
        /**
         * Which history messages resend their binaries this turn
         * (Phase 7, Step B3 — AgentMT's rule): only the 2 most recent
         * user messages that carry images or PDFs. Every other history
         * message keeps its text (and its inlined text attachments)
         * but no binary payload. Defaults to "every message", the old
         * behaviour, when the caller does not supply it.
         */
        resendBinariesFor: (Message) -> Boolean = { true }
    ): Flow<AgentEvent> = flow {
        // Compaction: only what is sent changes; history in Room is untouched.
        // Built by a local function (not a one-off list) because a
        // "context too long" failure rebuilds it from a harder summary
        // and retries the step (Phase 7, Step B2).
        suspend fun buildMessages(summary: String?): MutableList<LlmMessage> {
            val toSend = Compaction.buildSendMessages(history, summary, contextTokens)
            val list = mutableListOf(
                LlmMessage("system", buildSystemPrompt(tools.filter { it.name.startsWith("mcp:") }, skills))
            )
            // History messages keep their images (Phase 6, Step 2): the
            // model re-sees an old screenshot when the turn re-sends the
            // history, exactly like it re-reads the old text — but only
            // for the 2 most recent user messages (Phase 7, Step B3):
            // older images and PDFs are not re-sent, they become a note.
            // Text attachments are inlined every turn (text is cheap).
            for (msg in toSend) {
                val resend = resendBinariesFor(msg)
                val imgs = if (resend) imagesFor?.invoke(msg).orEmpty() else emptyList()
                val att = attachmentsFor?.invoke(msg, resend)
                val pdfs = if (resend) att?.pdfs.orEmpty() else emptyList()
                var text = msg.text
                if (att != null && att.extraText.isNotEmpty()) text += att.extraText
                if (!resend && (decodeImageNames(msg.imagesJson).isNotEmpty() ||
                        decodeAttachments(msg.attachmentsJson).any { it.kind == "image" || it.kind == "pdf" })
                ) {
                    text += "\n[Attached images / PDFs from this message were shared earlier and are not re-sent.]"
                }
                list += LlmMessage(msg.role, text, images = imgs, pdfs = pdfs)
            }
            list += LlmMessage("user", userMessage, images = userImages, pdfs = userPdfs)
            return list
        }
        var llmMessages = buildMessages(summaryMarkdown)

        var steps = 0
        // Auto-fix budget (Phase 6, Step 1): non-zero `run` results used
        // up so far in THIS turn. Resets on the next user message (and on
        // a post-approval resume, which is a new runBuild call).
        var fixAttemptsUsed = 0
        while (steps < MAX_STEPS) {
            steps++
            // --- One model turn: stream text, collect any native tool calls. ---
            // On a limit error the turn retries with the next key / fallback
            // model instead of failing — but only when nothing was emitted
            // yet, so the chat never shows a duplicated reply.
            val rawAttempts = when {
                router != null -> router.route(provider, modelId, routingMode)
                fallback != null -> fallback.attemptsFor(provider, modelId)
                else -> listOf(FallbackAttempt(provider, modelId, apiKey, null))
            }
            // Vision (Phase 6, Step 2): with images attached, only
            // attempts whose model can see images may run. When every
            // attempt was filtered out the turn is refused with the
            // plain message instead of sending a request the model
            // cannot answer; an empty raw list keeps the old behaviour
            // (the loop below reports exhaustion).
            val attempts = visionAttempts(rawAttempts, llmMessages)
            if (attempts.isEmpty() && llmMessages.any { it.images.isNotEmpty() }) {
                emit(AgentEvent.Error(visionRefusalMessage(provider, modelId)))
                return@flow
            }
            val turnText = StringBuilder()
            val nativeCalls = mutableListOf<ParsedToolCall>()
            // The raw native calls (with their provider ids) — the
            // assistant message carries these back to Bedrock so each
            // tool result can be paired by id. Kept in step with
            // nativeCalls: only added when parseToolArgs succeeds.
            val nativeToolCalls = mutableListOf<LlmToolCall>()
            var turnOk = false
            var lastLimitError: Exception? = null
            var lastFailure: Failure? = null
            var stoppedReason: String? = null
            // The attempt that answered this step, and the usage it
            // reported (Phase 5, Step 2). Cleared per attempt, set only
            // on success, so a failed attempt can never be recorded.
            var usedProviderId = provider.id
            var usedModelId = modelId
            var stepUsage: LlmUsage? = null
            // Bounded attempts + pre-first-token deadline (Phase 7,
            // Step B1, RouteBounds): at most 4 attempts, 180 s before
            // the first token, per model turn (per step). A while loop
            // (not for/withIndex) because a "context too long" failure
            // retries the SAME attempt once after harder compaction
            // (Phase 7, Step B2) — the index only advances when moving
            // on to the next candidate.
            val turnStartMs = System.currentTimeMillis()
            var attemptIndex = 0
            // One harder-compaction retry per step (Phase 7, Step B2).
            var contextRetryUsed = false
            while (attemptIndex < attempts.size) {
                val index = attemptIndex
                val attempt = attempts[index]
                if (index >= RouteBounds.MAX_ROUTE_ATTEMPTS) {
                    stoppedReason = "Tried ${RouteBounds.MAX_ROUTE_ATTEMPTS} models/keys and none could answer."
                    break
                }
                if (index > 0 && System.currentTimeMillis() - turnStartMs > RouteBounds.PRE_FIRST_TOKEN_DEADLINE_MS) {
                    stoppedReason = "Stopped after ${RouteBounds.PRE_FIRST_TOKEN_DEADLINE_MS / 1000} s without a first token."
                    break
                }
                turnText.clear()
                nativeCalls.clear()
                nativeToolCalls.clear()
                stepUsage = null
                val attemptStartMs = System.currentTimeMillis()
                try {
                    // Effort is looked up for THIS attempt's model, so a
                    // fallback switch sends the new model's effort (or none).
                    val effort = effortFor?.invoke(attempt.provider.id, attempt.modelId)
                    // Azure resource, looked up the same way — only for Azure
                    // attempts; every other kind passes null and is unaffected.
                    val azureResource = if (attempt.provider.kind == "azure") {
                        azureResourceFor?.invoke(attempt.provider.id)
                    } else null
                    // AWS credentials, looked up the same way — only for
                    // Bedrock attempts; every other kind passes null.
                    val awsCreds = if (attempt.provider.kind == "bedrock") {
                        awsCredentialsFor?.invoke()
                    } else null
                    llm.streamChatEvents(attempt.provider, attempt.modelId, attempt.apiKey, llmMessages, effort, azureResource, awsCreds).collect { event ->
                        when (event) {
                            is ChatStreamEvent.Text -> {
                                turnText.append(event.chunk)
                                emit(AgentEvent.TextChunk(event.chunk))
                            }
                            is ChatStreamEvent.ToolCall -> {
                                parseToolArgs(event.call.argumentsJson)?.let { args ->
                                    nativeCalls += ParsedToolCall(event.call.name, args, event.call.id)
                                    nativeToolCalls += event.call
                                }
                            }
                            is ChatStreamEvent.Usage -> stepUsage = event.usage
                        }
                    }
                    usedProviderId = attempt.provider.id
                    usedModelId = attempt.modelId
                    router?.recordSuccess(
                        attempt,
                        System.currentTimeMillis() - attemptStartMs,
                        stepUsage?.promptTokens?.toLong() ?: 0,
                        stepUsage?.completionTokens?.toLong() ?: 0
                    )
                    turnOk = true
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val failure = classifyFailure(e)
                    lastFailure = failure
                    // A stream that already produced text or tool calls
                    // must never be retried on another model — the
                    // half-streamed answer would be replayed
                    // ([MidStreamException]). The partial reply stays as
                    // shown; the user sends a message to continue.
                    if (turnText.isNotEmpty() || nativeCalls.isNotEmpty()) {
                        val mid = MidStreamException(failure, e)
                        emit(AgentEvent.Error("The reply stopped halfway (${mid.failure.label}) — the part above is kept. Send a message to continue."))
                        return@flow
                    }
                    // "Context too long" (Phase 7, Step B2 — AgentMT's
                    // "summarise harder once and redo this step"): the
                    // next candidate would hit the same oversized
                    // prompt, so compact harder and retry THIS attempt
                    // once instead. Only when nothing was emitted yet
                    // and the retry has not been used for this step; a
                    // null result (nothing more to compact) falls
                    // through to the normal next-candidate path below.
                    if (failure.kind == FailureKind.CONTEXT && !contextRetryUsed && onContextTooLong != null) {
                        val harderSummary = onContextTooLong()
                        if (harderSummary != null) {
                            contextRetryUsed = true
                            llmMessages = buildMessages(harderSummary)
                            emit(AgentEvent.Notice("Context too long — summarised the earlier chat more tightly and retrying this step."))
                            continue
                        }
                    }
                    val next = attempts.getOrNull(index + 1)
                    val canRetry = failure.fallback && (router != null || fallback != null) &&
                        next != null && index + 1 < RouteBounds.MAX_ROUTE_ATTEMPTS
                    if (canRetry) {
                        if (router != null) {
                            router.recordFailure(attempt, failure)
                        } else {
                            failure.limitKind()?.let { fallback?.markExhausted(attempt, it) }
                        }
                        lastLimitError = e
                        emit(
                            AgentEvent.FallbackSwitched(
                                attempt.provider.id, attempt.modelId,
                                next.provider.id, next.modelId,
                                kind = failure.limitKind(), failure = failure
                            )
                        )
                        if (failure.kind == FailureKind.SERVER || failure.kind == FailureKind.TIMEOUT) {
                            delay((400L shl index.coerceAtMost(4)).coerceAtMost(4000))
                        }
                        attemptIndex++
                        continue
                    }
                    emit(AgentEvent.Error("Build failed: ${e.message}"))
                    return@flow
                }
            }
            if (!turnOk) {
                val reason = stoppedReason
                    ?: "Build failed: ${lastLimitError?.message ?: lastFailure?.message ?: "all keys and fallback models are cooling down."}"
                emit(AgentEvent.Error(reason))
                return@flow
            }
            val text = turnText.toString()

            // Usage for this step (Phase 5, Step 2): the provider's real
            // counts when it sent any, otherwise the chars÷4 estimate
            // over the prompt sent for this step and the text received.
            // The prompt is the whole llmMessages list as it stood for
            // this step — a later step's longer prompt is its own row,
            // exactly as the provider bills it. Recorded even when the
            // step pauses for a file approval — the tokens were spent.
            val realUsage = stepUsage
            emit(
                AgentEvent.TurnUsage(
                    realUsage ?: estimateUsage(llmMessages, text),
                    usedProviderId, usedModelId, realUsage == null
                )
            )

            // --- Gather tool calls: native ones first, then fenced ```tool blocks. ---
            val calls = mutableListOf<ParsedToolCall>()
            calls += nativeCalls
            val seen = calls.map { it.name to it.args }.toSet()
            parseFencedToolCalls(text).forEach { call ->
                if ((call.name to call.args) !in seen) calls += call
            }

            // Structured tool pairing (Bedrock, Phase 4): only when every
            // call of this turn carried a provider-assigned id. Then the
            // assistant message carries the toolUses and the feedback goes
            // back paired by id; otherwise both stay plain text, exactly
            // as before Phase 4 — a toolUse without a matching toolResult
            // would make Bedrock reject the next request, so a mixed turn
            // (native + fenced calls) must not send any toolUse block.
            val structured = calls.isNotEmpty() && calls.all { it.id != null }
            if (text.isNotBlank() || structured) {
                llmMessages += LlmMessage(
                    "assistant", text,
                    toolUses = if (structured) nativeToolCalls else null
                )
            }

            if (calls.isEmpty()) {
                // No tool calls: maybe a legacy direct JSON file proposal.
                val proposal = parseProposal(text)
                if (proposal != null) {
                    val old = if (proposal.action == "edit") project.readText(proposal.path) else null
                    emit(AgentEvent.FileProposal(proposal, old))
                }
                emit(AgentEvent.Done)
                return@flow
            }

            // --- Execute the calls of this turn, in order. ---
            val results = StringBuilder()
            // Per-call results in call order, for the structured Bedrock
            // feedback (Phase 4): each entry pairs the call with the text
            // its toolResult block must carry. A write/edit proposal pauses
            // the turn, so it never gets an entry — and the feedback below
            // never runs for that turn.
            val callResults = mutableListOf<Pair<ParsedToolCall, String>>()
            var pausedForApproval = false
            for (call in calls) {
                // Gating (Phase 7, Step B2): the target-aware decision
                // (stored per-tool rule > glob rule > approval mode)
                // when the ViewModel supplied one, else the plain
                // per-tool rule exactly as before. "deny" refuses the
                // call before anything runs; "ask" runs it and it is
                // always shown as a tool card. Writes still need the
                // diff-card Approve either way (see below).
                val rule = toolDecision?.invoke(call.name, call.args) ?: toolRule(call.name)
                if (rule == "deny") {
                    val line = "[${call.name}] Denied by your tool permission rules."
                    results.appendLine(line)
                    callResults += call to line
                    continue
                }
                // Auto-fix bound (Phase 6, Step 1): once all 3 non-zero
                // runs of this turn are used, refuse further `run` calls
                // WITHOUT executing them — the loop can never run
                // unbounded, and the model is told to summarise instead.
                if (call.name == "run" && fixAttemptsUsed >= MAX_FIX_ATTEMPTS) {
                    val line = "[run] Refused: 3 commands already exited non-zero this turn " +
                        "(max 3 fix attempts). Do not run another command — summarise what " +
                        "failed and what to try next."
                    results.appendLine(line)
                    callResults += call to line
                    continue
                }
                when (call.name) {
                    "write", "edit" -> {
                        // Never execute a write directly: propose it and pause
                        // for the user's Approve on the diff card.
                        val proposal = toolCallToProposal(call)
                        if (proposal == null) {
                            val line = "[${call.name}] Invalid arguments — need a path."
                            results.appendLine(line)
                            callResults += call to line
                            continue
                        }
                        val old = if (proposal.action == "edit") project.readText(proposal.path) else null
                        emit(AgentEvent.FileProposal(proposal, old))
                        pausedForApproval = true
                        break
                    }
                    else -> {
                        val tool = tools.firstOrNull { it.name == call.name }
                        if (tool == null) {
                            val line = "[${call.name}] Unknown tool."
                            results.appendLine(line)
                            callResults += call to line
                            continue
                        }
                        val outcome = tool.run(call.args, toolContext)
                        val resultText = when (outcome) {
                            is ToolResult.Ok -> outcome.text
                            is ToolResult.Failed -> "Error: ${outcome.reason}"
                        }
                        // Auto-fix accounting (Phase 6, Step 1): a `run`
                        // that exits non-zero consumes one of the turn's
                        // 3 fix attempts. The model is told how many are
                        // left, so it can fix the code and re-run — or,
                        // on the last one, stop and summarise. A
                        // successful run, and a run that never executed
                        // (refused, unresolved folder), consume nothing.
                        var fixHint = ""
                        val runExit = (outcome as? ToolResult.Ok)?.exitCode
                        if (call.name == "run" && runExit != null && runExit != 0) {
                            fixAttemptsUsed++
                            fixHint = if (fixAttemptsUsed >= MAX_FIX_ATTEMPTS) {
                                "\nFix attempt $fixAttemptsUsed of $MAX_FIX_ATTEMPTS — no more " +
                                    "failing runs are allowed this turn. Do not run another " +
                                    "command; summarise what failed and what to try next."
                            } else {
                                "\nFix attempt $fixAttemptsUsed of $MAX_FIX_ATTEMPTS: the command " +
                                    "failed (exit code $runExit). Fix the code and run it again — " +
                                    "or, if the failure is not in the code, say so and stop."
                            }
                        }
                        emit(
                            AgentEvent.ToolRan(
                                tool = call.name,
                                summary = resultText.take(200),
                                result = resultText
                            )
                        )
                        val line = "[${call.name}] $resultText$fixHint"
                        results.appendLine(line)
                        callResults += call to line
                    }
                }
            }
            if (pausedForApproval) {
                emit(AgentEvent.Done)
                return@flow
            }
            // Feed the tool results back and let the model take the next step.
            // Bedrock (Phase 4): when every call carried an id, the results
            // go back as a structured user message paired by toolUseId;
            // otherwise the plain combined text is sent, exactly as before.
            // (The size check is a safety net: a turn that paused for
            // approval never reaches this line.)
            if (structured && callResults.size == calls.size) {
                llmMessages += LlmMessage(
                    "user", "Tool results:\n$results",
                    toolResults = callResults.map { (call, result) ->
                        ToolResultFeedback(call.id!!, result)
                    }
                )
            } else {
                llmMessages += LlmMessage("tool", "Tool results:\n$results")
            }
        }
        emit(AgentEvent.Error("Stopped after $MAX_STEPS tool steps — send a message to continue."))
        emit(AgentEvent.Done)
    }

    /** Apply an approved proposal. Called ONLY from the Approve button. */
    suspend fun applyFileProposal(
        proposal: PlannedFile,
        project: SafProject
    ): AgentEvent {
        val ok = when (proposal.action) {
            "create" -> proposal.newContent?.let { project.writeText(proposal.path, it) } ?: false
            "edit" -> {
                val old = proposal.oldSnippet
                val new = proposal.newSnippet
                val current = project.readText(proposal.path)
                if (old != null && new != null && current != null && current.contains(old)) {
                    project.writeText(proposal.path, current.replace(old, new))
                } else false
            }
            else -> false
        }
        if (!ok) return AgentEvent.Error("Could not write ${proposal.path}.")
        return AgentEvent.Verified(verify(proposal.path, project))
    }

    /** Light verification (v1): exists, non-empty, basic HTML sanity. */
    private fun verify(path: String, project: SafProject): String {
        val text = project.readText(path)
            ?: return "✗ Verification failed: $path was not created."
        if (text.isBlank()) return "✗ Verification failed: $path is empty."
        val lines = text.lines().size
        if (path.endsWith(".html", ignoreCase = true)) {
            val ok = text.contains("<html", ignoreCase = true) &&
                text.contains("</html>", ignoreCase = true)
            return if (ok) "✓ Verified: $path created, $lines lines, HTML looks valid."
            else "✓ Verified: $path created, $lines lines (HTML structure looks incomplete)."
        }
        return "✓ Verified: $path created, $lines lines."
    }

    /** Turn a write/edit tool call into a file proposal for the diff card. */
    private fun toolCallToProposal(call: ParsedToolCall): PlannedFile? {
        val path = call.args["path"] ?: return null
        return when (call.name) {
            "write" -> PlannedFile(path = path, action = "create", newContent = call.args["content"])
            "edit" -> PlannedFile(
                path = path,
                action = "edit",
                oldSnippet = call.args["old"],
                newSnippet = call.args["new"]
            )
            else -> null
        }
    }

    /** Parse a tool-call arguments JSON object into a string map. */
    private fun parseToolArgs(argumentsJson: String): Map<String, String>? {
        val trimmed = argumentsJson.trim()
        if (trimmed.isEmpty()) return emptyMap()
        return try {
            val obj = json.parseToJsonElement(trimmed).jsonObject
            obj.entries.associate { (key, value) ->
                key to (value.jsonPrimitive.content)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Find fenced ```tool {json} blocks in the model's text. */
    private fun parseFencedToolCalls(text: String): List<ParsedToolCall> {
        val found = mutableListOf<ParsedToolCall>()
        val fence = Regex("```tool\\s*\\n?(.*?)\\n?```", RegexOption.DOT_MATCHES_ALL)
        for (match in fence.findAll(text)) {
            val block = match.groupValues[1].trim()
            if (block.isEmpty()) continue
            try {
                val obj = json.parseToJsonElement(block).jsonObject
                val name = obj["tool"]?.jsonPrimitive?.content
                    ?: obj["name"]?.jsonPrimitive?.content
                    ?: continue
                val args = when (val a = obj["args"] ?: obj["arguments"]) {
                    is JsonObject -> a.entries.associate { (k, v) -> k to v.jsonPrimitive.content }
                    null -> obj.entries
                        .filter { (k, _) -> k != "tool" && k != "name" }
                        .associate { (k, v) -> k to v.jsonPrimitive.content }
                    else -> continue
                }
                found += ParsedToolCall(name, args)
            } catch (_: Exception) {
                // A malformed block is ignored — the model gets no result for it.
            }
        }
        return found
    }

    /**
     * Usage fallback (Phase 5, Step 2): when a provider sends no usage
     * for a turn, the counts are the chars÷4 estimate over the prompt
     * actually sent and the text actually received. The caller marks
     * the row estimated, so the dashboard never shows it as real.
     * Images add [IMAGE_TOKENS_ESTIMATE] each — a prompt with images
     * costs far more than its text, and counting only the text would
     * under-report the turn.
     */
    private fun estimateUsage(promptMessages: List<LlmMessage>, completionText: String): LlmUsage {
        val prompt = promptMessages.sumOf {
            TokenCounter.estimateTokens(it.content) + it.images.size * IMAGE_TOKENS_ESTIMATE
        }
        return LlmUsage(prompt, TokenCounter.estimateTokens(completionText))
    }

    private fun parsePlan(text: String): Plan? {
        val block = extractJson(text) ?: return null
        return try { json.decodeFromString<Plan>(block) } catch (_: Exception) { null }
    }

    private fun parseProposal(text: String): PlannedFile? {
        val block = extractJson(text) ?: return null
        return try { json.decodeFromString<PlannedFile>(block) } catch (_: Exception) { null }
    }

    /** First {...} JSON block in the text (fenced ```json blocks included). */
    private fun extractJson(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }
}
