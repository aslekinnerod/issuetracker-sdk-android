package no.issuetracker.sdk.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Full-screen drawing editor over a screenshot. Five-color palette
 * (matching the iOS palette) plus undo, and a non-drag "Box" tool
 * (WCAG 2.5.7 / 2.1.1) that places an adjustable rectangle without
 * requiring a drag gesture. No eraser/crop/highlighter in v1.
 *
 * Strokes are tracked in canvas coordinates and scaled to bitmap
 * coordinates when [onDone] rasterizes the result. A placed box is
 * committed as a closed polyline stroke, so it shares the undo model
 * and rasterizer with freehand drawing.
 */
@Composable
internal fun ScreenshotEditor(
    bitmap: Bitmap,
    onDone: (Bitmap) -> Unit,
    onCancel: () -> Unit,
) {
    val palette = remember {
        listOf(
            Color(0xFFE53935), // red
            Color(0xFFFB8C00), // orange
            Color(0xFFFDD835), // yellow
            Color(0xFF43A047), // green
            Color(0xFF1E88E5), // blue
        )
    }
    var color by remember { mutableStateOf(palette.first()) }
    val strokes = remember { mutableStateListOf<EditorStroke>() }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // Non-drag "Box" annotation (WCAG 2.5.7 / 2.1.1, ISU-38): a pending
    // rectangle in normalized image coordinates, adjusted via buttons or
    // TalkBack custom actions (pointer drag also works) and committed
    // into the regular stroke/undo model on Place.
    var pendingBox by remember { mutableStateOf<BoxAnnotation?>(null) }
    val view = LocalView.current

    fun placePendingBox() {
        val box = pendingBox ?: return
        if (canvasSize.width == 0 || canvasSize.height == 0) return
        val points = box
            .cornerPoints(canvasSize.width.toFloat(), canvasSize.height.toFloat())
            .map { (x, y) -> Offset(x, y) }
        strokes.add(EditorStroke(color, points.toMutableList()))
        pendingBox = null
        view.announceForAccessibility("Highlight box placed")
    }

    fun removePendingBox() {
        pendingBox = null
        view.announceForAccessibility("Highlight box removed")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF111111))
            .semantics { paneTitle = "Edit screenshot" },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) {
                Text("Cancel", color = Color.White)
            }
            Box(modifier = Modifier.weight(1f))
            TextButton(onClick = {
                val annotated = rasterize(bitmap, strokes, canvasSize)
                onDone(annotated)
            }) {
                Text("Done", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
            Box(
                modifier = Modifier
                    .aspectRatio(ratio)
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(color) {
                        var current: EditorStroke? = null
                        detectDragGestures(
                            onDragStart = { offset ->
                                current = EditorStroke(color, mutableListOf(offset))
                            },
                            onDrag = { change, _ ->
                                current?.points?.add(change.position)
                                change.consume()
                            },
                            onDragEnd = {
                                current?.let { strokes.add(it) }
                                current = null
                            },
                            onDragCancel = { current = null },
                        )
                    },
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Screenshot being annotated",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                Canvas(modifier = Modifier.fillMaxSize()) {
                    strokes.forEach { stroke ->
                        if (stroke.points.size < 2) return@forEach
                        val path = Path().apply {
                            val first = stroke.points.first()
                            moveTo(first.x, first.y)
                            for (i in 1 until stroke.points.size) {
                                val p = stroke.points[i]
                                lineTo(p.x, p.y)
                            }
                        }
                        drawPath(
                            path = path,
                            color = stroke.color,
                            style = Stroke(width = 8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                    }
                }
                // Pending highlight box: a real semantics node so TalkBack
                // can focus it and adjust it via custom actions; pointer
                // users can additionally drag it. Its own pointerInput
                // consumes the drag, so the freehand handler above never
                // starts a stroke while moving the box.
                pendingBox?.let { box ->
                    val density = LocalDensity.current
                    val canvasW = canvasSize.width.toFloat()
                    val canvasH = canvasSize.height.toFloat()
                    if (canvasW > 0f && canvasH > 0f) {
                        // Match the committed look: freehand strokes render
                        // at 8 canvas px, so the preview border does too.
                        val borderDp = with(density) { 8f.toDp() }
                        Box(
                            modifier = Modifier
                                .offset {
                                    IntOffset(
                                        (box.left * canvasW).roundToInt(),
                                        (box.top * canvasH).roundToInt(),
                                    )
                                }
                                .size(
                                    width = with(density) { (box.width * canvasW).toDp() },
                                    height = with(density) { (box.height * canvasH).toDp() },
                                )
                                .border(borderDp, color)
                                .pointerInput(canvasSize) {
                                    detectDragGestures { change, dragAmount ->
                                        pendingBox = pendingBox?.nudged(
                                            dragAmount.x / canvasW,
                                            dragAmount.y / canvasH,
                                        )
                                        change.consume()
                                    }
                                }
                                .semantics {
                                    contentDescription = "Highlight box, ${colorName(color)}"
                                    customActions = listOf(
                                        CustomAccessibilityAction("Move left") {
                                            pendingBox = pendingBox?.nudged(-BoxAnnotation.NUDGE_STEP, 0f); true
                                        },
                                        CustomAccessibilityAction("Move right") {
                                            pendingBox = pendingBox?.nudged(BoxAnnotation.NUDGE_STEP, 0f); true
                                        },
                                        CustomAccessibilityAction("Move up") {
                                            pendingBox = pendingBox?.nudged(0f, -BoxAnnotation.NUDGE_STEP); true
                                        },
                                        CustomAccessibilityAction("Move down") {
                                            pendingBox = pendingBox?.nudged(0f, BoxAnnotation.NUDGE_STEP); true
                                        },
                                        CustomAccessibilityAction("Bigger") {
                                            pendingBox = pendingBox?.bigger(); true
                                        },
                                        CustomAccessibilityAction("Smaller") {
                                            pendingBox = pendingBox?.smaller(); true
                                        },
                                        CustomAccessibilityAction("Place") {
                                            placePendingBox(); true
                                        },
                                        CustomAccessibilityAction("Remove") {
                                            removePendingBox(); true
                                        },
                                    )
                                },
                        )
                    }
                }
            }
        }

        // On-screen non-drag controls for the pending box: real 48dp
        // buttons so sighted users who cannot drag (switch access,
        // tremor, stylus-only) get the same adjustments as TalkBack's
        // custom actions.
        pendingBox?.let {
            BoxControls(
                onNudge = { dx, dy -> pendingBox = pendingBox?.nudged(dx, dy) },
                onBigger = { pendingBox = pendingBox?.bigger() },
                onSmaller = { pendingBox = pendingBox?.smaller() },
                onPlace = ::placePendingBox,
                onRemove = ::removePendingBox,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            palette.forEach { c ->
                // 48dp touch target with the 32dp swatch drawn inside.
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .selectable(
                            selected = c == color,
                            onClick = { color = c },
                            role = Role.RadioButton,
                        )
                        .semantics { contentDescription = colorName(c) }
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(c)
                        .border(
                            width = if (c == color) 3.dp else 0.dp,
                            color = Color.White,
                            shape = CircleShape,
                        ),
                )
            }
            TextButton(
                onClick = {
                    if (pendingBox == null) {
                        pendingBox = BoxAnnotation()
                        view.announceForAccessibility(
                            "Highlight box added at center. Use the controls or custom actions to move, resize, and place it.",
                        )
                    }
                },
                enabled = pendingBox == null,
                modifier = Modifier.semantics { contentDescription = "Add highlight box" },
            ) {
                Text("Box", color = if (pendingBox == null) Color.White else Color.Gray)
            }
            TextButton(
                onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) },
                enabled = strokes.isNotEmpty(),
            ) {
                Text("Undo", color = if (strokes.isNotEmpty()) Color.White else Color.Gray)
            }
        }
    }
}

