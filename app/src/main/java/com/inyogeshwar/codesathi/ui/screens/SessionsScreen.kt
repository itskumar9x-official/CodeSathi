package com.inyogeshwar.codesathi.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inyogeshwar.codesathi.agent.BUILT_IN_TEMPLATES
import com.inyogeshwar.codesathi.data.Session
import com.inyogeshwar.codesathi.ui.AppViewModel
import com.inyogeshwar.codesathi.ui.theme.ClozedInfoCard
import com.inyogeshwar.codesathi.ui.theme.ClozedSheet
import com.inyogeshwar.codesathi.ui.theme.LocalBottomBarInset
import com.inyogeshwar.codesathi.ui.theme.LocalSathiStatus
import com.inyogeshwar.codesathi.ui.theme.LocalSpacing
import com.inyogeshwar.codesathi.ui.theme.Pill
import com.inyogeshwar.codesathi.ui.theme.SathiCard
import com.inyogeshwar.codesathi.ui.theme.SathiMark
import com.inyogeshwar.codesathi.ui.theme.SathiScaffold
import com.inyogeshwar.codesathi.ui.theme.SathiTopBar

/**
 * Phase 8 Sessions — the new Home (start destination after onboarding),
 * in Clozed's layout: small logo + big "Sessions" title, refresh + gear
 * top-right, green-dot "live · on-device" pill, key-warning card, session
 * cards with avatar + ⋮ menu, floating "+ New session" pill. No tab bar.
 *
 * Keeps everything the old Chats tab had: search (Phase 5, Step 4 — live
 * Room LIKE filter), pin / rename / delete, backup and restore (Phase 5,
 * Step 3), encrypted backup (Phase 7, Step B3), and the crash report that
 * lived on Home (Phase 7, Step B3), moved here so it is not lost.
 *
 * New sessions open as a bottom sheet, not a separate screen.
 * [onSettings] opens Settings (the gear icon).
 */
@Composable
fun SessionsScreen(
    viewModel: AppViewModel,
    onOpenSession: (String) -> Unit,
    onSettings: () -> Unit
) {
    val sessions by viewModel.filteredSessions.collectAsState()
    val searchQuery = viewModel.sessionSearchQuery
    val context = LocalContext.current
    val spacing = LocalSpacing.current
    val bottomInset = LocalBottomBarInset.current
    val backup = rememberBackupActions(viewModel)
    var menuOpen by remember { mutableStateOf(false) }
    // One dialog at a time; both live at this level (not inside the
    // LazyColumn) so they survive list recomposition.
    var renameTarget by remember { mutableStateOf<Session?>(null) }
    var deleteTarget by remember { mutableStateOf<Session?>(null) }
    // Encrypted backup (Phase 7, Step B3): the passphrase dialog.
    var showEncryptedBackup by remember { mutableStateOf(false) }
    var showNewSession by remember { mutableStateOf(false) }
    // Refresh re-checks folder availability: the item keys carry the tick,
    // so every row recomposes and re-reads its folder grant.
    var refreshTick by remember { mutableStateOf(0) }

    // Key warning: read for subscription only — KeyStore is not a Flow, so
    // the check is re-run whenever this version bumps (add / delete key).
    @Suppress("UNUSED_VARIABLE")
    val keysVersion = viewModel.keysVersion
    val hasKey = remember(keysVersion) {
        viewModel.hasAwsCredential() || viewModel.localModelName != null ||
            viewModel.providerRepository.providers.any { !it.local && viewModel.hasApiKey(it.id) }
    }
    val live = LocalSathiStatus.current.live

    SathiScaffold(
        topBar = {
            SathiTopBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SathiMark(size = 32.dp, decorative = true)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Sessions",
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { refreshTick++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Backup sessions") },
                                leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                                onClick = { menuOpen = false; backup.backup() }
                            )
                            DropdownMenuItem(
                                text = { Text("Backup with encrypted keys") },
                                leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                                onClick = { menuOpen = false; showEncryptedBackup = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Restore sessions") },
                                leadingIcon = { Icon(Icons.Filled.Upload, contentDescription = null) },
                                onClick = { menuOpen = false; backup.restore() }
                            )
                        }
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { _ ->
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = spacing.gutter, end = spacing.gutter, top = spacing.xs,
                    bottom = bottomInset + 88.dp
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.sm + 2.dp)
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
                    Pill(
                        text = "live · on-device",
                        leading = {
                            Box(Modifier.size(8.dp).background(live, CircleShape))
                        }
                    )
                }
                if (!hasKey) {
                    item {
                        ClozedInfoCard(
                            title = "No provider key yet",
                            text = "Add a provider API key to start chatting — pick a " +
                                "provider in Settings and paste one key. Your keys stay " +
                                "encrypted on this phone.",
                            onClick = onSettings
                        )
                    }
                }
                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { viewModel.setSessionSearchQuery(it) },
                        placeholder = { Text("Search sessions") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        ),
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setSessionSearchQuery("") }) {
                                    Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(bottom = spacing.xs)
                    )
                }
                item { BackupResult(viewModel) }
                if (sessions.isEmpty()) {
                    item {
                        // A search with no matches says so plainly.
                        Text(
                            if (searchQuery.isBlank())
                                "Abhi koi session nahi hai. “New session” dabakar pehla session banayein — folder chunenge, path type nahi karna."
                            else "No sessions match “$searchQuery”.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = spacing.lg)
                        )
                    }
                }
                items(sessions, key = { "${refreshTick}:${it.id}" }) { session ->
                    SessionRow(
                        session = session,
                        available = viewModel.isFolderAvailable(context, session),
                        onOpen = { onOpenSession(session.id) },
                        onRechoose = { uri -> viewModel.rechooseFolder(context, session, uri) },
                        onTogglePin = { viewModel.setSessionPinned(session.id, !session.pinned) },
                        onRename = { renameTarget = session },
                        onDelete = { deleteTarget = session }
                    )
                }
            }
            ExtendedFloatingActionButton(
                onClick = { showNewSession = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New session") },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = spacing.gutter, bottom = bottomInset + spacing.sm)
            )
        }
    }
    renameTarget?.let { session ->
        RenameSessionDialog(
            session = session,
            onDismiss = { renameTarget = null },
            onRename = { title ->
                if (viewModel.renameSession(session.id, title)) {
                    renameTarget = null
                    true
                } else {
                    false
                }
            }
        )
    }
    deleteTarget?.let { session ->
        DeleteSessionDialog(
            session = session,
            onConfirm = {
                viewModel.deleteSession(session.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null }
        )
    }
    // Encrypted backup (Phase 7, Step B3): the passphrase is typed
    // here, used for this one export, and never stored anywhere.
    if (showEncryptedBackup) {
        EncryptedBackupDialog(
            onDismiss = { showEncryptedBackup = false },
            onConfirm = { passphrase ->
                showEncryptedBackup = false
                backup.backupEncrypted(passphrase)
            }
        )
    }
    // Encrypted import preview (Phase 7, Step B3): counts first, then
    // the passphrase when the backup carries secrets — nothing is
    // applied until Import is tapped.
    viewModel.encryptedImportPreview?.let { preview ->
        EncryptedImportDialog(
            preview = preview,
            onDismiss = { viewModel.dismissEncryptedImportPreview() },
            onConfirm = { passphrase -> viewModel.confirmEncryptedImport(passphrase) }
        )
    }
    if (showNewSession) {
        NewSessionSheet(
            viewModel = viewModel,
            onDismiss = { showNewSession = false },
            onCreated = { id ->
                showNewSession = false
                onOpenSession(id)
            }
        )
    }
}

