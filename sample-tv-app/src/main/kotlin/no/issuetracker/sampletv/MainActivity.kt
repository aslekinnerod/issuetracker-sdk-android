package no.issuetracker.sampletv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import no.issuetracker.sdk.Issuetracker

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Issuetracker.recordAction("tv_sample_launched")
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(64.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "Issuetracker TV sample",
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(
                            "Hold Play/Pause on the remote for 2 seconds to report an issue,\nor use the button below.",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                        )
                        Button(onClick = {
                            Issuetracker.recordAction("report_button_pressed")
                            Issuetracker.report()
                        }) {
                            Text("Report a bug")
                        }
                        Button(onClick = { Issuetracker.showOnboarding() }) {
                            Text("Show onboarding again")
                        }
                    }
                }
            }
        }
    }
}
