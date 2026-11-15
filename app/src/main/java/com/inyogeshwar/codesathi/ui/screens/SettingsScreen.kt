package com.inyogeshwar.codesathi.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.inyogeshwar.codesathi.ui.AppViewModel
import com.inyogeshwar.codesathi.ui.theme.LocalBottomBarInset
import com.inyogeshwar.codesathi.ui.theme.LocalSpacing
import com.inyogeshwar.codesathi.ui.theme.SathiCard
import com.inyogeshwar.codesathi.ui.theme.SathiLockup
import com.inyogeshwar.codesathi.ui.theme.SathiScaffold
import com.inyogeshwar.codesathi.ui.theme.SathiTopBar
import com.inyogeshwar.codesathi.ui.theme.SectionHeader
import com.inyogeshwar.codesathi.ui.theme.TopBarTitle

/**
 * v1.2 Settings tab: providers & keys, session backup/restore, storage
 * access (moved here from Home), MCP, usage, and app info.
 */
@Composable
fun SettingsScreen(
    viewModel: AppViewModel,
    onProviders: () -> Unit,
    onUsage: () -> Unit,
    onMcp: () -> Unit
) {
    val spacing = LocalSpacing.current
    val bottomInset = LocalBottomBarInset.current
    val backup = rememberBackupActions(viewModel)
    val context = LocalContext.current
    val version = remember(context) {
        runCatching {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            info.versionName
        }.getOrNull()
    }

    SathiScaffold(topBar = { SathiTopBar(title = { TopBarTitle("Settings") }) }) { _ ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = spacing.gutter, end = spacing.gutter, top = spacing.xs, bottom = bottomInset + spacing.lg
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            item { SectionHeader("Models") }
            item {
                SettingsGroup {
                    SettingsRow(Icons.Filled.Key, "Providers & API keys", "Pick models, add keys (stored encrypted on this device)", onProviders)
                    SettingsDivider()
                    SettingsRow(Icons.Filled.Extension, "MCP servers", "External tools for the agent", onMcp)
                    SettingsDivider()
                    SettingsRow(Icons.Filled.BarChart, "Usage & cost", "Tokens and estimated spend per model", onUsage)
                }
            }
            item { SectionHeader("Sessions") }
            item {
                SettingsGroup {
                    SettingsRow(Icons.Filled.Download, "Backup sessions", "Save all sessions to a JSON file you choose", backup.backup)
                    SettingsDivider()
                    SettingsRow(Icons.Filled.Upload, "Restore sessions", "Import a CodeSathi backup file", backup.restore)
                }
            }
            item { BackupResult(viewModel) }
            item { SectionHeader("Storage") }
            item { StorageAccessCard() }
            item { SectionHeader("About") }
            item {
                SathiCard(Modifier.fillMaxWidth()) {
                    SathiLockup(markSize = 36.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Your coding companion on Android." + (version?.let { "  Version $it." } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    SathiCard(Modifier.fillMaxWidth(), contentPadding = 0.dp) { content() }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(34.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
