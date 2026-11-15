@file:OptIn(ExperimentalMaterial3Api::class)

package com.inyogeshwar.codesathi.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inyogeshwar.codesathi.CodeSathiApp
import com.inyogeshwar.codesathi.data.KeyHealth
import com.inyogeshwar.codesathi.data.KeyStore
import com.inyogeshwar.codesathi.data.Provider
import com.inyogeshwar.codesathi.ui.AppViewModel
import com.inyogeshwar.codesathi.ui.theme.ClozedCard
import com.inyogeshwar.codesathi.ui.theme.ClozedSectionHeader
import com.inyogeshwar.codesathi.ui.theme.ClozedSegmentedControl
import com.inyogeshwar.codesathi.ui.theme.ClozedSheet
import com.inyogeshwar.codesathi.ui.theme.LocalSathiStatus
import com.inyogeshwar.codesathi.ui.theme.Pill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Providers & API keys (Phase 8, Settings part 1).
 *
 * Clozed's layout, CodeSathi's own strings and behaviour: the prebuilt
 * 207-provider catalog is kept — providers are picked from a sheet and
 * their models are fetched from the provider — and keys are stored only
 * in EncryptedSharedPreferences, shown masked, never logged.
 *
 * Two honest limits of this screen (see the Phase 8 report):
 * - A provider card's "in use" switch means "this provider supplies the
 *   default model". There is no per-provider enable flag in the app, so
 *   switching it on sets the default model to that provider's first
 *   model, and switching it off goes back to the first top pick.
 * - Custom endpoints are kept only while Settings is open: there is no
 *   store for custom provider definitions yet, so one is needed before
 *   a custom endpoint can survive a restart or be used in a chat. Its
 *   API key IS saved encrypted, like any other key.
 */
