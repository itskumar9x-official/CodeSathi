package com.inyogeshwar.codesathi.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.inyogeshwar.codesathi.ui.highlightCode
import com.inyogeshwar.codesathi.ui.languageForPath

/**
 * Diff card: Approve / Reject before any write.
 *
 * Shared by the Chat screen (agent proposals) and the Editor screen
 * (Phase 3, Step 3 — editor saves). Both go through the same
 * [com.inyogeshwar.codesathi.ui.AppViewModel.pendingProposal] state
 * and the same Approve / Reject calls, so there is exactly one
 * approval path and one write path in the app.
 *
 * Phase 3, Step 5 — the old / new text is syntax-highlighted
 * ([highlightCode], language picked from the file's extension).
 * Only the rendering changed: the 600-char display cap is applied
 * BEFORE highlighting, the Approve / Reject behaviour is untouched,
 * and a file whose language is unknown is shown plain. Highlighting
 * is computed once per display (`remember`), never on every
 * recomposition.
 */
@Composable
fun DiffCard(
    proposal: com.inyogeshwar.codesathi.agent.PlannedFile,
    oldContent: String?,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    // Highlight once per display: the cap is applied first, so the
    // spans always line up with the text shown.
    val language = remember(proposal.path) { languageForPath(proposal.path) }
    val oldDisplay = oldContent?.take(600) ?: "(old content unavailable)"
    val oldHighlighted = remember(oldDisplay, language) { highlightCode(oldDisplay, language) }
    val newSnippetDisplay = proposal.newSnippet?.take(600).orEmpty()
    val newSnippetHighlighted = remember(newSnippetDisplay, language) { highlightCode(newSnippetDisplay, language) }
    val newContentDisplay = proposal.newContent?.take(600).orEmpty()
    val newContentHighlighted = remember(newContentDisplay, language) { highlightCode(newContentDisplay, language) }

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (proposal.action == "create") "New file: ${proposal.path}" else "Edit: ${proposal.path}",
                style = MaterialTheme.typography.titleMedium
            )
            if (proposal.action == "edit") {
                Text("Old:", style = MaterialTheme.typography.labelMedium)
                Text(oldHighlighted, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text("New snippet:", style = MaterialTheme.typography.labelMedium)
                Text(newSnippetHighlighted, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            } else {
                Text("New file content (first 600 chars):", style = MaterialTheme.typography.labelMedium)
                Text(newContentHighlighted, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = onApprove) { Text("Approve") }
                OutlinedButton(onClick = onReject) { Text("Reject") }
            }
        }
    }
}
