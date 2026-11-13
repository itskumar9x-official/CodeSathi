package com.inyogeshwar.codesathi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.inyogeshwar.codesathi.agent.TerminalFolder
import com.inyogeshwar.codesathi.agent.TerminalLine
import com.inyogeshwar.codesathi.agent.TerminalLineKind
import com.inyogeshwar.codesathi.agent.hasAllFilesAccess
import com.inyogeshwar.codesathi.ui.TerminalViewModel

/**
 * Phase 2, Step 6 — in-app terminal.
 *
 * Runs shell commands in the last session's project folder
 * (`sh -c <command>`), streams stdout + stderr (merged) live, and shows
 * the exit code when the command finishes. One command at a time; Stop
 * kills the running process. Past commands are kept as chips — tap one
 * to re-run it.
 *
 * The terminal is a user-facing screen only. It is NOT an agent tool:
 * the agent's tool set stays read / glob / grep / write / edit / skill
 * / MCP tools, and the agent cannot run shell commands.
 *
 * A SAF folder address is not a filesystem path, so the folder is
 * resolved best-effort to a raw path (primary storage only). When that
 * fails the screen says so and commands run in the app's private files
 * dir instead — never silently in the wrong folder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(viewModel: TerminalViewModel, onBack: () -> Unit) {
    val session by viewModel.session.collectAsState()
    val sessionReady by viewModel.sessionReady.collectAsState()
    val folder = viewModel.folder
    val lines by viewModel.lines.collectAsState()
    val history by viewModel.history.collectAsState()
    val isRunning = viewModel.isRunning
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Auto-scroll to the bottom on new output.
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

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
                "The terminal runs commands in a session's project folder. " +
                    "Create a session first (Home → +) and pick a folder for it.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onBack) { Text("Back to Home") }
        }
        return
    }

    fun submit() {
        val command = input
        input = ""
        viewModel.runCommand(command)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Terminal") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
            // Folder card: always show which folder commands run in.
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
                            Text(
                                "Running in the app's private folder instead: " +
                                    folder.fallbackDir.absolutePath,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    // Phase 3, Step 4: say plainly whether the shell has
                    // raw-filesystem access, and what to do when it does
                    // not — a SAF grant alone can make file-touching
                    // commands print "Permission denied".
                    Text(
                        if (hasAllFilesAccess()) {
                            "All-files access is on — commands can read and " +
                                "change files in this folder directly."
                        } else {
                            "Commands run with the app's own permissions. " +
                                "File-touching commands may print \"Permission denied\" — " +
                                "turning on all-files access (Home → Storage access) can fix that."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Past commands: tap one to re-run it.
            if (history.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Tap to re-run:", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(history, key = { it }) { command ->
                        OutlinedButton(
                            onClick = { viewModel.runCommand(command) },
                            enabled = !isRunning && folder != null
                        ) {
                            Text(command, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Output", style = MaterialTheme.typography.labelMedium)
                OutlinedButton(onClick = { viewModel.clearOutput() }) {
                    Text("Clear")
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Index keys: output lines repeat (blank lines, "0", …),
                // so the line text itself is not a usable key.
                items(lines.size, key = { it }) { index ->
                    TerminalLineText(lines[index])
                }
                if (lines.isEmpty()) {
                    item {
                        Text(
                            "Type a command below — e.g. ls -la",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Command…") },
                    placeholder = { Text("ls -la") },
                    modifier = Modifier.weight(1f),
                    enabled = !isRunning && folder != null,
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() })
                )
                if (isRunning) {
                    IconButton(onClick = { viewModel.stopCommand() }) {
                        Icon(Icons.Filled.Stop, contentDescription = "Stop")
                    }
                } else {
                    IconButton(
                        onClick = { submit() },
                        enabled = folder != null && input.isNotBlank()
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = "Run command")
                    }
                }
            }
            if (isRunning) {
                Text(
                    "Running… tap Stop to kill the command.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun TerminalLineText(line: TerminalLine) {
    Text(
        text = line.text,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodyMedium,
        color = when (line.kind) {
            TerminalLineKind.COMMAND -> MaterialTheme.colorScheme.primary
            TerminalLineKind.STATUS -> MaterialTheme.colorScheme.secondary
            TerminalLineKind.OUTPUT -> MaterialTheme.colorScheme.onSurface
        }
    )
}
