package com.inyogeshwar.codesathi.agent

import com.inyogeshwar.codesathi.data.KeyStore
import com.inyogeshwar.codesathi.data.Message
import com.inyogeshwar.codesathi.data.Provider
import com.inyogeshwar.codesathi.data.TokenCounter
import kotlinx.coroutines.CancellationException
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
     * One model turn hit a limit and switched to another key or fallback
     * model. [kind] is why the previous key stopped (quota vs rate limit).
     */
    data class FallbackSwitched(
        val fromProviderId: String,
        val fromModelId: String,
        val toProviderId: String,
        val toModelId: String,
        val kind: LimitKind
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
 * Fallback (Phase 2, Step 1): when [fallback] is set, each model turn
 * tries the engine's ordered attempts — other keys of the same
 * provider + model first, then the user's fallback chain. A turn that
 * fails with a limit error (HTTP 402 / quota / 429) marks that key
 * exhausted and retries with the next attempt, emitting
 * [AgentEvent.FallbackSwitched] so the chat can say what changed. A
 * turn is only retried when nothing was emitted yet, so a reply is
 * never shown twice. Non-limit errors fail the turn as before.
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
 */
class AgentRunner(
    private val llm: LlmClient,
    private val tools: List<Tool>,
    private val fallback: FallbackEngine? = null,
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
    private val awsCredentialsFor: (suspend () -> KeyStore.AwsCredentials?)? = null
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
        Use read / glob / grep first to see the project before changing it.
        To change a file, call write (new file) or edit (change a snippet).
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
    }

    fun runPlan(
        provider: Provider,
        modelId: String,
        apiKey: String?,
        history: List<Message>,
        userGoal: String,
        project: SafProject,
        folderName: String
    ): Flow<AgentEvent> = flow {
        val files = try {
            project.listFiles().take(30).joinToString(", ")
        } catch (e: Exception) {
            "(could not list folder: ${e.message})"
        }
        val messages = listOf(
            LlmMessage("system", planSystemPrompt),
            LlmMessage("user", "Project folder: $folderName. Existing files: $files\n\nGoal: $userGoal")
        )
        val attempts = fallback?.attemptsFor(provider, modelId)
            ?: listOf(FallbackAttempt(provider, modelId, apiKey, null))
        if (attempts.isEmpty()) {
            emit(AgentEvent.Error("All keys and fallback models are exhausted — limits reset at midnight. Add another key, or check the fallback order in Providers."))
            return@flow
        }
        var fullText: String? = null
        var lastLimitError: Exception? = null
        // The turn that answered, and the usage it reported (Phase 5,
        // Step 2): set only by the successful attempt, so a failed
        // attempt's partial stream can never be recorded.
        var usedProviderId = provider.id
        var usedModelId = modelId
        var reportedUsage: LlmUsage? = null
        for ((index, attempt) in attempts.withIndex()) {
            val full = StringBuilder()
            reportedUsage = null
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
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val kind = if (e is LlmException) classifyLimitError(e.httpCode, e.body) else null
                if (kind != null && fallback != null && full.isEmpty()) {
                    fallback.markExhausted(attempt, kind)
                    lastLimitError = e
                    attempts.getOrNull(index + 1)?.let { next ->
                        emit(
                            AgentEvent.FallbackSwitched(
                                attempt.provider.id, attempt.modelId,
                                next.provider.id, next.modelId, kind
                            )
                        )
                    }
                    continue
                }
                emit(AgentEvent.Error("Plan failed: ${e.message}"))
                return@flow
            }
        }
        val full = fullText
        if (full == null) {
            emit(AgentEvent.Error("Plan failed: ${lastLimitError?.message ?: "all keys and fallback models are exhausted."}"))
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
        skills: List<Skill> = emptyList()
    ): Flow<AgentEvent> = flow {
        // Compaction: only what is sent changes; history in Room is untouched.
        val toSend = Compaction.buildSendMessages(history, summaryMarkdown, contextTokens)
        val llmMessages = mutableListOf(LlmMessage("system", buildSystemPrompt(tools.filter { it.name.startsWith("mcp:") }, skills)))
        llmMessages += toSend.map { LlmMessage(it.role, it.text) }
        llmMessages += LlmMessage("user", userMessage)

        var steps = 0
        while (steps < MAX_STEPS) {
            steps++
            // --- One model turn: stream text, collect any native tool calls. ---
            // On a limit error the turn retries with the next key / fallback
            // model instead of failing — but only when nothing was emitted
            // yet, so the chat never shows a duplicated reply.
            val attempts = fallback?.attemptsFor(provider, modelId)
                ?: listOf(FallbackAttempt(provider, modelId, apiKey, null))
            val turnText = StringBuilder()
            val nativeCalls = mutableListOf<ParsedToolCall>()
            // The raw native calls (with their provider ids) — the
            // assistant message carries these back to Bedrock so each
            // tool result can be paired by id. Kept in step with
            // nativeCalls: only added when parseToolArgs succeeds.
            val nativeToolCalls = mutableListOf<LlmToolCall>()
            var turnOk = false
            var lastLimitError: Exception? = null
            // The attempt that answered this step, and the usage it
            // reported (Phase 5, Step 2). Cleared per attempt, set only
            // on success, so a failed attempt can never be recorded.
            var usedProviderId = provider.id
            var usedModelId = modelId
            var stepUsage: LlmUsage? = null
            for ((index, attempt) in attempts.withIndex()) {
                turnText.clear()
                nativeCalls.clear()
                nativeToolCalls.clear()
                stepUsage = null
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
                    turnOk = true
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val kind = if (e is LlmException) classifyLimitError(e.httpCode, e.body) else null
                    if (kind != null && fallback != null && turnText.isEmpty() && nativeCalls.isEmpty()) {
                        fallback.markExhausted(attempt, kind)
                        lastLimitError = e
                        attempts.getOrNull(index + 1)?.let { next ->
                            emit(
                                AgentEvent.FallbackSwitched(
                                    attempt.provider.id, attempt.modelId,
                                    next.provider.id, next.modelId, kind
                                )
                            )
                        }
                        continue
                    }
                    emit(AgentEvent.Error("Build failed: ${e.message}"))
                    return@flow
                }
            }
            if (!turnOk) {
                emit(AgentEvent.Error("Build failed: ${lastLimitError?.message ?: "all keys and fallback models are exhausted."}"))
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
                val rule = toolRule(call.name)
                if (rule == "deny") {
                    val line = "[${call.name}] Denied by your tool permission rules."
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
                        emit(
                            AgentEvent.ToolRan(
                                tool = call.name,
                                summary = resultText.take(200),
                                result = resultText
                            )
                        )
                        val line = "[${call.name}] $resultText"
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
     */
    private fun estimateUsage(promptMessages: List<LlmMessage>, completionText: String): LlmUsage {
        val prompt = promptMessages.sumOf { TokenCounter.estimateTokens(it.content) }
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
