package com.inyogeshwar.codesathi.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inyogeshwar.codesathi.agent.BUILT_IN_TEMPLATES
import com.inyogeshwar.codesathi.ui.AppViewModel
import com.inyogeshwar.codesathi.ui.theme.LocalBottomBarInset
import com.inyogeshwar.codesathi.ui.theme.LocalSathiStatus
import com.inyogeshwar.codesathi.ui.theme.LocalSpacing
import com.inyogeshwar.codesathi.ui.theme.SathiCard
import com.inyogeshwar.codesathi.ui.theme.SathiLockup
import com.inyogeshwar.codesathi.ui.theme.SathiScaffold
import com.inyogeshwar.codesathi.ui.theme.SathiTopBar
import com.inyogeshwar.codesathi.ui.theme.SectionHeader
import com.inyogeshwar.codesathi.ui.theme.TopBarTitle
import com.inyogeshwar.codesathi.ui.theme.sathiGradient
import java.util.Calendar

/**
 * v1.2 Home: greeting, quick actions and the most recent sessions.
 * The full session list (search, pin, rename, delete, backup) is the
 * Chats tab; every other tool is in the Tools tab.
 */
@Composable
fun HomeScreen(
    viewModel: AppViewModel,
    onOpenSession: (String) -> Unit,
    onNewSession: () -> Unit,
    onAllSessions: () -> Unit,
    onEditor: () -> Unit,
    onTerminal: () -> Unit,
    onGit: () -> Unit,
    onProviders: () -> Unit,
    onTools: () -> Unit
) {
    val sessions by viewModel.sessions.collectAsState()
    val context = LocalContext.current
    val spacing = LocalSpacing.current
    val bottomInset = LocalBottomBarInset.current
    val recent = remember(sessions) { sessions.sortedByDescending { it.updatedAt }.take(4) }
    val last = recent.firstOrNull()

    SathiScaffold(topBar = { SathiTopBar(title = { SathiLockup(markSize = 30.dp) }) }) { _ ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = spacing.gutter, end = spacing.gutter, top = spacing.sm, bottom = bottomInset + spacing.lg
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            // Crash report (Phase 7, Step B3): the app crashed last
            // time. The report is local only — Copy sends it wherever
            // the user pastes it, Dismiss deletes it.
            viewModel.pendingCrash?.let { crash ->
                item {
                    CrashCard(
                        text = crash,
                        onDismiss = { viewModel.dismissCrashReport() }
                    )
                }
            }
            item {
                Column(Modifier.padding(bottom = spacing.xs)) {
                    Text(greeting(), style = MaterialTheme.typography.headlineLarge)
                    Text(
                        "Kya banayenge aaj?",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item { NewSessionHero(onNewSession) }
            if (last != null) {
                item {
                    val available = viewModel.isFolderAvailable(context, last)
                    ContinueCard(
                        title = last.title,
                        subtitle = "${last.folderName}  ·  ${relativeTime(last.updatedAt)}",
                        onClick = { if (available) onOpenSession(last.id) else onAllSessions() }
                    )
                }
            }
            item { SectionHeader("Quick actions") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                        QuickCard(Icons.Filled.Edit, "Editor", "Browse & edit files", Modifier.weight(1f), onEditor)
                        QuickCard(Icons.Filled.Terminal, "Terminal", "Run commands", Modifier.weight(1f), onTerminal)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                        QuickCard(Icons.Filled.AccountTree, "Git", "Commit, push, pull", Modifier.weight(1f), onGit)
                        QuickCard(Icons.Filled.Key, "Providers", "Models & API keys", Modifier.weight(1f), onProviders)
                    }
                }
            }
            item {
                SectionHeader("Recent sessions") {
                    if (sessions.isNotEmpty()) {
                        TextButton(onClick = onAllSessions) { Text("See all") }
                    }
                }
            }
            if (recent.isEmpty()) {
                item {
                    SathiCard(Modifier.fillMaxWidth()) {
                        Text("No sessions yet", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Abhi koi session nahi hai. “New session” dabakar pehla session banayein — folder chunenge, path type nahi karna.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(recent, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        available = viewModel.isFolderAvailable(context, session),
                        onOpen = { onOpenSession(session.id) },
                        onRechoose = { uri -> viewModel.rechooseFolder(context, session, uri) },
                        showActions = false
                    )
                }
            }
            item {
                TextButton(onClick = onTools, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Widgets, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("All tools — skills, commands, MCP, remote, sync, usage")
                }
            }
        }
    }
}

private fun greeting(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Namaste"
    }
}

@Composable
private fun NewSessionHero(onClick: () -> Unit) {
    val dark = LocalSathiStatus.current.isDark
    Surface(onClick = onClick, shape = MaterialTheme.shapes.extraLarge, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .background(sathiGradient(dark))
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("New session", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Pick a folder, optionally a template, and start in Plan mode.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier.size(48.dp).background(Color.White.copy(alpha = 0.18f), MaterialTheme.shapes.large),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White)
            }
        }
    }
}

