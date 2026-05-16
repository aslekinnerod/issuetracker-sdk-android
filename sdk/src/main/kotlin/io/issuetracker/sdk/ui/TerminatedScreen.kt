package io.issuetracker.sdk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Shown in place of the report form when the SDK is in the terminated
 * state — the server has signalled that the bound project is gone, the
 * API key is revoked, or the workspace is suspended. No retry button,
 * no raw error code, no link back to our service. ADR-0003 Decision 9.
 *
 * TODO (Phase C+): localise these strings. Held as hardcoded English
 * in Phase B/C because the rest of the SDK has no i18n infrastructure
 * yet — localising only the terminal view would create inconsistent
 * UX. When string resources land across the SDK, swap the Text("...")
 * calls for stringResource lookups in one pass.
 */
@Composable
internal fun TerminatedScreen(
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Bug reporting is no longer available.",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Contact your team.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onClose) {
            Text("Close")
        }
    }
}
