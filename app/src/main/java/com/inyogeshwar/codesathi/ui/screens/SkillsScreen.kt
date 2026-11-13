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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import com.inyogeshwar.codesathi.agent.Skill
import com.inyogeshwar.codesathi.agent.buildSkillMarkdown
import com.inyogeshwar.codesathi.ui.AppViewModel

/**
 * Skills (Phase 2, Step 4).
 *
 * A skill is one Markdown file that teaches the agent how to do one job
 * well. Built-in skills (code-review, write-tests, explain-code) ship
 * with the app and are copied into the app-private skills folder on
 * first use, so they can be edited. User-added skills are Markdown
 * files in the same folder.
 *
 * File format (one skill per file):
 * ```
 * # Code Review
 * Description: Review a file for bugs, security issues and style.
 *
 * <instructions body>
 * ```
 * The file name is the skill's id ("code-review") — that is what the
 * model passes to the skill tool. Enabled skills are listed in the
 * Build system prompt; a disabled skill is never offered to the model.
 * A skill never writes files by itself — anything it asks for still
 * goes through write / edit and needs the user's Approve.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val skills = viewModel.skills
    val enabledMap by viewModel.skillEnabled.collectAsState()
    var editingSkill by remember { mutableStateOf<Skill?>(null) }
    var newName by remember { mutableStateOf("") }
    var newDescription by remember { mutableStateOf("") }
    var newInstructions by remember { mutableStateOf("") }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Skills") },
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
                    "Skills are short instructions the agent follows for one job — " +
                        "review code, write tests, explain a file. In Build mode the " +
                        "model calls the skill tool with a skill's name, the skill's " +
                        "instructions come back as the tool result, and the model " +
                        "follows them. Only enabled skills are offered to the model.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            viewModel.skillNote?.let { note ->
                item { Text(note, style = MaterialTheme.typography.bodyMedium) }
            }
            viewModel.skillError?.let { err ->
                item { Text(err, color = MaterialTheme.colorScheme.error) }
            }
            // Editor: plain text editor for the skill's Markdown. Opens
            // from a skill's Edit button; Save overwrites that file.
            editingSkill?.let { skill ->
                item {
                    SkillEditorCard(
                        skill = skill,
                        onSave = { markdown ->
                            viewModel.saveSkill(skill.id, markdown)
                            editingSkill = null
                        },
                        onCancel = { editingSkill = null }
                    )
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Add a skill", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text("Skill name") },
                            placeholder = { Text("e.g. Fix typos") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newDescription,
                            onValueChange = { newDescription = it },
                            label = { Text("One-line description") },
                            placeholder = { Text("e.g. Find and fix typos in a file.") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newInstructions,
                            onValueChange = { newInstructions = it },
                            label = { Text("Instructions") },
                            placeholder = { Text("What should the agent do, step by step?") },
                            minLines = 4,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                viewModel.createSkill(newName, newDescription, newInstructions)
                                newName = ""
                                newDescription = ""
                                newInstructions = ""
                            },
                            enabled = newName.isNotBlank() && newInstructions.isNotBlank()
                        ) { Text("Add skill") }
                    }
                }
            }
            if (skills.isEmpty()) {
                item {
                    Text(
                        "No skills yet — the built-in skills are copied in when this screen first opens.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            items(skills, key = { it.id }) { skill ->
                SkillCard(
                    skill = skill,
                    enabled = enabledMap[skill.id] ?: true,
                    onToggle = { on -> viewModel.setSkillEnabled(skill.id, on) },
                    onEdit = { editingSkill = skill },
                    onDelete = { viewModel.deleteSkill(skill.id) },
                    onReset = { viewModel.resetSkill(skill.id) }
                )
            }
            item {
                Text(
                    "Built-in skills can be disabled or reset to the version that " +
                        "shipped with the app, but not deleted. A skill is a Markdown " +
                        "file in the app's private storage — no skill is ever downloaded.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun SkillCard(
    skill: Skill,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReset: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(skill.name, style = MaterialTheme.typography.titleMedium)
                    Text("id: ${skill.id}", style = MaterialTheme.typography.bodySmall)
                    if (skill.description.isNotBlank()) {
                        Text(skill.description, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        if (skill.builtIn) "Built-in skill" else "Your skill",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (!enabled) {
                        Text(
                            "Disabled — not offered to the agent.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit skill")
                    Text("Edit")
                }
                if (skill.builtIn) {
                    OutlinedButton(onClick = onReset) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reset to built-in")
                        Text("Reset")
                    }
                } else {
                    OutlinedButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete skill")
                        Text("Delete")
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillEditorCard(
    skill: Skill,
    onSave: (String) -> Unit,
    onCancel: () -> Unit
) {
    // Rebuilt from the skill whenever a different skill is opened, so
    // the editor always starts from that skill's current file.
    var text by remember(skill.id) {
        mutableStateOf(buildSkillMarkdown(skill.name, skill.description, skill.instructions))
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Edit skill: ${skill.id}", style = MaterialTheme.typography.titleMedium)
            Text(
                "First line is the name (“# …”), second line the description " +
                    "(“Description: …”), the rest is the instructions the agent follows.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                minLines = 12,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSave(text) }, enabled = text.isNotBlank()) {
                    Text("Save")
                }
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}
