package com.inyogeshwar.codesathi.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.inyogeshwar.codesathi.agent.hasAllFilesAccess
import com.inyogeshwar.codesathi.agent.isAllFilesAccessSupported
import com.inyogeshwar.codesathi.agent.openAllFilesAccessSettings
import com.inyogeshwar.codesathi.data.Session
import com.inyogeshwar.codesathi.ui.AppViewModel
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: AppViewModel,
    onOpenSession: (String) -> Unit,
    onNewSession: () -> Unit,
    onProviders: () -> Unit,
    onMcp: () -> Unit,
    onSkills: () -> Unit,
    onCommands: () -> Unit,
    onTerminal: () -> Unit,
    onRemote: () -> Unit,
    onGit: () -> Unit,
    onEditor: () -> Unit
) {
    val sessions by viewModel.sessions.collectAsState()
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CodeSathi") },
                actions = {
                    // Phase 3, Step 5 — overflow menu: eight icons did
                    // not fit a narrow phone, and Providers (Settings)
                    // sat last so it clipped first. The four most-used
                    // destinations stay visible (Editor, Git, Terminal,
                    // Providers — Providers is always one tap away on
                    // any screen width); Remote, Skills, Commands and
                    // MCP live behind the "⋮" menu. No destination was
                    // removed — all eight stay reachable. Same
                    // DropdownMenu pattern as the Chat screen's menu.
                    IconButton(onClick = onEditor) {
                        Icon(Icons.Filled.Edit, contentDescription = "Editor")
                    }
                    IconButton(onClick = onGit) {
                        Icon(Icons.Filled.AccountTree, contentDescription = "Git")
                    }
                    IconButton(onClick = onTerminal) {
                        Icon(Icons.Filled.Terminal, contentDescription = "Terminal")
                    }
                    IconButton(onClick = onProviders) {
                        Icon(Icons.Filled.Settings, contentDescription = "Providers")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Remote mode") },
                            leadingIcon = { Icon(Icons.Filled.Wifi, contentDescription = null) },
                            onClick = { menuOpen = false; onRemote() }
                        )
                        DropdownMenuItem(
                            text = { Text("Skills") },
                            leadingIcon = { Icon(Icons.Filled.Build, contentDescription = null) },
                            onClick = { menuOpen = false; onSkills() }
                        )
                        DropdownMenuItem(
                            text = { Text("Custom commands") },
                            leadingIcon = { Icon(Icons.Filled.Code, contentDescription = null) },
                            onClick = { menuOpen = false; onCommands() }
                        )
                        DropdownMenuItem(
                            text = { Text("MCP servers") },
                            leadingIcon = { Icon(Icons.Filled.Extension, contentDescription = null) },
                            onClick = { menuOpen = false; onMcp() }
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewSession) {
                Icon(Icons.Filled.Add, contentDescription = "New session")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("Sessions", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            StorageAccessCard()
            Spacer(Modifier.height(12.dp))
            if (sessions.isEmpty()) {
                Text(
                    "Abhi koi session nahi hai. Neeche + dabakar pehla session banayein — folder chunenge, path type nahi karna.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sessions, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        available = viewModel.isFolderAvailable(context, session),
                        modelLabel = "${session.providerId} / ${session.modelId}",
                        onOpen = { onOpenSession(session.id) },
                        onRechoose = { uri -> viewModel.rechooseFolder(context, session, uri) }
                    )
                }
            }
        }
    }
}

/**
 * Phase 3, Step 4 — "Storage access" card on Home.
 *
 * All-files access (MANAGE_EXTERNAL_STORAGE) is a special permission:
 * it can only be granted from system Settings, never from a runtime
 * dialog, so this card only OPENS that screen — and only when the
 * user taps it, never unprompted or at startup. The state is re-read
 * on every resume, so coming back from Settings shows the new state
 * without reopening the app.
 *
 * On Android 10 and older the permission does not exist: the card is
 * shown disabled with a one-line explanation instead of being hidden,
 * so the user is not left wondering where it went.
 */
@Composable
private fun StorageAccessCard() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasAllFilesAccess()) }
    val lifecycleOwner = context as? LifecycleOwner
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = hasAllFilesAccess()
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }
    val supported = isAllFilesAccessSupported()

    Card(
        modifier = Modifier.fillMaxWidth().then(
            if (supported) Modifier.clickable { openAllFilesAccessSettings(context) }
            else Modifier
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Storage access", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            when {
                !supported -> Text(
                    "All-files access needs Android 11 or newer — this phone is older, " +
                        "so the app keeps using the folder picker only.",
                    style = MaterialTheme.typography.bodyMedium
                )
                granted -> Text(
                    "Granted — the app can read and write files in the project folder " +
                        "directly, so the terminal, Git and editor work on real files " +
                        "instead of only through the folder picker grant.",
                    style = MaterialTheme.typography.bodyMedium
                )
                else -> Text(
                    "Not granted — the app works through the folder picker only. " +
                        "Turning this on lets the terminal, Git and editor read and " +
                        "write files in the project folder directly, instead of only " +
                        "through the folder picker grant.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "What it does NOT do: it does not send your files anywhere, and " +
                    "your API keys stay encrypted on this device.",
                style = MaterialTheme.typography.bodySmall
            )
            if (supported) {
                Spacer(Modifier.height(8.dp))
                Text(
                    if (granted) "Tap to open system settings (to change it)."
                    else "Tap to open system settings and turn it on.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: Session,
    available: Boolean,
    modelLabel: String,
    onOpen: () -> Unit,
    onRechoose: (Uri) -> Unit
) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? -> if (uri != null) onRechoose(uri) }

    Card(
        modifier = Modifier.fillMaxWidth().clickable {
            if (available) onOpen() else picker.launch(null)
        }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row {
                // Status dot: green = folder reachable, amber = unavailable.
                Text(if (available) "● " else "● ", color = if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Text(session.title, style = MaterialTheme.typography.titleMedium)
            }
            Text("Folder: ${session.folderName}", style = MaterialTheme.typography.bodyMedium)
            Text("Model: $modelLabel", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Last activity: ${DateFormat.getDateTimeInstance().format(Date(session.updatedAt))}",
                style = MaterialTheme.typography.bodySmall
            )
            if (!available) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Folder unavailable — tap to re-choose",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/**
 * New session flow (Step 1): name prefilled from the folder name →
 * [Choose folder] (system picker) → current default model → Create.
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

    Scaffold(topBar = { TopAppBar(title = { Text("New session") }) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Session name") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = { picker.launch(null) }) {
                Text(if (viewModel.pickedFolderUri == null) "Choose folder" else "Folder: $pickedName — change")
            }
            Spacer(Modifier.height(16.dp))
            Text("Default model: ${viewModel.providerRepository.topPicks().firstOrNull()?.let { "${it.first.name} / ${it.second}" } ?: "not set"}")
            Text("Change it later in Providers.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    val id = viewModel.createSession(name.trim())
                    if (id == null) error = "Pehle folder chuniye (Choose folder)."
                    else onCreated(id)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Create & open") }
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
        }
    }
}
