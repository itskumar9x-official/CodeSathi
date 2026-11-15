package com.inyogeshwar.codesathi.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import com.inyogeshwar.codesathi.ui.theme.SathiScaffold
import androidx.compose.material3.Text
import com.inyogeshwar.codesathi.ui.theme.SathiTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import android.net.Uri
import com.inyogeshwar.codesathi.data.KeyHealth
import com.inyogeshwar.codesathi.data.Provider
import com.inyogeshwar.codesathi.ui.AppViewModel

/**
 * Providers + model picker (Step 4, v1 slice; Phase 2 Step 1 adds
 * multi-key management and the fallback order below).
 * Order: (1) Top picks, (2) Favourites (starred, in Room),
 * (3) the provider's fetched models (GET {baseUrl}/models, cached in Room),
 * (4) the provider's bundled models. Search filters by model / provider name.
 * API keys are entered here and stored ONLY in EncryptedSharedPreferences —
 * several keys per provider, shown masked (first 3 + last 4 chars).
 * When a fetch fails, the cached list is shown with the error, and a model
 * id can be typed in by hand instead.
 * Fallback order: when a model's limit is reached, CodeSathi first tries
 * another key of the same model, then walks this list top to bottom.
 * Reasoning effort (Phase 3, Step 2): each model row has Default / Low /
 * Medium / High chips — the choice is stored per provider + model and
 * sent when set: `reasoning_effort` to OpenAI-kind providers,
 * `output_config.effort` to Anthropic-kind providers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderPickerScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var selectedProviderId by remember { mutableStateOf<String?>(null) }
    var keyInput by remember { mutableStateOf("") }
    var keySavedNote by remember { mutableStateOf<String?>(null) }
    // On-device .task model file (Phase 6, Step 5): the system picker
    // returns any file, so the ViewModel refuses non-.task files with a
    // plain message — the mime filter here is only a hint.
    val localModelLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val error = viewModel.setLocalModelFile(context, uri)
            keySavedNote = error
                ?: "Model file set — it is now the default model. The first load copies the file into the app and takes a while."
        }
    }
    val favourites by viewModel.favourites.collectAsState()
    val fallbackChain by viewModel.fallbackChain.collectAsState()
    // Routing mode + per-key health (Phase 7, Step B1).
    val routingMode by viewModel.routingMode.collectAsState()
    val keyHealth by viewModel.keyHealth.collectAsState()
    var showResetConfirm by remember { mutableStateOf(false) }
    // Reasoning effort per model (Phase 3, Step 2): "providerId/modelId" → low/medium/high.
    val efforts by viewModel.reasoningEfforts.collectAsState()
    // Azure resource names (Phase 4): "providerId" → resource name.
    val azureResourceNames by viewModel.azureResourceNames.collectAsState()
    val exhaustedModelKeys = viewModel.exhaustedModelKeys
    val exhaustedLabels = viewModel.exhaustedLabels
    // Read for subscription only: KeyStore is not a Flow, so the key lists
    // below are re-read whenever this version bumps (add / delete key).
    @Suppress("UNUSED_VARIABLE")
    val keysVersion = viewModel.keysVersion

    val providers = viewModel.providerRepository.providers
    val filtered = providers.filter { p ->
        query.isBlank() ||
            p.name.contains(query, ignoreCase = true) ||
            p.models.any { it.contains(query, ignoreCase = true) }
    }
    SathiScaffold(topBar = {
        SathiTopBar(
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
                Text("Fallback order", style = MaterialTheme.typography.titleMedium)
                Text(
                    "When a model fails, CodeSathi first tries another key of the same model, then walks this list top to bottom. A failed key cools down instead of waiting until midnight: rate limit 1 min, daily quota 1 h, out of credit 6 h, server error 20 s. A key the provider rejected stays skipped until you reset the limit flags.",
                    style = MaterialTheme.typography.bodySmall
                )
                // Routing mode (Phase 7, Step B1): pinned stays on the
                // session's model (rotating only its keys); fallback
                // walks the chain below. The chain order never changes.
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = routingMode == "pinned",
                        onClick = { viewModel.setRoutingMode("pinned") },
                        label = { Text("Pinned — stay on this model", style = MaterialTheme.typography.labelSmall) }
                    )
                    FilterChip(
                        selected = routingMode != "pinned",
                        onClick = { viewModel.setRoutingMode("fallback") },
                        label = { Text("Fallback — use the chain", style = MaterialTheme.typography.labelSmall) }
                    )
                }
                if (routingMode == "pinned") {
                    Text(
                        "Pinned: a failed model only rotates to its next key — the fallback list below is not used until you switch back.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showResetConfirm = true }) {
                        Text("Reset limit flags")
                    }
                }
                Text(
                    "Resetting lifts every cooldown and rejected-key flag, so the next turn retries the first key again. A key that is still really limited will simply fail and cool down again.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (showResetConfirm) {
                    AlertDialog(
                        onDismissRequest = { showResetConfirm = false },
                        title = { Text("Reset limit flags?") },
                        text = { Text("Every cooled-down or rejected key will be tried again on the next turn. This does not delete any key.") },
                        confirmButton = {
                            TextButton(onClick = {
                                viewModel.resetAllKeyLimits()
                                showResetConfirm = false
                                keySavedNote = "Limit flags reset — cooled-down keys can be tried again."
                            }) { Text("Reset") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") }
                        }
                    )
                }
                if (fallbackChain.isEmpty()) {
                    Text(
                        "No fallback set yet — tap + on any model below to add it.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                fallbackChain.forEachIndexed { index, entry ->
                    val entryProvider = viewModel.provider(entry.providerId) ?: return@forEachIndexed
                    val exhausted = "${entry.providerId}/${entry.modelId}" in exhaustedModelKeys
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${index + 1}. ${entryProvider.name} / ${entry.modelId}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (exhausted) {
                                Text(
                                    exhaustedLabels["${entry.providerId}/${entry.modelId}"]
                                        ?: "Limit reached — cooling down",
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
                            enabled = index < fallbackChain.size - 1
                        ) {
                            Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down")
                        }
                        IconButton(
                            onClick = { viewModel.removeFallbackEntry(entry.providerId, entry.modelId) }
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove from fallback")
                        }
                    }
                }
            }
            item {
                Text("Top picks", style = MaterialTheme.typography.titleMedium)
                viewModel.providerRepository.topPicks().forEach { (provider, model) ->
                    ModelRow(
                        provider = provider,
                        modelId = model,
                        starred = favourites.any { it.modelKey == provider.modelKey(model) },
                        effort = efforts[provider.modelKey(model)],
                        onSetEffort = { viewModel.setReasoningEffort(provider.id, model, it) },
                        onStar = { viewModel.toggleFavourite(provider.id, model) },
                        onSelect = {
                            val switched = viewModel.useModel(provider.id, model)
                            keySavedNote = if (switched) {
                                "Model set for this session and as default: ${provider.name} / $model"
                            } else {
                                "Default model set: ${provider.name} / $model"
                            }
                        },
                        onAddFallback = {
                            viewModel.addFallbackEntry(provider.id, model)
                            keySavedNote = "Added ${provider.name} / $model to the fallback order."
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
                            effort = efforts[provider.modelKey(fav.modelId)],
                            onSetEffort = { viewModel.setReasoningEffort(fav.providerId, fav.modelId, it) },
                            onStar = { viewModel.toggleFavourite(fav.providerId, fav.modelId) },
                            onSelect = {
                                val switched = viewModel.useModel(fav.providerId, fav.modelId)
                                keySavedNote = if (switched) {
                                    "Model set for this session and as default: ${provider.name} / ${fav.modelId}"
                                } else {
                                    "Default model set: ${provider.name} / ${fav.modelId}"
                                }
                            },
                            onAddFallback = {
                                viewModel.addFallbackEntry(fav.providerId, fav.modelId)
                                keySavedNote = "Added ${provider.name} / ${fav.modelId} to the fallback order."
                            }
                        )
                    }
                }
            }
            items(filtered, key = { it.id }) { provider ->
                ProviderCard(
                    provider = provider,
                    expanded = selectedProviderId == provider.id,
                    keyCount = viewModel.apiKeys(provider.id).size,
                    onExpand = { selectedProviderId = if (selectedProviderId == provider.id) null else provider.id },
                    keyInput = keyInput,
                    onKeyInput = { keyInput = it },
                    onSaveKey = {
                        viewModel.addApiKey(provider.id, keyInput)
                        keyInput = ""
                        keySavedNote = "Key added for ${provider.name} (encrypted on this device)."
                    },
                    onRemoveKey = { keyId ->
                        viewModel.removeApiKey(provider.id, keyId)
                        keySavedNote = "Key removed for ${provider.name}."
                    },
                    apiKeys = viewModel.apiKeys(provider.id),
                    keyHealth = keyHealth,
                    favourites = favourites,
                    efforts = efforts,
                    onSetEffort = { model, effort -> viewModel.setReasoningEffort(provider.id, model, effort) },
                    onStar = { model -> viewModel.toggleFavourite(provider.id, model) },
                    onAddFallback = { model ->
                        viewModel.addFallbackEntry(provider.id, model)
                        keySavedNote = "Added ${provider.name} / $model to the fallback order."
                    },
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
                    },
                    azureResourceName = azureResourceNames[provider.id],
                    onSaveAzureResourceName = { name ->
                        val ok = viewModel.saveAzureResourceName(provider.id, name)
                        keySavedNote = if (ok) {
                            "Azure resource name saved for ${provider.name}."
                        } else {
                            "That name doesn't match Azure's rules (3–24 chars, lowercase letters, digits, hyphens)."
                        }
                    },
                    awsAccessKeyIdMasked = viewModel.awsAccessKeyIdMasked(),
                    awsRegion = viewModel.awsRegion(),
                    onSaveAwsCredential = { accessKeyId, secret, region ->
                        val ok = viewModel.saveAwsCredential(accessKeyId, secret, region)
                        keySavedNote = if (ok) {
                            "AWS credentials saved for ${provider.name} (encrypted on this device)."
                        } else {
                            "The access key id and secret access key can't be blank."
                        }
                    },
                    onRemoveAwsCredential = {
                        viewModel.removeAwsCredential()
                        keySavedNote = "AWS credentials removed for ${provider.name}."
                    },
                    localModelName = viewModel.localModelName,
                    onChooseLocalModel = { localModelLauncher.launch(arrayOf("*/*")) },
                    onRemoveLocalModel = {
                        viewModel.removeLocalModelFile()
                        keySavedNote = "On-device model file removed. Pick another .task file to use the on-device model again."
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
                Spacer(Modifier.height(8.dp))
                Text(
                    "Reasoning effort (Default / Low / Medium / High) is set per model above and sent only when set. OpenAI-kind providers get it as reasoning_effort; Anthropic-kind providers get it as output_config.effort (verified from Anthropic's own docs — supported on Claude 4.5+ / 5.x models). Default sends nothing.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelRow(
    provider: Provider,
    modelId: String,
    starred: Boolean,
    effort: String? = null,
    /** Null when the picker is not shown for this row. */
    onSetEffort: ((String?) -> Unit)? = null,
    onStar: () -> Unit,
    onSelect: () -> Unit,
    onAddFallback: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(modifier = Modifier.weight(1f)) {
            Text("${provider.name} / $modelId", style = MaterialTheme.typography.bodyMedium)
            if (provider.local) Text("Runs on this device (no key needed)", style = MaterialTheme.typography.bodySmall)
            // Reasoning effort (Phase 3, Step 2): per-model low / medium /
            // high, or Default (unset — nothing is sent). Shown for every
            // provider except the on-device one: OpenAI-kind sends
            // `reasoning_effort`, Anthropic-kind sends `output_config.effort`
            // (verified from Anthropic's docs); the local engine takes no
            // effort field, so its rows show no chips.
            if (onSetEffort != null && provider.kind != "local") {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    EffortChip(label = "Default", selected = effort == null, onClick = { onSetEffort(null) })
                    EffortChip(label = "Low", selected = effort == "low", onClick = { onSetEffort("low") })
                    EffortChip(label = "Medium", selected = effort == "medium", onClick = { onSetEffort("medium") })
                    EffortChip(label = "High", selected = effort == "high", onClick = { onSetEffort("high") })
                }
            }
        }
        IconButton(onClick = onStar) {
            Icon(
                if (starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = if (starred) "Unstar" else "Star as favourite"
            )
        }
        IconButton(onClick = onAddFallback) {
            Icon(Icons.Filled.Add, contentDescription = "Add to fallback order")
        }
        OutlinedButton(onClick = onSelect) { Text("Use") }
    }
}

