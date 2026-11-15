package com.inyogeshwar.codesathi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.inyogeshwar.codesathi.ui.theme.LocalBottomBarInset
import com.inyogeshwar.codesathi.ui.theme.LocalSpacing
import com.inyogeshwar.codesathi.ui.theme.SathiScaffold
import com.inyogeshwar.codesathi.ui.theme.SathiTopBar
import com.inyogeshwar.codesathi.ui.theme.SectionHeader
import com.inyogeshwar.codesathi.ui.theme.TopBarTitle

private data class ToolEntry(val icon: ImageVector, val title: String, val subtitle: String, val onClick: () -> Unit)

/**
 * v1.2 Tools hub: every secondary screen in one place (they used to sit
 * in Home's top bar and "⋮" menu). Nothing was removed.
 */
@Composable
fun ToolsScreen(
    onEditor: () -> Unit,
    onGit: () -> Unit,
    onTerminal: () -> Unit,
    onSkills: () -> Unit,
    onCommands: () -> Unit,
    onMcp: () -> Unit,
    onRemote: () -> Unit,
    onSync: () -> Unit,
    onUsage: () -> Unit,
    onRuns: () -> Unit
) {
    val spacing = LocalSpacing.current
    val bottomInset = LocalBottomBarInset.current
    val workspace = listOf(
        ToolEntry(Icons.Filled.Edit, "Editor", "Browse and edit project files", onEditor),
        ToolEntry(Icons.Filled.Terminal, "Terminal", "Run shell commands in the folder", onTerminal),
        ToolEntry(Icons.Filled.AccountTree, "Git", "Status, commit, push, pull, branches", onGit),
    )
    val agent = listOf(
        ToolEntry(Icons.Filled.Build, "Skills", "Reusable instructions for the agent", onSkills),
        ToolEntry(Icons.Filled.Code, "Custom commands", "Your own /slash commands", onCommands),
        ToolEntry(Icons.Filled.Extension, "MCP servers", "Connect external tools", onMcp),
        ToolEntry(Icons.Filled.History, "Runs", "Past turns, tokens and changed files", onRuns),
    )
    val devices = listOf(
        ToolEntry(Icons.Filled.Wifi, "Remote mode", "Chat from another device on your Wi-Fi", onRemote),
        ToolEntry(Icons.Filled.Sync, "Sync devices", "Sync sessions with another device", onSync),
        ToolEntry(Icons.Filled.BarChart, "Usage & cost", "Tokens and estimated spend", onUsage),
    )
    SathiScaffold(topBar = { SathiTopBar(title = { TopBarTitle("Tools", "Everything CodeSathi can do") }) }) { _ ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = spacing.gutter, end = spacing.gutter, top = spacing.xs, bottom = bottomInset + spacing.lg
            ),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            section("Workspace", workspace)
            section("Agent", agent)
            section("Devices & insights", devices)
        }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.section(title: String, entries: List<ToolEntry>) {
    item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(title) }
    items(entries, key = { it.title }) { e -> QuickCard(e.icon, e.title, e.subtitle, onClick = e.onClick) }
}