@Composable
fun ProvidersSection(viewModel: AppViewModel) {
    val context = LocalContext.current
    val app = context.applicationContext as CodeSathiApp
    val keyStore = remember(app) { app.keyStore }
    val scope = rememberCoroutineScope()
    // Re-read the key lists whenever keys change (the ViewModel bumps
    // this on every add / remove) or after a local KeyStore write.
    @Suppress("UNUSED_VARIABLE")
    val keysVersion = viewModel.keysVersion
    var refreshTick by remember { mutableIntStateOf(0) }

    var customProviders by remember { mutableStateOf(listOf<Provider>()) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var keyDialogProvider by remember { mutableStateOf<Provider?>(null) }
    var multiKeyProvider by remember { mutableStateOf<Provider?>(null) }
    var deleteProvider by remember { mutableStateOf<Provider?>(null) }
    var showChooseSheet by remember { mutableStateOf(false) }
    var showCustomDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showKeyWarning by remember { mutableStateOf(false) }
    var warningAction by remember { mutableStateOf("") } // "export" | "share"
    var pendingExportJson by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    // "In use" = the provider of the default model. The stored default
    // is a DataStore flow; when nothing was ever picked it falls back
    // to the first top pick, exactly like the app's own default.
    val defaultProviderId by app.preferencesStore.defaultProviderId.collectAsState(initial = null)
    val firstTopPick = remember(viewModel) { viewModel.providerRepository.topPicks().firstOrNull() }
    val inUseProviderId = defaultProviderId ?: firstTopPick?.first?.id

    val connectedIds = remember(keysVersion, refreshTick) { keyStore.providerIdsWithKeys() }
    val connected = connectedIds.mapNotNull { viewModel.provider(it) }
    val bedrockProvider = viewModel.provider("bedrock")
    val localProvider = viewModel.provider("ondevice")
    val localName = viewModel.localModelName
    val showBedrock = viewModel.hasAwsCredential() && bedrockProvider != null && connected.none { it.id == "bedrock" }
    val showLocal = localName != null && localProvider != null && connected.none { it.id == "ondevice" }
    val cards: List<Pair<Provider, Boolean>> =
        connected.map { it to false } +
            (if (showBedrock) listOf(bedrockProvider!! to false) else emptyList()) +
            (if (showLocal) listOf(localProvider!! to false) else emptyList()) +
            customProviders.map { it to true }

    // On-device .task file picker (Phase 6, Step 5): the system picker
    // returns any file, so the ViewModel refuses non-.task files.
    val localLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val error = viewModel.setLocalModelFile(context, uri)
            note = error
                ?: "Model file set — it is now the default model. The first load copies the file into the app and takes a while."
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val json = pendingExportJson
        pendingExportJson = null
        if (uri != null && json != null) {
            scope.launch(Dispatchers.IO) {
                val ok = runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    } != null
                }.getOrDefault(false)
                withContext(Dispatchers.Main) {
                    note = if (ok) "Providers backup saved." else "Could not write the backup file — try another location."
                }
            }
        }
    }
    val importFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val text = runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
                val result = if (text.isNullOrBlank()) {
                    "That file is empty — nothing was imported."
                } else {
                    importProvidersBackup(viewModel, text) { imported ->
                        customProviders = mergeCustomProviders(customProviders, imported)
                    }
                }
                withContext(Dispatchers.Main) {
                    refreshTick++
                    note = result
                }
            }
        }
    }

    fun buildBackupJson(): String = buildProvidersBackupJson(keyStore, customProviders)

    fun shareBackup(json: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("CodeSathi providers backup", json))
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, json)
        }
        context.startActivity(Intent.createChooser(intent, "Share providers backup"))
        note = "Backup copied to the clipboard — share it only with people you trust."
    }

    ClozedSectionHeader("Providers & API keys")
    Text(
        "Add a provider, save its API key, and pick the model each chat uses. " +
            "Keys stay encrypted on this device and are only sent to the provider you choose.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))

    if (cards.isEmpty()) {
        ClozedCard(Modifier.fillMaxWidth()) {
            Text(
                "No provider connected yet — add one below to start chatting.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(12.dp))
    }
    cards.forEach { (provider, isCustom) ->
        ProviderCard(
            provider = provider,
            isCustom = isCustom,
            viewModel = viewModel,
            isInUse = provider.id == inUseProviderId,
            expanded = expandedId == provider.id,
            onToggleExpand = { expandedId = if (expandedId == provider.id) null else provider.id },
            onToggleInUse = { checked ->
                if (checked) {
                    firstModelId(viewModel, provider)?.let { modelId ->
                        viewModel.setDefaultModel(provider.id, modelId)
                        note = "${provider.name} is now in use — default model $modelId."
                    } ?: run {
                        note = "${provider.name} has no model to use yet — fetch its models first, or type a model id."
                    }
                } else {
                    firstTopPick?.let { (topProvider, topModel) ->
                        viewModel.setDefaultModel(topProvider.id, topModel)
                        note = "Back to the first top pick: ${topProvider.name} / $topModel."
                    }
                }
            },
            onEditKeys = { multiKeyProvider = provider },
            onDelete = { deleteProvider = provider },
            onChooseLocalModel = { localLauncher.launch(arrayOf("*/*")) },
            onNote = { note = it }
        )
        Spacer(Modifier.height(12.dp))
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill(
            text = "+ Add provider",
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
            onClick = { showChooseSheet = true }
        )
        OutlinedButton(
            onClick = { showCustomDialog = true },
            shape = MaterialTheme.shapes.extraLarge
        ) { Text("Custom endpoint") }
    }
    Spacer(Modifier.height(16.dp))
    Text(
        "Back up your providers and keys to a file, or share them with " +
            "another device. A backup holds your keys in plain text — " +
            "keep the file somewhere safe.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { pendingExportJson = buildBackupJson(); warningAction = "export"; showKeyWarning = true },
            shape = MaterialTheme.shapes.extraLarge
        ) { Text("Export") }
        OutlinedButton(
            onClick = { pendingExportJson = buildBackupJson(); warningAction = "share"; showKeyWarning = true },
            shape = MaterialTheme.shapes.extraLarge
        ) { Text("Share") }
        OutlinedButton(
            onClick = { showImportDialog = true },
            shape = MaterialTheme.shapes.extraLarge
        ) { Text("Import") }
    }
    note?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, style = MaterialTheme.typography.bodyMedium)
    }

    if (showChooseSheet) {
        ChooseProviderSheet(
            viewModel = viewModel,
            onDismiss = { showChooseSheet = false },
            onPick = { provider ->
                showChooseSheet = false
                keyDialogProvider = provider
            }
        )
    }
    keyDialogProvider?.let { provider ->
        ApiKeyDialog(
            provider = provider,
            onDismiss = { keyDialogProvider = null },
            onSave = { key ->
                viewModel.addApiKey(provider.id, key)
                keyDialogProvider = null
                note = "Key added for ${provider.name} (encrypted on this device)."
            }
        )
    }
    multiKeyProvider?.let { provider ->
        MultiKeyDialog(
            viewModel = viewModel,
            provider = provider,
            onDismiss = { multiKeyProvider = null }
        )
    }
    deleteProvider?.let { provider ->
        AlertDialog(
            onDismissRequest = { deleteProvider = null },
            title = { Text("Remove ${provider.name}?") },
            text = {
                Text(
                    when {
                        provider.kind == "local" -> "The on-device model file is forgotten. The file itself is not deleted."
                        provider.kind == "bedrock" -> "The saved AWS credentials are deleted."
                        provider.id.startsWith("custom-") -> "The custom endpoint and its saved keys are removed."
                        else -> "Every saved key for ${provider.name} is deleted. You can add a key again any time."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    when {
                        provider.kind == "local" -> viewModel.removeLocalModelFile()
                        provider.kind == "bedrock" -> viewModel.removeAwsCredential()
                        provider.id.startsWith("custom-") -> {
                            viewModel.apiKeys(provider.id).forEach { viewModel.removeApiKey(provider.id, it.id) }
                            customProviders = customProviders.filterNot { it.id == provider.id }
                        }
                        else -> viewModel.apiKeys(provider.id).forEach { viewModel.removeApiKey(provider.id, it.id) }
                    }
                    expandedId = null
                    deleteProvider = null
                    note = "${provider.name} removed."
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteProvider = null }) { Text("Cancel") }
            }
        )
    }
    if (showCustomDialog) {
        CustomProviderDialog(
            viewModel = viewModel,
            onDismiss = { showCustomDialog = false },
            onAdd = { provider, apiKey ->
                customProviders = mergeCustomProviders(customProviders, listOf(provider))
                if (apiKey.isNotBlank()) viewModel.addApiKey(provider.id, apiKey)
                showCustomDialog = false
                note = "${provider.name} added for this session — custom endpoints are not saved yet, so re-add it after a restart."
            }
        )
    }
    if (showKeyWarning) {
        AlertDialog(
            onDismissRequest = { showKeyWarning = false; pendingExportJson = null },
            title = { Text("This backup contains your API keys") },
            text = {
                Text(
                    "This backup holds your API keys in plain text. Anyone with " +
                        "the file can use your keys and spend your credit. Share it " +
                        "only with people you trust, and keep the file somewhere safe."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val json = pendingExportJson
                    showKeyWarning = false
                    pendingExportJson = null
                    if (json == null) return@TextButton
                    if (warningAction == "share") {
                        shareBackup(json)
                    } else {
                        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                        exportLauncher.launch("codesathi-providers-$stamp.json")
                    }
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { showKeyWarning = false; pendingExportJson = null }) { Text("Cancel") }
            }
        )
    }
    if (showImportDialog) {
        ImportBackupDialog(
            onDismiss = { showImportDialog = false },
            onChooseFile = {
                showImportDialog = false
                importFileLauncher.launch(arrayOf("application/json", "text/plain"))
            },
            onImportText = { text ->
                val result = importProvidersBackup(viewModel, text) { imported ->
                    customProviders = mergeCustomProviders(customProviders, imported)
                }
                refreshTick++
                showImportDialog = false
                note = result
            }
        )
    }
}


