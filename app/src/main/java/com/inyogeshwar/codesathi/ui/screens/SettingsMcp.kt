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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.inyogeshwar.codesathi.ui.AppViewModel
import com.inyogeshwar.codesathi.ui.theme.ClozedCard
import com.inyogeshwar.codesathi.ui.theme.ClozedSectionHeader
import com.inyogeshwar.codesathi.ui.theme.Pill

/**
 * MCP servers (Phase 8 settings, part 2).
 *
 * The servers themselves, with an enable toggle each, plus a short add
 * dialog (name + URL + optional token) and a link to the full MCP
 * screen, where tools can be refreshed and per-tool rules set.
 */
@Composable
fun McpSection(viewModel: AppViewModel, onMcp: () -> Unit) {
    val servers by viewModel.mcpServers.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    ClozedSectionHeader("MCP servers")
    ClozedCard {
        Text(
            "MCP servers give the agent extra tools — web search, docs, " +
                "databases, anything a server offers. Only enabled servers " +
                "are contacted, and their tools follow the same allow / ask / " +
                "deny rules as the built-in tools. Tokens are stored encrypted " +
                "on this device and sent only to the server you added.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(12.dp))
        if (servers.isEmpty()) {
            Text(
                "No servers yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
        } else {
            servers.forEachIndexed { index, server ->
                if (index > 0) ClozedRowDivider()
                ClozedSettingRow(
                    title = server.name,
                    subtitle = buildString {
                        append(server.url)
                        if (server.hasToken) append(" · Token saved")
                        if (server.pluginId != null) append(" · From a plugin")
                    }
                ) {
                    Switch(
                        checked = server.enabled,
                        onCheckedChange = { viewModel.setMcpServerEnabled(server.id, it) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Pill("+ Add MCP server", onClick = { showAdd = true })
        Spacer(Modifier.height(4.dp))
        ClozedNavRow(
            title = "All MCP servers",
            subtitle = "Refresh tool lists and set a rule for each tool",
            onClick = onMcp
        )
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add MCP server") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        placeholder = { Text("e.g. Exa") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("URL") },
                        placeholder = { Text("https://mcp.exa.ai/mcp") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text("Token (optional)") },
                        placeholder = { Text("Paste the server's key, if it needs one") },
                        visualTransformation = PasswordVisualTransformation(),
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
                    enabled = name.isNotBlank() && url.isNotBlank(),
                    onClick = {
                        if (viewModel.addMcpServer(name, url, token)) {
                            showAdd = false
                            name = ""
                            url = ""
                            token = ""
                            error = null
                        } else {
                            error = "Enter a name and a URL starting with " +
                                "http://, https://, or termux:."
                        }
                    }
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text("Cancel") }
            }
        )
    }
}
