package no.issuetracker.sdk.ui

/**
 * Pure geometry model for the non-drag "Box" annotation tool in the
 * screenshot editor (WCAG 2.5.7 dragging-movements / 2.1.1 keyboard,
 * ISU-38).
 *
 * Coordinates are normalized to the displayed image: x, y, width and
 * height are all fractions of the canvas in the 0..1 range. The editor
 * canvas has the exact aspect ratio of the bitmap (no letterboxing), so
 * converting to canvas pixels — and from there to bitmap pixels, via
 * the same linear scale the freehand strokes use — is a plain multiply
 * by the canvas dimensions in [cornerPoints].
 *
 * Deliberately free of Android/Compose types so the nudge/resize/clamp
 * math is testable as a plain JVM unit test.
 */
internal data class BoxAnnotation(
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val width: Float = INITIAL_WIDTH,
    val height: Float = INITIAL_HEIGHT,
) {

    val left: Float get() = centerX - width / 2f
    val top: Float get() = centerY - height / 2f
    val right: Float get() = centerX + width / 2f
    val bottom: Float get() = centerY + height / 2f

    /**
     * Move by ([dx], [dy]) in normalized units, keeping the box fully
     * inside the image. Accessibility actions pass +/-[NUDGE_STEP];
     * pointer drags pass the drag delta divided by the canvas size.
     */
    fun nudged(dx: Float, dy: Float): BoxAnnotation =
        copy(centerX = centerX + dx, centerY = centerY + dy).clamped()

    fun bigger(): BoxAnnotation = resized(RESIZE_STEP)

    fun smaller(): BoxAnnotation = resized(-RESIZE_STEP)

    private fun resized(delta: Float): BoxAnnotation =
        copy(
            width = (width + delta).coerceIn(MIN_SIZE, 1f),
            height = (height + delta).coerceIn(MIN_SIZE, 1f),
        ).clamped()

    /** Shift the center so the whole box stays inside the 0..1 image. */
    fun clamped(): BoxAnnotation =
        copy(
            centerX = centerX.coerceIn(width / 2f, 1f - width / 2f),
            centerY = centerY.coerceIn(height / 2f, 1f - height / 2f),
        )

    /**
     * The rectangle as a closed polyline in canvas pixels — five
     * (x, y) points, last == first — matching the point-list shape of
     * a freehand stroke so placement reuses the existing stroke/undo/
     * rasterize model unchanged.
     */
    fun cornerPoints(canvasWidth: Float, canvasHeight: Float): List<Pair<Float, Float>> {
        val l = left * canvasWidth
        val t = top * canvasHeight
        val r = right * canvasWidth
        val b = bottom * canvasHeight
        return listOf(l to t, r to t, r to b, l to b, l to t)
    }

    companion object {
        /** Initial size: ~30% of the image width, centered. */
        const val INITIAL_WIDTH = 0.30f
        const val INITIAL_HEIGHT = 0.20f

        /** Move step for accessibility actions and nudge buttons: 2%. */
        const val NUDGE_STEP = 0.02f

        /** Resize step: 2% growth on each side per activation. */
        const val RESIZE_STEP = 0.04f

        /** Never shrink below 8% — keeps the box findable and visible. */
        const val MIN_SIZE = 0.08f
    }
}
