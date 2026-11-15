package com.inyogeshwar.codesathi.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.inyogeshwar.codesathi.agent.AttachmentStore
import com.inyogeshwar.codesathi.agent.ImageStore
import com.inyogeshwar.codesathi.agent.VoiceInput
import com.inyogeshwar.codesathi.agent.ApprovalMode
import com.inyogeshwar.codesathi.agent.decodeAttachments
import com.inyogeshwar.codesathi.agent.decodeImageNames
import com.inyogeshwar.codesathi.agent.mcpToolName
import com.inyogeshwar.codesathi.agent.supportsPdfUpload
import com.inyogeshwar.codesathi.data.Message
import com.inyogeshwar.codesathi.agent.PermissionRule
import com.inyogeshwar.codesathi.data.PreferencesStore
import com.inyogeshwar.codesathi.data.TokenCounter
import com.inyogeshwar.codesathi.data.supportsVision
import com.inyogeshwar.codesathi.ui.AppViewModel
import com.inyogeshwar.codesathi.ui.MarkdownText
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import com.inyogeshwar.codesathi.ui.theme.BouncingDots
import com.inyogeshwar.codesathi.ui.theme.ClozedCard
import com.inyogeshwar.codesathi.ui.theme.Pill
import com.inyogeshwar.codesathi.ui.theme.SathiCodeType
import com.inyogeshwar.codesathi.ui.theme.SathiColors
import com.inyogeshwar.codesathi.ui.theme.SathiMark
import com.inyogeshwar.codesathi.ui.theme.SathiScaffold
import com.inyogeshwar.codesathi.ui.theme.SathiTopBar
import com.inyogeshwar.codesathi.ui.theme.TopBarTitle
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * Chat screen (Phase 8 — Clozed layout, CodeSathi's own strings).
 *
 * Header: back arrow, session title, model pill + agent pill + token
 * counter, gear → Settings. The Plan | Build toggle row is gone;
 * mode switching lives in the agent pill's "Choose agent" sheet.
 * Plan is the default for a new session and stays read-only in code
 * (AgentRunner + tools), not just in the prompt.
 * - A plan ends in a pinned summary card: Approve & switch to Build /
 *   Edit plan / Discard. Nothing is written before Approve.
 * - In Build, a proposed file change shows a diff card with Approve /
 *   Reject BEFORE any write happens.
 * - If no API key / model is set, the screen shows "Add a provider API key
 *   first" instead of pretending to work.
 * - Tool messages render as expandable cards (green ✓, teal tool name,
 *   input / output boxes). Assistant replies end with a small mono
 *   model label. The composer is a light-grey rounded bar with a "+"
 *   menu, mic, model chip, token estimate and Send / Stop.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: AppViewModel,
    sessionId: String,
    onProviders: () -> Unit,
    onBack: () -> Unit,
    onSettings: () -> Unit
) {
    val context = LocalContext.current
    val sessions by viewModel.sessions.collectAsState()
    val session = sessions.firstOrNull { it.id == sessionId }
    val messages by viewModel.messages(sessionId).collectAsState()
    var input by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var showSummary by remember { mutableStateOf(false) }
    var showToolRules by remember { mutableStateOf(false) }
    var showAgentSheet by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }
    var plusMenuOpen by remember { mutableStateOf(false) }
    val toolRules by viewModel.toolRules.collectAsState()
    // Approval mode + glob rules (Phase 7, Step B2): shown in the same
    // Tool permissions dialog as the per-tool rules.
    val approvalMode by viewModel.approvalMode.collectAsState()
    val permissionRules by viewModel.permissionRules.collectAsState()
    // MCP tools (enabled servers) also appear in the permissions dialog.
    val mcpServers by viewModel.mcpServers.collectAsState()
    val mcpToolNames = mcpServers.filter { it.enabled }.flatMap { server ->
        viewModel.mcpServerTools[server.id].orEmpty()
            .map { info -> mcpToolName(server.id, info.name) }
    }

    // Custom commands (Phase 3, Step 1): while the input starts with "/"
    // and has no space yet, suggest enabled commands matching what is typed.
    val commands by viewModel.customCommands.collectAsState()
    val commandSuggestions = if (input.startsWith("/") && !input.contains(" ")) {
        val prefix = input.removePrefix("/").lowercase()
        commands.filter { it.enabled && it.name.startsWith(prefix) }.take(5)
    } else emptyList()

    // Voice (Phase 2, Step 5): mic input + voice-output toggle.
    val voiceOutputEnabled by viewModel.voiceOutputEnabled.collectAsState()
    var listening by remember { mutableStateOf(false) }
    var voiceNote by remember { mutableStateOf<String?>(null) }
    val voiceInput = remember {
        VoiceInput(context).apply {
            onResult = { text ->
                input = if (input.isBlank()) text else "${input.trimEnd()} $text"
                voiceNote = null
            }
            onErrorMessage = { message -> voiceNote = message }
            onListeningChanged = { isListening -> listening = isListening }
        }
    }
    DisposableEffect(voiceInput) {
        onDispose { voiceInput.destroy() }
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            voiceNote = null
            voiceInput.start()
        } else {
            voiceNote = "Microphone permission is needed for voice input. Allow it to use the mic."
        }
    }
    fun onMicTapped() {
        if (listening) {
            voiceInput.stop()
            return
        }
        // First tap with no permission yet asks for it; the launcher's
        // callback starts listening when it is granted, so the next tap
        // records. A denied tap just shows the note — never a crash.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            voiceNote = null
            voiceInput.start()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Images (Phase 6, Step 2): attach up to 3 pictures to a message.
    // The system picker (SAF, image picker) returns content URIs; the
    // ViewModel downscales + JPEG re-encodes each one into app-private
    // storage before it is shown or sent.
    val pendingImages = viewModel.pendingImages
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.addPendingImages(context, uris)
    }

    // Files (Phase 7, Step B3): attach up to 3 PDF / text files to a
    // message. The system picker returns content URIs; the ViewModel
    // copies each one into app-private storage before it is shown or
    // sent. Text files are inlined into the prompt; PDFs go as raw
    // bytes (no text is extracted on-device).
    val pendingAttachments = viewModel.pendingAttachments
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.addPendingAttachments(context, uris)
    }

    if (session == null) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(24.dp)) {
            Text("Session not found.", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onBack) { Text("Back") }
        }
        return
    }

    // This session is the one the provider picker should switch models for.
    DisposableEffect(sessionId) {
        viewModel.setActiveSession(sessionId)
        onDispose { viewModel.setActiveSession(null) }
    }
    // "Edit plan" prefills the goal here so it can be rephrased and resent.
    val editDraft = viewModel.planEditDraft
    LaunchedEffect(editDraft) {
        if (editDraft != null) {
            input = editDraft
            viewModel.consumeEditDraft()
        }
    }

    val isPlan = session.agentMode == "plan"
    val provider = viewModel.provider(session.providerId)
    val needsKey = provider != null && !provider.local && !viewModel.hasApiKey(session.providerId)
    // Warn before sending when the current model cannot see images
    // (Phase 6, Step 2): the picker warns, and sendMessage refuses, so
    // a vision request never goes to a text-only model.
    val visionBlocked = pendingImages.isNotEmpty() && provider != null &&
        !supportsVision(provider, session.modelId)
    // Warn before sending when the current model cannot receive a PDF
    // (Phase 7, Step B3): only OpenAI / Anthropic-kind models take a
    // PDF part. Text files go to any model. Send is refused while
    // this shows.
    val pdfBlocked = pendingAttachments.any { it.kind == "pdf" } && provider != null &&
        !supportsPdfUpload(provider)
    val tokenEstimate = viewModel.tokenEstimate(messages)
    // Reasoning effort for this session's model (Phase 3, Step 2), if set.
    // The on-device engine takes no effort field (Phase 6, Step 5), so no
    // effort label is shown for a local model even when one is stored.
    val efforts by viewModel.reasoningEfforts.collectAsState()
    val effort = if (provider?.kind == "local") null else efforts["${session.providerId}/${session.modelId}"]

    if (showAgentSheet) {
        ChooseAgentSheet(
            viewModel = viewModel,
            session = session,
            onDismiss = { showAgentSheet = false }
        )
    }
    if (showModelSheet) {
        ChooseModelSheet(
            viewModel = viewModel,
            session = session,
            onDismiss = { showModelSheet = false },
            onProviders = onProviders
        )
    }

    SathiScaffold(
        topBar = {
            SathiTopBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // The mark's spark breathes softly while the agent works.
                        SathiMark(size = 26.dp, working = viewModel.isRunning, decorative = true)
                        Spacer(Modifier.width(10.dp))
                        TopBarTitle(session.title, "${session.folderName}  ·  ${session.providerId}")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Summarise chat") },
                            onClick = {
                                menuOpen = false
                                viewModel.summariseChat(session)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Tool permissions") },
                            onClick = { menuOpen = false; showToolRules = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Voice output") },
                            trailingIcon = {
                                Switch(
                                    checked = voiceOutputEnabled,
                                    onCheckedChange = {
                                        menuOpen = false
                                        viewModel.setVoiceOutputEnabled(it)
                                    }
                                )
                            },
                            onClick = {
                                menuOpen = false
                                viewModel.setVoiceOutputEnabled(!voiceOutputEnabled)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Providers & API keys") },
                            onClick = { menuOpen = false; onProviders() }
                        )
                    }
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            // Model + agent pills and the token counter (Phase 8).
            // Tapping the model pill opens the model sheet; tapping the
            // agent pill opens the agent sheet. Mode switching never
            // writes files; it only flips the flag (setMode).
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Pill(
                    text = session.modelId,
                    onClick = { showModelSheet = true },
                    leading = {
                        Icon(
                            Icons.Filled.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    modifier = Modifier.weight(1f, fill = false)
                )
                Pill(
                    text = if (isPlan) "Plan" else "Build",
                    onClick = { showAgentSheet = true },
                    container = if (isPlan) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
                    content = if (isPlan) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onTertiaryContainer,
                    leading = {
                        Icon(
                            Icons.Filled.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
                Spacer(Modifier.weight(1f))
                Text(
                    TokenCounter.formatApproximate(tokenEstimate),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            if (viewModel.summarisedBanner) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    onClick = { showSummary = true }
                ) {
                    Text(
                        "Chat summarised to save space — older messages kept in history. Tap to read the summary.",
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            // Fallback switch notice (Phase 2, Step 1): which model the turn
            // moved to and why. The same text is also kept as a tool message
            // in the chat history.
            viewModel.fallbackNotice?.let { notice ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(notice, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { viewModel.dismissFallbackNotice() }) {
                            Text("Dismiss")
                        }
                    }
                }
            }
            if (showSummary) {
                val summary = viewModel.lastSummary
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Summary", style = MaterialTheme.typography.titleMedium)
                        if (summary != null) MarkdownText(summary.summaryMarkdown)
                        else Text("No summary saved yet.")
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { showSummary = false; viewModel.dismissSummaryBanner() }) {
                            Text("Close")
                        }
                    }
                }
            }
            if (showToolRules) {
                ToolRulesDialog(
                    tools = listOf("read", "glob", "grep", "write", "edit", "run") + mcpToolNames,
                    rules = toolRules,
                    onSetRule = { tool, rule -> viewModel.setToolRule(tool, rule) },
                    mode = approvalMode,
                    permissionRules = permissionRules,
                    onSetMode = { viewModel.setApprovalMode(it) },
                    onAddRule = { viewModel.addPermissionRule(it) },
                    onRemoveRule = { viewModel.removePermissionRule(it) },
                    onMoveRule = { from, to -> viewModel.movePermissionRule(from, to) },
                    onClose = { showToolRules = false }
                )
            }
            if (needsKey) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Add a provider API key first", style = MaterialTheme.typography.titleMedium)
                        Text("Provider ${provider?.name} needs an API key before the chat can run. Keys are stored encrypted on this device only.")
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onProviders) { Text("Open Providers") }
                    }
                }
            }

            // reverseLayout: the newest message sits at the bottom and the
            // list stays pinned there as new messages / the working row arrive.
            val reversed = remember(messages) { messages.asReversed() }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                reverseLayout = true,
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (viewModel.isRunning) {
                    item(key = "working") { WorkingIndicator(startedAt = viewModel.runStartedAt) }
                }
                items(reversed, key = { it.id }) { msg -> MessageBubble(msg, session.modelId) }
                if (messages.isEmpty() && !needsKey) {
                    item(key = "empty") { EmptyChatHint(onSuggestion = { input = it }) }
                }
            }

            // Pinned plan card (Plan mode).
            viewModel.currentPlan?.let { plan ->
                PlanCard(
                    plan = plan,
                    onApprove = { viewModel.approvePlanAndSwitchToBuild(session) },
                    onEdit = { viewModel.editPlan(plan) },
                    onDiscard = { viewModel.dismissPlan() }
                )
            }

            // Diff card: Approve / Reject before any write. A template
            // proposal (Phase 6, Step 3) shows only in its own session's
            // chat — see shouldShowProposalFor — so a template chosen
            // for another session never offers its file here.
            viewModel.pendingProposal?.takeIf { viewModel.shouldShowProposalFor(session.id) }?.let { proposal ->
                DiffCard(
                    proposal = proposal,
                    oldContent = viewModel.pendingProposalOld,
                    onApprove = { viewModel.approveProposal(session, context) },
                    onReject = { viewModel.rejectProposal(session) }
                )
            }

            viewModel.lastError?.let { err ->
                Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            // Continue (Phase 7, Step B2): the last turn ended
            // interrupted — error, limit, or Stop — and the finished
            // steps are still in the history. Continue picks the turn
            // up where it stopped instead of resending the message.
            // Shown only while no turn runs; sending a new message or
            // tapping Continue clears it.
            if (viewModel.interruptedTurn && !viewModel.isRunning) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "The turn stopped before finishing.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = { viewModel.continueTurn(session, context) }) {
                        Text("Continue")
                    }
                }
            }

            // Command suggestions: tap one to complete "/name " in the input.
            if (commandSuggestions.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    commandSuggestions.forEach { cmd ->
                        OutlinedButton(onClick = { input = "/${cmd.name} " }) {
                            Text("/${cmd.name}")
                        }
                    }
                }
            }
            // Attached images (Phase 6, Step 2): thumbnails of the staged
            // images, with × to remove one before sending.
            if (pendingImages.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pendingImages.forEach { fileName ->
                        PendingImageThumbnail(
                            fileName = fileName,
                            onRemove = { viewModel.removePendingImage(fileName) }
                        )
                    }
                }
            }
            // Vision warning: the current model cannot see the attached
            // images — pick a vision model in Providers, or remove the
            // image before sending. Send is refused while this shows.
            if (visionBlocked) {
                Text(
                    "This model cannot see images — pick a vision model in Providers, " +
                        "or remove the image before sending.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            // Attached files (Phase 7, Step B3): chips of the staged
            // files, with × to remove one before sending.
            if (pendingAttachments.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pendingAttachments.forEach { record ->
                        PendingAttachmentChip(
                            name = record.name,
                            size = AttachmentStore.displaySize(record.size),
                            onRemove = { viewModel.removePendingAttachment(record) }
                        )
                    }
                }
            }
            // PDF warning: the current model cannot receive a PDF —
            // pick an OpenAI / Anthropic / Azure model, or remove the
            // PDF before sending. Send is refused while this shows.
            if (pdfBlocked) {
                Text(
                    "This model cannot receive PDFs — pick an OpenAI, Anthropic or Azure model, " +
                        "or remove the PDF before sending.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            viewModel.attachmentNote?.let { note ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = { viewModel.dismissAttachmentNote() }) {
                        Text("OK")
                    }
                }
            }
            viewModel.imageNote?.let { note ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = { viewModel.dismissImageNote() }) {
                        Text("OK")
                    }
                }
            }
            // "+" menu (Phase 8): a small popup above the composer with
            // the two real attach actions. There is no "copy from
            // another project" feature, so no such row is offered.
            if (plusMenuOpen) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    shadowElevation = 2.dp
                ) {
                    Column {
                        TextButton(
                            onClick = { plusMenuOpen = false; imagePickerLauncher.launch("image/*") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null)
                                Spacer(Modifier.width(12.dp))
                                Text("Attach image", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        TextButton(
                            onClick = { plusMenuOpen = false; filePickerLauncher.launch("*/*") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.AttachFile, contentDescription = null)
                                Spacer(Modifier.width(12.dp))
                                Text("Attach file", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            // Thin segmented progress bar above the composer while a
            // turn runs. The segments fill in sequence; no percentage
            // is shown, because no real progress fraction exists.
            if (viewModel.isRunning) {
                SegmentedProgressBar()
            }
            // Rounded composer bar: "+" menu, text, mic, model chip,
            // token estimate and Send / Stop underneath the text.
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shadowElevation = 2.dp
            ) {
                Column(Modifier.padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 6.dp)) {
                    TextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("Type a message…") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !viewModel.isRunning,
                        maxLines = 6,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent
                        )
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // "+" opens the attach menu above the composer.
                        IconButton(
                            onClick = { plusMenuOpen = !plusMenuOpen },
                            enabled = !viewModel.isRunning
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "More options")
                        }
                        // Mic: tap to dictate into the input field; tap again to stop.
                        // While listening the icon changes and turns red.
                        IconButton(onClick = { onMicTapped() }, enabled = !viewModel.isRunning) {
                            Icon(
                                if (listening) Icons.Filled.MicOff else Icons.Filled.Mic,
                                contentDescription = if (listening) "Stop listening" else "Voice input",
                                tint = if (listening) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Model chip: the session's model (+ reasoning effort);
                        // tap to switch it in the model sheet.
                        Pill(
                            text = session.modelId + (if (effort != null) " · $effort" else ""),
                            onClick = { showModelSheet = true },
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            TokenCounter.formatApproximate(tokenEstimate),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 6.dp)
                        )
                        if (viewModel.isRunning) {
                            FilledIconButton(
                                onClick = { viewModel.stopRun() },
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                )
                            ) {
                                Icon(Icons.Filled.Stop, contentDescription = "Stop")
                            }
                        } else {
                            FilledIconButton(
                                onClick = {
                                    val text = input
                                    // Only clear the input when the message was
                                    // actually sent — a vision refusal keeps the text
                                    // (and the staged images) in place.
                                    if (viewModel.sendMessage(session, text, context)) input = ""
                                },
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = if (input.isBlank()) MaterialTheme.colorScheme.surfaceContainerHigh
                                    else MaterialTheme.colorScheme.primary,
                                    contentColor = if (input.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                            }
                        }
                    }
                }
            }
            // Listening state / voice-input message, under the input row.
            if (listening) {
                Text(
                    "Listening… speak now.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
                voiceNote?.let { note ->
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

/** Thin segmented bar that fills in sequence while a turn runs. */
@Composable
private fun SegmentedProgressBar() {
    val transition = rememberInfiniteTransition(label = "composerProgress")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Restart),
        label = "progress"
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        repeat(5) { index ->
            val fill = (progress * 5f - index).coerceIn(0f, 1f)
            Box(
                modifier = Modifier.weight(1f).height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(fill).height(3.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: Message, modelLabel: String) {
    // Attached images (Phase 6, Step 2): thumbnails above the message
    // text, so a screenshot stays visible in the history.
    val imageNames = remember(msg.imagesJson) { decodeImageNames(msg.imagesJson) }
    val images: @Composable () -> Unit = {
        if (imageNames.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                imageNames.forEach { fileName -> StoredImageThumbnail(fileName = fileName) }
            }
        }
    }
    // Attached files (Phase 7, Step B3): chips above the message text,
    // so a PDF or text file stays visible in the history.
    val attachments = remember(msg.attachmentsJson) { decodeAttachments(msg.attachmentsJson) }
    val attachmentChips: @Composable () -> Unit = {
        if (attachments.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                attachments.forEach { record ->
                    PendingAttachmentChip(
                        name = record.name,
                        size = AttachmentStore.displaySize(record.size),
                        onRemove = null
                    )
                }
            }
        }
    }
    when (msg.role) {
        "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.fillMaxWidth(0.88f).wrapContentWidth(Alignment.End)
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    images()
                    attachmentChips()
                    Text(msg.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        "tool" -> ToolCard(msg = msg, images = images)
        else -> Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                SathiMark(size = 18.dp, decorative = true)
                Spacer(Modifier.width(6.dp))
                Text("CodeSathi", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(
                shape = RoundedCornerShape(topStart = 6.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    images()
                    // Assistant replies render as Markdown (headings, bold,
                    // code blocks with Copy, lists, links).
                    MarkdownText(msg.text)
                }
            }
            // Small mono model label under the reply, as in Clozed.
            Text(
                modelLabel,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp, start = 4.dp)
            )
        }
    }
}

/** A parsed tool message, or null when the message is not a tool call. */
private data class ToolParsed(
    val name: String,
    val target: String?,
    val input: String?,
    val output: String
)

/**
 * Parse a "tool" message into a tool card. Two shapes exist:
 * - [Message.toolJson] when present: {"name"/"tool", "args"/"input",
 *   "result"/"output"} — the args object also yields the target
 *   (path / command / query / dir / name).
 * - The text the runner actually stores today: "[tool] result"
 *   (see AppViewModel's ToolRan handling, which inserts tool
 *   messages with no toolJson). The target is then unknown, so the
 *   collapsed line shows the first line of the output instead.
 * Returns null for tool-role notices that are not tool calls at all
 * ("Plan approved…", "Switched to…"), which keep the plain rendering.
 */
private fun parseToolMessage(msg: Message): ToolParsed? {
    msg.toolJson?.let { raw ->
        try {
            val json = JSONObject(raw)
            val name = json.optString("name").ifBlank { json.optString("tool") }
            if (name.isNotBlank()) {
                val args = json.optJSONObject("args") ?: json.optJSONObject("input")
                val input = args?.toString(2)
                    ?: json.optString("args").ifBlank { null }
                    ?: json.optString("input").ifBlank { null }
                val output = json.optString("result").ifBlank { json.optString("output") }
                val target = args?.let {
                    it.optString("path")
                        .ifBlank { it.optString("command") }
                        .ifBlank { it.optString("query") }
                        .ifBlank { it.optString("dir") }
                        .ifBlank { it.optString("name") }
                        .ifBlank { null }
                }
                return ToolParsed(name, target, input, output)
            }
        } catch (_: Exception) {
            // Fall through to the text form below.
        }
    }
    val match = Regex("^\\[([^\\]]+)\\]\\s*(.*)$", RegexOption.DOT_MATCHES_ALL)
        .matchEntire(msg.text.trim()) ?: return null
    val name = match.groupValues[1].trim()
    if (name.isBlank()) return null
    return ToolParsed(name, null, null, match.groupValues[2])
}

/**
 * Expandable tool card (Phase 8): green ✓ + teal tool name +
 * truncated target (or first output line) + chevron. Expanded, it
 * shows the input as a white mono JSON box and the output as a
 * white box. Messages that do not parse as a tool call keep the
 * previous plain rendering. Nothing about execution changes.
 */
@Composable
private fun ToolCard(msg: Message, images: @Composable () -> Unit) {
    val parsed = remember(msg.toolJson, msg.text) { parseToolMessage(msg) }
    if (parsed == null) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    "Tool",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                images()
                Text(msg.text, style = SathiCodeType.small, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        return
    }
    var expanded by remember(msg.id) { mutableStateOf(false) }
    val collapsedLine = parsed.target
        ?: parsed.output.lineSequence().firstOrNull()?.trim().orEmpty()
    Surface(
        onClick = { expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            images()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "✓",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFF15803D)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    parsed.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = SathiColors.TealDeep,
                    maxLines = 1
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    collapsedLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand"
                )
            }
            if (expanded) {
                parsed.input?.let { inputJson ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "input",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    ) {
                        Text(
                            inputJson.take(2000),
                            style = SathiCodeType.small,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "output",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    Text(
                        parsed.output.take(2000),
                        style = SathiCodeType.small,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}

/**
 * Shown at the bottom of the chat while a turn runs: the softly
 * breathing mark, bouncing dots and the real seconds since the run
 * started ([AppViewModel.runStartedAt]), ticking once a second.
 *
 * The status word stays "Working": the runner / ViewModel expose no
 * live phase (thinking vs. reading files vs. checking), so the more
 * specific Clozed words would be guesses, not what is happening.
 */
@Composable
private fun WorkingIndicator(startedAt: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val seconds = if (startedAt > 0) ((now - startedAt) / 1000).coerceAtLeast(0) else 0
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SathiMark(size = 20.dp, working = true, decorative = true)
        Spacer(Modifier.width(8.dp))
        Text("Working", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        BouncingDots()
        Spacer(Modifier.width(8.dp))
        Text(
            formatElapsed(seconds),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

internal fun formatElapsed(seconds: Long): String =
    if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${(seconds % 60).toString().padStart(2, '0')}s"

/** Empty state: logo, question, subtitle, and suggestion pills that fill the input. */
@Composable
private fun EmptyChatHint(onSuggestion: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SathiMark(size = 56.dp, tile = true, decorative = true)
        Spacer(Modifier.height(6.dp))
        Text("What do you want to build today?", style = MaterialTheme.typography.titleMedium)
        Text(
            "Try: “Build a single-file portfolio website for Yogeshwar Kumar”. Type / to see your custom commands.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        listOf("Explain this project", "Find and fix bugs", "Write tests", "/init").forEach { label ->
            OutlinedButton(
                onClick = { onSuggestion(label) },
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(label)
            }
        }
    }
}

/**
 * Per-tool permission rules (allow / ask / deny), the v1 safety core.
 * Read-only tools run freely on "allow"; on "deny" the agent is told the
 * tool is refused. Write / edit always show the diff card for Approve,
 * even on "allow" — a write is never applied without approval.
 *
 * Phase 7, Step B2 adds two sections to the same dialog: the approval
 * mode (what happens for a tool with no explicit rule) and the ordered
 * glob rules (allow / deny a whole class, e.g. all reads under
 * `docs/` + `**`). Precedence: explicit per-tool rule > glob rule > mode.
 * A stored per-tool "deny" always wins. The mode defaults to
 * "Ask for everything", which behaves exactly like v1.
 */
@Composable
private fun ToolRulesDialog(
    tools: List<String>,
    rules: Map<String, String>,
    onSetRule: (String, String) -> Unit,
    mode: String,
    permissionRules: List<PermissionRule>,
    onSetMode: (String) -> Unit,
    onAddRule: (PermissionRule) -> Unit,
    onRemoveRule: (String) -> Unit,
    onMoveRule: (Int, Int) -> Unit,
    onClose: () -> Unit
) {
    var newTool by remember { mutableStateOf("") }
    var newPattern by remember { mutableStateOf("") }
    var newAction by remember { mutableStateOf("deny") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Tool permissions") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Allow: runs without asking. Ask: runs, always shown as a tool card. " +
                        "Deny: the agent is told the tool is refused. " +
                        "File writes always need your Approve on the diff card.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text("Approval mode", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Decides tools with no rule of their own below. " +
                        "A per-tool rule you set always wins over the mode.",
                    style = MaterialTheme.typography.bodySmall
                )
                listOf(
                    "ask_all" to ApprovalMode.ASK_ALL,
                    "ask_writes" to ApprovalMode.ASK_WRITES,
                    "auto_edits" to ApprovalMode.AUTO_EDITS,
                    "auto_all" to ApprovalMode.AUTO_ALL
                ).forEach { (value, modeEnum) ->
                    Column {
                        Text(modeEnum.label, style = MaterialTheme.typography.labelMedium)
                        Text(modeEnum.desc, style = MaterialTheme.typography.bodySmall)
                        if (value == mode) {
                            Button(onClick = {}) { Text("Selected") }
                        } else {
                            OutlinedButton(onClick = { onSetMode(value) }) { Text("Use this") }
                        }
                    }
                }
                Text("Per-tool rules", style = MaterialTheme.typography.titleMedium)
                tools.forEach { tool ->
                    val current = rules[tool] ?: PreferencesStore.RULE_ASK
                    Column {
                        Text(tool, style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                PreferencesStore.RULE_ALLOW to "Allow",
                                PreferencesStore.RULE_ASK to "Ask",
                                PreferencesStore.RULE_DENY to "Deny"
                            ).forEach { (rule, label) ->
                                if (rule == current) {
                                    Button(onClick = {}) { Text(label) }
                                } else {
                                    OutlinedButton(onClick = { onSetRule(tool, rule) }) { Text(label) }
                                }
                            }
                        }
                    }
                }
                Text("Glob rules", style = MaterialTheme.typography.titleMedium)
                Text(
                    "First match wins, in this order. A rule names a tool (or * for all, " +
                        "or mcp:* for every MCP tool) and a pattern over its target: a file " +
                        "path for read / write / edit, a command for run. " +
                        "Patterns use ** (any folders), * (any name), ? and {a,b} — " +
                        "e.g. read + docs/** allows every read under docs/. " +
                        "A glob rule never beats a per-tool rule you set above.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (permissionRules.isEmpty()) {
                    Text("No glob rules yet.", style = MaterialTheme.typography.bodySmall)
                }
                permissionRules.forEachIndexed { index, rule ->
                    Column {
                        Text(
                            "${rule.tool}  ·  ${rule.pattern}  ·  ${rule.action}",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { onMoveRule(index, index - 1) },
                                enabled = index > 0
                            ) { Text("↑") }
                            OutlinedButton(
                                onClick = { onMoveRule(index, index + 1) },
                                enabled = index < permissionRules.size - 1
                            ) { Text("↓") }
                            OutlinedButton(onClick = { onRemoveRule(rule.id) }) { Text("Delete") }
                        }
                    }
                }
                OutlinedTextField(
                    value = newTool,
                    onValueChange = { newTool = it },
                    label = { Text("Tool (*, read, run, mcp:*)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = newPattern,
                    onValueChange = { newPattern = it },
                    label = { Text("Pattern (e.g. docs/**)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("allow" to "Allow", "ask" to "Ask", "deny" to "Deny").forEach { (action, label) ->
                        if (action == newAction) {
                            Button(onClick = {}) { Text(label) }
                        } else {
                            OutlinedButton(onClick = { newAction = action }) { Text(label) }
                        }
                    }
                }
                Button(
                    onClick = {
                        if (newTool.isNotBlank() && newPattern.isNotBlank()) {
                            onAddRule(
                                PermissionRule(
                                    id = UUID.randomUUID().toString(),
                                    tool = newTool.trim(),
                                    pattern = newPattern.trim(),
                                    action = newAction
                                )
                            )
                            newTool = ""
                            newPattern = ""
                        }
                    }
                ) { Text("Add rule") }
            }
        },
        confirmButton = {
            Button(onClick = onClose) { Text("Done") }
        }
    )
}

@Composable
private fun PlanCard(
    plan: com.inyogeshwar.codesathi.agent.Plan,
    onApprove: () -> Unit,
    onEdit: () -> Unit,
    onDiscard: () -> Unit
) {
    ClozedCard(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Plan: ${plan.goal}", style = MaterialTheme.typography.titleMedium)
        Text("Files to create / edit:", style = MaterialTheme.typography.labelMedium)
        plan.files.forEach { f -> Text("• ${f.action}: ${f.path}") }
        Text("Steps:", style = MaterialTheme.typography.labelMedium)
        plan.steps.forEachIndexed { i, s -> Text("${i + 1}. $s") }
        if (plan.risks.isNotEmpty()) {
            Text("Risks:", style = MaterialTheme.typography.labelMedium)
            plan.risks.forEach { r -> Text("• $r") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = onApprove) { Text("Approve & switch to Build") }
            OutlinedButton(onClick = onEdit) { Text("Edit plan") }
            OutlinedButton(onClick = onDiscard) { Text("Discard") }
        }
        Text(
            "Nothing is written to disk until you approve.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/**
 * One stored image as a small thumbnail, decoded off the main thread
 * (Phase 6, Step 2). A missing or undecodable file shows nothing —
 * the message text still renders, and the turn still sends the text.
 */
@Composable
private fun StoredImageThumbnail(fileName: String, size: Dp = 72.dp) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, fileName) {
        value = withContext(Dispatchers.IO) { ImageStore.loadThumbnailBitmap(context, fileName) }
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = "Attached image",
            modifier = Modifier.size(size),
            contentScale = ContentScale.Crop
        )
    }
}

/** A staged image above the input, with × to remove it before sending. */
@Composable
private fun PendingImageThumbnail(fileName: String, onRemove: () -> Unit) {
    Box {
        StoredImageThumbnail(fileName = fileName, size = 72.dp)
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(24.dp).align(Alignment.TopEnd)
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Remove image")
        }
    }
}

/**
 * A staged file above the input (Phase 7, Step B3), and the same chip
 * on a stored message: name + size, with × to remove it before
 * sending. On a stored message [onRemove] is null and no × is shown.
 */
@Composable
private fun PendingAttachmentChip(name: String, size: String, onRemove: (() -> Unit)?) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 10.dp, end = if (onRemove == null) 10.dp else 2.dp, top = 4.dp, bottom = 4.dp)
        ) {
            Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                "$name ($size)",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1
            )
            if (onRemove != null) {
                IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove file")
                }
            }
        }
    }
}
