package no.issuetracker.sdk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import no.issuetracker.sdk.R

/**
 * First-launch popover teaching the user which gestures open the
 * reporter. Renders only the tiles for gestures the host has enabled
 * (shake / two-finger long-press) — passed in by OnboardingActivity
 * from the configure-time flags. With both disabled the activity
 * doesn't start in the first place; see OnboardingPresenter.
 */
@Composable
internal fun OnboardingScreen(
    showsShake: Boolean,
    showsLongPress: Boolean,
    onDismiss: () -> Unit,
) {
    val visibleCount = (if (showsShake) 1 else 0) + (if (showsLongPress) 1 else 0)
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = OnboardingTokens.surfaceApp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp),
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(OnboardingTokens.accentSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "✓",
                        color = OnboardingTokens.accent,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Report bugs from anywhere",
                        color = OnboardingTokens.fg1,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (visibleCount == 1) "One quick gesture is all it takes"
                               else "Two quick gestures, your choice",
                        color = OnboardingTokens.fg3,
                        fontSize = 12.sp,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // Tiles
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (showsShake) {
                    TriggerTile(
                        illustration = R.drawable.onboarding_shake,
                        title = "Shake your phone",
                        caption = "Shake to open the reporter.",
                    )
                }
                if (showsLongPress) {
                    TriggerTile(
                        illustration = R.drawable.onboarding_longpress,
                        title = "Two-finger press",
                        caption = "Hold with two fingers for 3 seconds.",
                    )
                }
            }

            Spacer(Modifier.fillMaxSize().weight(1f))

            // Primary action
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = OnboardingTokens.accent,
                    contentColor = Color.White,
                ),
                shape = RoundedCornerShape(4.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                Text("Got it", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun TriggerTile(
    illustration: Int,
    title: String,
    caption: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(OnboardingTokens.surfaceCard)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(OnboardingTokens.surface1),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(id = illustration),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.padding(12.dp).fillMaxSize(),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                text = title,
                color = OnboardingTokens.fg1,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = caption,
                color = OnboardingTokens.fg2,
                fontSize = 13.sp,
            )
        }
    }
}

// Minimal token palette inlined here (Compose) so the SDK doesn't
// depend on a Material theme being provided by the host app. Matches
// the values in the iOS / Web SDKs.
private object OnboardingTokens {
    val surfaceApp = Color(0xFFF4F7FA)
    val surfaceCard = Color(0xFFFFFFFF)
    val surface1 = Color(0xFFEAF0F6)
    val fg1 = Color(0xFF0E1A2B)
    val fg2 = Color(0xFF43536B)
    val fg3 = Color(0xFF6E7E94)
    val accent = Color(0xFF1FA2E8)
    val accentSoft = Color(0xFFDDF2FB)
}
