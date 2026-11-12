package com.inyogeshwar.codesathi.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.inyogeshwar.codesathi.R
import com.inyogeshwar.codesathi.ui.AppViewModel

/**
 * Step 1 — permission-first onboarding.
 * One screen, one button. The button opens the SYSTEM folder picker
 * (OpenDocumentTree); the app takes a persistable URI grant.
 * There is deliberately NO path text field anywhere in this app.
 */
@Composable
fun OnboardingScreen(viewModel: AppViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }

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
            viewModel.completeOnboarding()
            onDone()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = stringResource(R.string.onboarding_title),
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.onboarding_body),
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Aapka folder sirf aapke phone par rehta hai. CodeSathi aapki files ko kahin upload nahi karta.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = {
            // Persistable read+write grant is requested by the contract itself
            // (FLAG_GRANT_PERSISTABLE_URI_PERMISSION is implied by OpenDocumentTree).
            picker.launch(null)
        }) {
            Text(stringResource(R.string.allow_file_access))
        }
        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        // Keep the picked URI visible for the next screen: the folder the user
        // just chose becomes the default folder for their first session.
        if (viewModel.pickedFolderUri != null) {
            Spacer(Modifier.height(16.dp))
            Text("Chosen folder: ${viewModel.pickedFolderName}")
        }
    }
}
