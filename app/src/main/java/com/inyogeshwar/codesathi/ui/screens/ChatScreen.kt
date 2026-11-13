package com.inyogeshwar.codesathi.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.inyogeshwar.codesathi.agent.VoiceInput
import com.inyogeshwar.codesathi.agent.mcpToolName
import com.inyogeshwar.codesathi.data.Message
import com.inyogeshwar.codesathi.data.PreferencesStore
import com.inyogeshwar.codesathi.data.TokenCounter
import com.inyogeshwar.codesathi.ui.AppViewModel
import com.inyogeshwar.codesathi.ui.MarkdownText

/**
 * Chat screen (Steps 2 + 3, same screen).
 *
 * - Plan | Build toggle at the top; Plan is the default for a new session.
 * - Plan mode shows a "Plan mode — read-only" chip; writes are disabled in
 *   code (AgentRunner + tools), not just in the prompt.
 * - A plan ends in a pinned summary card: Approve & switch to Build /
 *   Edit plan / Discard. Nothing is written before Approve.
 * - In Build, a proposed file change shows a diff card with Approve /
 *   Reject BEFORE any write happens.
 * - If no API key / model is set, the screen shows "Add a provider API key
 *   first" instead of pretending to work.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: AppViewModel,
    sessionId: String,
    onProviders: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val sessions by viewModel.sessions.collectAsState()
    val session = sessions.firstOrNull { it.id == sessionId }
    val messages by viewModel.messages(sessionId).collectAsState()
    var input by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var showSummary by remember { mutableStateOf(false) }
    var showToolRules by remember { mutableStateOf(false) }
    val toolRules by viewModel.toolRules.collectAsState()
    // MCP tools (enabled servers) also appear in the permissions dialog.
    val mcpServers by viewModel.mcpServers.collectAsState()
    val mcpToolNames = mcpServers.filter { it.enabled }.flatMap { server ->
        viewModel.mcpServerTools[server.id].orEmpty()
            .map { info -> mcpToolName(server.id, info.name) }
    }

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

    if (session == null) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
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
    val tokenEstimate = viewModel.tokenEstimate(messages)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(session.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
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
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
            // Mode toggle + read-only chip + model + token counter.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Plan")
                    Switch(
                        checked = !isPlan,
                        onCheckedChange = { build ->
                            // Switching modes never writes files; it only flips the flag.
                            viewModel.setMode(session, if (build) "build" else "plan")
                        }
                    )
                    Text("Build")
                }
                Text(
                    if (isPlan) "Plan mode — read-only" else "Build mode",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isPlan) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                )
            }
            Text(
                "Model: ${session.providerId} / ${session.modelId}  •  ${TokenCounter.formatApproximate(tokenEstimate)}",
                style = MaterialTheme.typography.bodySmall
            )
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
                    tools = listOf("read", "glob", "grep", "write", "edit") + mcpToolNames,
                    rules = toolRules,
                    onSetRule = { tool, rule -> viewModel.setToolRule(tool, rule) },
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

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { msg -> MessageBubble(msg) }
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

            // Diff card: Approve / Reject before any write.
            viewModel.pendingProposal?.let { proposal ->
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

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Type a message…") },
                    modifier = Modifier.weight(1f),
                    enabled = !viewModel.isRunning
                )
                // Mic: tap to dictate into the input field; tap again to stop.
                // While listening the icon changes and turns red.
                IconButton(onClick = { onMicTapped() }, enabled = !viewModel.isRunning) {
                    Icon(
                        if (listening) Icons.Filled.MicOff else Icons.Filled.Mic,
                        contentDescription = if (listening) "Stop listening" else "Voice input",
                        tint = if (listening) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
                if (viewModel.isRunning) {
                    IconButton(onClick = { viewModel.stopRun() }) {
                        Icon(Icons.Filled.Stop, contentDescription = "Stop")
                    }
                } else {
                    IconButton(onClick = {
                        val text = input
                        input = ""
                        viewModel.sendMessage(session, text, context)
                    }) {
                        Icon(Icons.Filled.Send, contentDescription = "Send")
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
            if (messages.isEmpty() && !needsKey) {
                Text(
                    "What do you want to build today? Try: “Build a single-file portfolio website for Yogeshwar Kumar”.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: Message) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (msg.role) {
                "user" -> MaterialTheme.colorScheme.primaryContainer
                "tool" -> MaterialTheme.colorScheme.surfaceVariant
                else -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                when (msg.role) {
                    "user" -> "You"
                    "tool" -> "Tool"
                    else -> "CodeSathi"
                },
                style = MaterialTheme.typography.labelMedium
            )
            // Assistant replies render as Markdown (headings, bold, code
            // blocks, lists, links); user and tool messages stay plain.
            if (msg.role == "assistant") MarkdownText(msg.text)
            else Text(msg.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Per-tool permission rules (allow / ask / deny), the v1 safety core.
 * Read-only tools run freely on "allow"; on "deny" the agent is told the
 * tool is refused. Write / edit always show the diff card for Approve,
 * even on "allow" — a write is never applied without approval.
 */
@Composable
private fun ToolRulesDialog(
    tools: List<String>,
    rules: Map<String, String>,
    onSetRule: (String, String) -> Unit,
    onClose: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Tool permissions") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Allow: runs without asking. Ask: runs, always shown as a tool card. " +
                        "Deny: the agent is told the tool is refused. " +
                        "File writes always need your Approve on the diff card.",
                    style = MaterialTheme.typography.bodySmall
                )
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
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
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
}

@Composable
private fun DiffCard(
    proposal: com.inyogeshwar.codesathi.agent.PlannedFile,
    oldContent: String?,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (proposal.action == "create") "New file: ${proposal.path}" else "Edit: ${proposal.path}",
                style = MaterialTheme.typography.titleMedium
            )
            if (proposal.action == "edit") {
                Text("Old:", style = MaterialTheme.typography.labelMedium)
                Text(oldContent?.take(600) ?: "(old content unavailable)", style = MaterialTheme.typography.bodySmall)
                Text("New snippet:", style = MaterialTheme.typography.labelMedium)
                Text(proposal.newSnippet?.take(600).orEmpty(), style = MaterialTheme.typography.bodySmall)
            } else {
                Text("New file content (first 600 chars):", style = MaterialTheme.typography.labelMedium)
                Text(proposal.newContent?.take(600).orEmpty(), style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = onApprove) { Text("Approve") }
                OutlinedButton(onClick = onReject) { Text("Reject") }
            }
        }
    }
}