/**
 * New session as a bottom sheet (Phase 8): name prefilled from the
 * folder name → [Choose project folder] (system picker) → template
 * picker (Phase 6, Step 3 — blank by default) → current default model
 * → Start. There is no "copy from another project" — CodeSathi has no
 * such feature, so it is not offered here.
 * The template only queues its files as diff-card proposals in the
 * new session's chat; nothing is written until Approve is tapped.
 */
@Composable
private fun NewSessionSheet(
    viewModel: AppViewModel,
    onDismiss: () -> Unit,
    onCreated: (String) -> Unit
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
    LaunchedEffect(pickedName) {
        if (pickedName.isNotBlank() && name.isBlank()) name = pickedName
    }
    val folderChosen = viewModel.pickedFolderUri != null

    ClozedSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text("New session", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { picker.launch(null) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = MaterialTheme.shapes.extraLarge,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Icon(
                    if (folderChosen) Icons.Filled.CheckCircle else Icons.Filled.Folder,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(if (folderChosen) "Folder: $pickedName — change" else "Choose project folder")
            }
            if (folderChosen) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Project folder: $pickedName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Project name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            // Phase 6, Step 3 — template picker. It sits in this sheet,
            // after the folder is picked and before Start is tapped:
            // the folder comes first (the template needs a folder to
            // land in), and the choice is recorded in the ViewModel
            // until Start consumes it. A template only pre-fills the
            // first file(s) — each one still waits for Approve on the
            // diff card in the chat, so nothing is written straight away.
            Text("Template", style = MaterialTheme.typography.titleMedium)
            Text(
                "A starting file for the new session. It is only created after you approve it in the chat — nothing is written straight away.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
            Text("Change it later in Settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).height(52.dp)
                ) { Text("Cancel") }
                Button(
                    onClick = {
                        val id = viewModel.createSession(name.trim(), context, viewModel.selectedTemplateId)
                        if (id == null) error = "Pehle folder chuniye (Choose folder)."
                        else onCreated(id)
                    },
                    modifier = Modifier.weight(1f).height(52.dp)
                ) { Text("Start") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Crash report card, moved here from Home (Phase 7, Step B3). */
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

/** Passphrase dialog for the encrypted backup export. */
@Composable
private fun EncryptedBackupDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Backup with encrypted keys") },
        text = {
            Column {
                Text(
                    "The backup also carries your API keys and credentials, " +
                        "encrypted with this passphrase. It is not stored anywhere — " +
                        "you will need it to restore the keys.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase (at least 8 characters)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text("Repeat passphrase") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    passphrase.length < 8 -> error = "Use at least 8 characters."
                    passphrase != confirm -> error = "The two passphrases do not match."
                    else -> onConfirm(passphrase)
                }
            }) { Text("Save backup") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Preview dialog for an encrypted backup import. */
@Composable
private fun EncryptedImportDialog(
    preview: com.inyogeshwar.codesathi.data.EncryptedBackupPreview,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit
) {
    var passphrase by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore encrypted backup") },
        text = {
            Column {
                Text(
                    "${preview.sessionCount} session" +
                        (if (preview.sessionCount == 1) "" else "s") +
                        ", ${preview.messageCount} message" +
                        (if (preview.messageCount == 1) "" else "s") +
                        " in this backup.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (preview.hasSecrets) {
                        "It also carries encrypted API keys and credentials. " +
                            "Enter the passphrase it was encrypted with to restore them. " +
                            "Keys already on this device are kept — nothing is overwritten."
                    } else {
                        "It carries no encrypted keys — add your API keys again in Providers after restoring."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                if (preview.hasSecrets) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("Passphrase") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(if (preview.hasSecrets) passphrase else null) }) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
