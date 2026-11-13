package com.inyogeshwar.codesathi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.inyogeshwar.codesathi.data.ModelPrices
import com.inyogeshwar.codesathi.ui.AppViewModel

/**
 * Usage & cost dashboard (Phase 5, Step 2).
 *
 * Everything shown here is derived from the stored usage rows (one per
 * model turn) — totals are SUMs, never numbers kept only in memory.
 * Costs come from the small bundled price table
 * ([ModelPrices], list prices checked 2026-11-13) and are always shown
 * as approximate ("~₹"); a model with no table entry shows
 * "cost unknown" instead of an invented price. Rows whose provider
 * sent no usage are flagged "estimated" — they are the chars÷4
 * estimate, not real counts.
 *
 * Reached from Home → the "⋮" overflow menu → "Usage & cost".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit
) {
    val totals by viewModel.usageTotals.collectAsState()
    val rows by viewModel.usageBySessionModel.collectAsState()
    val sessions by viewModel.sessions.collectAsState()

    // Per-model totals, derived from the stored rows.
    val byModel = remember(rows) {
        rows.groupBy { it.providerId to it.modelId }
            .map { (key, group) ->
                ModelUsage(
                    providerId = key.first,
                    modelId = key.second,
                    promptTokens = group.sumOf { it.promptTokens },
                    completionTokens = group.sumOf { it.completionTokens },
                    estimatedTurns = group.sumOf { it.estimatedTurns },
                    turns = group.sumOf { it.turns }
                )
            }
            .sortedByDescending { it.promptTokens + it.completionTokens }
    }
    // Per-session totals, derived from the stored rows.
    val bySession = remember(rows, sessions) {
        rows.groupBy { it.sessionId }
            .map { (sessionId, group) ->
                SessionUsage(
                    sessionId = sessionId,
                    title = sessions.firstOrNull { it.id == sessionId }?.title
                        ?: "Deleted session",
                    promptTokens = group.sumOf { it.promptTokens },
                    completionTokens = group.sumOf { it.completionTokens },
                    estimatedTurns = group.sumOf { it.estimatedTurns },
                    turns = group.sumOf { it.turns },
                    hasPricedRow = group.any {
                        ModelPrices.priceFor(it.providerId, it.modelId) != null
                    },
                    costUsd = group.sumOf {
                        ModelPrices.approximateCostUsd(
                            it.providerId, it.modelId,
                            it.promptTokens, it.completionTokens
                        ) ?: 0.0
                    }
                )
            }
            .sortedByDescending { it.promptTokens + it.completionTokens }
    }
    // Grand-total cost: only the priced rows contribute; the rest are
    // reported separately as tokens with unknown cost, never silently
    // counted as free.
    val totalCostUsd = remember(rows) {
        rows.sumOf {
            ModelPrices.approximateCostUsd(
                it.providerId, it.modelId, it.promptTokens, it.completionTokens
            ) ?: 0.0
        }
    }
    val hasPricedRow = remember(rows) {
        rows.any { ModelPrices.priceFor(it.providerId, it.modelId) != null }
    }
    val unknownCostTokens = remember(rows) {
        rows.filter { ModelPrices.priceFor(it.providerId, it.modelId) == null }
            .sumOf { it.promptTokens + it.completionTokens }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Usage & cost") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (totals.turns == 0L) {
                item {
                    Text(
                        "No usage recorded yet. Send a message in any session " +
                            "and the tokens it uses will show up here.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("All sessions", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Total tokens: ${formatTokens(totals.promptTokens + totals.completionTokens)}",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                "Prompt: ${formatTokens(totals.promptTokens)} · " +
                                    "Completion: ${formatTokens(totals.completionTokens)}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "Turns: ${totals.turns}" +
                                    if (totals.estimatedTurns > 0) {
                                        " (${totals.estimatedTurns} estimated)"
                                    } else "",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Approx. cost: " + if (hasPricedRow) {
                                    ModelPrices.formatApproxInr(totalCostUsd)
                                } else {
                                    "unknown — no priced models used yet"
                                },
                                style = MaterialTheme.typography.bodyLarge
                            )
                            if (unknownCostTokens > 0) {
                                Text(
                                    "Excludes ${formatTokens(unknownCostTokens)} tokens " +
                                        "with unknown cost (models with no verified price).",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
                item {
                    Text("By model", style = MaterialTheme.typography.titleLarge)
                }
                items(byModel, key = { "${it.providerId}/${it.modelId}" }) { model ->
                    ModelUsageRow(model)
                }
                item {
                    Text("By session", style = MaterialTheme.typography.titleLarge)
                }
                items(bySession, key = { it.sessionId }) { session ->
                    SessionUsageRow(session)
                }
            }
            item {
                Text(
                    "Prices are list prices checked ${ModelPrices.PRICE_DATE}, " +
                        "converted at ~₹${ModelPrices.USD_TO_INR.toInt()} per \$1. " +
                        "All costs are estimates, not your bill — providers bill " +
                        "cached input, long prompts and batch calls differently, " +
                        "and gateways add their own fees. Real usage is shown " +
                        "when the provider reports it (OpenAI, Anthropic, " +
                        "Bedrock); rows marked “estimated” use the chars÷4 " +
                        "estimate because the provider sent no usage. " +
                        "Compaction summary calls are not recorded here.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/** One (provider, model) summed over all sessions. */
private data class ModelUsage(
    val providerId: String,
    val modelId: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val estimatedTurns: Long,
    val turns: Long
)

/** One session summed over all its models. */
private data class SessionUsage(
    val sessionId: String,
    val title: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val estimatedTurns: Long,
    val turns: Long,
    val hasPricedRow: Boolean,
    val costUsd: Double
)

@Composable
private fun ModelUsageRow(model: ModelUsage) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "${model.providerId} / ${model.modelId}",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "${formatTokens(model.promptTokens + model.completionTokens)} tokens " +
                    "(${formatTokens(model.promptTokens)} in · " +
                    "${formatTokens(model.completionTokens)} out) · ${model.turns} turns",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Approx. cost: " + (
                    ModelPrices.formatApproxInr(
                        model.providerId, model.modelId,
                        model.promptTokens, model.completionTokens
                    ) ?: "unknown — no verified price for this model"
                    ),
                style = MaterialTheme.typography.bodyMedium
            )
            estimatedLabel(model.estimatedTurns, model.turns)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SessionUsageRow(session: SessionUsage) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(session.title, style = MaterialTheme.typography.titleMedium)
            Text(
                "${formatTokens(session.promptTokens + session.completionTokens)} tokens " +
                    "(${formatTokens(session.promptTokens)} in · " +
                    "${formatTokens(session.completionTokens)} out) · ${session.turns} turns",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Approx. cost: " + if (session.hasPricedRow) {
                    ModelPrices.formatApproxInr(session.costUsd)
                } else {
                    "unknown — no verified price for the models used"
                },
                style = MaterialTheme.typography.bodyMedium
            )
            estimatedLabel(session.estimatedTurns, session.turns)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** "estimated" when every turn was estimated, "partly estimated" when some were, else null. */
private fun estimatedLabel(estimatedTurns: Long, turns: Long): String? = when {
    turns == 0L -> null
    estimatedTurns == turns -> "Estimated — the provider sent no usage for these turns."
    estimatedTurns > 0 -> "Partly estimated — $estimatedTurns of $turns turns had no provider usage."
    else -> null
}

private fun formatTokens(tokens: Long): String = "%,d".format(tokens)
