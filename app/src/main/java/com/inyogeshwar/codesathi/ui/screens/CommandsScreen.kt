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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import com.inyogeshwar.codesathi.ui.theme.SathiScaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.inyogeshwar.codesathi.ui.theme.SathiTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.inyogeshwar.codesathi.data.CustomCommand
import com.inyogeshwar.codesathi.ui.AppViewModel

/**
 * Custom commands (Phase 3, Step 1).
 *
 * A command is a name, a short description, and a prompt template.
 * Typing "/name" in any chat expands to the template before the
 * message reaches the model, in Plan and Build alike. Text typed
 * after the name is the command's input: "{input}" in the template
 * is replaced by it, otherwise the text is appended after the
 * template. A command the model does not know ("/typo") is sent as
 * plain text, and a disabled command never expands — the chat never
 * breaks on a slash.
 *
 * Names use lowercase letters, digits and hyphens only
 * ("review-code"); duplicates are refused. The two built-in examples
 * (review-code, write-tests) can be edited or deleted like any other
 * command. Commands hold no secrets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandsScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val commands by viewModel.customCommands.collectAsState()
    var editingCommand by remember { mutableStateOf<CustomCommand?>(null) }
    var newName by remember { mutableStateOf("") }
    var newDescription by remember { mutableStateOf("") }
    var newPrompt by remember { mutableStateOf("") }

    SathiScaffold(topBar = {
        SathiTopBar(
            title = { Text("Custom commands") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "Commands are shortcuts for things you ask the agent often. " +
                        "Type /review-code in chat and the agent gets the full review " +
                        "prompt; type /review-code index.html and that file becomes " +
                        "the command's input. Only enabled commands expand.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            viewModel.commandNote?.let { note ->
                item { Text(note, style = MaterialTheme.typography.bodyMedium) }
            }
            viewModel.commandError?.let { err ->
                item { Text(err, color = MaterialTheme.colorScheme.error) }
            }
            // Editor: opens from a command's Edit button; Save overwrites it.
            editingCommand?.let { command ->
                item {
                    CommandEditorCard(
                        command = command,
                        onSave = { edited ->
                            if (viewModel.saveCommand(edited)) editingCommand = null
                        },
                        onCancel = { editingCommand = null }
                    )
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Add a command", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text("Command name") },
                            placeholder = { Text("e.g. explain-file") },
                            prefix = { Text("/") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newDescription,
                            onValueChange = { newDescription = it },
                            label = { Text("One-line description") },
                            placeholder = { Text("e.g. Explain a file in plain words.") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newPrompt,
                            onValueChange = { newPrompt = it },
                            label = { Text("Prompt template") },
                            placeholder = { Text("What should the agent do? Use {input} where the text after /name goes.") },
                            minLines = 4,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                if (viewModel.createCommand(newName, newDescription, newPrompt)) {
                                    newName = ""
                                    newDescription = ""
                                    newPrompt = ""
                                }
                            },
                            enabled = newName.isNotBlank() && newPrompt.isNotBlank()
                        ) { Text("Add command") }
                    }
                }
            }
            if (commands.isEmpty()) {
                item {
                    Text(
                        "No commands yet — the two built-in examples are added when this screen first opens.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            items(commands, key = { it.id }) { command ->
                CommandCard(
                    command = command,
                    onToggle = { on -> viewModel.setCommandEnabled(command.id, on) },
                    onEdit = { editingCommand = command },
                    onDelete = { viewModel.deleteCommand(command.id) }
                )
            }
            item {
                Text(
                    "A command is only a prompt template — it never runs code by " +
                        "itself. In Plan mode the expanded prompt is still answered " +
                        "as a plan (read-only); in Build mode the agent may use tools " +
                        "and file writes still need your Approve on the diff card.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun CommandCard(
    command: CustomCommand,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("/${command.name}", style = MaterialTheme.typography.titleMedium)
                    if (command.description.isNotBlank()) {
                        Text(command.description, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(command.prompt, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                    if (!command.enabled) {
                        Text(
                            "Disabled — typing /${command.name} sends it as plain text.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Switch(checked = command.enabled, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit command")
                    Text("Edit")
                }
                OutlinedButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete command")
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
private fun CommandEditorCard(
    command: CustomCommand,
    onSave: (CustomCommand) -> Unit,
    onCancel: () -> Unit
) {
    // Rebuilt from the command whenever a different one is opened, so
    // the editor always starts from that command's current values.
    var name by remember(command.id) { mutableStateOf(command.name) }
    var description by remember(command.id) { mutableStateOf(command.description) }
    var prompt by remember(command.id) { mutableStateOf(command.prompt) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Edit command: /${command.name}", style = MaterialTheme.typography.titleMedium)
            Text(
                "Name: lowercase letters, digits and hyphens only. " +
                    "Put {input} in the prompt where the text typed after /name should go.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Command name") },
                prefix = { Text("/") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("One-line description") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("Prompt template") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onSave(command.copy(name = name, description = description, prompt = prompt))
                    },
                    enabled = name.isNotBlank() && prompt.isNotBlank()
                ) { Text("Save") }
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}
