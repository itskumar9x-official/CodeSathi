package com.inyogeshwar.codesathi.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.inyogeshwar.codesathi.remote.RemoteServer
import com.inyogeshwar.codesathi.remote.RemoteService
import com.inyogeshwar.codesathi.ui.AppViewModel

/**
 * Remote mode (Phase 2, Step 7).
 *
 * An optional local-network HTTP server, OFF by default, so the user can
 * chat with the agent from another device on the same Wi-Fi. The phone
 * runs a foreground service with a persistent notification (with a Stop
 * action) while the server is on.
 *
 * How it works: set a password (stored encrypted, KeyStore only) →
 * turn the server on → open the shown URL on the other device, type
 * the same password there, pick a session and chat. A turn runs the
 * same agent loop as in-app, with the session's own provider / model /
 * tools / fallback. A file change it proposes is NOT applied remotely —
 * approve or reject it on the phone's diff card.
 *
 * LAN only, plain HTTP, no streaming, chat only. No remote file
 * browsing, no remote terminal, no remote approve.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val enabled by viewModel.remoteEnabled.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    var password by remember { mutableStateOf("") }
    var passwordSet by remember { mutableStateOf(viewModel.hasRemotePassword()) }
    var wifiIp by remember { mutableStateOf(RemoteServer.wifiIpAddress()) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    // Android 13+: the service's notification only shows with this
    // runtime permission. The server still starts without it — the
    // notification is just hidden — so the result is not waited on.
    val notificationPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* Result ignored — see above. */ }

    SathiScaffold(topBar = {
        SathiTopBar(
            title = { Text("Remote mode") },
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
                "Chat with the agent from another device on the same Wi-Fi. " +
                    "The other device opens the URL below in a browser, types the " +
                    "password, picks a session and chats — same provider, model " +
                    "and tools as on this phone.",
                style = MaterialTheme.typography.bodyMedium
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Remote server", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (enabled) "Running — see the notification to stop it"
                                else "Stopped",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { turnOn ->
                                if (turnOn) {
                                    if (!viewModel.hasRemotePassword()) {
                                        error = "Set a password first — the server will not start without one."
                                        note = null
                                    } else {
                                        askNotificationPermission(context, notificationPermission)
                                        // The service sets remoteEnabled=true itself once
                                        // the server is actually listening, so the toggle
                                        // never claims "on" when the start failed.
                                        RemoteService.start(context)
                                        wifiIp = RemoteServer.wifiIpAddress()
                                        error = null
                                        note = null
                                    }
                                } else {
                                    RemoteService.stop(context)
                                    viewModel.setRemoteEnabled(false)
                                }
                            }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    if (wifiIp != null) {
                        Text(
                            "Open on the other device:",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "http://${wifiIp}:${RemoteServer.PORT}",
                            style = MaterialTheme.typography.titleMedium
                        )
                    } else {
                        Text(
                            "No Wi-Fi address found — connect this phone to the same Wi-Fi as the other device, then tap Refresh.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { wifiIp = RemoteServer.wifiIpAddress() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh address")
                        Text("Refresh address")
                    }
                    Text(
                        "${sessions.size} session(s) available remotely.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Password", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (passwordSet) "A password is set. Enter a new one below to change it."
                        else "No password set yet — set one to turn the server on.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Remote password (min. 4 characters)") },
                        placeholder = { Text("Type a password") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            if (viewModel.setRemotePassword(password)) {
                                passwordSet = true
                                password = ""
                                note = "Password saved (stored encrypted on this device only)."
                                error = null
                            } else {
                                error = "Password must be at least 4 characters."
                                note = null
                            }
                        },
                        enabled = password.isNotBlank()
                    ) { Text(if (passwordSet) "Change password" else "Save password") }
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

            Text(
                "LAN only, plain HTTP (no HTTPS) — only use it on a Wi-Fi network you trust. " +
                    "No streaming: each message returns the final reply. Chat only — no remote " +
                    "file browsing, no remote terminal. If a turn proposes a file change, " +
                    "nothing is written until you approve it on this phone.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/** Ask for POST_NOTIFICATIONS on Android 13+ so the Stop notification shows. */
private fun askNotificationPermission(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<String>
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    ) return
    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
}
