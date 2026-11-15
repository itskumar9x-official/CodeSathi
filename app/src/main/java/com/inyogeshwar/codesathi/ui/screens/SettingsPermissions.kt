@file:OptIn(ExperimentalMaterial3Api::class)

package com.inyogeshwar.codesathi.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inyogeshwar.codesathi.agent.ApprovalMode
import com.inyogeshwar.codesathi.agent.PermissionRule
import com.inyogeshwar.codesathi.ui.AppViewModel
import com.inyogeshwar.codesathi.ui.theme.ClozedCard
import com.inyogeshwar.codesathi.ui.theme.ClozedSectionHeader
import com.inyogeshwar.codesathi.ui.theme.ClozedSegmentedControl
import com.inyogeshwar.codesathi.ui.theme.LocalSathiStatus
import com.inyogeshwar.codesathi.ui.theme.Pill
import java.util.UUID

/**
 * Permissions (Phase 8, Settings part 1).
 *
 * The toggle switches between the two ends of the approval mode: ON is
 * "auto-approve everything", OFF is "ask for everything". A mode set
 * elsewhere (ask-before-changes, auto-approve edits) is shown as-is
 * and the toggle only flips between the two ends.
 */
@Composable
fun PermissionsSection(viewModel: AppViewModel) {
    val mode by viewModel.approvalMode.collectAsState()
    val autoAll = mode == "auto_all"
    val modeLabel = when (mode) {
        "auto_all" -> ApprovalMode.AUTO_ALL.label
        "ask_writes" -> ApprovalMode.ASK_WRITES.label
        "auto_edits" -> ApprovalMode.AUTO_EDITS.label
        else -> ApprovalMode.ASK_ALL.label
    }

    ClozedSectionHeader("Permissions")
    ClozedCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Auto-approve all permissions", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Risky: file edits and shell commands will run without asking you first. " +
                        "A file write still needs your Approve on the diff card, and a stored deny rule still applies.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = autoAll,
                onCheckedChange = { checked ->
                    viewModel.setApprovalMode(if (checked) "auto_all" else "ask_all")
                }
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Current mode: $modeLabel",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Permission rules (Phase 8, Settings part 1).
 *
 * Allow / ask / deny by tool and file or command, stored in order.
 * CodeSathi's engine checks the rules top to bottom and the first
 * matching rule wins (a stored deny always wins, whatever the mode).
 */
@Composable
fun PermissionRulesSection(viewModel: AppViewModel) {
    val rules by viewModel.permissionRules.collectAsState()
    var showAdd by remember { mutableStateOf(false) }

    ClozedSectionHeader("Permission rules")
    Text(
        "Allow, ask or deny by tool and file or command. * works as a wildcard " +
            "in patterns. The first matching rule wins.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))
    ClozedCard(Modifier.fillMaxWidth()) {
        if (rules.isEmpty()) {
            Text(
                "No rules yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        rules.forEachIndexed { index, rule ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${rule.tool} · ${rule.pattern.ifBlank { "*" }}",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        rule.action.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.bodySmall,
                        color = when (rule.action) {
                            "allow" -> LocalSathiStatus.current.success
                            "ask" -> LocalSathiStatus.current.warning
                            else -> MaterialTheme.colorScheme.error
                        }
                    )
                }
                IconButton(
                    onClick = { viewModel.movePermissionRule(index, index - 1) },
                    enabled = index > 0
                ) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up")
                }
                IconButton(
                    onClick = { viewModel.movePermissionRule(index, index + 1) },
                    enabled = index < rules.size - 1
                ) {
                    Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down")
                }
                IconButton(onClick = { viewModel.removePermissionRule(rule.id) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete rule", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Pill(
            text = "+ Add rule",
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
            onClick = { showAdd = true }
        )
    }

    if (showAdd) {
        AddPermissionRuleDialog(
            onDismiss = { showAdd = false },
            onAdd = { rule ->
                viewModel.addPermissionRule(rule)
                showAdd = false
            }
        )
    }
}

/**
 * Add-rule dialog: a horizontally scrollable row of CodeSathi's real
 * tool names (Clozed also lists webfetch / websearch / external_directory,
 * which CodeSathi has no tools for, so they are not offered here), a
 * pattern field, and an allow / ask / deny segment (deny by default).
 */
@Composable
private fun AddPermissionRuleDialog(
    onDismiss: () -> Unit,
    onAdd: (PermissionRule) -> Unit
) {
    val tools = listOf("*", "read", "glob", "grep", "write", "edit", "run", "skill", "mcp:*", "background_process")
    var selectedTool by remember { mutableStateOf("*") }
    var pattern by remember { mutableStateOf("") }
    var actionIndex by remember { mutableStateOf(2) } // Allow / Ask / Deny, default Deny
    val actions = listOf("allow", "ask", "deny")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    tools.forEach { tool ->
                        FilterChip(
                            selected = selectedTool == tool,
                            onClick = { selectedTool = tool },
                            label = { Text(tool, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text("Pattern (e.g. docs/**)") },
                    placeholder = { Text("*") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                ClozedSegmentedControl(
                    options = listOf("Allow", "Ask", "Deny"),
                    selected = actionIndex,
                    onSelect = { actionIndex = it },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Blank pattern means * (every file or command for that tool).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onAdd(
                    PermissionRule(
                        id = UUID.randomUUID().toString(),
                        tool = selectedTool,
                        pattern = pattern.trim().ifBlank { "*" },
                        action = actions[actionIndex]
                    )
                )
            }) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
