package com.inyogeshwar.codesathi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.inyogeshwar.codesathi.data.Session
import com.inyogeshwar.codesathi.ui.AppViewModel
import com.inyogeshwar.codesathi.ui.theme.LocalBottomBarInset
import com.inyogeshwar.codesathi.ui.theme.LocalSpacing
import com.inyogeshwar.codesathi.ui.theme.SathiScaffold
import com.inyogeshwar.codesathi.ui.theme.SathiTopBar
import com.inyogeshwar.codesathi.ui.theme.TopBarTitle

/**
 * v1.2 "Chats" tab: every session, with search by title (Phase 5,
 * Step 4 — live Room LIKE filter), pin / rename / delete, backup and
 * restore (Phase 5, Step 3) and the New session button.
 */
@Composable
fun SessionsScreen(
    viewModel: AppViewModel,
    onOpenSession: (String) -> Unit,
    onNewSession: () -> Unit
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

    SathiScaffold(
        topBar = {
            SathiTopBar(
                title = { TopBarTitle("Chats", if (sessions.isEmpty()) null else "${sessions.size} session" + if (sessions.size == 1) "" else "s") },
                actions = {
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
                items(sessions, key = { it.id }) { session ->
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
                onClick = onNewSession,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New session") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
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
