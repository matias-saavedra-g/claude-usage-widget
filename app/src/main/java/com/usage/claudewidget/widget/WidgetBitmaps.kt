package com.usage.claudewidget.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache

/**
 * White-on-transparent masks for the gauges Glance has no component for (rings and vertical
 * bars). The widget tints them with theme color resources, so they follow light/dark and
 * wallpaper colors like the rest of the widget, without re-rendering.
 *
 * Masks are ALPHA_8 (one byte per pixel; the tint supplies the color) and cached, so the same
 * instance is shared by every responsive layout and sent to the launcher once. A rings widget
 * uses at most five 384 px masks, about 0.7 MB, well inside the launcher's bitmap budget
 * (screen width x height x 4 x 1.5 bytes, about 5.5 MB even on a 720p phone).
 */
object WidgetBitmaps {

    /** Ring canvas edge in pixels; the launcher scales it to fit (ContentScale.Fit). */
    private const val RING_PX = 384

    /** Ring stroke as a fraction of the canvas edge. */
    const val RING_STROKE = 0.11f

    /** Vertical bar mask height; stretched with ContentScale.FillBounds, so only the ratio matters. */
    private const val BAR_PX = 400

    private val cache = LruCache<String, Bitmap>(48)

    /**
     * Arc from 12 o'clock, clockwise, covering [pct] percent. [inset] (0..0.5) shrinks the ring
     * toward the center, for concentric rings that share one canvas.
     */
    fun ring(pct: Int, inset: Float = 0f): Bitmap {
        val p = pct.coerceIn(0, 100)
        return cached("ring:$p:$inset") {
            draw(RING_PX, RING_PX) { c, paint ->
                if (p == 0) return@draw
                val r = ringRect(inset)
                if (p >= 100) c.drawOval(r, paint) else c.drawArc(r, -90f, 360f * p / 100f, false, paint)
            }
        }
    }

    /** Full-circle track behind [ring]. */
    fun ringTrack(inset: Float = 0f): Bitmap = cached("track:$inset") {
        draw(RING_PX, RING_PX) { c, paint -> c.drawOval(ringRect(inset), paint) }
    }

    /** Vertical bar: the bottom [pct] percent is opaque. */
    fun columnFill(pct: Int): Bitmap {
        val p = pct.coerceIn(0, 100)
        return cached("col:$p") {
            Bitmap.createBitmap(1, BAR_PX, Bitmap.Config.ALPHA_8).also { bmp ->
                val filled = BAR_PX * p / 100f
                if (filled > 0f) {
                    Canvas(bmp).drawRect(0f, BAR_PX - filled, 1f, BAR_PX.toFloat(), Paint().apply { color = Color.WHITE })
                }
            }
        }
    }

    private fun ringRect(inset: Float): RectF {
        val stroke = RING_PX * RING_STROKE
        val pad = stroke / 2f + RING_PX * inset
        return RectF(pad, pad, RING_PX - pad, RING_PX - pad)
    }

    private inline fun draw(w: Int, h: Int, block: (Canvas, Paint) -> Unit): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = w * RING_STROKE
            strokeCap = Paint.Cap.ROUND
        }
        block(Canvas(bmp), paint)
        return bmp
    }

    private inline fun cached(key: String, create: () -> Bitmap): Bitmap =
        synchronized(cache) { cache.get(key) ?: create().also { cache.put(key, it) } }
}
