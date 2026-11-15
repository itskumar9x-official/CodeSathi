package com.inyogeshwar.codesathi.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.inyogeshwar.codesathi.R
import com.inyogeshwar.codesathi.ui.AppViewModel
import com.inyogeshwar.codesathi.ui.theme.LocalSathiStatus
import com.inyogeshwar.codesathi.ui.theme.SathiCard
import com.inyogeshwar.codesathi.ui.theme.SathiMark
import kotlinx.coroutines.launch

private const val PAGE_WELCOME = 0
private const val PAGE_PRIVACY = 1
private const val PAGE_PERMISSIONS = 2
private const val PAGE_READY = 3

/**
 * v1.2 multi-page onboarding: Welcome → Keys stay on device →
 * Permissions (folder grant) → Ready. Shown once.
 *
 * The folder step is unchanged from v1.1: the button opens the SYSTEM
 * folder picker (OpenDocumentTree) and [AppViewModel.onFolderPicked]
 * takes the persistable read+write grant and stores the URI exactly as
 * before; [AppViewModel.completeOnboarding] is called right after a
 * successful pick, as before. There is deliberately NO path text field.
 *
 * The Ready page only exists once a folder is chosen, so the pager can
 * never be swiped past the permission step without a grant. "Skip"
 * jumps straight to that step.
 *
 * [onDone] gets true when the user asked to add a provider key next.
 */
@Composable
fun OnboardingScreen(viewModel: AppViewModel, onDone: (openProviders: Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val folderChosen = viewModel.pickedFolderUri != null
    val pageCount = if (folderChosen) 4 else 3
    // Read the state inside the lambda so the pager sees the Ready page appear.
    val pager = rememberPagerState(pageCount = { if (viewModel.pickedFolderUri != null) 4 else 3 })

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) {
            error = "No folder chosen. Pick a folder to continue."
            return@rememberLauncherForActivityResult
        }
        val name = viewModel.onFolderPicked(context, uri)
        if (name == null) {
            error = "Could not use this folder. Try another one."
        } else {
            error = null
            viewModel.completeOnboarding()
        }
    }

    // After a successful pick the Ready page appears; move to it.
    LaunchedEffect(folderChosen) {
        if (folderChosen && pager.currentPage == PAGE_PERMISSIONS) {
            pager.animateScrollToPage(PAGE_READY)
        }
    }
    // Back walks the pages before it leaves the app.
    BackHandler(enabled = pager.currentPage > 0) {
        scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Top row: small lockup + Skip.
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.weight(1f))
            if (pager.currentPage < PAGE_PERMISSIONS) {
                TextButton(onClick = { scope.launch { pager.animateScrollToPage(PAGE_PERMISSIONS) } }) {
                    Text("Skip")
                }
            }
        }

        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) { page ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp)
            ) {
                when (page) {
                    PAGE_WELCOME -> WelcomePage()
                    PAGE_PRIVACY -> PrivacyPage()
                    PAGE_PERMISSIONS -> PermissionsPage(
                        folderName = if (folderChosen) viewModel.pickedFolderName else null,
                        error = error
                    )
                    else -> ReadyPage(viewModel.pickedFolderName)
                }
            }
        }

        // Bottom: page dots + primary action.
        Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 20.dp)) {
            PageDots(count = 4, current = pager.currentPage, reachable = pageCount)
            Spacer(Modifier.height(20.dp))
            when (pager.currentPage) {
                PAGE_PERMISSIONS -> {
                    if (!folderChosen) {
                        Button(
                            onClick = {
                                // Persistable read+write grant is requested by the contract itself.
                                picker.launch(null)
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) {
                            Icon(Icons.Filled.Folder, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.allow_file_access))
                        }
                    } else {
                        Button(
                            onClick = { scope.launch { pager.animateScrollToPage(PAGE_READY) } },
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) { Text("Continue") }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { picker.launch(null) },
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) { Text("Choose a different folder") }
                    }
                }
                PAGE_READY -> {
                    Button(
                        onClick = { onDone(false) },
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) { Text("Start coding") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { onDone(true) },
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Add a provider API key")
                    }
                }
                else -> Button(
                    onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Next") }
            }
        }
    }
}

