package com.inyogeshwar.codesathi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.inyogeshwar.codesathi.agent.TerminalFolder
import com.inyogeshwar.codesathi.ui.GitViewModel

/**
 * Phase 2, Step 8 — Git, via JGit (pure Java, no git binary).
 *
 * Works on the last session's project folder: status (staged /
 * changed / untracked), stage-all and per-file stage / unstage,
 * commit, push, pull, branch list + checkout, and clone. HTTPS only —
 * SSH remotes are not supported.
 *
 * A SAF folder address is not a filesystem path, so the folder is
 * resolved best-effort to a raw path (primary storage only), exactly
 * like the terminal. Unlike the terminal there is NO fallback folder:
 * Git needs a real path, so on an unresolved folder the screen says so
 * and every Git action is refused — go back to Home and re-choose the
 * folder from the session.
 *
 * Push / pull / private clones need a credential: username + token,
 * stored encrypted in KeyStore only, never in a URL or a log.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitScreen(viewModel: GitViewModel, onBack: () -> Unit) {
    val session by viewModel.session.collectAsState()
    val sessionReady by viewModel.sessionReady.collectAsState()
    val folder = viewModel.folder
    val repoInfo = viewModel.repoInfo
    val isRepo = viewModel.isRepo
    val isBusy = viewModel.isBusy
    val message = viewModel.message
    val error = viewModel.error

    var commitMessage by remember { mutableStateOf("") }
    var cloneUrl by remember { mutableStateOf("") }
    var credUsername by remember { mutableStateOf("") }
    var credToken by remember { mutableStateOf("") }

    // No session / no usable project folder yet — explain, don't crash.
    // (Before the first session list arrives, just say loading.)
    if (!sessionReady) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Text("Loading…", style = MaterialTheme.typography.titleLarge)
        }
        return
    }
    if (session == null) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Text("No project folder yet.", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            Text(
                "Git works on a session's project folder. " +
                    "Create a session first (Home → +) and pick a folder for it.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onBack) { Text("Back to Home") }
        }
        return
    }

    val status = repoInfo?.status
    val staged = status?.staged.orEmpty()
    val unstaged = status?.unstaged.orEmpty()
    val untracked = status?.untracked.orEmpty()
    val conflicting = status?.conflicting.orEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Git") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.refresh() },
                        enabled = !isBusy && folder is TerminalFolder.Resolved
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh status")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Result / error of the last operation.
            message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            if (isBusy) {
                Text(
                    "Working…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Folder card: always show which folder Git works on.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (folder is TerminalFolder.Unresolved) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "Folder: ${session?.folderName}",
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (folder == null) {
                        Text("Finding folder…", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(
                            folder.workDir.absolutePath,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace
                        )
                        if (folder is TerminalFolder.Unresolved) {
                            Spacer(Modifier.height(4.dp))
                            Text(folder.reason, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                GitViewModel.UNRESOLVED_MESSAGE,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = onBack) { Text("Back to Home") }
                        }
                    }
                }
            }

            // Nothing below this point works on an unresolved folder.
            if (folder !is TerminalFolder.Resolved) return@Column

            if (!isRepo) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Not a Git repository yet.", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "This folder has no .git in it. Clone a repo below, " +
                                "or run git init in the terminal.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else if (repoInfo != null && status != null) {
                // Status card.
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "On branch ${repoInfo.currentBranch ?: "?"}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Remote: ${repoInfo.remoteUrl ?: "none (origin not set)"}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace
                        )
                        if (status.isClean) {
                            Spacer(Modifier.height(4.dp))
                            Text("Working tree clean.", style = MaterialTheme.typography.bodyMedium)
                        }
                        if (staged.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("Staged:", style = MaterialTheme.typography.labelMedium)
                            staged.forEach { path ->
                                FileRow(
                                    path = path,
                                    actionLabel = "Unstage",
                                    enabled = !isBusy,
                                    onAction = { viewModel.unstageFile(path) }
                                )
                            }
                        }
                        if (unstaged.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("Changed (not staged):", style = MaterialTheme.typography.labelMedium)
                            status.unstagedModified.forEach { path ->
                                FileRow(
                                    path = path,
                                    actionLabel = "Stage",
                                    enabled = !isBusy,
                                    onAction = { viewModel.stageFile(path, wasDeleted = false) }
                                )
                            }
                            status.unstagedDeleted.forEach { path ->
                                FileRow(
                                    path = "$path (deleted)",
                                    actionLabel = "Stage",
                                    enabled = !isBusy,
                                    onAction = { viewModel.stageFile(path, wasDeleted = true) }
                                )
                            }
                        }
                        if (untracked.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("Untracked:", style = MaterialTheme.typography.labelMedium)
                            untracked.forEach { path ->
                                FileRow(
                                    path = path,
                                    actionLabel = "Stage",
                                    enabled = !isBusy,
                                    onAction = { viewModel.stageFile(path, wasDeleted = false) }
                                )
                            }
                        }
                        if (conflicting.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Conflicts — resolve these, then commit:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            conflicting.forEach { path ->
                                Text(
                                    path,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }

                // Stage / commit / push / pull.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.stageAll() },
                        enabled = !isBusy && (unstaged.isNotEmpty() || untracked.isNotEmpty()),
                        modifier = Modifier.weight(1f)
                    ) { Text("Stage all") }
                    OutlinedButton(
                        onClick = { viewModel.pull() },
                        enabled = !isBusy,
                        modifier = Modifier.weight(1f)
                    ) { Text("Pull") }
                    OutlinedButton(
                        onClick = { viewModel.push() },
                        enabled = !isBusy,
                        modifier = Modifier.weight(1f)
                    ) { Text("Push") }
                }
                OutlinedTextField(
                    value = commitMessage,
                    onValueChange = { commitMessage = it },
                    label = { Text("Commit message") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isBusy
                )
                Button(
                    onClick = {
                        viewModel.commit(commitMessage)
                        commitMessage = ""
                    },
                    enabled = !isBusy && staged.isNotEmpty() && commitMessage.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Commit") }

                // Branches.
                if (repoInfo.branches.isNotEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Branches", style = MaterialTheme.typography.titleMedium)
                            repoInfo.branches.forEach { branch ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        if (branch == repoInfo.currentBranch) "$branch (current)" else branch,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (branch != repoInfo.currentBranch) {
                                        OutlinedButton(
                                            onClick = { viewModel.checkout(branch) },
                                            enabled = !isBusy
                                        ) { Text("Checkout") }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Clone.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Clone a repo", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Clones into a subfolder of this project folder, named after " +
                            "the repo. Open the clone as a new session from Home to work in it.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = cloneUrl,
                        onValueChange = { cloneUrl = it },
                        label = { Text("Repo URL (https://…)") },
                        placeholder = { Text("https://github.com/user/repo.git") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isBusy,
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.cloneRepo(cloneUrl) },
                        enabled = !isBusy && cloneUrl.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Clone") }
                }
            }

            // Remote credential.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Remote credential", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (viewModel.hasCredential) {
                            "Saved for ${viewModel.credentialUsername ?: "?"} " +
                                "(token ${viewModel.credentialMasked ?: ""}). " +
                                "Enter new values below to change it."
                        } else {
                            "Needed for push / pull and private clones. " +
                                "For GitHub use your username + a personal access token."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = credUsername,
                        onValueChange = { credUsername = it },
                        label = { Text("Username") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isBusy,
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = credToken,
                        onValueChange = { credToken = it },
                        label = { Text("Token / password") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isBusy,
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (viewModel.saveCredential(credUsername, credToken)) {
                                    credUsername = ""
                                    credToken = ""
                                }
                            },
                            enabled = !isBusy && credUsername.isNotBlank() && credToken.isNotBlank()
                        ) { Text(if (viewModel.hasCredential) "Change credential" else "Save credential") }
                        if (viewModel.hasCredential) {
                            OutlinedButton(
                                onClick = { viewModel.removeCredential() },
                                enabled = !isBusy
                            ) { Text("Remove") }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Stored encrypted on this device only (KeyStore). " +
                            "It is never put in a URL, never logged, and never leaves the phone.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Text(
                "Git runs on this phone with JGit (pure Java, no git app needed). " +
                    "HTTPS only — SSH remotes are not supported.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun FileRow(
    path: String,
    actionLabel: String,
    enabled: Boolean,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            path,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        OutlinedButton(onClick = onAction, enabled = enabled) {
            Text(actionLabel)
        }
    }
}
