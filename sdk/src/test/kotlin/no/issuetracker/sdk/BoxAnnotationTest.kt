package no.issuetracker.sdk

import no.issuetracker.sdk.ui.BoxAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geometry/nudge math for the non-drag "Box" annotation tool (ISU-38,
 * WCAG 2.5.7 / 2.1.1). Plain JVM test — [BoxAnnotation] has no
 * Android/Compose dependencies by design.
 */
class BoxAnnotationTest {

    private val eps = 1e-6f

    @Test
    fun `initial box is centered at 30 percent width`() {
        val box = BoxAnnotation()
        assertEquals(0.5f, box.centerX, eps)
        assertEquals(0.5f, box.centerY, eps)
        assertEquals(0.30f, box.width, eps)
        assertEquals(0.20f, box.height, eps)
    }

    @Test
    fun `nudge moves by two percent steps`() {
        val box = BoxAnnotation().nudged(BoxAnnotation.NUDGE_STEP, 0f)
        assertEquals(0.52f, box.centerX, eps)
        assertEquals(0.5f, box.centerY, eps)

        val up = box.nudged(0f, -BoxAnnotation.NUDGE_STEP)
        assertEquals(0.48f, up.centerY, eps)
    }

    @Test
    fun `nudge clamps so box never leaves the image`() {
        var box = BoxAnnotation()
        repeat(100) { box = box.nudged(BoxAnnotation.NUDGE_STEP, BoxAnnotation.NUDGE_STEP) }
        assertEquals(1f, box.right, eps)
        assertEquals(1f, box.bottom, eps)

        repeat(100) { box = box.nudged(-BoxAnnotation.NUDGE_STEP, -BoxAnnotation.NUDGE_STEP) }
        assertEquals(0f, box.left, eps)
        assertEquals(0f, box.top, eps)
    }

    @Test
    fun `bigger grows both dimensions and caps at full image`() {
        val box = BoxAnnotation().bigger()
        assertEquals(0.30f + BoxAnnotation.RESIZE_STEP, box.width, eps)
        assertEquals(0.20f + BoxAnnotation.RESIZE_STEP, box.height, eps)

        var maxed = BoxAnnotation()
        repeat(100) { maxed = maxed.bigger() }
        assertEquals(1f, maxed.width, eps)
        assertEquals(1f, maxed.height, eps)
        // Fully grown box must still be inside the image.
        assertEquals(0f, maxed.left, eps)
        assertEquals(1f, maxed.right, eps)
    }

    @Test
    fun `smaller shrinks and stops at minimum size`() {
        var box = BoxAnnotation()
        repeat(100) { box = box.smaller() }
        assertEquals(BoxAnnotation.MIN_SIZE, box.width, eps)
        assertEquals(BoxAnnotation.MIN_SIZE, box.height, eps)
    }

    @Test
    fun `growing a corner-clamped box keeps it inside the image`() {
        var box = BoxAnnotation()
        repeat(100) { box = box.nudged(BoxAnnotation.NUDGE_STEP, BoxAnnotation.NUDGE_STEP) }
        repeat(100) { box = box.bigger() }
        assertTrue(box.left >= -eps)
        assertTrue(box.top >= -eps)
        assertTrue(box.right <= 1f + eps)
        assertTrue(box.bottom <= 1f + eps)
    }

    @Test
    fun `corner points form a closed rectangle in canvas pixels`() {
        val box = BoxAnnotation(centerX = 0.5f, centerY = 0.5f, width = 0.30f, height = 0.20f)
        val points = box.cornerPoints(canvasWidth = 1000f, canvasHeight = 2000f)

        assertEquals(5, points.size)
        assertEquals(points.first(), points.last()) // closed polyline
        assertEquals(350f to 800f, points[0]) // top-left
        assertEquals(650f to 800f, points[1]) // top-right
        assertEquals(650f to 1200f, points[2]) // bottom-right
        assertEquals(350f to 1200f, points[3]) // bottom-left
    }

    @Test
    fun `pointer drag deltas clamp the same way as nudges`() {
        // A huge drag off the right edge must clamp, not escape.
        val box = BoxAnnotation().nudged(5f, 0f)
        assertEquals(1f, box.right, eps)
        assertTrue(box.left >= 0f)
    }
}
