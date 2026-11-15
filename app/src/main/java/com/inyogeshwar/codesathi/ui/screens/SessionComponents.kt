package com.inyogeshwar.codesathi.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.inyogeshwar.codesathi.agent.hasAllFilesAccess
import com.inyogeshwar.codesathi.agent.isAllFilesAccessSupported
import com.inyogeshwar.codesathi.agent.openAllFilesAccessSettings
import com.inyogeshwar.codesathi.data.Session
import com.inyogeshwar.codesathi.ui.AppViewModel
import com.inyogeshwar.codesathi.ui.theme.LocalSathiStatus
import com.inyogeshwar.codesathi.ui.theme.Pill
import com.inyogeshwar.codesathi.ui.theme.SathiCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 5, Step 3 — session backup & restore. Both use the system file
 * picker (SAF) only: CreateDocument lets the user choose where the
 * backup goes, OpenDocument lets them pick a backup to restore. No new
 * permission, no path text field. v1.2: shared by Chats and Settings.
 * Phase 7, Step B3 adds the encrypted backup: a ZIP with the same
 * sessions JSON plus the device's secrets encrypted with a passphrase
 * the user types (never stored). Restore accepts both the plain JSON
 * and the encrypted ZIP — a ZIP shows a preview first.
 */
class BackupActions(
    val backup: () -> Unit,
    val restore: () -> Unit,
    /** Export the encrypted backup (ZIP); the passphrase is typed by the caller. */
    val backupEncrypted: (String) -> Unit = {}
)

@Composable
internal fun rememberBackupActions(viewModel: AppViewModel): BackupActions {
    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? -> if (uri != null) viewModel.exportSessions(context, uri) }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) viewModel.importSessions(context, uri) }
    // The passphrase for the encrypted export, held only until the
    // picker returns — it is never stored anywhere.
    var encryptedPassphrase by remember { mutableStateOf<String?>(null) }
    val encryptedExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        val passphrase = encryptedPassphrase
        encryptedPassphrase = null
        if (uri != null && passphrase != null) {
            viewModel.exportEncryptedSessions(context, uri, passphrase)
        }
    }
    return BackupActions(
        backup = {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
            exportLauncher.launch("codesathi-backup-$stamp.json")
        },
        restore = { importLauncher.launch(arrayOf("application/json", "application/zip")) },
        backupEncrypted = { passphrase ->
            encryptedPassphrase = passphrase
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
            encryptedExportLauncher.launch("codesathi-backup-$stamp.zip")
        }
    )
}

/** Plain result of the last backup / restore, if any. */
@Composable
internal fun BackupResult(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    viewModel.backupNote?.let { note ->
        Text(note, style = MaterialTheme.typography.bodyMedium, modifier = modifier.padding(vertical = 6.dp))
    }
    viewModel.backupError?.let { error ->
        Text(
            error,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier.padding(vertical = 6.dp)
        )
    }
}

/**
 * Phase 3, Step 4 — "Storage access" card (v1.2: lives in Settings).
 *
 * All-files access (MANAGE_EXTERNAL_STORAGE) is a special permission:
 * it can only be granted from system Settings, never from a runtime
 * dialog, so this card only OPENS that screen — and only when the
 * user taps it, never unprompted or at startup. The state is re-read
 * on every resume, so coming back from Settings shows the new state
 * without reopening the app.
 *
 * On Android 10 and older the permission does not exist: the card is
 * shown disabled with a one-line explanation instead of being hidden.
 */
@Composable
internal fun StorageAccessCard(modifier: Modifier = Modifier) {
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
    val status = LocalSathiStatus.current

    SathiCard(
        modifier = modifier.fillMaxWidth(),
        onClick = if (supported) ({ openAllFilesAccessSettings(context) }) else null
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.SdStorage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text("Storage access", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            when {
                !supported -> Pill("Not available")
                granted -> Pill("Granted", container = status.success.copy(alpha = 0.16f), content = status.success)
                else -> Pill("Not granted")
            }
        }
        Spacer(Modifier.height(8.dp))
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
        Spacer(Modifier.height(6.dp))
        Text(
            "What it does NOT do: it does not send your files anywhere, and " +
                "your API keys stay encrypted on this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (supported) {
            Spacer(Modifier.height(8.dp))
            Text(
                if (granted) "Tap to open system settings (to change it)."
                else "Tap to open system settings and turn it on.",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * One session in a list. Tapping opens it, or — when its folder grant
 * is gone — opens the system folder picker to re-choose it.
 * [showActions] adds pin / rename / delete (Phase 5, Step 4) behind a
 * "⋮" menu; the pin state stays visible as an icon.
 */
@Composable
internal fun SessionRow(
    session: Session,
    available: Boolean,
    onOpen: () -> Unit,
    onRechoose: (Uri) -> Unit,
    modifier: Modifier = Modifier,
    showActions: Boolean = true,
    onTogglePin: () -> Unit = {},
    onRename: () -> Unit = {},
    onDelete: () -> Unit = {}
) {
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? -> if (uri != null) onRechoose(uri) }
    var menuOpen by remember { mutableStateOf(false) }
    val status = LocalSathiStatus.current

    SathiCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = 14.dp,
        onClick = { if (available) onOpen() else picker.launch(null) }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Folder badge with a status dot: teal = folder reachable, red = unavailable.
            Box {
                Box(
                    Modifier.size(42.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(11.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                        .padding(2.dp)
                        .background(if (available) status.success else MaterialTheme.colorScheme.error, CircleShape)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (session.pinned) {
                        Icon(
                            Icons.Filled.PushPin, contentDescription = "Pinned",
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        session.title, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    "${session.folderName}  ·  ${relativeTime(session.updatedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${session.providerId} / ${session.modelId}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (showActions) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Session actions")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(if (session.pinned) "Unpin session" else "Pin session") },
                            leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null) },
                            onClick = { menuOpen = false; onTogglePin() }
                        )
                        DropdownMenuItem(
                            text = { Text("Rename session") },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                            onClick = { menuOpen = false; onRename() }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete session") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = { menuOpen = false; onDelete() }
                        )
                    }
                }
            }
        }
        if (!available) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Folder unavailable — tap to re-choose",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

/** "just now", "5 min ago", "3 h ago", "2 d ago", else the date. */
internal fun relativeTime(millis: Long, now: Long = System.currentTimeMillis()): String {
    val diff = (now - millis).coerceAtLeast(0)
    val min = diff / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        min < 60 * 24 -> "${min / 60} h ago"
        min < 60 * 24 * 7 -> "${min / (60 * 24)} d ago"
        else -> SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(millis))
    }
}

/** Delete confirmation (Phase 5, Step 4). */
@Composable
internal fun DeleteSessionDialog(session: Session, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete this session?") },
        text = { Text("“${session.title}” and its messages will be deleted. This cannot be undone.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Phase 5, Step 4 — rename dialog. The field starts with the current
 * title; an empty title is refused with a plain message and the old
 * title stays (the dialog stays open, nothing is written).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RenameSessionDialog(
    session: Session,
    onDismiss: () -> Unit,
    onRename: (String) -> Boolean
) {
    var title by remember { mutableStateOf(session.title) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename session") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Session name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!onRename(title)) {
                        error = "Title cannot be empty — the old title stays."
                    }
                }
            ) { Text("Rename") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

