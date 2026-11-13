package com.inyogeshwar.codesathi.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.inyogeshwar.codesathi.agent.SafEntry
import com.inyogeshwar.codesathi.ui.AppViewModel
import com.inyogeshwar.codesathi.ui.EditorViewModel

/**
 * Phase 3, Step 5 — folder tree browser for the Editor screen.
 *
 * Replaces the old top-level file chips. The root level reuses the
 * editor's existing top-level listing ([EditorViewModel.files]); every
 * subfolder loads its children lazily the first time it is expanded,
 * through the same [com.inyogeshwar.codesathi.agent.SafProject.listEntries]
 * the agent's glob tool uses — there is no second listing
 * implementation. Collapsing a folder drops its cached children, so
 * memory stays proportional to what is on screen.
 *
 * - Tap a file: open it in a tab (nested paths work — see
 *   [EditorViewModel.openFile], which reads through
 *   `SafProject.find("sub/file.kt")`).
 * - Tap a folder's chevron: expand / collapse it.
 * - Tap the "+" on a folder row: start a new session from that
 *   subfolder (a small visible button, not a hidden long-press — a
 *   long-press would be undiscoverable). A confirm dialog names the
 *   folder first; on confirm the editor creates the session and shows
 *   the result with an "Open session" button instead of navigating
 *   away silently.
 *
 * Caps (see [EditorViewModel]): folders expand at most
 * [EditorViewModel.MAX_TREE_DEPTH] levels deep (deeper folders are
 * still reachable — open them as their own session with "+"), at most
 * [EditorViewModel.MAX_CHILDREN_PER_DIR] entries are shown per folder,
 * and at most [EditorViewModel.MAX_TREE_NODES] tree nodes are loaded
 * in total, so a huge folder cannot freeze the UI.
 */
@Composable
fun FolderTree(
    viewModel: EditorViewModel,
    appViewModel: AppViewModel,
    onOpenSession: (String) -> Unit
) {
    val context = LocalContext.current
    var confirmDir by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Folders open up to ${EditorViewModel.MAX_TREE_DEPTH} levels deep — " +
                "for a deeper folder, tap + to open it as its own session.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        if (viewModel.files.isEmpty()) {
            Text(
                "No files in this folder yet.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        } else {
            TreeDir(
                viewModel = viewModel,
                relativeDir = "",
                depth = 0,
                entries = viewModel.files,
                onNewSession = { confirmDir = it }
            )
        }
        viewModel.treeError?.let { err ->
            Text(
                err,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        // A subfolder session was just created: show the outcome
        // (including the honest grant note) with a way to open it.
        viewModel.createdSubfolderSessionId?.let { sessionId ->
            Text(
                viewModel.subfolderNote ?: "New session created.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
            Row(modifier = Modifier.padding(top = 4.dp)) {
                Button(onClick = {
                    viewModel.consumeCreatedSubfolderSession()
                    onOpenSession(sessionId)
                }) { Text("Open session") }
                Spacer(modifier = Modifier.size(8.dp))
                OutlinedButton(onClick = { viewModel.consumeCreatedSubfolderSession() }) {
                    Text("Stay here")
                }
            }
        }
    }

    // Confirm before creating a session from a subfolder.
    confirmDir?.let { dir ->
        AlertDialog(
            onDismissRequest = { confirmDir = null },
            title = { Text("New session from this folder?") },
            text = {
                Text(
                    "Create a new session in \"$dir\"? It starts in Plan mode " +
                        "with the current default model, like any new session."
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.createSessionFromSubfolder(context, appViewModel, dir)
                    confirmDir = null
                }) { Text("Create") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmDir = null }) { Text("Cancel") }
            }
        )
    }
}

/** One level of the tree: folder rows (expandable) then file rows. */
@Composable
private fun TreeDir(
    viewModel: EditorViewModel,
    relativeDir: String,
    depth: Int,
    entries: List<SafEntry>,
    onNewSession: (String) -> Unit
) {
    Column {
        entries.forEach { entry ->
            val path = if (relativeDir.isBlank()) entry.name else "$relativeDir/${entry.name}"
            if (entry.isDirectory) {
                val expanded = path in viewModel.expandedDirs
                val tooDeep = viewModel.treeDepth(path) >= EditorViewModel.MAX_TREE_DEPTH
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = (depth * 16).dp)
                ) {
                    if (tooDeep) {
                        // No chevron at the depth cap: the folder cannot
                        // expand further, but "+" still opens it as a
                        // session of its own.
                        Spacer(modifier = Modifier.size(48.dp))
                    } else {
                        IconButton(onClick = { viewModel.toggleTreeDir(path) }) {
                            Icon(
                                if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                                contentDescription = if (expanded) "Collapse ${entry.name}" else "Expand ${entry.name}"
                            )
                        }
                    }
                    Icon(
                        Icons.Filled.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "${entry.name}/",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp)
                            .clickable(enabled = !tooDeep) { viewModel.toggleTreeDir(path) }
                    )
                    IconButton(onClick = { onNewSession(path) }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "New session from ${entry.name}"
                        )
                    }
                }
                if (expanded && !tooDeep) {
                    if (path in viewModel.treeLoading) {
                        Text(
                            "Loading…",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = ((depth + 1) * 16 + 48).dp)
                        )
                    } else {
                        viewModel.treeChildren[path]?.let { children ->
                            TreeDir(
                                viewModel = viewModel,
                                relativeDir = path,
                                depth = depth + 1,
                                entries = children,
                                onNewSession = onNewSession
                            )
                        }
                    }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = (depth * 16).dp)
                        .clickable { viewModel.openFile(path) }
                        .padding(vertical = 2.dp)
                ) {
                    // Align with folder rows (48dp chevron slot).
                    Spacer(modifier = Modifier.size(48.dp))
                    Icon(Icons.Filled.InsertDriveFile, contentDescription = null)
                    Text(
                        entry.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }
    }
}