@Composable
private fun WelcomePage() {
    Spacer(Modifier.height(24.dp))
    SathiMark(size = 96.dp, tile = true)
    Spacer(Modifier.height(28.dp))
    Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(12.dp))
    Text(
        "Ek AI coding agent jo aapke phone par, aapke project folder mein kaam karta hai.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(24.dp))
    FeatureRow(Icons.AutoMirrored.Filled.Rule, "Plan first, then build", "Plan mode is read-only. Files change only after you approve a plan and each diff.")
    FeatureRow(Icons.Filled.Terminal, "Editor, terminal and Git", "Edit files, run commands and commit/push over HTTPS — all on the phone.")
    FeatureRow(Icons.Filled.Key, "Your provider, your key", "Bring a key for the provider you like, or run an on-device model.")
}

@Composable
private fun PrivacyPage() {
    Spacer(Modifier.height(24.dp))
    IconBadge(Icons.Filled.Lock)
    Spacer(Modifier.height(28.dp))
    Text("Your keys stay on this device", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(12.dp))
    Text(
        "API keys are stored encrypted (Android Keystore-backed) on this phone only. They are never stored unencrypted, and session backups never include them.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(24.dp))
    FeatureRow(Icons.Filled.Key, "Talks only to the provider you pick", "Your messages, and the file contents the agent reads for a task, go to that provider to get an answer.")
    FeatureRow(Icons.Filled.PhoneAndroid, "On-device option", "With a local model, chats never leave the phone.")
    FeatureRow(Icons.Filled.Folder, "No uploads of your folder", "CodeSathi does not upload your project anywhere. Remote and Sync work only if you turn them on.")
}

@Composable
private fun PermissionsPage(folderName: String?, error: String?) {
    Spacer(Modifier.height(24.dp))
    IconBadge(Icons.Filled.Folder)
    Spacer(Modifier.height(28.dp))
    Text("Choose your project folder", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.onboarding_body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(20.dp))
    if (folderName != null) {
        SathiCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Access granted", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("Chosen folder: $folderName", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
    }
    Text("Asked later, only when you use them", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    FeatureRow(Icons.Filled.Mic, "Microphone", "When you first tap the mic for voice input.")
    FeatureRow(Icons.Filled.Notifications, "Notifications", "When you start Remote mode (Android 13+).")
    FeatureRow(Icons.Filled.SdStorage, "All-files access (optional)", "Only from Settings, if you want the terminal and Git on real files.")
}

@Composable
private fun ReadyPage(folderName: String) {
    Spacer(Modifier.height(24.dp))
    SathiMark(size = 96.dp, tile = true)
    Spacer(Modifier.height(28.dp))
    Text("Sab set hai!", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(12.dp))
    Text(
        "Folder “$folderName” is ready. Add a provider API key (or pick an on-device model), then start your first session.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun IconBadge(icon: ImageVector) {
    Box(
        Modifier
            .size(80.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.extraLarge),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(36.dp))
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int, reachable: Int) {
    val live = LocalSathiStatus.current.live
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        repeat(count) { i ->
            val selected = i == current
            val width by animateDpAsState(if (selected) 22.dp else 8.dp, label = "dotW")
            val color by animateColorAsState(
                when {
                    selected -> MaterialTheme.colorScheme.primary
                    i < reachable -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    else -> MaterialTheme.colorScheme.outlineVariant
                },
                label = "dotC"
            )
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .height(8.dp)
                    .width(width)
                    .background(if (selected && i == PAGE_READY) live else color, CircleShape)
            )
        }
    }
}