/** One small effort chip (Default / Low / Medium / High) in a model row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EffortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
    )
}

@Composable
private fun ProviderCard(
    provider: Provider,
    expanded: Boolean,
    keyCount: Int,
    onExpand: () -> Unit,
    keyInput: String,
    onKeyInput: (String) -> Unit,
    onSaveKey: () -> Unit,
    onRemoveKey: (String) -> Unit,
    apiKeys: List<com.inyogeshwar.codesathi.data.KeyStore.KeyDisplay>,
    /** Per-key health, keyed by "providerId|modelId|keyId" (Phase 7, Step B1). */
    keyHealth: Map<String, KeyHealth> = emptyMap(),
    favourites: List<com.inyogeshwar.codesathi.data.FavouriteModel>,
    efforts: Map<String, String>,
    /** (modelId, effort) — effort null means back to Default. */
    onSetEffort: (String, String?) -> Unit,
    onStar: (String) -> Unit,
    onAddFallback: (String) -> Unit,
    onSelectModel: (String) -> Unit,
    fetchedModels: List<String>,
    isFetching: Boolean,
    fetchError: String?,
    onLoadCached: () -> Unit,
    onRefresh: () -> Unit,
    onUseManualModel: (String) -> Unit,
    /** The saved Azure resource name, or null when unset. Only used for kind == "azure". */
    azureResourceName: String? = null,
    /** Save a new Azure resource name (the screen sets the note). Only used for kind == "azure". */
    onSaveAzureResourceName: (String) -> Unit = {},
    /** Masked AWS access key id, or null when unset. Only used for kind == "bedrock". */
    awsAccessKeyIdMasked: String? = null,
    /** Stored AWS region (us-east-1 when unset). Only used for kind == "bedrock". */
    awsRegion: String = "us-east-1",
    /** Save an AWS credential set (the screen sets the note). Only used for kind == "bedrock". */
    onSaveAwsCredential: (String, String, String) -> Unit = { _, _, _ -> },
    /** Delete the AWS credential set. Only used for kind == "bedrock". */
    onRemoveAwsCredential: () -> Unit = {},
    /** Display name of the picked .task file, or null when none is picked. Only used for kind == "local". */
    localModelName: String? = null,
    /** Open the system picker for a .task file. Only used for kind == "local". */
    onChooseLocalModel: () -> Unit = {},
    /** Forget the picked .task file. Only used for kind == "local". */
    onRemoveLocalModel: () -> Unit = {}
) {
    var manualModel by remember { mutableStateOf("") }
    var azureInput by remember { mutableStateOf("") }
    var awsAccessKeyIdInput by remember { mutableStateOf("") }
    var awsSecretInput by remember { mutableStateOf("") }
    var awsRegionInput by remember { mutableStateOf("") }
    // Show the cached fetched list as soon as the card opens.
    if (expanded) {
        LaunchedEffect(provider.id) { onLoadCached() }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(provider.name, style = MaterialTheme.typography.titleMedium)
                    // The on-device provider has no server URL (its baseUrl is empty).
                    if (provider.baseUrl.isNotBlank()) Text(provider.baseUrl, style = MaterialTheme.typography.bodySmall)
                    // Bedrock has no API key — its status is the AWS
                    // credential set (Phase 4), not a key count.
                    val keyStatus = when {
                        provider.local -> "Local — no API key needed"
                        provider.kind == "bedrock" ->
                            if (awsAccessKeyIdMasked != null) "AWS credentials saved ✓" else "No AWS credentials yet"
                        keyCount > 0 -> "$keyCount API key${if (keyCount == 1) "" else "s"} saved ✓"
                        else -> "No API key yet"
                    }
                    Text(keyStatus, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = onExpand) { Text(if (expanded) "Hide" else "Show") }
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                // On-device .task model (Phase 6, Step 5): no server, no
                // fetched or bundled list, no API key — the model is a
                // file the user picks. The fetched / manual / bundled
                // sections below are skipped for this kind.
                if (provider.kind == "local") {
                    Text("Model file (.task)", style = MaterialTheme.typography.labelMedium)
                    Text(
                        "Runs completely on this device — no server, no API key, no internet. " +
                            "Download a .task model file yourself first, then pick the file here. " +
                            "The app never bundles a model. Gemma-3 1B in 4-bit (about 550 MB) is the " +
                            "known-good starting point — check the download size before you start.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (localModelName != null) {
                        Text("Chosen file: $localModelName", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "First use copies the file into the app and loads it — this takes a while " +
                                "and needs free space about twice the file size. Later turns reuse the loaded model.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text("No model file chosen yet.", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onChooseLocalModel) {
                            Text(if (localModelName == null) "Choose .task file" else "Choose a different file")
                        }
                        if (localModelName != null) {
                            OutlinedButton(onClick = onRemoveLocalModel) { Text("Remove") }
                        }
                    }
                    Text(
                        "Known-compatible .task models (Google's MediaPipe docs): Gemma-3 1B (4-bit) " +
                            "and Gemma-3n E2B / E4B. Another MediaPipe .task bundle may work, but a GGUF " +
                            "or safetensors file will not load — it must be a .task file.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    // The chosen file as a model row, so it can be starred,
                    // added to the fallback order, or tapped Use like any
                    // other model. Its id is the file name.
                    if (localModelName != null) {
                        ModelRow(
                            provider = provider,
                            modelId = localModelName,
                            starred = favourites.any { it.modelKey == provider.modelKey(localModelName) },
                            effort = null,
                            onSetEffort = null,
                            onStar = { onStar(localModelName) },
                            onSelect = { onSelectModel(localModelName) },
                            onAddFallback = { onAddFallback(localModelName) }
                        )
                    }
                }
                if (provider.kind != "local") {
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
                        effort = efforts[provider.modelKey(model)],
                        onSetEffort = { onSetEffort(model, it) },
                        onStar = { onStar(model) },
                        onSelect = { onSelectModel(model) },
                        onAddFallback = { onAddFallback(model) }
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
                        effort = efforts[provider.modelKey(model)],
                        onSetEffort = { onSetEffort(model, it) },
                        onStar = { onStar(model) },
                        onSelect = { onSelectModel(model) },
                        onAddFallback = { onAddFallback(model) }
                    )
                }
                } // end of the non-local sections (fetched / manual / bundled)
                // Azure resource name (Phase 4): the {resource} in
                // https://{resource}.openai.azure.com. Chat won't run
                // until this is set, and the model id is the deployment
                // name — see LlmClient. Not a secret, so it is shown.
                if (provider.kind == "azure") {
                    Spacer(Modifier.height(8.dp))
                    Text("Azure resource name", style = MaterialTheme.typography.labelMedium)
                    Text(
                        "Your Azure OpenAI resource name — the {resource} in https://{resource}.openai.azure.com. The model id you pick is the deployment name. Chat won't run until this is set.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (azureResourceName != null) {
                        Text("Saved: $azureResourceName", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedTextField(
                        value = azureInput,
                        onValueChange = { azureInput = it },
                        label = { Text("Resource name") },
                        placeholder = { Text("e.g. my-resource") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onSaveAzureResourceName(azureInput); azureInput = "" },
                            enabled = azureInput.isNotBlank()
                        ) { Text("Save resource name") }
                    }
                }
                // AWS credentials (Phase 4): Bedrock has no API key — it
                // needs an access key id, a secret access key and a region,
                // signed per request (SigV4). The secret is never shown;
                // the access key id is masked like other keys.
                if (provider.kind == "bedrock") {
                    Spacer(Modifier.height(8.dp))
                    Text("AWS credentials", style = MaterialTheme.typography.labelMedium)
                    Text(
                        "Bedrock does not use an API key. Enter the access key id, secret access key and region of an AWS account with bedrock:InvokeModel permission. Requests are signed (SigV4) and sent to the Converse API.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (awsAccessKeyIdMasked != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Access key id: $awsAccessKeyIdMasked", modifier = Modifier.weight(1f))
                            OutlinedButton(onClick = onRemoveAwsCredential) { Text("Delete") }
                        }
                        Text("Region: $awsRegion", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("No AWS credentials saved yet.", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedTextField(
                        value = awsAccessKeyIdInput,
                        onValueChange = { awsAccessKeyIdInput = it },
                        label = { Text("Access key id") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = awsSecretInput,
                        onValueChange = { awsSecretInput = it },
                        label = { Text("Secret access key") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = awsRegionInput,
                        onValueChange = { awsRegionInput = it },
                        label = { Text("Region") },
                        placeholder = { Text(awsRegion) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                onSaveAwsCredential(awsAccessKeyIdInput, awsSecretInput, awsRegionInput)
                                awsAccessKeyIdInput = ""
                                awsSecretInput = ""
                                awsRegionInput = ""
                            },
                            enabled = awsAccessKeyIdInput.isNotBlank() && awsSecretInput.isNotBlank()
                        ) { Text("Save AWS credentials") }
                    }
                }
                if (!provider.local && provider.kind != "bedrock") {
                    Spacer(Modifier.height(8.dp))
                    Text("API keys", style = MaterialTheme.typography.labelMedium)
                    if (apiKeys.isEmpty()) {
                        Text("No key saved yet.", style = MaterialTheme.typography.bodySmall)
                    }
                    apiKeys.forEach { stored ->
                        // Health across this key's models (Phase 7,
                        // Step B1): a key cools down per model, so the
                        // row shows its worst state — rejected beats
                        // cooling down, cooling down beats healthy.
                        val keyEntries = keyHealth.filterKeys {
                            it.startsWith("${provider.id}|") && it.endsWith("|${stored.id}")
                        }.values
                        val healthText = when {
                            keyEntries.any { it.status == KeyHealth.STATUS_INVALID } ->
                                "Key rejected — reset limit flags to try again"
                            keyEntries.any { it.cooldownUntil > System.currentTimeMillis() } ->
                                "Cooling down — ${keyEntries.firstNotNullOfOrNull { it.lastError } ?: "try again shortly"}"
                            else -> null
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stored.masked)
                                healthText?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            OutlinedButton(onClick = { onRemoveKey(stored.id) }) { Text("Delete") }
                        }
                    }
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = onKeyInput,
                        label = { Text("Add another API key") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onSaveKey, enabled = keyInput.isNotBlank()) { Text("Add key") }
                    }
                }
            }
        }
    }
}
