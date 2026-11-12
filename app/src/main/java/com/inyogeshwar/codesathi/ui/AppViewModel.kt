package com.inyogeshwar.codesathi.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.inyogeshwar.codesathi.CodeSathiApp
import com.inyogeshwar.codesathi.agent.AgentEvent
import com.inyogeshwar.codesathi.agent.AgentRunner
import com.inyogeshwar.codesathi.agent.Compaction
import com.inyogeshwar.codesathi.agent.LlmClient
import com.inyogeshwar.codesathi.agent.LlmMessage
import com.inyogeshwar.codesathi.agent.PlannedFile
import com.inyogeshwar.codesathi.agent.Plan
import com.inyogeshwar.codesathi.agent.SafProject
import com.inyogeshwar.codesathi.agent.ToolContext
import com.inyogeshwar.codesathi.agent.v1Tools
import com.inyogeshwar.codesathi.data.FavouriteModel
import com.inyogeshwar.codesathi.data.Message
import com.inyogeshwar.codesathi.data.PreferencesStore
import com.inyogeshwar.codesathi.data.Provider
import com.inyogeshwar.codesathi.data.Session
import com.inyogeshwar.codesathi.data.SessionSummary
import com.inyogeshwar.codesathi.data.TokenCounter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Single ViewModel for the v1 app.
 * Holds sessions, the picked folder, the active plan / pending file proposal,
 * and the running agent job (Stop cancels it).
 */
class AppViewModel(private val app: CodeSathiApp) : ViewModel() {

    private val db = app.database
    private val prefs = app.preferencesStore
    private val keys = app.keyStore
    val providerRepository = app.providerRepository

    var onboarded by mutableStateOf(false)
        private set

    val sessions: StateFlow<List<Session>> =
        db.sessionDao().observeSessions()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favourites: StateFlow<List<FavouriteModel>> =
        db.favouriteDao().observeFavourites()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Folder picked in the New Session flow (or re-choose flow). */
    var pickedFolderUri by mutableStateOf<Uri?>(null)
        private set
    var pickedFolderName by mutableStateOf("")
        private set

    /** Plan card currently shown in the chat screen, if any. */
    var currentPlan by mutableStateOf<Plan?>(null)
        private set

    /** File change awaiting Approve / Reject, with the old content for the diff. */
    var pendingProposal by mutableStateOf<PlannedFile?>(null)
        private set
    var pendingProposalOld by mutableStateOf<String?>(null)
        private set

    var isRunning by mutableStateOf(false)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set
    var summarisedBanner by mutableStateOf(false)
        private set
    var lastSummary by mutableStateOf<SessionSummary?>(null)
        private set

