package com.inyogeshwar.codesathi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.inyogeshwar.codesathi.remote.RemoteServer
import com.inyogeshwar.codesathi.ui.AppViewModel

/**
 * Multi-device sync (Phase 6, Step 4).
 *
 * Sync sessions with a second device running CodeSathi, over the
 * same remote server Remote mode runs: the other device must have
 * Remote mode ON, on the same Wi-Fi, and the password typed here is
 * that device's Remote password — sync reuses the Remote mode
 * password, there is no second password. The address is typed in by
 * hand (no auto-discovery in this version); it is shown on the other
 * device's Remote screen.
 *
 * Push sends this device's sessions, Pull fetches the other device's,
 * both does Pull then Push so one round converges both sides. Per
 * session the newest `updatedAt` wins — there is no conflict screen
 * in this version, so if the same session changed on both devices
 * the older change is replaced by the newer one.
 *
 * Sessions only, exactly like a file backup: no API keys, no provider
 * credentials, no MCP tokens, no usage records ever leave the device.
 * An imported session keeps its folder, but the folder permission
 * does not travel with it — re-choose the folder from Home if it
 * shows "Folder unavailable".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val sessions by viewModel.sessions.collectAsState()
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(RemoteServer.PORT.toString()) }
    // Prefilled with this device's Remote password — most users set
    // the same password on both devices. Change it when the other
    // device uses a different one; it is never shown in full.
    var password by remember { mutableStateOf(viewModel.remotePassword() ?: "") }
    var push by remember { mutableStateOf(true) }
    var pull by remember { mutableStateOf(true) }
    val busy = viewModel.syncBusy
    val result = viewModel.syncResult
    val error = viewModel.syncError

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Sync devices") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )
    }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Sync sessions with another device running CodeSathi. " +
                    "The other device must have Remote mode ON, on the same Wi-Fi.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "This device has ${sessions.size} session" +
                    (if (sessions.size == 1) "" else "s") + ".",
                style = MaterialTheme.typography.bodyMedium
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Other device", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("Other device's IP address") },
                        placeholder = { Text("192.168.1.5") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("Port") },
                        placeholder = { Text(RemoteServer.PORT.toString()) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Other device's Remote password") },
                        placeholder = { Text("Remote password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "The IP address and port are shown on the other device's Remote screen. " +
                            "The password is that device's Remote password — sync uses the same " +
                            "password as Remote mode, not a second one.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = push, onCheckedChange = { push = it }, enabled = !busy)
                        Text("Push my sessions to the other device")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = pull, onCheckedChange = { pull = it }, enabled = !busy)
                        Text("Pull the other device's sessions to this device")
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            viewModel.syncWithDevice(host, port, password, push, pull)
                        },
                        enabled = !busy && host.isNotBlank() && password.isNotBlank() && (push || pull),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (busy) "Syncing…" else "Sync now")
                    }
                    error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    result?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Text(
                "Sessions only — no API keys, provider credentials, MCP tokens or usage " +
                    "records ever leave this device. If the same session changed on both " +
                    "devices, the newest change wins; there is no conflict screen in this " +
                    "version. A pulled session keeps its folder, but the folder permission " +
                    "does not travel — re-choose the folder from Home if it shows " +
                    "\"Folder unavailable\".",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
