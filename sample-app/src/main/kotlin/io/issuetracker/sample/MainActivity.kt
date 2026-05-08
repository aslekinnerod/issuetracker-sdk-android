package io.issuetracker.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.issuetracker.sdk.Issuetracker

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
        topBar = { CenterAlignedTopAppBar(title = { Text("Issuetracker SDK Sample") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Shake the device to trigger the reporter, or use the buttons below.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { Issuetracker.report() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Report a bug") }
            OutlinedButton(
                onClick = { Issuetracker.recordAction("button_tapped") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Record breadcrumb") }
            OutlinedButton(
                onClick = { Issuetracker.identify("Alice Andersen") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Identify as Alice") }
            OutlinedButton(
                onClick = { Issuetracker.testCrash() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Test crash (closes app)") }
        }
    }
}
