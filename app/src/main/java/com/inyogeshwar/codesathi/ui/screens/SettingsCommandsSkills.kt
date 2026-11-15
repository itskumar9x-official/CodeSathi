package com.inyogeshwar.codesathi.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.inyogeshwar.codesathi.ui.AppViewModel
import com.inyogeshwar.codesathi.ui.theme.ClozedCard
import com.inyogeshwar.codesathi.ui.theme.ClozedSectionHeader
import com.inyogeshwar.codesathi.ui.theme.Pill

/**
 * Custom commands (Phase 8 settings, part 2).
 *
 * The user's slash commands, with an enable toggle each, plus a short
 * add dialog and a link to the full Commands screen for editing and
 * deleting. A name the app refuses (bad characters, already taken, or
 * an empty prompt) is reported in the dialog — nothing is added silently.
 */
@Composable
fun CommandsSection(viewModel: AppViewModel, onCommands: () -> Unit) {
    val commands by viewModel.customCommands.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    ClozedSectionHeader("Custom commands")
    ClozedCard {
        Text(
            "Type /name in chat and the command's prompt is sent to the " +
                "model instead. Put {input} in the prompt where the words " +
                "you type after the name should go — otherwise they are " +
                "added after the prompt.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(12.dp))
        if (commands.isEmpty()) {
            Text(
                "No custom commands yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
        } else {
            commands.forEachIndexed { index, command ->
                if (index > 0) ClozedRowDivider()
                ClozedSettingRow(
                    title = "/${command.name}",
                    subtitle = command.description.ifBlank {
                        command.prompt.replace('\n', ' ').take(80)
                    }
                ) {
                    Switch(
                        checked = command.enabled,
                        onCheckedChange = { viewModel.setCommandEnabled(command.id, it) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Pill("+ Add command", onClick = { showAdd = true })
        Spacer(Modifier.height(4.dp))
        ClozedNavRow(
            title = "All custom commands",
            subtitle = "Edit, delete and try out your commands",
            onClick = onCommands
        )
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add command") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name (without /)") },
                        placeholder = { Text("e.g. review-code") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Description") },
                        placeholder = { Text("What this command does") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        label = { Text("Prompt") },
                        placeholder = { Text("What the model should do… Use {input} for your words.") },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (error != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(error!!, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && prompt.isNotBlank(),
                    onClick = {
                        if (viewModel.createCommand(name, description, prompt)) {
                            showAdd = false
                            name = ""
                            description = ""
                            prompt = ""
                            error = null
                        } else {
                            error = viewModel.commandError ?: "Could not add that command."
                        }
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text("Cancel") }
            }
        )
    }
}

/**
 * Skills (Phase 8 settings, part 2).
 *
 * The skills on the device, with an enable toggle each, plus a short
 * add dialog and a link to the full Skills screen for viewing, editing,
 * deleting and resetting. The result of an add is shown under the card:
 * a duplicate name is only found after the add starts, so the dialog
 * closes and the error is reported here instead of being lost.
 */
@Composable
fun SkillsSection(viewModel: AppViewModel, onSkills: () -> Unit) {
    val skills = viewModel.skills
    val enabledMap by viewModel.skillEnabled.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val skillNote = viewModel.skillNote
    val skillError = viewModel.skillError

    LaunchedEffect(Unit) { viewModel.refreshSkills() }

    ClozedSectionHeader("Skills")
    ClozedCard {
        Text(
            "Skills are reusable instructions the agent can call on for a " +
                "job — reviewing code, writing tests, explaining a file. A " +
                "skill that is off is never offered to the model.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(12.dp))
        if (skills.isEmpty()) {
            Text(
                "No skills yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
        } else {
            skills.forEachIndexed { index, skill ->
                if (index > 0) ClozedRowDivider()
                ClozedSettingRow(
                    title = skill.name,
                    subtitle = skill.description.ifBlank { skill.id }
                ) {
                    Switch(
                        checked = enabledMap[skill.id] ?: true,
                        onCheckedChange = { viewModel.setSkillEnabled(skill.id, it) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Pill("+ Add skill", onClick = { showAdd = true })
        Spacer(Modifier.height(4.dp))
        ClozedNavRow(
            title = "All skills",
            subtitle = "View, edit, delete and reset skills",
            onClick = onSkills
        )
        if (skillNote != null || skillError != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                skillError ?: skillNote.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (skillError != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add skill") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        placeholder = { Text("e.g. Code Review") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("When to use it") },
                        placeholder = { Text("When the agent should reach for this skill") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = instructions,
                        onValueChange = { instructions = it },
                        label = { Text("Instructions") },
                        placeholder = { Text("What the skill should do, step by step…") },
                        minLines = 5,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (error != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(error!!, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && instructions.isNotBlank(),
                    onClick = {
                        // Clear any stale result first, so the check below
                        // only sees this add's own synchronous validation.
                        // A duplicate name is found later, in the
                        // background write — that error is shown under the
                        // card (see the note above this dialog).
                        viewModel.dismissSkillMessage()
                        viewModel.createSkill(name, description, instructions)
                        if (viewModel.skillError != null) {
                            error = viewModel.skillError
                        } else {
                            showAdd = false
                            name = ""
                            description = ""
                            instructions = ""
                            error = null
                        }
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text("Cancel") }
            }
        )
    }
}
