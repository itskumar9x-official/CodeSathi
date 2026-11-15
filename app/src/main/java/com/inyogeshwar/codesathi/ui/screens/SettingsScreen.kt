package com.inyogeshwar.codesathi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.inyogeshwar.codesathi.ui.AppViewModel
import com.inyogeshwar.codesathi.ui.theme.ClozedCard
import com.inyogeshwar.codesathi.ui.theme.ClozedSectionHeader
import com.inyogeshwar.codesathi.ui.theme.LocalBottomBarInset
import com.inyogeshwar.codesathi.ui.theme.LocalSpacing
import com.inyogeshwar.codesathi.ui.theme.SathiScaffold
import com.inyogeshwar.codesathi.ui.theme.SathiTopBar
import com.inyogeshwar.codesathi.ui.theme.TopBarTitle

/**
 * Settings (Phase 8): one scrollable screen in Clozed's section order.
 * No back arrow in the top bar — the system back takes you back from
 * here (Settings is reached from the Sessions gear / Chat gear).
 *
 * Sections are split across files by owner: part 1 (this agent) owns
 * Providers, Auto-switch, Permissions and Permission rules; part 2 owns
 * Background & voice, Reasoning, MCP, Commands, Skills, Appearance,
 * Connection, Tools and About. The calls below use the exact
 * cross-agent contract names, so the two halves meet here.
 */
@Composable
fun SettingsScreen(
    viewModel: AppViewModel,
    onUsage: () -> Unit,
    onMcp: () -> Unit,
    onSkills: () -> Unit,
    onCommands: () -> Unit,
    onTerminal: () -> Unit,
    onRemote: () -> Unit,
    onSync: () -> Unit,
    onGit: () -> Unit,
    onEditor: () -> Unit,
    onRuns: () -> Unit
) {
    val spacing = LocalSpacing.current
    val bottomInset = LocalBottomBarInset.current
    val backup = rememberBackupActions(viewModel)

    SathiScaffold(topBar = { SathiTopBar(title = { TopBarTitle("Settings") }) }) { _ ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = spacing.gutter, end = spacing.gutter, top = spacing.xs, bottom = bottomInset + spacing.lg
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            item { ProvidersSection(viewModel) }
            item { AutoSwitchSection(viewModel) }
            item { PermissionsSection(viewModel) }
            item { BackgroundVoiceSection(viewModel) }
            item { ReasoningSection(viewModel) }
            item { McpSection(viewModel, onMcp) }
            item { PermissionRulesSection(viewModel) }
            item { CommandsSection(viewModel, onCommands) }
            item { SkillsSection(viewModel, onSkills) }
            item { AppearanceSection(viewModel) }
            item { ConnectionSection(viewModel) }
            item {
                ToolsSection(
                    onEditor = onEditor,
                    onTerminal = onTerminal,
                    onGit = onGit,
                    onRemote = onRemote,
                    onSync = onSync,
                    onUsage = onUsage,
                    onRuns = onRuns
                )
            }
            item { ClozedSectionHeader("Sessions") }
            item {
                ClozedCard(Modifier.fillMaxWidth()) {
                    SettingsRow(Icons.Filled.Download, "Backup sessions", "Save all sessions to a JSON file you choose", backup.backup)
                    SettingsRow(Icons.Filled.Upload, "Restore sessions", "Import a CodeSathi backup file", backup.restore)
                }
            }
            item { BackupResult(viewModel) }
            item { AboutSection(viewModel) }
        }
    }
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp),
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
