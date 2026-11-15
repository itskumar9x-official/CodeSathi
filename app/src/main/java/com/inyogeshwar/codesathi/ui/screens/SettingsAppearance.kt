package com.inyogeshwar.codesathi.ui.screens

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.inyogeshwar.codesathi.agent.hasAllFilesAccess
import com.inyogeshwar.codesathi.agent.isAllFilesAccessSupported
import com.inyogeshwar.codesathi.agent.openAllFilesAccessSettings
import com.inyogeshwar.codesathi.remote.RemoteService
import com.inyogeshwar.codesathi.ui.AppViewModel
import com.inyogeshwar.codesathi.ui.theme.ClozedCard
import com.inyogeshwar.codesathi.ui.theme.ClozedSegmentedControl
import com.inyogeshwar.codesathi.ui.theme.ClozedSectionHeader
import com.inyogeshwar.codesathi.ui.theme.SathiLockup

/** The app version, as the current Settings screen reads it. */
private fun appVersionName(context: Context): String? = runCatching {
    val info = if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(0)
        )
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    info.versionName
}.getOrNull()

/**
 * Appearance (Phase 8 settings, part 2).
 *
 * Honest limitation, stated in the card: the app has NO stored theme
 * setting — MainActivity always calls CodeSathiTheme with the system
 * theme, and there is no dynamic-color setting anywhere (no key in
 * PreferencesStore, no dynamic colour scheme in the theme). So the
 * control shows the real current mode ("System", following Android)
 * and the dynamic-color row is omitted entirely rather than faked.
 * A saved Light / Dark choice needs a new theme key in
 * PreferencesStore plus MainActivity wiring — that is a gap, not
 * something this section can do on its own.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun AppearanceSection(viewModel: AppViewModel) {
    val systemDark = isSystemInDarkTheme()
    val themeNow = if (systemDark) "Dark" else "Light"

    ClozedSectionHeader("Appearance")
    ClozedCard {
        Text("Theme", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        ClozedSegmentedControl(
            options = listOf("System", "Light", "Dark"),
            selected = 0,
            onSelect = {
                // No stored theme exists yet, so there is nothing to
                // save — the app follows the system theme. See the note
                // under this control and the section doc above.
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "CodeSathi follows your system theme for now — currently " +
                themeNow +
                ". A saved Light / Dark choice is not available yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Connection (Phase 8 settings, part 2).
 *
 * Mode, project folder and storage access, plus Disconnect. Disconnect
 * mirrors the Remote screen exactly: it stops the RemoteService and
 * then clears the stored remote flag. The button is disabled while
 * remote mode is already off.
 */
@Composable
fun ConnectionSection(viewModel: AppViewModel) {
    val context = LocalContext.current
    val remoteOn by viewModel.remoteEnabled.collectAsState()
    val filesSupported = isAllFilesAccessSupported()
    var filesAccess by remember { mutableStateOf(hasAllFilesAccess()) }
    val lifecycleOwner = context as? LifecycleOwner
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) filesAccess = hasAllFilesAccess()
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }
    val folderName = viewModel.pickedFolderName

    ClozedSectionHeader("Connection")
    ClozedCard {
        ClozedSettingRow(
            title = "Mode",
            subtitle = if (remoteOn) {
                "Remote — this phone serves chats to another device on your Wi-Fi."
            } else {
                "The agent runs on this phone."
            }
        ) {
            Text(
                if (remoteOn) "Remote" else "On-device",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        ClozedRowDivider()
        ClozedSettingRow(
            title = "Project folder",
            subtitle = folderName.ifBlank { "Not chosen yet" }
        )
        ClozedRowDivider()
        ClozedSettingRow(
            title = "Storage access",
            subtitle = if (filesSupported) {
                "Direct file access for the terminal, Git and editor."
            } else {
                "Needs Android 11 or newer — this phone is older, so the app " +
                    "keeps using the folder picker only."
            },
            onClick = if (filesSupported) ({ openAllFilesAccessSettings(context) }) else null
        ) {
            ClozedOkPill(
                when {
                    !filesSupported -> "Not available"
                    filesAccess -> "ON ✓"
                    else -> "Not granted"
                },
                filesAccess
            )
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = {
                RemoteService.stop(context)
                viewModel.setRemoteEnabled(false)
            },
            enabled = remoteOn,
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text("Disconnect")
        }
        if (!remoteOn) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Remote mode is off — there is nothing to disconnect.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * About (Phase 8 settings, part 2). Sathi Spark branding, the app
 * version from the package info, and the key-privacy line in
 * CodeSathi's own words.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun AboutSection(viewModel: AppViewModel) {
    // The signature carries the ViewModel like every section; About
    // needs nothing from it.
    val context = LocalContext.current
    val version = remember(context) { appVersionName(context) }

    ClozedSectionHeader("About")
    ClozedCard {
        SathiLockup()
        Spacer(Modifier.height(8.dp))
        Text(
            if (version != null) "CodeSathi v$version" else "CodeSathi",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Your coding companion on Android.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "On-device AI coding agent. Bring your own API key — your keys " +
                "stay encrypted on this device and are sent only to the " +
                "provider you choose, never anywhere else.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Tools (Phase 8 settings, part 2).
 *
 * The destinations that used to live in the tab-bar Tools hub, as
 * plain navigation rows, so the move to Settings removes nothing:
 * Editor, Terminal, Git, Remote mode, Sync, Usage & cost, Runs.
 */
@Composable
fun ToolsSection(
    onEditor: () -> Unit,
    onTerminal: () -> Unit,
    onGit: () -> Unit,
    onRemote: () -> Unit,
    onSync: () -> Unit,
    onUsage: () -> Unit,
    onRuns: () -> Unit
) {
    ClozedSectionHeader("Tools")
    ClozedCard {
        ClozedNavRow(
            title = "Editor",
            subtitle = "Browse and edit project files",
            onClick = onEditor
        )
        ClozedRowDivider()
        ClozedNavRow(
            title = "Terminal",
            subtitle = "Run shell commands in the folder",
            onClick = onTerminal
        )
        ClozedRowDivider()
        ClozedNavRow(
            title = "Git",
            subtitle = "Status, commit, push, pull, branches",
            onClick = onGit
        )
        ClozedRowDivider()
        ClozedNavRow(
            title = "Remote mode",
            subtitle = "Chat from another device on your Wi-Fi",
            onClick = onRemote
        )
        ClozedRowDivider()
        ClozedNavRow(
            title = "Sync devices",
            subtitle = "Sync sessions with another device",
            onClick = onSync
        )
        ClozedRowDivider()
        ClozedNavRow(
            title = "Usage & cost",
            subtitle = "Tokens and estimated spend",
            onClick = onUsage
        )
        ClozedRowDivider()
        ClozedNavRow(
            title = "Runs",
            subtitle = "Past turns, tokens and changed files",
            onClick = onRuns
        )
    }
}