@Composable
private fun CrashCard(text: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    SathiCard(Modifier.fillMaxWidth()) {
        Column {
            Text("CodeSathi closed unexpectedly last time", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "This crash report never left your phone. Copy it to share it, or dismiss it to delete it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text.take(600) + if (text.length > 600) "\n…" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 12,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(text))
                    copied = true
                }) { Text(if (copied) "Copied" else "Copy") }
                OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun ContinueCard(title: String, subtitle: String, onClick: () -> Unit) {
    SathiCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).background(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Continue", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun QuickCard(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    SathiCard(modifier, onClick = onClick, contentPadding = 14.dp) {
        Box(
            Modifier.size(38.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        Text(
            subtitle, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * New session flow (Step 1): name prefilled from the folder name →
 * [Choose folder] (system picker) → template picker (Phase 6,
 * Step 3 — blank by default) → current default model → Create.
 * The template only queues its files as diff-card proposals in the
 * new session's chat; nothing is written until Approve is tapped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSessionScreen(
    viewModel: AppViewModel,
    onCreated: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val folderName = viewModel.onFolderPicked(context, uri)
        if (folderName == null) {
            error = "Could not use this folder. Try another one."
        } else if (name.isBlank()) {
            name = folderName
        }
    }

    // If onboarding already picked a folder, reuse it as the starting point.
    val pickedName = viewModel.pickedFolderName
    androidx.compose.runtime.LaunchedEffect(pickedName) {
        if (pickedName.isNotBlank() && name.isBlank()) name = pickedName
    }

    SathiScaffold(
        topBar = {
            SathiTopBar(
                title = { TopBarTitle("New session", "Folder → template → create") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Session name") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            SathiCard(Modifier.fillMaxWidth(), onClick = { picker.launch(null) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (viewModel.pickedFolderUri == null) Icons.Filled.Folder else Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (viewModel.pickedFolderUri == null) "Choose folder" else "Folder: $pickedName — change",
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            // Phase 6, Step 3 — template picker. It sits on this screen,
            // after the folder is picked and before Create is tapped:
            // the folder comes first (the template needs a folder to
            // land in), and the choice is recorded in the ViewModel
            // until Create consumes it. A template only pre-fills the
            // first file(s) — each one still waits for Approve on the
            // diff card in the chat, so nothing is written straight away.
            Text("Template", style = MaterialTheme.typography.titleMedium)
            Text(
                "A starting file for the new session. It is only created after you approve it in the chat — nothing is written straight away.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            BUILT_IN_TEMPLATES.forEach { template ->
                SathiCard(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    contentPadding = 6.dp,
                    color = if (viewModel.selectedTemplateId == template.id) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                    onClick = { viewModel.selectTemplate(template.id) }
                ) {
                    Row(
                        modifier = Modifier.padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = viewModel.selectedTemplateId == template.id,
                            onClick = { viewModel.selectTemplate(template.id) }
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(template.name, style = MaterialTheme.typography.titleMedium)
                            Text(template.description, style = MaterialTheme.typography.bodyMedium)
                            if (template.files.isNotEmpty()) {
                                Text(
                                    "Creates: ${template.files.joinToString(", ") { it.path }}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Default model: ${viewModel.providerRepository.topPicks().firstOrNull()?.let { "${it.first.name} / ${it.second}" } ?: "not set"}", style = MaterialTheme.typography.bodyMedium)
            Text("Change it later in Providers.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    val id = viewModel.createSession(name.trim(), context, viewModel.selectedTemplateId)
                    if (id == null) error = "Pehle folder chuniye (Choose folder)."
                    else onCreated(id)
                },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("Create & open") }
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Back") }
            Spacer(Modifier.height(16.dp))
        }
    }
}