/**
 * Non-drag adjustment controls for the pending highlight box. Every
 * target is a real 48dp button with a content description; glyphs are
 * decoration only.
 */
@Composable
private fun BoxControls(
    onNudge: (Float, Float) -> Unit,
    onBigger: () -> Unit,
    onSmaller: () -> Unit,
    onPlace: () -> Unit,
    onRemove: () -> Unit,
) {
    val step = BoxAnnotation.NUDGE_STEP
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BoxControlButton("←", "Move box left") { onNudge(-step, 0f) }
            BoxControlButton("→", "Move box right") { onNudge(step, 0f) }
            BoxControlButton("↑", "Move box up") { onNudge(0f, -step) }
            BoxControlButton("↓", "Move box down") { onNudge(0f, step) }
            BoxControlButton("+", "Make box bigger", onBigger)
            BoxControlButton("−", "Make box smaller", onSmaller)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onPlace,
                modifier = Modifier.semantics { contentDescription = "Place highlight box" },
            ) {
                Text("Place", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
            TextButton(
                onClick = onRemove,
                modifier = Modifier.semantics { contentDescription = "Remove highlight box" },
            ) {
                Text("Remove", color = Color.White)
            }
        }
    }
}

@Composable
private fun BoxControlButton(
    glyph: String,
    description: String,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = description },
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(glyph, color = Color.White)
    }
}

private data class EditorStroke(val color: Color, val points: MutableList<Offset>)

/** Accessible name for a palette swatch (TalkBack has no other cue). */
private fun colorName(c: Color): String = when (c.toArgb()) {
    0xFFE53935.toInt() -> "Red"
    0xFFFB8C00.toInt() -> "Orange"
    0xFFFDD835.toInt() -> "Yellow"
    0xFF43A047.toInt() -> "Green"
    0xFF1E88E5.toInt() -> "Blue"
    else -> "Color"
}

private fun rasterize(bitmap: Bitmap, strokes: List<EditorStroke>, canvasSize: IntSize): Bitmap {
    if (strokes.isEmpty() || canvasSize.width == 0 || canvasSize.height == 0) return bitmap
    val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
    val androidCanvas = android.graphics.Canvas(out)
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        style = android.graphics.Paint.Style.STROKE
        // Scale stroke width by the same ratio as coordinates so the
        // rendered line matches what the user drew on screen.
        val ratio = bitmap.width.toFloat() / canvasSize.width
        strokeWidth = (8f * ratio).coerceAtLeast(2f)
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    val scaleX = bitmap.width.toFloat() / canvasSize.width
    val scaleY = bitmap.height.toFloat() / canvasSize.height
    strokes.forEach { stroke ->
        if (stroke.points.size < 2) return@forEach
        paint.color = stroke.color.toArgb()
        val path = android.graphics.Path().apply {
            val first = stroke.points.first()
            moveTo(first.x * scaleX, first.y * scaleY)
            for (i in 1 until stroke.points.size) {
                val p = stroke.points[i]
                lineTo(p.x * scaleX, p.y * scaleY)
            }
        }
        androidCanvas.drawPath(path, paint)
    }
    return out
}
