package com.inyogeshwar.codesathi.ui.screens

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.inyogeshwar.codesathi.agent.hasAllFilesAccess
import com.inyogeshwar.codesathi.agent.isAllFilesAccessSupported
import com.inyogeshwar.codesathi.agent.openAllFilesAccessSettings
import com.inyogeshwar.codesathi.ui.AppViewModel
import com.inyogeshwar.codesathi.ui.theme.ClozedCard
import com.inyogeshwar.codesathi.ui.theme.ClozedInfoCard
import com.inyogeshwar.codesathi.ui.theme.ClozedSectionHeader
import com.inyogeshwar.codesathi.ui.theme.LocalSathiStatus
import com.inyogeshwar.codesathi.ui.theme.Pill

// ---------------------------------------------------------------------------
// Shared row helpers for the Phase 8 settings sections (this file owns
// them so the other Settings*.kt files in this package can reuse them).
// ---------------------------------------------------------------------------

/** One title + optional subtitle row with a trailing slot (pill, switch, …). */
@Composable
internal fun ClozedSettingRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    val content: @Composable RowScope.() -> Unit = {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailing()
    }
    if (onClick != null) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    } else {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

/** A row that navigates somewhere else, with a chevron at the right. */
@Composable
internal fun ClozedNavRow(title: String, subtitle: String?, onClick: () -> Unit) {
    ClozedSettingRow(title = title, subtitle = subtitle, onClick = onClick) {
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Green-tinted pill for a granted / on state, grey otherwise. */
@Composable
internal fun ClozedOkPill(text: String, ok: Boolean) {
    val status = LocalSathiStatus.current
    Pill(
        text,
        container = if (ok) status.success.copy(alpha = 0.16f)
        else MaterialTheme.colorScheme.surfaceContainerHighest,
        content = if (ok) status.success
        else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Thin divider between rows inside a settings card. */
@Composable
internal fun ClozedRowDivider() {
    HorizontalDivider(
        Modifier.padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    )
}

/** True when the app's notifications are switched on in system settings. */
private fun areNotificationsEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    return manager?.areNotificationsEnabled() ?: false
}

/** True when the system lets this app run without battery restrictions. */
private fun isBatteryExempt(context: Context): Boolean {
    val manager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    return manager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
}

/** Open this app's page in system Settings (permissions, battery, …). */
internal fun openAppDetailsSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    runCatching { context.startActivity(intent) }
}

private fun openNotificationSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= 26) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
    } else {
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        )
    }.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    runCatching { context.startActivity(intent) }
}

private fun openBatteryOptimisationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    runCatching { context.startActivity(intent) }
}

/**
 * Background work & voice (Phase 8 settings, part 2).
 *
 * The three permission rows show the REAL state, read from the system
 * every time the screen resumes: notifications, battery exemption and
 * all-files access. Nothing here is faked — a state the app cannot
 * detect would say so instead of showing "Allowed".
 */
@Composable
fun BackgroundVoiceSection(viewModel: AppViewModel) {
    val context = LocalContext.current
    var notificationsOn by remember { mutableStateOf(areNotificationsEnabled(context)) }
    var batteryExempt by remember { mutableStateOf(isBatteryExempt(context)) }
    var filesAccess by remember { mutableStateOf(hasAllFilesAccess()) }
    val filesSupported = isAllFilesAccessSupported()
    val lifecycleOwner = context as? LifecycleOwner
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsOn = areNotificationsEnabled(context)
                batteryExempt = isBatteryExempt(context)
                filesAccess = hasAllFilesAccess()
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }
    val voiceOn by viewModel.voiceOutputEnabled.collectAsState()

    ClozedSectionHeader("Background work & voice")
    ClozedInfoCard(
        "When a task runs in the background, the app keeps a small " +
            "notification up so Android does not stop the agent mid-turn. " +
            "Voice reads out a short message when the task is done, so you " +
            "know without looking at the phone."
    )
    Spacer(Modifier.height(12.dp))
    ClozedCard {
        ClozedSettingRow(
            title = "Notifications",
            subtitle = "The working notification keeps the agent alive while it runs.",
            onClick = { openNotificationSettings(context) }
        ) {
            ClozedOkPill(if (notificationsOn) "✓ Allowed" else "Off", notificationsOn)
        }
        ClozedRowDivider()
        ClozedSettingRow(
            title = "Run in background (battery)",
            subtitle = "Battery optimisation can stop the agent when the screen is off.",
            onClick = { openBatteryOptimisationSettings(context) }
        ) {
            ClozedOkPill(if (batteryExempt) "✓ Allowed" else "Restricted", batteryExempt)
        }
        ClozedRowDivider()
        ClozedSettingRow(
            title = "All files access",
            subtitle = if (filesSupported) {
                "Lets the terminal, Git and editor work on real files in the folder."
            } else {
                "Needs Android 11 or newer — this phone is older, so the app " +
                    "keeps using the folder picker only."
            },
            onClick = if (filesSupported) ({ openAllFilesAccessSettings(context) }) else null
        ) {
            ClozedOkPill(
                when {
                    !filesSupported -> "Not available"
                    filesAccess -> "✓ Allowed"
                    else -> "Not granted"
                },
                filesAccess
            )
        }
        ClozedRowDivider()
        ClozedSettingRow(
            title = "Auto-start / background activity",
            subtitle = "Some phones (Xiaomi, Oppo, Vivo, Realme, Samsung and others) " +
                "block apps from starting in the background. If background " +
                "work stops, allow auto-start for CodeSathi in the system settings."
        ) {
            Pill("Open app settings", onClick = { openAppDetailsSettings(context) })
        }
        ClozedRowDivider()
        ClozedSettingRow(
            title = "Voice message when a task is done",
            subtitle = "Speaks a short “Task completed.” when a build turn finishes."
        ) {
            Switch(checked = voiceOn, onCheckedChange = { viewModel.setVoiceOutputEnabled(it) })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { viewModel.voiceSpeaker.speakAnnouncement() }) {
                Text("Test voice")
            }
        }
    }
}
