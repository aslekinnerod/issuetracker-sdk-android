package no.issuetracker.sdk.internal

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Snapshots the current Activity's window via PixelCopy (API 24+).
 * Returns null if no foreground Activity is tracked, the window is
 * unattached, or PixelCopy fails (e.g. SurfaceView with DRM content).
 */
internal object ScreenshotCapture {
    suspend fun captureCurrentActivity(): Bitmap? {
        val activity = ActivityProvider.current() ?: return null
        val window = activity.window ?: return null
        val view = window.decorView
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return null

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return suspendCancellableCoroutine { cont ->
            val thread = HandlerThread("issuetracker-screenshot").apply { start() }
            val handler = Handler(thread.looper)
            try {
                PixelCopy.request(window, bitmap, { result ->
                    thread.quitSafely()
                    cont.resume(if (result == PixelCopy.SUCCESS) bitmap else null)
                }, handler)
            } catch (_: Throwable) {
                thread.quitSafely()
                cont.resume(null)
            }
        }
    }
}
