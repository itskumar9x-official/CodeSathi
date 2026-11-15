package com.inyogeshwar.codesathi.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.inyogeshwar.codesathi.ui.AppViewModel
import com.inyogeshwar.codesathi.ui.theme.ClozedCard
import com.inyogeshwar.codesathi.ui.theme.ClozedSegmentedControl
import com.inyogeshwar.codesathi.ui.theme.ClozedSectionHeader

/**
 * Reasoning effort (Phase 8 settings, part 2).
 *
 * There is NO global default effort in the app — effort is stored per
 * model ("providerId/modelId" → low / medium / high, unset = default).
 * This control therefore sets the effort of the default model (the
 * first top pick), and the card says so. A session using a different
 * model uses that model's own stored effort, which the chat header shows.
 */
@Composable
fun ReasoningSection(viewModel: AppViewModel) {
    val efforts by viewModel.reasoningEfforts.collectAsState()
    val topPick = remember { viewModel.providerRepository.topPicks().firstOrNull() }
    val providerId = topPick?.first?.id
    val modelId = topPick?.second
    val effort = if (providerId != null && modelId != null) {
        efforts["$providerId/$modelId"]
    } else {
        null
    }
    val selected = when (effort) {
        "low" -> 1
        "medium" -> 2
        "high" -> 3
        else -> 0
    }

    ClozedSectionHeader("Reasoning")
    ClozedCard {
        Text("Reasoning effort (default model)", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            if (topPick != null) {
                "Default model: ${topPick.first.name} · $modelId"
            } else {
                "No default model yet — pick a model in Providers first."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        ClozedSegmentedControl(
            options = listOf("Auto", "Low", "Medium", "High"),
            selected = selected,
            onSelect = { index ->
                if (providerId == null || modelId == null) return@ClozedSegmentedControl
                viewModel.setReasoningEffort(
                    providerId,
                    modelId,
                    when (index) {
                        1 -> "low"
                        2 -> "medium"
                        3 -> "high"
                        else -> null
                    }
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Only reasoning models pay attention to this — most chat models " +
                "ignore it. “Auto” sends no effort at all and lets the model " +
                "decide. The effort is stored per model, so a session using a " +
                "different model uses that model's own setting.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
