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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.inyogeshwar.codesathi.agent.McpToolInfo
import com.inyogeshwar.codesathi.agent.mcpToolName
import com.inyogeshwar.codesathi.data.McpServer
import com.inyogeshwar.codesathi.data.PreferencesStore
import com.inyogeshwar.codesathi.ui.AppViewModel

/**
 * MCP servers (Phase 2, Step 3).
 *
 * Add a server (name + URL + optional auth token), enable / disable it,
 * delete it, refresh its tool list, and set each tool's allow / ask /
 * deny rule — the same rules as the built-in tools. MCP tools are named
 * "mcp:<server>:<tool>" and appear in the agent's tool list whenever
 * their server is enabled.
 *
 * Exa web search (https://mcp.exa.ai/mcp) is pre-filled and DISABLED by
 * default — enable it and add your own Exa key when you want web search.
 * No key is bundled with the app. Tokens are stored encrypted
 * (EncryptedSharedPreferences) and are never shown in full.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val servers by viewModel.mcpServers.collectAsState()
    val toolRules by viewModel.toolRules.collectAsState()
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("MCP servers") },
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
                    "MCP servers give the agent extra tools — web search, docs, " +
                        "databases, anything a server offers. Only enabled servers' " +
                        "tools are offered to the model, and every MCP tool follows " +
                        "the same allow / ask / deny rules as the built-in tools.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Add a server", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Server name") },
                            placeholder = { Text("e.g. Exa") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text("Server URL") },
                            placeholder = { Text("https://mcp.exa.ai/mcp") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = token,
                            onValueChange = { token = it },
                            label = { Text("Auth token (optional)") },
                            placeholder = { Text("Paste the server's API key, if it needs one") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                if (viewModel.addMcpServer(name, url, token)) {
                                    note = "Server “${name.trim()}” added. Its tools are being fetched."
                                    error = null
                                    name = ""
                                    url = ""
                                    token = ""
                                } else {
                                    error = "Enter a name and a URL starting with http:// or https://."
                                    note = null
                                }
                            },
                            enabled = name.isNotBlank() && url.isNotBlank()
                        ) { Text("Add server") }
                        error?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, color = MaterialTheme.colorScheme.error)
                        }
                        note?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (servers.isEmpty()) {
                item {
                    Text(
                        "No MCP servers yet. Add one above, or enable the pre-filled Exa server below.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            items(servers, key = { it.id }) { server ->
                McpServerCard(
                    server = server,
                    tools = viewModel.mcpServerTools[server.id].orEmpty(),
                    isLoading = server.id in viewModel.mcpLoadingServers,
                    fetchError = viewModel.mcpErrors[server.id],
                    rules = toolRules,
                    tokenMasked = viewModel.mcpTokenMasked(server.id),
                    onToggle = { enabled -> viewModel.setMcpServerEnabled(server.id, enabled) },
                    onDelete = { viewModel.removeMcpServer(server.id) },
                    onRefresh = { viewModel.refreshMcpTools(server) },
                    onSetRule = { tool, rule -> viewModel.setToolRule(tool, rule) }
                )
            }
            item {
                Text(
                    "Auth tokens are stored encrypted on this device only (EncryptedSharedPreferences) " +
                        "and are sent only to the server you added. A server that is off is never contacted.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun McpServerCard(
    server: McpServer,
    tools: List<McpToolInfo>,
    isLoading: Boolean,
    fetchError: String?,
    rules: Map<String, String>,
    tokenMasked: String?,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onRefresh: () -> Unit,
    onSetRule: (String, String) -> Unit
) {
    // Fetch the tool list the first time an enabled server is shown.
    if (server.enabled) {
        LaunchedEffect(server.id) { onRefresh() }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(server.name, style = MaterialTheme.typography.titleMedium)
                    Text(server.url, style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (server.hasToken) "Auth token saved ✓${tokenMasked?.let { " ($it)" } ?: ""}"
                        else "No auth token",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (!server.enabled) {
                        Text("Disabled — its tools are not offered to the agent.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Switch(checked = server.enabled, onCheckedChange = onToggle)
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete server")
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Tools (${tools.size})", style = MaterialTheme.typography.labelMedium)
                OutlinedButton(onClick = onRefresh, enabled = server.enabled && !isLoading) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh tools")
                    Text(if (isLoading) "Fetching…" else "Refresh")
                }
            }
            fetchError?.let { err ->
                Text(
                    "Could not fetch tools: $err",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (!server.enabled) {
                Text("Enable the server to see its tools.", style = MaterialTheme.typography.bodySmall)
            } else if (tools.isEmpty() && fetchError == null && !isLoading) {
                Text("No tools found — tap Refresh to try again.", style = MaterialTheme.typography.bodySmall)
            }
            tools.forEach { tool ->
                val toolName = mcpToolName(server.id, tool.name)
                val current = rules[toolName] ?: PreferencesStore.RULE_ASK
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(tool.name, style = MaterialTheme.typography.bodyMedium)
                    if (tool.description.isNotBlank()) {
                        Text(tool.description, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            PreferencesStore.RULE_ALLOW to "Allow",
                            PreferencesStore.RULE_ASK to "Ask",
                            PreferencesStore.RULE_DENY to "Deny"
                        ).forEach { (rule, label) ->
                            if (rule == current) {
                                Button(onClick = {}) { Text(label) }
                            } else {
                                OutlinedButton(onClick = { onSetRule(toolName, rule) }) { Text(label) }
                            }
                        }
                    }
                }
            }
        }
    }
}
