package no.issuetracker.sdk.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import no.issuetracker.sdk.IssueReportType
import no.issuetracker.sdk.Runtime
import no.issuetracker.sdk.internal.FormFactor
import no.issuetracker.sdk.internal.LifecycleStore
import no.issuetracker.sdk.internal.ReporterIdentity
import no.issuetracker.sdk.internal.ReportingSession
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReportScreen(
    runtime: Runtime,
    screenshot: Bitmap?,
    onChangeName: () -> Unit,
    onClose: () -> Unit,
    onTerminated: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(IssueReportType.BUG) }
    var includeScreenshot by remember { mutableStateOf(screenshot != null) }
    var currentScreenshot by remember { mutableStateOf(screenshot) }
    var editing by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val context = LocalContext.current
    // The screenshot annotation canvas is touch-only; on television
    // form factors the Edit affordance is hidden (TV_TRIGGERS.md
    // scope: touch-dependent flows don't exist on TV).
    val isTv = remember { FormFactor.isTelevision(context) }
    val canSubmit = title.trim().isNotEmpty() && !submitting

    if (editing && currentScreenshot != null) {
        ScreenshotEditor(
            bitmap = currentScreenshot!!,
            onDone = { annotated ->
                currentScreenshot = annotated
                editing = false
            },
            onCancel = { editing = false },
        )
        return
    }

    Scaffold(
        modifier = Modifier.semantics { paneTitle = "Report an issue" },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Report an issue",
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onClose) { Text("Cancel") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "From: ${ReporterIdentity.name ?: "Anonymous"}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onChangeName,
                    modifier = Modifier.semantics { contentDescription = "Change reporter name" },
                ) { Text("Not you?") }
            }

            TypeSelector(selected = type, onSelect = { type = it })

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(200) },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = description,
                onValueChange = { description = it.take(10_000) },
                label = { Text("Description (optional)") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            currentScreenshot?.let { ss ->
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = includeScreenshot,
                            role = Role.Switch,
                            onValueChange = { includeScreenshot = it },
                        ),
                ) {
                    // onCheckedChange = null: the row is the toggleable —
                    // one focusable "Include screenshot, switch" element.
                    Switch(checked = includeScreenshot, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Include screenshot", modifier = Modifier.weight(1f))
                    if (includeScreenshot && !isTv) {
                        TextButton(
                            onClick = { editing = true },
                            modifier = Modifier.semantics { contentDescription = "Edit screenshot" },
                        ) { Text("Edit") }
                    }
                }
                if (includeScreenshot) {
                    Spacer(Modifier.height(8.dp))
                    Image(
                        bitmap = ss.asImageBitmap(),
                        contentDescription = "Screenshot preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.05f)),
                    )
                }
            }

            errorMessage?.let { msg ->
                Spacer(Modifier.height(12.dp))
                Text(
                    "Error: $msg",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    submitting = true
                    errorMessage = null
                    scope.launch {
                        val result = ReportingSession.submit(
                            runtime = runtime,
                            title = title.trim(),
                            description = description.trim(),
                            type = type,
                            screenshot = if (includeScreenshot) currentScreenshot else null,
                        )
                        submitting = false
                        result
                            .onSuccess {
                                // The activity finishes immediately, so a
                                // composable live region would never render;
                                // announce directly on the host view.
                                view.announceForAccessibility("Report sent")
                                onClose()
                            }
                            .onFailure {
                                // ADR-0003 Decision 9: if submit
                                // transitioned the SDK to TERMINATED,
                                // swap the form for TerminatedScreen
                                // immediately rather than leaving the
                                // user on a stale form with an error
                                // they cannot retry past.
                                if (LifecycleStore.isTerminated) {
                                    onTerminated()
                                } else {
                                    errorMessage = it.message ?: "Failed to send report"
                                }
                            }
                    }
                },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (submitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Sending…",
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                } else {
                    Text("Send report")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeSelector(
    selected: IssueReportType,
    onSelect: (IssueReportType) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IssueReportType.values().forEach { t ->
            FilterChip(
                selected = t == selected,
                onClick = { onSelect(t) },
                label = { Text(t.displayName) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}
