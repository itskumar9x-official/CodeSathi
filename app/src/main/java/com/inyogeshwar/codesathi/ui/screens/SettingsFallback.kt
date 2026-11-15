@file:OptIn(ExperimentalMaterial3Api::class)

package com.inyogeshwar.codesathi.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import com.inyogeshwar.codesathi.data.Provider
import com.inyogeshwar.codesathi.ui.AppViewModel
import com.inyogeshwar.codesathi.ui.theme.ClozedCard
import com.inyogeshwar.codesathi.ui.theme.ClozedSectionHeader
import com.inyogeshwar.codesathi.ui.theme.ClozedSheet
import com.inyogeshwar.codesathi.ui.theme.Pill

/**
 * Auto-switch + fallback order (Phase 8, Settings part 1).
 *
 * The toggle is backed by the real routing mode: ON means "fallback"
 * (walk the chain when a limit is reached), OFF means "pinned" (stay on
 * the session's model and only rotate its keys). A failed key cools
 * down instead of waiting until midnight — rate limit about a minute,
 * daily quota about an hour, out of credit about six hours, server
 * error about twenty seconds.
 */
@Composable
fun AutoSwitchSection(viewModel: AppViewModel) {
    val routingMode by viewModel.routingMode.collectAsState()
    val chain by viewModel.fallbackChain.collectAsState()
    val favourites by viewModel.favourites.collectAsState()
    val exhausted = viewModel.exhaustedModelKeys
    val exhaustedLabels = viewModel.exhaustedLabels
    val notice = viewModel.fallbackNotice
    var showAddSheet by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val autoSwitchOn = routingMode != "pinned"

    ClozedSectionHeader("Auto-switch (when a limit is reached)")
    ClozedCard(Modifier.fillMaxWidth()) {
        Text("Auto-switch", style = MaterialTheme.typography.titleMedium)
        Text(
            "When the model you're chatting with hits a limit, CodeSathi first tries " +
                "another key for the same model, then walks the fallback order below.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (autoSwitchOn) "On" else "Off",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = autoSwitchOn,
                onCheckedChange = { checked ->
                    viewModel.setRoutingMode(if (checked) "fallback" else "pinned")
                }
            )
        }
        if (!autoSwitchOn) {
            Text(
                "Pinned: a failed model only rotates to its next key — the fallback list below " +
                    "is not used until auto-switch is back on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        notice?.let { message ->
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { viewModel.dismissFallbackNotice() }) { Text("Dismiss") }
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        Spacer(Modifier.height(8.dp))
        Text("Fallback order", style = MaterialTheme.typography.titleMedium)
        if (chain.isEmpty()) {
            Text(
                "No fallback yet. Add a provider + model below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        chain.forEachIndexed { index, entry ->
            val entryProvider = viewModel.provider(entry.providerId)
            if (entryProvider != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${index + 1}. ${entryProvider.name} / ${entry.modelId}",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val key = "${entry.providerId}/${entry.modelId}"
                        if (key in exhausted) {
                            Text(
                                exhaustedLabels[key] ?: "Limit reached — cooling down",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    IconButton(
                        onClick = { viewModel.moveFallbackEntryUp(index) },
                        enabled = index > 0
                    ) {
                        Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up")
                    }
                    IconButton(
                        onClick = { viewModel.moveFallbackEntryDown(index) },
                        enabled = index < chain.size - 1
                    ) {
                        Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down")
                    }
                    IconButton(onClick = {
                        viewModel.removeFallbackEntry(entry.providerId, entry.modelId)
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove from fallback")
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(
                text = "+ Add fallback",
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
                onClick = { showAddSheet = true }
            )
            TextButton(onClick = { showResetConfirm = true }) { Text("Limit flags reset") }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "If every key and provider has hit its limit, the app stops and tells you. " +
                "Limits clear on their own once the cooldown ends.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        note?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset limit flags?") },
            text = {
                Text(
                    "Every cooled-down or rejected key will be tried again on the next turn. " +
                        "This does not delete any key."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetAllKeyLimits()
                    showResetConfirm = false
                    note = "Limit flags reset — cooled-down keys can be tried again."
                }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") }
            }
        )
    }
    if (showAddSheet) {
        AddFallbackSheet(
            viewModel = viewModel,
            favourites = favourites.mapNotNull { fav ->
                viewModel.provider(fav.providerId)?.let { it to fav.modelId }
            },
            onDismiss = { showAddSheet = false },
            onPick = { provider, modelId ->
                viewModel.addFallbackEntry(provider.id, modelId)
                showAddSheet = false
                note = "Added ${provider.name} / $modelId to the fallback order."
            }
        )
    }
}

/** Pick a provider + model to add to the fallback order. */
@Composable
private fun AddFallbackSheet(
    viewModel: AppViewModel,
    favourites: List<Pair<Provider, String>>,
    onDismiss: () -> Unit,
    onPick: (Provider, String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val candidates = remember(viewModel, favourites, query) {
        val list = ArrayList<Pair<Provider, String>>()
        viewModel.providerRepository.topPicks().forEach { list += it }
        favourites.forEach { list += it }
        viewModel.providerRepository.providers.forEach { provider ->
            provider.models.forEach { model -> list += provider to model }
        }
        list.distinct()
            .filter { (provider, model) ->
                query.isBlank() ||
                    provider.name.contains(query, ignoreCase = true) ||
                    model.contains(query, ignoreCase = true)
            }
    }
    ClozedSheet(onDismiss = onDismiss) {
        Text("Add fallback", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search providers / models") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(candidates, key = { (provider, model) -> provider.modelKey(model) }) { (provider, model) ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onPick(provider, model) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            provider.name.firstOrNull()?.uppercase().orEmpty(),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(provider.name, style = MaterialTheme.typography.bodyLarge)
                        Text(model, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