    /** Fetched model lists, keyed by provider id (fresh or cached). */
    var fetchedModels by mutableStateOf<Map<String, List<String>>>(emptyMap())
        private set
    /** Provider whose model list is being refreshed right now, if any. */
    var fetchingProviderId by mutableStateOf<String?>(null)
        private set
    /** Last fetch error per provider id — shown next to the cached list. */
    var fetchErrors by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** Per-tool permission rules (allow / ask / deny), from DataStore. */
    val toolRules: StateFlow<Map<String, String>> =
        prefs.toolRules.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            PreferencesStore.DEFAULT_TOOL_RULES
        )

    fun setToolRule(toolName: String, rule: String) {
        viewModelScope.launch { prefs.setToolRule(toolName, rule) }
    }

    private var runJob: Job? = null
    private val llm = LlmClient()

    init {
        viewModelScope.launch {
            prefs.onboarded.collect { onboarded = it }
        }
    }

    // ---- Onboarding / folder picking (Step 1) ----

    /**
     * Called with the URI the system folder picker returned.
     * Takes a persistable grant so the folder survives app restarts.
     * Returns the folder's display name, or null if the URI is unusable.
     */
    fun onFolderPicked(context: Context, uri: Uri): String? {
        return try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            val name = DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment.orEmpty()
            pickedFolderUri = uri
            pickedFolderName = name
            name
        } catch (_: SecurityException) {
            null
        }
    }

    fun clearPickedFolder() {
        pickedFolderUri = null
        pickedFolderName = ""
    }

    fun completeOnboarding() {
        viewModelScope.launch { prefs.setOnboarded(true) }
    }

    fun createSession(title: String): String? {
        val uri = pickedFolderUri ?: return null
        val id = UUID.randomUUID().toString()
        val providerId = defaultProviderPair().first
        val modelId = defaultProviderPair().second
        val session = Session(
            id = id,
            title = title.ifBlank { pickedFolderName },
            folderName = pickedFolderName,
            projectUri = uri.toString(),
            providerId = providerId,
            modelId = modelId,
            agentMode = "plan",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        viewModelScope.launch {
            db.sessionDao().upsert(session)
            prefs.setLastSession(id)
            clearPickedFolder()
        }
        return id
    }

    private var cachedDefault: Pair<String, String>? = null

    private fun defaultProviderPair(): Pair<String, String> {
        // v1 default: first top pick. The user picks a model in the provider
        // screen before chatting; this is only the prefilled default.
        val top = providerRepository.topPicks().firstOrNull()
        return cachedDefault ?: (top?.let { it.first.id to it.second } ?: ("openrouter" to "openrouter/free"))
            .also { cachedDefault = it }
    }

    /** Flip a session between Plan and Build. Never writes files by itself. */
    fun setMode(session: Session, mode: String) {
        if (mode != "plan" && mode != "build") return
        viewModelScope.launch {
            db.sessionDao().setMode(session.id, mode)
            db.sessionDao().touch(session.id, System.currentTimeMillis())
        }
    }

    fun setDefaultModel(providerId: String, modelId: String) {
        cachedDefault = providerId to modelId
        viewModelScope.launch { prefs.setDefaultModel(providerId, modelId) }
    }

    // ---- Folder availability ----

    fun isFolderAvailable(context: Context, session: Session): Boolean {
        return try {
            SafProject(context, Uri.parse(session.projectUri)).isAvailable()
        } catch (_: Exception) {
            false
        }
    }

    fun rechooseFolder(context: Context, session: Session, uri: Uri) {
        val name = onFolderPicked(context, uri) ?: return
        viewModelScope.launch {
            db.sessionDao().upsert(session.copy(projectUri = uri.toString(), folderName = name))
        }
    }

    // ---- Providers / keys / favourites ----

    fun provider(id: String): Provider? = providerRepository.provider(id)

    fun hasApiKey(providerId: String): Boolean = keys.hasKey(providerId)

    fun saveApiKey(providerId: String, apiKey: String) = keys.saveKey(providerId, apiKey)

    fun removeApiKey(providerId: String) = keys.removeKey(providerId)

    fun toggleFavourite(providerId: String, modelId: String) {
        val key = "$providerId/$modelId"
        viewModelScope.launch {
            val existing = db.favouriteDao().get(key)
            if (existing == null) {
                db.favouriteDao().star(FavouriteModel(key, providerId, modelId, System.currentTimeMillis()))
            } else {
                db.favouriteDao().unstar(key)
            }
        }
    }

    // ---- Model fetching (GET {baseUrl}/models, cached in Room) ----

    /** Load the cached model list for a provider into picker state. */
    fun loadCachedModels(provider: Provider) {
        viewModelScope.launch {
            val cached = providerRepository.cachedModels(provider.id)
            if (cached.isNotEmpty()) {
                fetchedModels = fetchedModels + (provider.id to cached)
            }
        }
    }

    /** Refresh from the network; on failure the cached list stays visible. */
    fun refreshModels(provider: Provider) {
        viewModelScope.launch {
            fetchingProviderId = provider.id
            try {
                val result = providerRepository.refreshModels(provider, keys.getKey(provider.id))
                if (result.models.isNotEmpty()) {
                    fetchedModels = fetchedModels + (provider.id to result.models)
                }
                fetchErrors = if (result.error != null) {
                    fetchErrors + (provider.id to result.error)
                } else {
                    fetchErrors - provider.id
                }
            } finally {
                fetchingProviderId = null
            }
        }
    }

    /** Use a model id typed by hand (when fetch is unavailable). */
    fun useManualModel(providerId: String, modelId: String): Boolean {
        val trimmed = modelId.trim()
        if (trimmed.isEmpty()) return false
        return useModel(providerId, trimmed)
    }

    // ---- Chat ----

    private val messageFlows = mutableMapOf<String, StateFlow<List<Message>>>()

    fun messages(sessionId: String): StateFlow<List<Message>> =
        messageFlows.getOrPut(sessionId) {
            db.messageDao().observeMessages(sessionId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        }

    fun tokenEstimate(messages: List<Message>): Int = TokenCounter.estimateTokens(messages)

    fun stopRun() {
        runJob?.cancel()
        isRunning = false
    }

    fun dismissPlan() {
        currentPlan = null
    }

    /** Draft text for the chat input when the user taps "Edit plan". */
    var planEditDraft by mutableStateOf<String?>(null)
        private set

    /** "Edit plan": close the card and prefill the goal in the chat box
     *  so the user can rephrase it and send a new plan request. */
    fun editPlan(plan: Plan) {
        planEditDraft = plan.goal
        currentPlan = null
    }

    fun consumeEditDraft() {
        planEditDraft = null
    }

    /** Session currently open in the chat screen, if any. */
    var activeSessionId by mutableStateOf<String?>(null)
        private set

    fun setActiveSession(id: String?) {
        activeSessionId = id
    }

    /**
     * Use a model: sets it as the default for new sessions, and — when a
     * chat session is open — switches that session to it too. Returns true
     * when a session was switched.
     */
    fun useModel(providerId: String, modelId: String): Boolean {
        setDefaultModel(providerId, modelId)
        val sessionId = activeSessionId ?: return false
        viewModelScope.launch {
            db.sessionDao().setModel(sessionId, providerId, modelId)
            db.sessionDao().touch(sessionId, System.currentTimeMillis())
        }
        return true
    }

    /** Approve switches the session to Build mode. The user then sends the
     *  next message to start building — the runner never writes on its own. */
    fun approvePlanAndSwitchToBuild(session: Session) {
        currentPlan = null
        viewModelScope.launch {
            db.sessionDao().setMode(session.id, "build")
            db.messageDao().insert(
                Message(
                    sessionId = session.id,
                    role = "tool",
                    text = "Plan approved — Build mode is on. Send a message to start building, one file at a time.",
                    createdAt = System.currentTimeMillis()
                )
            )
            db.sessionDao().touch(session.id, System.currentTimeMillis())
        }
    }

    fun approveProposal(session: Session, context: Context) {
        val proposal = pendingProposal ?: return
        pendingProposal = null
        pendingProposalOld = null
        val project = SafProject(context, Uri.parse(session.projectUri))
        val runner = AgentRunner(llm, v1Tools(project))
        viewModelScope.launch {
            val event = runner.applyFileProposal(proposal, project)
            val text = when (event) {
                is AgentEvent.Verified -> event.message
                is AgentEvent.Error -> event.message
                else -> "Done."
            }
            db.messageDao().insert(
                Message(sessionId = session.id, role = "tool", text = text, createdAt = System.currentTimeMillis())
            )
            db.sessionDao().touch(session.id, System.currentTimeMillis())
            // The loop paused for approval — resume it so the agent keeps
            // working toward the goal instead of stopping after one file.
            if (event is AgentEvent.Verified) {
                resumeBuild(
                    session,
                    context,
                    "User approved and applied the change to ${proposal.path}. " +
                        "Continue working toward the goal; use tools as needed and finish with a short summary."
                )
            }
        }
    }

    fun rejectProposal(session: Session) {
        val proposal = pendingProposal ?: return
        pendingProposal = null
        pendingProposalOld = null
        viewModelScope.launch {
            db.messageDao().insert(
                Message(
                    sessionId = session.id,
                    role = "tool",
                    text = "Change to ${proposal.path} rejected — nothing was written.",
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }

    /** [context] is required: file tools run against the picked SAF folder. */
    fun sendMessage(session: Session, text: String, context: Context) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || isRunning) return
        val provider = provider(session.providerId)
        val apiKey = keys.getKey(session.providerId)
        if (provider == null) {
            lastError = "Unknown provider ${session.providerId}. Pick a model in Providers."
            return
        }
        if (!provider.local && apiKey.isNullOrBlank()) {
            lastError = "Add a provider API key first (Providers → ${provider.name})."
            return
        }
        isRunning = true
        lastError = null
        runJob = viewModelScope.launch {
            try {
                // Insert the user message first, in this coroutine, so the
                // history read below always includes it.
                db.messageDao().insert(
                    Message(sessionId = session.id, role = "user", text = trimmed, createdAt = System.currentTimeMillis())
                )
                db.sessionDao().touch(session.id, System.currentTimeMillis())
                val history = db.messageDao().getMessages(session.id)
                runAgentTurn(session, provider, apiKey, history, trimmed, context)
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    lastError = e.message ?: "Agent run failed."
                }
            } finally {
                isRunning = false
            }
        }
    }

    /**
     * One agent turn (Plan or Build). Shared by [sendMessage] and the
     * post-approval resume in [resumeBuild].
     */
    private suspend fun runAgentTurn(
        session: Session,
        provider: Provider,
        apiKey: String?,
        history: List<Message>,
        userMessage: String,
        context: Context
    ) {
        val project = SafProject(context, Uri.parse(session.projectUri))
        val runner = AgentRunner(llm, v1Tools(project))
        val toolContext = ToolContext(
            readOnly = session.agentMode == "plan",
            projectUri = Uri.parse(session.projectUri),
            folderName = session.folderName
        )
        val rules = prefs.toolRules.first()
        val toolRule: (String) -> String = { name -> rules[name] ?: "ask" }
        val assistantText = StringBuilder()
        // Compaction (Build only): summarise first when the estimate is
        // over 75% of the context window; the summary is sent, not the
        // full old history. Nothing is dropped from Room.
        val summary = if (session.agentMode == "build") {
            maybeCompact(session, provider, apiKey, history)
        } else {
            null
        }
        val flow = if (session.agentMode == "plan") {
            runner.runPlan(provider, session.modelId, apiKey, history, userMessage, project, session.folderName)
        } else {
            runner.runBuild(
                provider, session.modelId, apiKey, history, userMessage, project,
                toolContext, toolRule, summary
            )
        }
        collectRunEvents(session, flow, assistantText)
        if (assistantText.isNotBlank()) {
            db.messageDao().insert(
                Message(sessionId = session.id, role = "assistant", text = assistantText.toString(), createdAt = System.currentTimeMillis())
            )
        }
        db.sessionDao().touch(session.id, System.currentTimeMillis())
    }

    /** Continue a Build turn that paused for a file-approval. */
    private fun resumeBuild(session: Session, context: Context, continuation: String) {
        if (isRunning) return
        val provider = provider(session.providerId) ?: return
        val apiKey = keys.getKey(session.providerId)
        if (!provider.local && apiKey.isNullOrBlank()) return
        isRunning = true
        runJob = viewModelScope.launch {
            try {
                val history = db.messageDao().getMessages(session.id)
                runAgentTurn(session, provider, apiKey, history, continuation, context)
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    lastError = e.message ?: "Agent run failed."
                }
            } finally {
                isRunning = false
            }
        }
    }

    /** Persist tool results / verification messages and track plan/proposals. */
    private suspend fun collectRunEvents(
        session: Session,
        flow: Flow<AgentEvent>,
        assistantText: StringBuilder
    ) {
        flow.collect { event ->
            when (event) {
                is AgentEvent.TextChunk -> assistantText.append(event.text)
                is AgentEvent.PlanReady -> currentPlan = event.plan
                is AgentEvent.FileProposal -> {
                    pendingProposal = event.file
                    pendingProposalOld = event.oldContent
                }
                is AgentEvent.ToolRan -> {
                    db.messageDao().insert(
                        Message(
                            sessionId = session.id,
                            role = "tool",
                            text = "[${event.tool}] ${event.result}",
                            createdAt = System.currentTimeMillis()
                        )
                    )
                }
                is AgentEvent.Error -> lastError = event.message
                is AgentEvent.Verified -> {
                    db.messageDao().insert(
                        Message(sessionId = session.id, role = "tool", text = event.message, createdAt = System.currentTimeMillis())
                    )
                }
                else -> Unit
            }
        }
    }

    // ---- Compaction (Step 5) ----

    /**
     * Manual "Summarise chat": ask the session's model for a structured
     * summary first; if the model call fails (or no key is set), fall back
     * to the offline local summary. Either way the full history stays in
     * Room — a summary only changes what is sent to the model.
     */
    fun summariseChat(session: Session) {
        viewModelScope.launch {
            val history = db.messageDao().getMessages(session.id)
            if (history.size < 2) {
                lastError = "Not enough messages to summarise yet."
                return@launch
            }
            val provider = provider(session.providerId)
            val apiKey = keys.getKey(session.providerId)
            val saved = summariseWithModel(session, provider, apiKey, history)
                ?: saveLocalSummary(session, history)
            lastSummary = saved
            summarisedBanner = true
        }
    }

    /**
     * Auto-compaction check before a Build turn: when the token estimate
     * is over 75% of the context window, produce a summary (model first,
     * local fallback) and return its Markdown so the runner sends the
     * summary + recent tail instead of the full history. Returns null
     * when no compaction is needed.
     */
    private suspend fun maybeCompact(
        session: Session,
        provider: Provider,
        apiKey: String?,
        history: List<Message>
    ): String? {
        val estimate = TokenCounter.estimateTokens(history)
        if (!Compaction.needsSummary(estimate, Compaction.DEFAULT_CONTEXT_TOKENS)) return null
        val already = db.summaryDao().get(session.id)
        val (covered, _) = Compaction.splitForSummary(history)
        val lastCovered = covered.lastOrNull()?.id ?: 0L
        // Reuse the saved summary when it already covers these messages.
        if (already != null && already.coversUpToMessageId >= lastCovered) {
            lastSummary = already
            summarisedBanner = true
            return already.summaryMarkdown
        }
        val saved = summariseWithModel(session, provider, apiKey, history)
            ?: saveLocalSummary(session, history)
        lastSummary = saved
        summarisedBanner = true
        return saved.summaryMarkdown
    }

    /** Ask the model for a structured summary; null when unavailable. */
    private suspend fun summariseWithModel(
        session: Session,
        provider: Provider?,
        apiKey: String?,
        history: List<Message>
    ): SessionSummary? {
        if (provider == null) return null
        if (!provider.local && apiKey.isNullOrBlank()) return null
        val (covered, _) = Compaction.splitForSummary(history)
        if (covered.isEmpty()) return null
        val coveredText = covered.joinToString("\n\n") { "${it.role}: ${it.text.take(4000)}" }
        val reply = StringBuilder()
        return try {
            llm.streamChat(
                provider,
                session.modelId,
                apiKey,
                listOf(LlmMessage("user", Compaction.summaryPrompt(coveredText)))
            ).collect { reply.append(it) }
            val text = reply.toString().trim()
            if (text.isBlank()) return null
            val saved = SessionSummary(
                sessionId = session.id,
                summaryMarkdown = text,
                coversUpToMessageId = covered.last().id,
                modelId = session.modelId,
                createdAt = System.currentTimeMillis()
            )
            db.summaryDao().save(saved)
            saved
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // Stop must cancel, never fall through to the local summary.
        } catch (_: Exception) {
            null
        }
    }

    /** Offline fallback summary, saved the same way as a model summary. */
    private suspend fun saveLocalSummary(session: Session, history: List<Message>): SessionSummary {
        val (covered, _) = Compaction.splitForSummary(history)
        val saved = SessionSummary(
            sessionId = session.id,
            summaryMarkdown = Compaction.buildLocalSummary(covered, session.folderName),
            coversUpToMessageId = covered.lastOrNull()?.id ?: 0L,
            modelId = session.modelId,
            createdAt = System.currentTimeMillis()
        )
        db.summaryDao().save(saved)
        return saved
    }

    fun dismissSummaryBanner() {
        summarisedBanner = false
    }

    suspend fun loadSummary(sessionId: String): SessionSummary? = db.summaryDao().get(sessionId)

    companion object {
        fun factory(app: CodeSathiApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AppViewModel(app) as T
                }
            }
    }
}
