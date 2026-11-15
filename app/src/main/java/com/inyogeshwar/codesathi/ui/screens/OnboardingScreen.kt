package com.inyogeshwar.codesathi.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.inyogeshwar.codesathi.remote.RemoteServer
import com.inyogeshwar.codesathi.remote.RemoteService
import com.inyogeshwar.codesathi.ui.AppViewModel
import com.inyogeshwar.codesathi.ui.theme.ClozedCard
import com.inyogeshwar.codesathi.ui.theme.ClozedInfoCard
import com.inyogeshwar.codesathi.ui.theme.ClozedSegmentedControl
import com.inyogeshwar.codesathi.ui.theme.Pill
import com.inyogeshwar.codesathi.ui.theme.SathiMark

/**
 * Phase 8 onboarding, in Clozed's layout (centred logo, big title,
 * On-device / Remote toggle, info card, folder PICKER button, storage
 * pill, big Start button) with CodeSathi's own strings and Sathi Spark
 * branding.
 *
 * The folder step is unchanged from v1.1: the button opens the SYSTEM
 * folder picker (OpenDocumentTree) and [AppViewModel.onFolderPicked]
 * takes the persistable read+write grant; [AppViewModel.completeOnboarding]
 * is called when Start (or "Add a provider API key") is tapped. There is
 * deliberately NO path text field. The folder is optional here, as in
 * Clozed — if none is picked, one is chosen when the first session is
 * created.
 *
 * Remote is CodeSathi's real remote mode, which runs the other way round
 * from Clozed's: this phone IS the server (port 8080), and another device
 * on the same Wi-Fi opens this phone's address in a browser. There is no
 * connect-to-a-server client mode to offer, so the Remote tab shows this
 * phone's own server address (read-only), the server password, and a
 * Connect button that really starts the server.
 *
 * [onDone] gets true when the user asked to add a provider key next.
 */
@Composable
fun OnboardingScreen(viewModel: AppViewModel, onDone: (openProviders: Boolean) -> Unit) {
    var mode by remember { mutableStateOf(0) } // 0 = On-device, 1 = Remote

    // Completing onboarding is the same whichever button finishes it.
    val finish: (Boolean) -> Unit = { openProviders ->
        viewModel.completeOnboarding()
        onDone(openProviders)
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        SathiMark(size = 96.dp, tile = true)
        Spacer(Modifier.height(28.dp))
        Text(
            "CodeSathi",
            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Your AI coding partner, running on your phone.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        ClozedSegmentedControl(
            options = listOf("On-device", "Remote"),
            selected = mode,
            onSelect = { mode = it },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(20.dp))
        if (mode == 0) {
            OnDeviceSection(viewModel)
        } else {
            RemoteSection(viewModel)
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { finish(false) },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("Start coding") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { finish(true) },
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add a provider API key")
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** On-device mode: info card, folder picker button, storage pill. */
@Composable
private fun OnDeviceSection(viewModel: AppViewModel) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    val folderChosen = viewModel.pickedFolderUri != null
    val folderName = viewModel.pickedFolderName

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) {
            error = "No folder chosen — pick one above, or choose it when you start your first session."
            return@rememberLauncherForActivityResult
        }
        val name = viewModel.onFolderPicked(context, uri)
        if (name == null) {
            error = "Could not use this folder. Try another one."
        } else {
            error = null
        }
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        ClozedInfoCard(
            title = "On-device agent",
            text = "The agent runs on this phone, inside your project folder. " +
                "Add one provider API key to start chatting — your key stays " +
                "encrypted on this device, and with an on-device model nothing " +
                "leaves the phone at all.",
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { picker.launch(null) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = MaterialTheme.shapes.extraLarge,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        ) {
            Icon(Icons.Filled.Folder, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (folderChosen) "Folder: $folderName — change" else "Choose project folder")
        }
        Spacer(Modifier.height(12.dp))
        Pill(
            text = if (folderChosen) "Folder access: ON ✓" else "No folder chosen yet",
            container = if (folderChosen) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHighest,
            content = if (folderChosen) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!folderChosen) {
            Spacer(Modifier.height(8.dp))
            Text(
                "No folder yet? You can pick one when you start your first session.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Remote mode — CodeSathi's real direction: this phone runs the server
 * (port 8080) and another device on the same Wi-Fi opens this phone's
 * address in a browser. The URL field shows this phone's own address
 * (read-only), the password is the server password, and Connect really
 * starts the server. There is no connect-to-a-server client mode.
 */
@Composable
private fun RemoteSection(viewModel: AppViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val remoteEnabled by viewModel.remoteEnabled.collectAsState()
    var wifiIp by remember { mutableStateOf(RemoteServer.wifiIpAddress()) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    val serverUrl = wifiIp?.let { "http://$it:${RemoteServer.PORT}" }.orEmpty()

    Column(Modifier.fillMaxWidth()) {
        ClozedInfoCard(
            title = "This phone is the server",
            text = "Remote works the other way round here: this phone runs the " +
                "server, and another device on the same Wi-Fi opens the address " +
                "below in a browser, types the password, and chats. Set a " +
                "password, tap Connect, and share the address with the other device.",
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = serverUrl,
            onValueChange = {},
            label = { Text("Server URL") },
            placeholder = { Text("Connect to Wi-Fi to see the address") },
            leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
            readOnly = true,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Server password (min. 4 characters)") },
            placeholder = { Text("Type a password") },
            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                if (password.isNotBlank()) {
                    if (!viewModel.setRemotePassword(password)) {
                        error = "Password must be at least 4 characters."
                        note = null
                        return@Button
                    }
                    password = ""
                    note = "Password saved on this device only."
                }
                if (!viewModel.hasRemotePassword()) {
                    error = "Set a password first — the server will not start without one."
                    note = null
                    return@Button
                }
                RemoteService.start(context)
                wifiIp = RemoteServer.wifiIpAddress()
                error = null
                note = "Server starting — open the address above on the other device."
            },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("Connect") }
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        note?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(16.dp))
        ClozedCard(modifier = Modifier.fillMaxWidth()) {
            Text("Server details", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (serverUrl.isNotBlank()) serverUrl
                    else "No Wi-Fi address found — connect this phone to the same Wi-Fi as the other device.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        if (serverUrl.isNotBlank()) {
                            clipboard.setText(AnnotatedString(serverUrl))
                            copied = true
                        }
                    },
                    enabled = serverUrl.isNotBlank()
                ) { Text(if (copied) "Copied" else "Copy") }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (remoteEnabled) "Server: running — see the notification to stop it"
                else "Server: stopped",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { wifiIp = RemoteServer.wifiIpAddress() }) {
                Text("Refresh address")
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "LAN only, plain HTTP — only use it on Wi-Fi you trust. Chat only: " +
                "no remote file browsing or terminal, and a file change still waits " +
                "for your approval on this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
