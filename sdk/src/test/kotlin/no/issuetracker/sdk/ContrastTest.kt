package no.issuetracker.sdk

import androidx.compose.ui.graphics.toArgb
import no.issuetracker.sdk.internal.ReportButtonObserver
import no.issuetracker.sdk.ui.OnboardingTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * WCAG 2.x contrast-ratio checks for the design-token colors the SDK
 * ships (audit S-4/S-5, ISU-39). Asserts against the REAL constants in
 * [OnboardingTokens] and [ReportButtonObserver], so a token change that
 * regresses below WCAG 1.4.3 AA (4.5:1 for normal text) fails here.
 *
 * Plain JVM test: luminance math is implemented locally per WCAG 2.x
 * (android.graphics.Color is not available without Robolectric, and
 * Compose Color is a pure-Kotlin value class so reading it is safe).
 */
class ContrastTest {

    private data class Requirement(
        val name: String,
        val foregroundArgb: Int,
        val backgroundArgb: Int,
        val minimumRatio: Double,
    )

    // Add new (name, fg, bg, min) rows here as tokens grow.
    private val requirements = listOf(
        Requirement(
            name = "OnboardingTokens.accent vs white (CTA container / accent text on white)",
            foregroundArgb = OnboardingTokens.accent.toArgb(),
            backgroundArgb = WHITE,
            minimumRatio = 4.5,
        ),
        Requirement(
            name = "White label on ReportButtonObserver pill (Color.WHITE on BACKGROUND_COLOR)",
            foregroundArgb = WHITE,
            backgroundArgb = ReportButtonObserver.BACKGROUND_COLOR,
            minimumRatio = 4.5,
        ),
        Requirement(
            name = "OnboardingTokens.fg2 on OnboardingTokens.surfaceApp",
            foregroundArgb = OnboardingTokens.fg2.toArgb(),
            backgroundArgb = OnboardingTokens.surfaceApp.toArgb(),
            minimumRatio = 4.5,
        ),
        Requirement(
            name = "OnboardingTokens.fg3 on OnboardingTokens.surfaceApp",
            foregroundArgb = OnboardingTokens.fg3.toArgb(),
            backgroundArgb = OnboardingTokens.surfaceApp.toArgb(),
            minimumRatio = 4.5,
        ),
    )

    @Test
    fun `black on white is 21 to 1 (helper sanity check)`() {
        assertEquals(21.0, contrastRatio(BLACK, WHITE), 1e-9)
    }

    @Test
    fun `design tokens meet WCAG contrast minimums`() {
        for (req in requirements) {
            val ratio = contrastRatio(req.foregroundArgb, req.backgroundArgb)
            assertTrue(
                "${req.name}: contrast ratio %.2f:1 (fg=%s on bg=%s) is below the required %.1f:1"
                    .format(ratio, hex(req.foregroundArgb), hex(req.backgroundArgb), req.minimumRatio),
                ratio >= req.minimumRatio,
            )
        }
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()

        fun hex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

        // WCAG 2.x sRGB channel linearization.
        fun linearize(channel: Int): Double {
            val c = channel / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }

        // WCAG 2.x relative luminance, L = 0.2126R + 0.7152G + 0.0722B.
        fun relativeLuminance(argb: Int): Double {
            val r = linearize((argb shr 16) and 0xFF)
            val g = linearize((argb shr 8) and 0xFF)
            val b = linearize(argb and 0xFF)
            return 0.2126 * r + 0.7152 * g + 0.0722 * b
        }

        // WCAG 2.x contrast ratio, (L1 + 0.05) / (L2 + 0.05), L1 lighter.
        fun contrastRatio(a: Int, b: Int): Double {
            val la = relativeLuminance(a)
            val lb = relativeLuminance(b)
            return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
        }
    }
}