/** First usable model for a provider: the on-device file, a fetched model, or a bundled model. */
private fun firstModelId(viewModel: AppViewModel, provider: Provider): String? {
    if (provider.kind == "local") return viewModel.localModelName
    return viewModel.fetchedModels[provider.id]?.firstOrNull()
        ?: provider.models.firstOrNull()
}

/** Worst health line for one key across its models, or null when it looks fine. */
private fun keyHealthText(
    keyHealth: Map<String, KeyHealth>,
    providerId: String,
    keyId: String
): String? {
    val entries = keyHealth.filterKeys {
        it.startsWith("$providerId|") && it.endsWith("|$keyId")
    }.values
    return when {
        entries.any { it.status == KeyHealth.STATUS_INVALID } ->
            "Key rejected — reset the limit flags to try it again"
        entries.any { it.cooldownUntil > System.currentTimeMillis() } ->
            "Cooling down — try again shortly"
        else -> null
    }
}

private fun mergeCustomProviders(
    current: List<Provider>,
    imported: List<Provider>
): List<Provider> {
    val byId = current.associateBy { it.id }.toMutableMap()
    imported.forEach { byId[it.id] = it }
    return byId.values.toList()
}

/** One connected provider, in Clozed's card shape. */
@Composable
private fun ProviderCard(
    provider: Provider,
    isCustom: Boolean,
    viewModel: AppViewModel,
    isInUse: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggleInUse: (Boolean) -> Unit,
    onEditKeys: () -> Unit,
    onDelete: () -> Unit,
    onChooseLocalModel: () -> Unit,
    onNote: (String) -> Unit
) {
    val success = LocalSathiStatus.current.success
    val context = LocalContext.current
    val keyHealth by viewModel.keyHealth.collectAsState()
    val favourites by viewModel.favourites.collectAsState()
    val efforts by viewModel.reasoningEfforts.collectAsState()
    val azureResourceNames by viewModel.azureResourceNames.collectAsState()
    val keys = viewModel.apiKeys(provider.id)
    val fetchedModels = viewModel.fetchedModels[provider.id].orEmpty()
    val isFetching = viewModel.fetchingProviderId == provider.id
    val fetchError = viewModel.fetchErrors[provider.id]
    val localName = viewModel.localModelName
    var manualModel by remember { mutableStateOf("") }
    var azureInput by remember { mutableStateOf("") }
    var awsAccessKeyIdInput by remember { mutableStateOf("") }
    var awsSecretInput by remember { mutableStateOf("") }
    var awsRegionInput by remember { mutableStateOf("") }

    if (expanded && provider.kind != "local") {
        LaunchedEffect(provider.id) { viewModel.loadCachedModels(provider) }
    }

    ClozedCard(Modifier.fillMaxWidth().clickable { onToggleExpand() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    provider.name.firstOrNull()?.uppercase().orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(provider.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                when {
                    provider.kind == "local" -> Text(
                        if (localName != null) "On-device · $localName" else "On-device · no model file chosen yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    provider.kind == "bedrock" -> {
                        val masked = viewModel.awsAccessKeyIdMasked()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (masked != null) {
                                Icon(Icons.Filled.Check, contentDescription = null, tint = success, modifier = Modifier.size(16.dp))
                                Text(" AWS credentials saved ✓", style = MaterialTheme.typography.bodySmall)
                            } else {
                                Text("No AWS credentials yet", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    isCustom -> Text(
                        "Custom · ${provider.baseUrl.take(40)}${if (provider.baseUrl.length > 40) "…" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    keys.isNotEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = success, modifier = Modifier.size(16.dp))
                        Text(
                            " Key: ${keys.first().masked}" + if (keys.size > 1) " +${keys.size - 1} more" else "",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    else -> Text("No API key yet", style = MaterialTheme.typography.bodySmall)
                }
            }
            IconButton(onClick = onEditKeys) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit keys for ${provider.name}")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove ${provider.name}", tint = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isCustom) {
                Text(
                    "Custom endpoint — kept only while Settings is open.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "Keys",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onEditKeys() }.padding(8.dp)
                )
            } else {
                Switch(
                    checked = isInUse,
                    onCheckedChange = onToggleInUse,
                    enabled = provider.kind != "local" || localName != null
                )
                Text(
                    if (isInUse) "ON - in use" else "Off",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isInUse) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp).weight(1f)
                )
            }
            if (provider.kind != "local") {
                Text(
                    if (isFetching) "Fetching…" else "↻ Fetch models",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(enabled = !isFetching) {
                        viewModel.refreshModels(provider)
                    }.padding(8.dp)
                )
            }
        }
        if (expanded) {
            Spacer(Modifier.height(12.dp))
            if (provider.kind == "local") {
                Text(
                    "Runs completely on this device — no server, no API key, no internet. " +
                        "Download a .task model file yourself first, then pick the file here. " +
                        "Gemma-3 1B in 4-bit (about 550 MB) is the known-good starting point.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onChooseLocalModel) {
                        Text(if (localName == null) "Choose .task file" else "Choose a different file")
                    }
                    if (localName != null) {
                        OutlinedButton(onClick = {
                            viewModel.removeLocalModelFile()
                            onNote("On-device model file removed. Pick another .task file to use the on-device model again.")
                        }) { Text("Remove") }
                    }
                }
                if (localName != null) {
                    Spacer(Modifier.height(8.dp))
                    ModelRow(
                        provider = provider,
                        modelId = localName,
                        viewModel = viewModel,
                        starred = favourites.any { it.modelKey == provider.modelKey(localName) },
                        effort = null,
                        onNote = onNote
                    )
                }
            } else {
                Text("Fetched models", style = MaterialTheme.typography.labelMedium)
                fetchError?.let { err ->
                    Text(
                        "Fetch failed — showing the cached list: $err",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (fetchedModels.isEmpty() && fetchError == null && !isFetching) {
                    Text(
                        "No fetched models yet — tap Fetch models to load them from ${provider.name}.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                fetchedModels.forEach { model ->
                    ModelRow(
                        provider = provider,
                        modelId = model,
                        viewModel = viewModel,
                        starred = favourites.any { it.modelKey == provider.modelKey(model) },
                        effort = efforts[provider.modelKey(model)],
                        onNote = onNote
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = manualModel,
                    onValueChange = { manualModel = it },
                    label = { Text("Or type a model id") },
                    placeholder = { Text("e.g. deepseek-chat") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val switched = viewModel.useManualModel(provider.id, manualModel)
                            onNote(
                                if (switched) "Model set for this session and as default: ${provider.name} / ${manualModel.trim()}"
                                else "Default model set: ${provider.name} / ${manualModel.trim()}"
                            )
                            manualModel = ""
                        },
                        enabled = manualModel.isNotBlank()
                    ) { Text("Use this model") }
                }
                Spacer(Modifier.height(8.dp))
                Text("Bundled models", style = MaterialTheme.typography.labelMedium)
                if (provider.models.isEmpty()) {
                    Text(
                        "This provider ships no bundled model ids — fetch its list, or type a model id above.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                provider.models.forEach { model ->
                    ModelRow(
                        provider = provider,
                        modelId = model,
                        viewModel = viewModel,
                        starred = favourites.any { it.modelKey == provider.modelKey(model) },
                        effort = efforts[provider.modelKey(model)],
                        onNote = onNote
                    )
                }
            }
            if (provider.kind == "azure") {
                Spacer(Modifier.height(12.dp))
                Text("Azure resource name", style = MaterialTheme.typography.labelMedium)
                Text(
                    "Your Azure OpenAI resource name — the {resource} in " +
                        "https://{resource}.openai.azure.com. The model id you pick is the deployment name.",
                    style = MaterialTheme.typography.bodySmall
                )
                azureResourceNames[provider.id]?.let { saved ->
                    Text("Saved: $saved", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = azureInput,
                    onValueChange = { azureInput = it },
                    label = { Text("Resource name") },
                    placeholder = { Text("e.g. my-resource") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val ok = viewModel.saveAzureResourceName(provider.id, azureInput)
                            onNote(
                                if (ok) "Azure resource name saved for ${provider.name}."
                                else "That name doesn't match Azure's rules (3–24 chars, lowercase letters, digits, hyphens)."
                            )
                            azureInput = ""
                        },
                        enabled = azureInput.isNotBlank()
                    ) { Text("Save resource name") }
                }
            }
            if (provider.kind == "bedrock") {
                Spacer(Modifier.height(12.dp))
                Text("AWS credentials", style = MaterialTheme.typography.labelMedium)
                Text(
                    "Bedrock does not use an API key. Enter the access key id, secret access key " +
                        "and region of an AWS account with bedrock:InvokeModel permission.",
                    style = MaterialTheme.typography.bodySmall
                )
                val masked = viewModel.awsAccessKeyIdMasked()
                if (masked != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Access key id: $masked", modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = {
                            viewModel.removeAwsCredential()
                            onNote("AWS credentials removed for ${provider.name}.")
                        }) { Text("Delete") }
                    }
                    Text("Region: ${viewModel.awsRegion()}", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = awsAccessKeyIdInput,
                    onValueChange = { awsAccessKeyIdInput = it },
                    label = { Text("Access key id") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = awsSecretInput,
                    onValueChange = { awsSecretInput = it },
                    label = { Text("Secret access key") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = awsRegionInput,
                    onValueChange = { awsRegionInput = it },
                    label = { Text("Region") },
                    placeholder = { Text(viewModel.awsRegion()) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val ok = viewModel.saveAwsCredential(awsAccessKeyIdInput, awsSecretInput, awsRegionInput)
                            onNote(
                                if (ok) "AWS credentials saved for ${provider.name} (encrypted on this device)."
                                else "The access key id and secret access key can't be blank."
                            )
                            awsAccessKeyIdInput = ""
                            awsSecretInput = ""
                            awsRegionInput = ""
                        },
                        enabled = awsAccessKeyIdInput.isNotBlank() && awsSecretInput.isNotBlank()
                    ) { Text("Save AWS credentials") }
                }
            }
            if (provider.kind != "local" && provider.kind != "bedrock") {
                Spacer(Modifier.height(12.dp))
                Text("API keys", style = MaterialTheme.typography.labelMedium)
                if (keys.isEmpty()) {
                    Text("No key saved yet — tap the pencil above to add one.", style = MaterialTheme.typography.bodySmall)
                }
                keys.forEach { stored ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stored.masked)
                            keyHealthText(keyHealth, provider.id, stored.id)?.let { health ->
                                Text(health, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        OutlinedButton(onClick = {
                            viewModel.removeApiKey(provider.id, stored.id)
                            onNote("Key removed for ${provider.name}.")
                        }) { Text("Delete") }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Keys are stored encrypted and are never put in providers.json or sent anywhere except the provider you chose.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** One model row: use it, star it, add it to the fallback order, set its reasoning effort. */
@Composable
private fun ModelRow(
    provider: Provider,
    modelId: String,
    viewModel: AppViewModel,
    starred: Boolean,
    effort: String?,
    onNote: (String) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(modifier = Modifier.weight(1f)) {
            Text("${provider.name} / $modelId", style = MaterialTheme.typography.bodyMedium)
            if (provider.local) Text("Runs on this device (no key needed)", style = MaterialTheme.typography.bodySmall)
            if (provider.kind != "local") {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    EffortChip(label = "Default", selected = effort == null, onClick = { viewModel.setReasoningEffort(provider.id, modelId, null) })
                    EffortChip(label = "Low", selected = effort == "low", onClick = { viewModel.setReasoningEffort(provider.id, modelId, "low") })
                    EffortChip(label = "Medium", selected = effort == "medium", onClick = { viewModel.setReasoningEffort(provider.id, modelId, "medium") })
                    EffortChip(label = "High", selected = effort == "high", onClick = { viewModel.setReasoningEffort(provider.id, modelId, "high") })
                }
            }
        }
        IconButton(onClick = { viewModel.toggleFavourite(provider.id, modelId) }) {
            Icon(
                if (starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = if (starred) "Unstar" else "Star as favourite"
            )
        }
        IconButton(onClick = {
            viewModel.addFallbackEntry(provider.id, modelId)
            onNote("Added ${provider.name} / $modelId to the fallback order.")
        }) {
            Icon(Icons.Filled.Add, contentDescription = "Add to fallback order")
        }
        OutlinedButton(onClick = {
            val switched = viewModel.useModel(provider.id, modelId)
            onNote(
                if (switched) "Model set for this session and as default: ${provider.name} / $modelId"
                else "Default model set: ${provider.name} / $modelId"
            )
        }) { Text("Use") }
    }
}

@Composable
private fun EffortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
    )
}

/** "Choose provider" bottom sheet: the whole catalog, alphabetical, with search. */
@Composable
private fun ChooseProviderSheet(
    viewModel: AppViewModel,
    onDismiss: () -> Unit,
    onPick: (Provider) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val providers = remember(viewModel) { viewModel.providerRepository.providers.sortedBy { it.name.lowercase() } }
    val filtered = providers.filter { p ->
        query.isBlank() ||
            p.name.contains(query, ignoreCase = true) ||
            p.id.contains(query, ignoreCase = true)
    }
    ClozedSheet(onDismiss = onDismiss) {
        Text("Choose provider", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search providers") },
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
            items(filtered, key = { it.id }) { provider ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onPick(provider) }.padding(vertical = 10.dp),
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
                        Text(provider.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        if (viewModel.hasApiKey(provider.id)) "Key saved ✓" else "No key yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (viewModel.hasApiKey(provider.id)) LocalSathiStatus.current.success else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** API key dialog: lock icon, provider name, key field, Show / Paste, Cancel / Save. */
@Composable
private fun ApiKeyDialog(
    provider: Provider,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    val context = LocalContext.current
    var keyInput by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        icon = { Icon(Icons.Filled.Lock, contentDescription = null) },
        title = { Text(provider.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Paste the API key for ${provider.name}. It is stored encrypted on this device.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text("API key") },
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showKey) "Hide key" else "Show key"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        if (showKey) "Hide" else "Show",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { showKey = !showKey }.padding(4.dp)
                    )
                    Text(
                        "Paste",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                                ?.takeIf { it.isNotBlank() }?.let { keyInput = it }
                        }.padding(4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(keyInput.trim()) },
                enabled = keyInput.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Multi-key dialog: first key is tried first, the next key on a limit. */
@Composable
private fun MultiKeyDialog(
    viewModel: AppViewModel,
    provider: Provider,
    onDismiss: () -> Unit
) {
    val keyHealth by viewModel.keyHealth.collectAsState()
    var newKey by remember { mutableStateOf("") }
    var showNewKey by remember { mutableStateOf(false) }
    val keys = viewModel.apiKeys(provider.id)
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = { Text("${provider.name} keys") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The top key is tried first. When it hits a limit, the next key is tried instead. " +
                        "A key that cools down is tried again once its cooldown ends.",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (keys.isEmpty()) {
                    Text("No key saved yet.", style = MaterialTheme.typography.bodyMedium)
                }
                keys.forEachIndexed { index, stored ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${index + 1}. ", style = MaterialTheme.typography.bodyMedium)
                        Column(Modifier.weight(1f)) {
                            Text(stored.masked, style = MaterialTheme.typography.bodyMedium)
                            val health = keyHealthText(keyHealth, provider.id, stored.id)
                            Text(
                                health ?: "✓ Ready",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (health == null) LocalSathiStatus.current.success else MaterialTheme.colorScheme.error
                            )
                        }
                        IconButton(onClick = { viewModel.removeApiKey(provider.id, stored.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove key", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                OutlinedTextField(
                    value = newKey,
                    onValueChange = { newKey = it },
                    label = { Text("New API key") },
                    visualTransformation = if (showNewKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showNewKey = !showNewKey }) {
                            Icon(
                                if (showNewKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showNewKey) "Hide key" else "Show key"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            viewModel.addApiKey(provider.id, newKey)
                            newKey = ""
                        },
                        enabled = newKey.isNotBlank()
                    ) { Text("+ Add key") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

/** Custom endpoint dialog (Ollama / LM Studio presets, or any OpenAI-compatible server). */
@Composable
private fun CustomProviderDialog(
    viewModel: AppViewModel,
    onDismiss: () -> Unit,
    onAdd: (Provider, String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var modelIds by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var styleIndex by remember { mutableStateOf(0) } // 0 OpenAI-compatible, 1 Anthropic
    var fetchResult by remember { mutableStateOf<String?>(null) }
    var fetching by remember { mutableStateOf(false) }
    val context = LocalContext.current

    fun slug(): String =
        name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "endpoint" }

    fun tempProvider(): Provider {
        val models = modelIds.split(",").map { it.trim() }.filter { it.isNotBlank() }
        return Provider(
            id = "custom-${slug()}",
            name = name.trim().ifBlank { "Custom endpoint" },
            kind = if (styleIndex == 1) "anthropic" else "openai",
            baseUrl = baseUrl.trim(),
            models = models
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Custom endpoint") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = name == "Ollama",
                        onClick = { name = "Ollama"; baseUrl = "http://127.0.0.1:11434" },
                        label = { Text("Ollama") }
                    )
                    FilterChip(
                        selected = name == "LM Studio",
                        onClick = { name = "LM Studio"; baseUrl = "http://127.0.0.1:1234/v1" },
                        label = { Text("LM Studio") }
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    placeholder = { Text("http://127.0.0.1:11434") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = modelIds,
                    onValueChange = { modelIds = it },
                    label = { Text("Model IDs (comma separated)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API key (optional)") },
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showKey) "Hide key" else "Show key"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        if (showKey) "Hide" else "Show",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { showKey = !showKey }.padding(4.dp)
                    )
                    Text(
                        "Paste",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                                ?.takeIf { it.isNotBlank() }?.let { apiKey = it }
                        }.padding(4.dp)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (baseUrl.isBlank()) {
                                fetchResult = "Enter a base URL first."
                                return@OutlinedButton
                            }
                            fetching = true
                            fetchResult = null
                            val temp = tempProvider()
                            scope.launch {
                                val result = runCatching {
                                    viewModel.providerRepository.refreshModels(temp, apiKey.ifBlank { null })
                                }.getOrNull()
                                fetching = false
                                fetchResult = result?.let {
                                    if (it.error != null) it.error
                                    else "${it.models.size} models found ✓"
                                } ?: "Fetch failed — check the base URL."
                            }
                        },
                        enabled = !fetching
                    ) { Text(if (fetching) "Fetching…" else "Fetch models") }
                }
                fetchResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                ClozedSegmentedControl(
                    options = listOf("OpenAI-compatible", "Anthropic"),
                    selected = styleIndex,
                    onSelect = { styleIndex = it },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Custom endpoints are kept only while Settings is open — they are not saved yet. " +
                        "The API key is saved encrypted like any other key.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(tempProvider(), apiKey.trim()) },
                enabled = name.isNotBlank() && baseUrl.isNotBlank()
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Import dialog: a backup file, or a shared backup pasted from the clipboard. */
@Composable
private fun ImportBackupDialog(
    onDismiss: () -> Unit,
    onChooseFile: () -> Unit,
    onImportText: (String) -> Unit
) {
    val context = LocalContext.current
    var pasted by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Import providers backup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Import a backup file, or paste a shared backup from your clipboard. " +
                        "Providers already on this device get the new keys added to them.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = pasted,
                    onValueChange = { pasted = it },
                    label = { Text("Pasted backup") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                            ?.takeIf { it.isNotBlank() }?.let { pasted = it }
                    }) { Text("Paste from clipboard") }
                    OutlinedButton(onClick = onChooseFile) { Text("Choose file") }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImportText(pasted) },
                enabled = pasted.isNotBlank()
            ) { Text("Import") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// ---------------------------------------------------------------------------
// Providers backup: plain JSON with the keys in plain text (the warning
// dialog says so before any export or share). Built only from KeyStore
// reads — the values are never logged, and the JSON only goes where the
// user sends it (their file, or their share target).
// Format: {"version":1,
//          "keys":{"<providerId>":["<key>", ...]},
//          "customProviders":[{"id","name","kind","baseUrl","models":[]}]}
// Import also accepts the encrypted-backup secrets shape
// (top-level "api_keys.<providerId>" arrays).
// ---------------------------------------------------------------------------

private fun buildProvidersBackupJson(keyStore: KeyStore, customProviders: List<Provider>): String {
    val keysObj = buildJsonObject {
        keyStore.providerIdsWithKeys().forEach { providerId ->
            val values = keyStore.getKeyValues(providerId)
            if (values.isNotEmpty()) {
                put(providerId, JsonArray(values.map { JsonPrimitive(it) }))
            }
        }
    }
    val customs = buildJsonArray {
        customProviders.forEach { p ->
            add(
                buildJsonObject {
                    put("id", p.id)
                    put("name", p.name)
                    put("kind", p.kind)
                    put("baseUrl", p.baseUrl)
                    put("models", JsonArray(p.models.map { JsonPrimitive(it) }))
                }
            )
        }
    }
    return Json.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        buildJsonObject {
            put("version", 1)
            put("keys", keysObj)
            put("customProviders", customs)
        }
    )
}

/**
 * Apply a providers backup: every key is added through the ViewModel
 * (deduped, encrypted, never overwriting a different key), custom
 * endpoints come back for this session via [onCustomProviders].
 * Returns the one-line result for the screen.
 */
private fun importProvidersBackup(
    viewModel: AppViewModel,
    text: String,
    onCustomProviders: (List<Provider>) -> Unit
): String {
    val root: JsonObject = try {
        Json.parseToJsonElement(text).jsonObject
    } catch (_: Exception) {
        return "That backup could not be read — nothing was imported."
    }
    var keyCount = 0
    var providerCount = 0
    val keyLists = LinkedHashMap<String, List<String>>()
    root["keys"]?.jsonObject?.forEach { (providerId, value) ->
        runCatching { value.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }.filter { it.isNotBlank() } }
            .getOrNull()?.takeIf { it.isNotEmpty() }?.let { keyLists[providerId] = it }
    }
    // Secrets shape: top-level "api_keys.<providerId>" arrays.
    root.forEach { (key, value) ->
        if (key.startsWith("api_keys.")) {
            val providerId = key.removePrefix("api_keys.")
            if (providerId.isNotBlank() && providerId !in keyLists) {
                runCatching { value.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }.filter { it.isNotBlank() } }
                    .getOrNull()?.takeIf { it.isNotEmpty() }?.let { keyLists[providerId] = it }
            }
        }
    }
    keyLists.forEach { (providerId, values) ->
        val before = viewModel.apiKeys(providerId).size
        values.forEach { viewModel.addApiKey(providerId, it) }
        val added = viewModel.apiKeys(providerId).size - before
        if (added > 0) {
            keyCount += added
            providerCount++
        }
    }
    val customs = ArrayList<Provider>()
    root["customProviders"]?.jsonArray?.forEach { item ->
        runCatching {
            val o = item.jsonObject
            val id = o["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val kind = o["kind"]?.jsonPrimitive?.contentOrNull?.ifBlank { "openai" } ?: "openai"
            val baseUrl = o["baseUrl"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val models = o["models"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            if (id.isNotBlank() && name.isNotBlank()) {
                customs += Provider(id = id, name = name, kind = kind, baseUrl = baseUrl, models = models)
            }
        }
    }
    if (customs.isNotEmpty()) onCustomProviders(customs)
    return when {
        keyCount == 0 && customs.isEmpty() -> "Nothing new to import — the keys in that backup are already saved here."
        customs.isEmpty() -> "Imported $keyCount key${if (keyCount == 1) "" else "s"} across $providerCount provider${if (providerCount == 1) "" else "s"}."
        else -> "Imported $keyCount key${if (keyCount == 1) "" else "s"} and ${customs.size} custom endpoint${if (customs.size == 1) "" else "s"} (custom endpoints last until you leave Settings)."
    }
}
