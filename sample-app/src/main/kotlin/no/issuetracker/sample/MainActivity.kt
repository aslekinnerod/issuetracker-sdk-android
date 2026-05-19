package no.issuetracker.sample

import android.app.Application
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import no.issuetracker.sdk.Issuetracker

/**
 * Single-screen demo. Every meaningful SDK surface gets a section
 * with one or two buttons + a short explanation, so a person who's
 * never seen the SDK can shake-test it in 30 seconds.
 *
 * The same feature checklist is the spec for the iOS / Flutter / RN
 * sample apps — see sample-app/README.md.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SampleScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SampleScreen() {
    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("Issuetracker SDK demo") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LifecycleSection()
            ReportingSection()
            IdentitySection()
            BreadcrumbSection()
            OnboardingSection()
            I18nSection()
            DestructiveSection()
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

@Composable
private fun LifecycleSection() {
    val context = LocalContext.current
    val prefs = remember { samplePrefs(context.applicationContext as Application) }
    val lastError = prefs.getString(SampleApplication.PREF_LAST_CONFIG_ERROR, null)
    val lastErrorAt = prefs.getLong(SampleApplication.PREF_LAST_CONFIG_ERROR_AT, 0L)
    val whenStr = if (lastErrorAt > 0) {
        DateUtils.getRelativeTimeSpanString(
            lastErrorAt,
            System.currentTimeMillis(),
            DateUtils.SECOND_IN_MILLIS,
        ).toString()
    } else null

    SectionCard(
        title = "Lifecycle",
        subtitle = if (lastError != null) {
            "Last onConfigurationError: $lastError ($whenStr)"
        } else {
            "Listening for onConfigurationError. Nothing fired yet."
        },
    ) {
        OutlinedButton(
            onClick = {
                prefs.edit()
                    .remove(SampleApplication.PREF_LAST_CONFIG_ERROR)
                    .remove(SampleApplication.PREF_LAST_CONFIG_ERROR_AT)
                    .apply()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Reset last error") }
    }
}

@Composable
private fun ReportingSection() {
    SectionCard(
        title = "Reporting",
        subtitle = "Shake the device, two-finger long-press (3s), or tap the button.",
    ) {
        Button(
            onClick = { Issuetracker.report() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Report a bug") }
    }
}

@Composable
private fun IdentitySection() {
    var name by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf<String?>(null) }

    SectionCard(
        title = "Identity",
        subtitle = "Skips the in-form name prompt and stamps every report.",
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Display name") },
            placeholder = { Text("e.g. Kari Nordmann") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        Issuetracker.identify(name.trim())
                        feedback = "Saved \"${name.trim()}\""
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text("Save") }
            OutlinedButton(
                onClick = {
                    Issuetracker.clearIdentity()
                    name = ""
                    feedback = "Cleared."
                },
                modifier = Modifier.weight(1f),
            ) { Text("Clear") }
        }
        if (feedback != null) {
            Text(
                feedback!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BreadcrumbSection() {
    val recent = remember { mutableStateOf<List<String>>(emptyList()) }
    SectionCard(
        title = "Breadcrumbs",
        subtitle = "Last 5 actions ride along with every report.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    Issuetracker.recordAction("viewed_home")
                    recent.value = (recent.value + "viewed_home").takeLast(5)
                },
                modifier = Modifier.weight(1f),
            ) { Text("viewed_home") }
            OutlinedButton(
                onClick = {
                    Issuetracker.recordAction(
                        "tapped_button",
                        mapOf("id" to "settings"),
                    )
                    recent.value = (recent.value + "tapped_button").takeLast(5)
                },
                modifier = Modifier.weight(1f),
            ) { Text("tapped_button") }
        }
        if (recent.value.isNotEmpty()) {
            Text(
                "Recorded: " + recent.value.joinToString(" → "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                overflow = TextOverflow.Ellipsis,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun OnboardingSection() {
    SectionCard(
        title = "Onboarding",
        subtitle = "Re-show the trigger introduction popover regardless of whether it has been shown before.",
    ) {
        OutlinedButton(
            onClick = { Issuetracker.showOnboarding() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Show intro again") }
    }
}

@Composable
private fun I18nSection() {
    val context = LocalContext.current
    val prefs = remember { samplePrefs(context.applicationContext as Application) }
    var useNorwegian by remember {
        mutableStateOf(prefs.getBoolean(SampleApplication.PREF_USE_NORWEGIAN_TERMINATED_UI, false))
    }
    SectionCard(
        title = "TERMINATED-UI i18n",
        subtitle = "When ON, the SDK shows the terminal screen in Norwegian. Restart the app to apply.",
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Norwegian strings")
            Switch(
                checked = useNorwegian,
                onCheckedChange = { v ->
                    useNorwegian = v
                    prefs.edit()
                        .putBoolean(SampleApplication.PREF_USE_NORWEGIAN_TERMINATED_UI, v)
                        .apply()
                },
            )
        }
    }
}

@Composable
private fun DestructiveSection() {
    var confirm by remember { mutableStateOf(false) }
    SectionCard(
        title = "Destructive",
        subtitle = "Test the crash-reporting flow. The app will die immediately and the SDK files an issue on next launch.",
    ) {
        OutlinedButton(
            onClick = { confirm = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) { Text("Force crash") }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            confirmButton = {
                Button(
                    onClick = { Issuetracker.testCrash() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Crash now") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirm = false }) { Text("Cancel") }
            },
            title = { Text("Force crash") },
            text = { Text("This will throw and the app process will die. Re-open the app and the SDK will queue a crash report on the next launch.") },
        )
    }
}
