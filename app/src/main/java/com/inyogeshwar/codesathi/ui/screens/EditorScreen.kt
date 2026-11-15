package com.inyogeshwar.codesathi.ui.screens

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import com.inyogeshwar.codesathi.ui.theme.SathiScaffold
import androidx.compose.material3.Text
import com.inyogeshwar.codesathi.ui.theme.SathiTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.inyogeshwar.codesathi.agent.TerminalFolder
import com.inyogeshwar.codesathi.ui.AppViewModel
import com.inyogeshwar.codesathi.ui.EditorViewModel

/**
 * Phase 3, Step 3 — editor with tabs and a live .html preview.
 *
 * - Works on the last-opened session's project folder (same session
 *   choice as the terminal and Git). When the folder cannot be
 *   resolved to a raw path, the screen refuses with a plain message
 *   telling the user to re-choose the folder from Home.
 * - File list: the top-level files of the folder (same SAF listing the
 *   agent's glob tool uses). Folders are shown but cannot be opened —
 *   a fuller tree browser is Step 5.
 * - File list (Phase 3, Step 5): the old top-level chips are replaced
 *   by [FolderTree] — an expandable tree of the whole project folder
 *   (children load lazily on expand), tap a file to open it, tap "+"
 *   on a folder row to start a new session from that subfolder.
 * - Tabs: tap a file to open it; edits stay in memory per tab with a
 *   "•" dirty marker; switching tabs keeps unsaved edits; closing a
 *   dirty tab asks "Discard changes?" first.
 * - Save goes through the SAME diff card as the agent's writes
 *   ([DiffCard] + [AppViewModel.pendingProposal]): the file is only
 *   written after Approve, and Plan mode refuses to save at all.
 * - Preview: for .html files, an Editor / Preview toggle under the
 *   tabs. The preview is a plain WebView fed with the file's SAVED
 *   text read through SAF ([android.webkit.WebView.loadDataWithBaseURL])
 *   — no SAF URI or file:// URL is ever loaded, so the WebView needs
 *   no filesystem access. It reloads when a file is opened and when a
 *   save is approved; typing alone does not reload it. A phone screen
 *   is too narrow for editor + preview side by side, so the toggle
 *   (not a split) is the layout. Local relative assets (a linked
 *   .css / .js / image next to the .html) do NOT load in the preview:
 *   the content is in-memory data, not a file URL.
 * - Text files only: binary files (by extension and by a NUL byte in
 *   the first bytes) and non-UTF-8 files are refused with a plain
 *   message; files over 1 MB are refused too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    appViewModel: AppViewModel,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit
) {
    val context = LocalContext.current
    val sessionReady by viewModel.sessionReady.collectAsState()
    val session by viewModel.session.collectAsState()
    var showPreview by remember { mutableStateOf(false) }
    val activePath = viewModel.activePath
    // Leaving an .html file (or opening a non-.html one) goes back to
    // the editor view — the preview only exists for .html files.
    LaunchedEffect(activePath) { showPreview = false }

    SathiScaffold(
        topBar = {
            SathiTopBar(
                title = { Text("Editor" + (session?.let { " — ${it.title}" } ?: "")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
            if (!sessionReady) {
                Text("Loading…", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            val currentSession = session
            if (currentSession == null) {
                Text("No session yet.", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                Text("Create a session from Home first, then open the editor.")
                Spacer(Modifier.height(16.dp))
                Button(onClick = onBack) { Text("Back") }
                return@Column
            }
            val folder = viewModel.folder
            if (folder == null) {
                Text("Loading…", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            if (folder is TerminalFolder.Unresolved) {
                Text("Folder unavailable", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                Text(folder.reason)
                Spacer(Modifier.height(8.dp))
                Text("Go back to Home and open the session to re-choose its folder.")
                Spacer(Modifier.height(16.dp))
                Button(onClick = onBack) { Text("Back") }
                return@Column
            }

            // Folder card + refresh.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Folder: ${currentSession.folderName}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { viewModel.refreshFiles() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh file list")
                }
            }

            // Folder tree (Phase 3, Step 5): replaces the old
            // top-level file chips. Expandable folders (children load
            // lazily), tap a file to open it in a tab, "+" on a folder
            // row starts a new session from that subfolder.
            FolderTree(
                viewModel = viewModel,
                appViewModel = appViewModel,
                onOpenSession = onOpenSession
            )

            // Tabs: name + dirty dot + close. Tap a tab to switch to it.
            if (viewModel.tabs.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    items(viewModel.tabs, key = { it.path }) { tab ->
                        val selected = tab.path == viewModel.activePath
                        Card(
                            onClick = { viewModel.switchTab(tab.path) },
                            colors = androidx.compose.material3.CardDefaults.cardColors(
                                containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp)
                            ) {
                                Text(
                                    tab.path + if (tab.isDirty) " •" else "",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                IconButton(onClick = { viewModel.requestCloseTab(tab.path) }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Close ${tab.path}")
                                }
                            }
                        }
                    }
                }
            }

            // Diff card: the SAME card the chat shows for agent writes.
            // When the pending proposal is this editor's save, Approve
            // does NOT resume any agent turn (there is none) and reports
            // the outcome back so the tab's dirty marker can clear.
            // An agent proposal approved here resumes the agent loop,
            // exactly as approving it in Chat would. A template proposal
            // (Phase 6, Step 3) shows only when it belongs to this
            // session — see shouldShowProposalFor.
            val pendingSavePath = viewModel.awaitingApproval?.path
            appViewModel.pendingProposal?.takeIf { appViewModel.shouldShowProposalFor(currentSession.id) }?.let { proposal ->
                DiffCard(
                    proposal = proposal,
                    oldContent = appViewModel.pendingProposalOld,
                    onApprove = {
                        if (pendingSavePath != null) {
                            appViewModel.approveProposal(
                                currentSession, context, resumeAgent = false
                            ) { ok -> viewModel.onSaveApplied(pendingSavePath, ok) }
                        } else {
                            appViewModel.approveProposal(currentSession, context)
                        }
                    },
                    onReject = {
                        appViewModel.rejectProposal(currentSession)
                        if (pendingSavePath != null) viewModel.onSaveRejected()
                    }
                )
            }

            // "Discard changes?" confirm for closing a dirty tab.
            viewModel.pendingClosePath?.let { path ->
                AlertDialog(
                    onDismissRequest = { viewModel.dismissCloseConfirm() },
                    title = { Text("Discard changes?") },
                    text = { Text("$path has unsaved changes. Close without saving?") },
                    confirmButton = {
                        Button(onClick = { viewModel.confirmCloseTab() }) { Text("Discard") }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = { viewModel.dismissCloseConfirm() }) {
                            Text("Keep editing")
                        }
                    }
                )
            }

            val activeTab = viewModel.activeTab
            if (activeTab == null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    if (viewModel.isLoading) "Opening…" else "Tap a file above to open it in a tab.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                // Editor / Preview toggle, .html files only.
                if (viewModel.isHtmlActive) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        if (showPreview) {
                            OutlinedButton(onClick = { showPreview = false }) { Text("Editor") }
                            Button(onClick = {}) { Text("Preview") }
                        } else {
                            Button(onClick = {}) { Text("Editor") }
                            OutlinedButton(onClick = { showPreview = true }) { Text("Preview") }
                        }
                    }
                }
                if (showPreview && viewModel.isHtmlActive) {
                    // The preview shows the SAVED text, and reloads only
                    // when the file is opened or a save is approved
                    // (previewVersion) — never while typing.
                    val previewContent = activeTab.originalContent
                    key(activeTab.path, viewModel.previewVersion) {
                        AndroidView(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    // Content comes from memory (read
                                    // through SAF), never a file:// or SAF
                                    // URI — the WebView gets no filesystem
                                    // access, so local relative assets do
                                    // not load (see the header comment).
                                    settings.allowFileAccess = false
                                    webViewClient = WebViewClient()
                                    loadDataWithBaseURL(
                                        null, previewContent, "text/html", "UTF-8", null
                                    )
                                }
                            },
                            update = {},
                            onRelease = { it.destroy() }
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = activeTab.currentContent,
                        onValueChange = { viewModel.updateTabContent(activeTab.path, it) },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        ),
                        singleLine = false,
                        maxLines = Int.MAX_VALUE,
                        enabled = !viewModel.isLoading
                    )
                }

                // Save row: Save is disabled in Plan mode and while a
                // proposal is waiting for approval.
                val isPlan = currentSession.agentMode == "plan"
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.saveActiveTab(context, appViewModel) },
                        enabled = activeTab.isDirty &&
                            appViewModel.pendingProposal == null &&
                            !isPlan &&
                            !viewModel.isLoading
                    ) { Text("Save") }
                    Text(
                        when {
                            isPlan -> "Plan mode is read-only — switch to Build in Chat to save."
                            activeTab.isDirty -> "Unsaved changes"
                            else -> "Saved"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            viewModel.message?.let { note ->
                Text(note, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            }
            viewModel.error?.let { err ->
                Text(
                    err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
