package com.inyogeshwar.codesathi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inyogeshwar.codesathi.data.Message
import com.inyogeshwar.codesathi.data.RunEntity
import com.inyogeshwar.codesathi.ui.AppViewModel
import com.inyogeshwar.codesathi.ui.theme.LocalBottomBarInset
import com.inyogeshwar.codesathi.ui.theme.LocalSpacing
import com.inyogeshwar.codesathi.ui.theme.SathiCard
import com.inyogeshwar.codesathi.ui.theme.SathiScaffold
import com.inyogeshwar.codesathi.ui.theme.SathiTopBar
import com.inyogeshwar.codesathi.ui.theme.SectionHeader
import com.inyogeshwar.codesathi.ui.theme.TopBarTitle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs history (Phase 7, Step B3 — adapted from AgentMT's
 * `feature/runs/RunsScreen.kt` + `RunDetailScreen.kt`, the user's own
 * code, rewritten for CodeSathi's screens and storage).
 *
 * Every agent turn is one run: title, status, steps (model turns),
 * tool calls, changed files, tokens in / out, the route log (which
 * model took over after a limit, and why) and the messages written
 * while it was active. Reached from the Tools tab → Runs.
 *
 * Statuses: "running" (a turn in flight), "waiting" (paused for a
 * file approval), "succeeded", "failed", "cancelled" (stopped by the
 * user, or orphaned by a killed process — see
 * [RunDao.cancelOrphaned]). Clearing removes finished runs only; a
 * running or waiting run is never cleared, and chats and files are
 * never touched by clearing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RunsScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit
) {
    val runs by viewModel.runs.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    var filter by remember { mutableStateOf("all") }
    var confirmClear by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<RunEntity?>(null) }
    val spacing = LocalSpacing.current
    val bottomInset = LocalBottomBarInset.current

    SathiScaffold(
        topBar = {
            SathiTopBar(
                title = { TopBarTitle(if (selected == null) "Runs" else "Run", null) },
                navigationIcon = {
                    IconButton(onClick = { if (selected != null) selected = null else onBack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (selected == null) {
                        IconButton(
                            onClick = { confirmClear = true },
                            enabled = runs.any { it.status != "running" && it.status != "waiting" }
                        ) {
                            Icon(Icons.Outlined.ClearAll, contentDescription = "Clear history")
                        }
                    }
                }
            )
        }
    ) { _ ->
        val run = selected
        if (run != null) {
            RunDetail(
                viewModel = viewModel,
                run = run,
                sessionTitle = sessions.firstOrNull { it.id == run.sessionId }?.title,
                onOpenChat = { onOpenSession(run.sessionId) }
            )
            return@SathiScaffold
        }
        if (runs.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = spacing.gutter),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Filled.History, contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Text("No runs yet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Every task you give the agent shows up here with its steps, " +
                        "tool calls and changed files.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@SathiScaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = spacing.gutter, end = spacing.gutter, top = spacing.xs,
                bottom = bottomInset + spacing.lg
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.sm + 2.dp)
        ) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("all" to "All", "active" to "Active", "succeeded" to "Done", "failed" to "Failed").forEach { (key, label) ->
                        FilterChip(
                            selected = filter == key,
                            onClick = { filter = key },
                            label = { Text(label) }
                        )
                    }
                }
            }
            val shown = runs.filter {
                when (filter) {
                    "active" -> it.status == "running" || it.status == "waiting"
                    "all" -> true
                    else -> it.status == filter
                }
            }
            items(shown, key = { it.id }) { r ->
                RunCard(
                    run = r,
                    sessionTitle = sessions.firstOrNull { it.id == r.sessionId }?.title,
                    onClick = { selected = r }
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear run history?") },
            text = { Text("Finished runs are removed from this list. Chats and files are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearFinishedRuns()
                    confirmClear = false
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            }
        )
    }
}

private fun statusLabel(status: String): String = when (status) {
    "succeeded" -> "Done"
    "failed" -> "Failed"
    "waiting" -> "Needs approval"
    "running" -> "Running"
    "cancelled" -> "Stopped"
    else -> status
}

@Composable
private fun statusColor(status: String): Color = when (status) {
    "succeeded" -> MaterialTheme.colorScheme.primary
    "failed" -> MaterialTheme.colorScheme.error
    "waiting" -> MaterialTheme.colorScheme.tertiary
    "running" -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun RunCard(run: RunEntity, sessionTitle: String?, onClick: () -> Unit) {
    SathiCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (run.status == "running") {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.6.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    statusLabel(run.status),
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor(run.status)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    relativeTime(run.startedAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(run.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            val files = run.changedFiles.lines().count { it.isNotBlank() }
            Text(
                listOfNotNull(
                    sessionTitle,
                    "${run.steps} steps",
                    "${run.toolCalls} tools",
                    if (files > 0) "$files files changed" else null,
                    run.endedAt?.let { formatElapsed((it - run.startedAt) / 1000) }
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            run.error?.let {
                Text(
                    it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun RunDetail(
    viewModel: AppViewModel,
    run: RunEntity,
    sessionTitle: String?,
    onOpenChat: () -> Unit
) {
    val spacing = LocalSpacing.current
    val bottomInset = LocalBottomBarInset.current
    val messages by produceState(initialValue = emptyList<Message>(), run) {
        value = viewModel.runMessages(run)
    }
    val files = run.changedFiles.lines().filter { it.isNotBlank() }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = spacing.gutter, end = spacing.gutter, top = spacing.xs,
            bottom = bottomInset + spacing.lg
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.md)
    ) {
        item {
            SathiCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        statusLabel(run.status),
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor(run.status)
                    )
                    Text(run.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(run.startedAt)) +
                            (run.endedAt?.let { " · " + formatElapsed((it - run.startedAt) / 1000) } ?: "") +
                            (sessionTitle?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(top = 6.dp)) {
                        RunStat("Steps", run.steps.toString())
                        RunStat("Tools", run.toolCalls.toString())
                        RunStat("Tokens in", compactTokens(run.inputTokens))
                        RunStat("Tokens out", compactTokens(run.outputTokens))
                    }
                    run.model?.let {
                        Text("Model: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    run.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedButton(onClick = onOpenChat) { Text("Open chat") }
                    }
                }
            }
        }
        if (files.isNotEmpty()) {
            item { SectionHeader("Changed files (${files.size})") }
            items(files) { f ->
                Text(
                    f, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
        if (run.routeLog.isNotBlank()) {
            item { SectionHeader("Routing") }
            item {
                Text(
                    run.routeLog.trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
        item { SectionHeader("Timeline") }
        if (messages.isEmpty()) {
            item {
                Text(
                    "No messages were written during this run.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(messages, key = { it.id }) { msg ->
                Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                    Text(
                        msg.role.replaceFirstChar { c -> c.uppercase() },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        msg.text.take(500) + if (msg.text.length > 500) "…" else "",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun RunStat(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun compactTokens(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1e6)
    n >= 1000 -> "%.1fk".format(n / 1e3)
    else -> n.toString()
}
