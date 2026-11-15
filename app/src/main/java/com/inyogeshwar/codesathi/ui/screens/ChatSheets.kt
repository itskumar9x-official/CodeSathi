package com.inyogeshwar.codesathi.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inyogeshwar.codesathi.data.Session
import com.inyogeshwar.codesathi.ui.AppViewModel
import com.inyogeshwar.codesathi.ui.theme.ClozedSheet

/**
 * "Choose agent" bottom sheet (Phase 8): Build / Plan rows with a
 * search field. Tapping a row flips the session's mode through the
 * existing [AppViewModel.setMode] — Plan stays read-only in code,
 * exactly as before; only where the switch lives has moved.
 */
@Composable
fun ChooseAgentSheet(
    viewModel: AppViewModel,
    session: Session,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val agents = listOf(
        "build" to ("Build" to "Full access agent for development work"),
        "plan" to ("Plan" to "Read-only agent for exploring the codebase and planning")
    ).filter { (_, pair) ->
        query.isBlank() ||
            pair.first.contains(query, ignoreCase = true) ||
            pair.second.contains(query, ignoreCase = true)
    }
    ClozedSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Choose agent", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search agents") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth()
            )
            agents.forEach { (mode, pair) ->
                val (name, desc) = pair
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable {
                            viewModel.setMode(session, mode)
                            onDismiss()
                        }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            desc,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (session.agentMode == mode) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Current agent",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            if (agents.isEmpty()) {
                Text(
                    "No agent matches that search.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * "Choose model" bottom sheet (Phase 8): provider chips, a search
 * field, and model rows (name large, provider id small grey below).
 * Selecting a model switches the CURRENT session through the exact
 * same call the provider picker uses — [AppViewModel.useModel] —
 * which also sets it as the default for new sessions. The chat
 * screen keeps the session active (setActiveSession), so useModel
 * returns true and the switch lands on this session.
 *
 * There is no per-provider "last used model" stored anywhere, so a
 * provider chip filters the list to that provider instead of
 * auto-switching to a remembered model. If useModel ever reports
 * that no session was switched, the sheet falls back to opening the
 * full provider screen instead of pretending the switch happened.
 */
@Composable
fun ChooseModelSheet(
    viewModel: AppViewModel,
    session: Session,
    onDismiss: () -> Unit,
    onProviders: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selectedProviderId by remember { mutableStateOf(session.providerId) }
    val providers = viewModel.providerRepository.providers
    val selectedProvider = providers.firstOrNull { it.id == selectedProviderId }
        ?: providers.firstOrNull { it.id == session.providerId }
    // Show this provider's fetched (cached) models as soon as it is
    // picked, like the provider screen does when a card opens.
    LaunchedEffect(selectedProvider?.id) {
        selectedProvider?.let { viewModel.loadCachedModels(it) }
    }
    val models = selectedProvider?.let { provider ->
        (viewModel.fetchedModels[provider.id].orEmpty() + provider.models)
            .distinct()
            .filter { model -> query.isBlank() || model.contains(query, ignoreCase = true) }
    }.orEmpty()

    ClozedSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Choose model", style = MaterialTheme.typography.titleLarge)
            Text(
                "Pick a provider, then tap a model to switch this chat to it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                providers.forEach { provider ->
                    FilterChip(
                        selected = provider.id == selectedProvider?.id,
                        onClick = { selectedProviderId = provider.id },
                        label = { Text(provider.name, maxLines = 1) }
                    )
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search models") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth()
            )
            if (selectedProvider == null) {
                Text(
                    "No provider available.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (models.isEmpty()) {
                Text(
                    "No model matches that search.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(models, key = { "${selectedProvider.id}/$it" }) { model ->
                        val isCurrent = selectedProvider.id == session.providerId &&
                            model == session.modelId
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable {
                                    val switched = viewModel.useModel(selectedProvider.id, model)
                                    onDismiss()
                                    if (!switched) onProviders()
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    model,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    selectedProvider.id,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (isCurrent) {
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "Current model",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
