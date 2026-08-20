package no.issuetracker.sdk.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import no.issuetracker.sdk.TerminatedUiStrings

/**
 * Shown in place of the report form when the SDK is in the terminated
 * state — the server has signalled that the bound project is gone, the
 * API key is revoked, or the workspace is suspended. No retry button,
 * no raw error code, no link back to our service. ADR-0003 Decision 9.
 *
 * Strings come from `Issuetracker.configure(...).terminatedUI`; English
 * defaults apply for any field the host doesn't override. Sister i18n
 * hooks exist on sdk-ios and sdk-web.
 */
private const val DEFAULT_TITLE = "Bug reporting is no longer available."
private const val DEFAULT_SUBTITLE = "Contact your team."
private const val DEFAULT_CLOSE_LABEL = "Close"

@Composable
internal fun TerminatedScreen(
    onClose: () -> Unit,
    strings: TerminatedUiStrings? = null,
) {
    val title = strings?.title ?: DEFAULT_TITLE
    // Box keeps the content visually centered while the inner Column
    // scrolls at large font scales instead of clipping.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics { paneTitle = title },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = strings?.subtitle ?: DEFAULT_SUBTITLE,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onClose) {
                Text(strings?.closeLabel ?: DEFAULT_CLOSE_LABEL)
            }
        }
    }
}
