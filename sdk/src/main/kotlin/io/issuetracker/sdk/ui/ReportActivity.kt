package io.issuetracker.sdk.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.issuetracker.sdk.internal.LifecycleStore
import io.issuetracker.sdk.internal.ReporterIdentity
import io.issuetracker.sdk.internal.ReportingSession

/**
 * Hosts the reporter UI on top of the host app. Translucent theme so
 * the host's content is visible behind the sheet. Reads its handoff
 * (runtime + screenshot) from ReportingSession; if the handoff is
 * already consumed (config-change recreate, late onCreate), finishes.
 */
internal class ReportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val handoff = ReportingSession.consume()
        if (handoff == null) {
            finish()
            return
        }
        val (runtime, screenshot) = handoff

        setContent {
            MaterialTheme {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    if (LifecycleStore.isTerminated) {
                        // ADR-0003 Decision 9 pre-flight gate. When the
                        // SDK has been terminated, every trigger that
                        // launches this activity shows the terminal
                        // message — no retry, no error code, no link
                        // back to our service.
                        TerminatedScreen(onClose = { finish() })
                    } else {
                        var showNamePrompt by remember { mutableStateOf(ReporterIdentity.name == null) }
                        if (showNamePrompt) {
                            NamePromptScreen(
                                onContinue = { name ->
                                    ReporterIdentity.setName(name)
                                    showNamePrompt = false
                                },
                                onCancel = { finish() },
                            )
                        } else {
                            ReportScreen(
                                runtime = runtime,
                                screenshot = screenshot,
                                onChangeName = {
                                    ReporterIdentity.clearName()
                                    showNamePrompt = true
                                },
                                onClose = { finish() },
                            )
                        }
                    }
                }
            }
        }
    }
}
