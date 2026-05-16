package no.issuetracker.sdk.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * Full-screen drawing editor over a screenshot. Five-color palette
 * (matching the iOS palette) plus undo. No eraser/crop/highlighter in
 * v1 — pen-only keeps the toolbar single-row and the rasterizer
 * trivial.
 *
 * Strokes are tracked in canvas coordinates and scaled to bitmap
 * coordinates when [onDone] rasterizes the result.
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF111111)),
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
                    contentDescription = null,
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
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            palette.forEach { c ->
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(c)
                        .border(
                            width = if (c == color) 3.dp else 0.dp,
                            color = Color.White,
                            shape = CircleShape,
                        )
                        .clickable { color = c },
                )
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

private data class EditorStroke(val color: Color, val points: MutableList<Offset>)

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
