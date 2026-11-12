package com.inyogeshwar.codesathi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.inyogeshwar.codesathi.data.Provider
import com.inyogeshwar.codesathi.ui.AppViewModel

/**
 * Providers + model picker (Step 4, v1 slice).
 * Order: (1) Top picks, (2) Favourites (starred, in Room),
 * (3) the provider's fetched models (GET {baseUrl}/models, cached in Room),
 * (4) the provider's bundled models. Search filters by model / provider name.
 * API keys are entered here and stored ONLY in EncryptedSharedPreferences.
 * When a fetch fails, the cached list is shown with the error, and a model
 * id can be typed in by hand instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderPickerScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var selectedProviderId by remember { mutableStateOf<String?>(null) }
    var keyInput by remember { mutableStateOf("") }
    var keySavedNote by remember { mutableStateOf<String?>(null) }
    val favourites by viewModel.favourites.collectAsState()

    val providers = viewModel.providerRepository.providers
    val filtered = providers.filter { p ->
        query.isBlank() ||
            p.name.contains(query, ignoreCase = true) ||
            p.models.any { it.contains(query, ignoreCase = true) }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Providers & models") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search providers / models") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Text("Top picks", style = MaterialTheme.typography.titleMedium)
                viewModel.providerRepository.topPicks().forEach { (provider, model) ->
                    ModelRow(
                        provider = provider,
                        modelId = model,
                        starred = favourites.any { it.modelKey == provider.modelKey(model) },
                        onStar = { viewModel.toggleFavourite(provider.id, model) },
                        onSelect = {
                            val switched = viewModel.useModel(provider.id, model)
                            keySavedNote = if (switched) {
                                "Model set for this session and as default: ${provider.name} / $model"
                            } else {
                                "Default model set: ${provider.name} / $model"
                            }
                        }
                    )
                }
            }
            if (favourites.isNotEmpty()) {
                item {
                    Text("Favourites", style = MaterialTheme.typography.titleMedium)
                    favourites.forEach { fav ->
                        val provider = viewModel.provider(fav.providerId) ?: return@forEach
                        ModelRow(
                            provider = provider,
                            modelId = fav.modelId,
                            starred = true,
                            onStar = { viewModel.toggleFavourite(fav.providerId, fav.modelId) },
                            onSelect = {
                                val switched = viewModel.useModel(fav.providerId, fav.modelId)
                                keySavedNote = if (switched) {
                                    "Model set for this session and as default: ${provider.name} / ${fav.modelId}"
                                } else {
                                    "Default model set: ${provider.name} / ${fav.modelId}"
                                }
                            }
                        )
                    }
                }
            }
            items(filtered, key = { it.id }) { provider ->
                ProviderCard(
                    provider = provider,
                    expanded = selectedProviderId == provider.id,
                    hasKey = viewModel.hasApiKey(provider.id),
                    onExpand = { selectedProviderId = if (selectedProviderId == provider.id) null else provider.id },
                    keyInput = keyInput,
                    onKeyInput = { keyInput = it },
                    onSaveKey = {
                        viewModel.saveApiKey(provider.id, keyInput)
                        keyInput = ""
                        keySavedNote = "Key saved securely for ${provider.name} (encrypted on this device)."
                    },
                    onRemoveKey = {
                        viewModel.removeApiKey(provider.id)
                        keySavedNote = "Key removed for ${provider.name}."
                    },
                    favourites = favourites,
                    onStar = { model -> viewModel.toggleFavourite(provider.id, model) },
                    onSelectModel = { model ->
                        val switched = viewModel.useModel(provider.id, model)
                        keySavedNote = if (switched) {
                            "Model set for this session and as default: ${provider.name} / $model"
                        } else {
                            "Default model set: ${provider.name} / $model"
                        }
                    },
                    fetchedModels = viewModel.fetchedModels[provider.id].orEmpty(),
                    isFetching = viewModel.fetchingProviderId == provider.id,
                    fetchError = viewModel.fetchErrors[provider.id],
                    onLoadCached = { viewModel.loadCachedModels(provider) },
                    onRefresh = { viewModel.refreshModels(provider) },
                    onUseManualModel = { modelId ->
                        val switched = viewModel.useManualModel(provider.id, modelId)
                        keySavedNote = if (switched) {
                            "Model set for this session and as default: ${provider.name} / ${modelId.trim()}"
                        } else {
                            "Default model set: ${provider.name} / ${modelId.trim()}"
                        }
                    }
                )
            }
            item {
                keySavedNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Keys are stored encrypted (EncryptedSharedPreferences) and are never put in providers.json or sent anywhere except the provider you chose.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun ModelRow(
    provider: Provider,
    modelId: String,
    starred: Boolean,
    onStar: () -> Unit,
    onSelect: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(modifier = Modifier.weight(1f)) {
            Text("${provider.name} / $modelId", style = MaterialTheme.typography.bodyMedium)
            if (provider.local) Text("Runs on this device (no key needed)", style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = onStar) {
            Icon(
                if (starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = if (starred) "Unstar" else "Star as favourite"
            )
        }
        OutlinedButton(onClick = onSelect) { Text("Use") }
    }
}

@Composable
private fun ProviderCard(
    provider: Provider,
    expanded: Boolean,
    hasKey: Boolean,
    onExpand: () -> Unit,
    keyInput: String,
    onKeyInput: (String) -> Unit,
    onSaveKey: () -> Unit,
    onRemoveKey: () -> Unit,
    favourites: List<com.inyogeshwar.codesathi.data.FavouriteModel>,
    onStar: (String) -> Unit,
    onSelectModel: (String) -> Unit,
    fetchedModels: List<String>,
    isFetching: Boolean,
    fetchError: String?,
    onLoadCached: () -> Unit,
    onRefresh: () -> Unit,
    onUseManualModel: (String) -> Unit
) {
    var manualModel by remember { mutableStateOf("") }
    // Show the cached fetched list as soon as the card opens.
    if (expanded) {
        LaunchedEffect(provider.id) { onLoadCached() }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(provider.name, style = MaterialTheme.typography.titleMedium)
                    Text(provider.baseUrl, style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (provider.local) "Local — no API key needed" else if (hasKey) "API key saved ✓" else "No API key yet",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                OutlinedButton(onClick = onExpand) { Text(if (expanded) "Hide" else "Show") }
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                // (3) Fetched models — GET {baseUrl}/models, cached in Room.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Fetched models", style = MaterialTheme.typography.labelMedium)
                    OutlinedButton(onClick = onRefresh, enabled = !isFetching) {
                        Text(if (isFetching) "Refreshing…" else "Refresh")
                    }
                }
                fetchError?.let { err ->
                    Text(
                        "Fetch failed — showing cached list: $err",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (fetchedModels.isEmpty() && fetchError == null && !isFetching) {
                    Text(
                        "No fetched models yet — tap Refresh to load them from ${provider.name}.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                fetchedModels.forEach { model ->
                    ModelRow(
                        provider = provider,
                        modelId = model,
                        starred = favourites.any { it.modelKey == provider.modelKey(model) },
                        onStar = { onStar(model) },
                        onSelect = { onSelectModel(model) }
                    )
                }
                // Manual model id — for providers whose list cannot be fetched.
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = manualModel,
                    onValueChange = { manualModel = it },
                    label = { Text("Or type a model id") },
                    placeholder = { Text("e.g. deepseek-chat") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onUseManualModel(manualModel); manualModel = "" },
                        enabled = manualModel.isNotBlank()
                    ) { Text("Use this model") }
                }
                // (4) Bundled models.
                Spacer(Modifier.height(8.dp))
                Text("Bundled models", style = MaterialTheme.typography.labelMedium)
                provider.models.forEach { model ->
                    ModelRow(
                        provider = provider,
                        modelId = model,
                        starred = favourites.any { it.modelKey == provider.modelKey(model) },
                        onStar = { onStar(model) },
                        onSelect = { onSelectModel(model) }
                    )
                }
                if (!provider.local) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = onKeyInput,
                        label = { Text("API key for ${provider.name}") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onSaveKey, enabled = keyInput.isNotBlank()) { Text("Save key") }
                        if (hasKey) OutlinedButton(onClick = onRemoveKey) { Text("Remove key") }
                    }
                }
            }
        }
    }
}
