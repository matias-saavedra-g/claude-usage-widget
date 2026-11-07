package com.usage.claudewidget.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the widget, as the launcher would, for every style at common launcher sizes, in
 * light and dark theme, and writes contact sheets to app/build/widget-renders/.
 *
 * Like a real launcher in responsive mode, each size is composed at the size class it falls
 * into (WidgetSizes.responsive) and then laid out at the actual size.
 *
 * Run: ./gradlew :app:testDebugUnitTest --tests '*WidgetRenderTest*'
 *
 * Besides the pictures, it checks that no text is cut off: every TextView must fit its line.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "xxhdpi")
class WidgetRenderTest {

    private val out = File("build/widget-renders").apply { mkdirs() }

    /** Actual widget sizes in dp. Cell math from Android's sizing guide (Pixel 4, 5x4 grid). */
    private val sizes = listOf(
        "1x1 land" to DpSize(57.dp, 51.dp),
        "1x1" to DpSize(57.dp, 102.dp),
        "2x1" to DpSize(130.dp, 102.dp),
        "4x1" to DpSize(276.dp, 102.dp),
        "2x1 land" to DpSize(269.dp, 51.dp),
        "1x2" to DpSize(57.dp, 220.dp),
        "2x2 land" to DpSize(130.dp, 117.dp),
        "2x2" to DpSize(130.dp, 220.dp),
        "3x2" to DpSize(203.dp, 220.dp),
        "4x2 land" to DpSize(276.dp, 117.dp),
        "4x2" to DpSize(276.dp, 220.dp),
        "1x3" to DpSize(57.dp, 338.dp),
        "2x3" to DpSize(130.dp, 338.dp),
        "4x3" to DpSize(276.dp, 338.dp),
        "5x3" to DpSize(349.dp, 338.dp),
    )

    private val sample = WidgetState(
        needsLogin = false, hasData = true,
        fiveHourPct = 42, fiveHourResets = "2h 13m",
        sevenDayPct = 91, sevenDayResets = "4d 6h",
        stale = false, updatedAt = "14:05",
    )

    @Test
    fun renderAllStyles() {
        val problems = mutableListOf<String>()
        for (night in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers(if (night) "+night" else "+notnight")
            val theme = if (night) "dark" else "light"
            for (style in WidgetStyle.entries) {
                for (metrics in WidgetMetrics.entries) {
                    val config = WidgetConfig(style, metrics)
                    val cells = sizes.map { (label, size) ->
                        label to render(sample, config, size, problems, "$theme/${style.name}/${metrics.name}/$label")
                    }
                    sheet(cells, "${style.name.lowercase()}-${metrics.name.lowercase()}-$theme")
                }
            }
            // Backgrounds and states, on one sheet.
            val extra = buildList {
                for (bg in WidgetBackground.entries) {
                    add("bg ${bg.name.lowercase()}" to render(sample, WidgetConfig(background = bg), DpSize(130.dp, 220.dp), problems, "$theme/bg/$bg"))
                    add("rings ${bg.name.lowercase()}" to render(sample, WidgetConfig(WidgetStyle.RINGS, background = bg), DpSize(276.dp, 117.dp), problems, "$theme/bg rings/$bg"))
                }
                add("sign in 1x1" to render(sample.copy(needsLogin = true), WidgetConfig.DEFAULT, DpSize(57.dp, 51.dp), problems, "$theme/signin"))
                add("sign in 2x2" to render(sample.copy(needsLogin = true), WidgetConfig.DEFAULT, DpSize(130.dp, 220.dp), problems, "$theme/signin2"))
                add("loading" to render(sample.copy(hasData = false), WidgetConfig.DEFAULT, DpSize(130.dp, 117.dp), problems, "$theme/loading"))
                add("stale" to render(sample.copy(stale = true), WidgetConfig.DEFAULT, DpSize(130.dp, 117.dp), problems, "$theme/stale"))
                add("0% / 100%" to render(sample.copy(fiveHourPct = 0, sevenDayPct = 100, sevenDayResets = "6d 23h"), WidgetConfig(WidgetStyle.RINGS), DpSize(130.dp, 117.dp), problems, "$theme/edge rings"))
                add("100% cols" to render(sample.copy(fiveHourPct = 100, sevenDayPct = 0), WidgetConfig(WidgetStyle.COLUMNS), DpSize(130.dp, 220.dp), problems, "$theme/edge cols"))
                for (style in WidgetStyle.entries) {
                    add("100% 1x2 ${style.name.lowercase()}" to render(sample.copy(fiveHourPct = 100, sevenDayPct = 100), WidgetConfig(style), DpSize(57.dp, 220.dp), problems, "$theme/edge narrow $style"))
                    add("100% 1x1 ${style.name.lowercase()}" to render(sample.copy(fiveHourPct = 100, sevenDayPct = 100), WidgetConfig(style), DpSize(57.dp, 51.dp), problems, "$theme/edge 1x1 $style"))
                }
                add("100% nums" to render(sample.copy(fiveHourPct = 100, sevenDayPct = 100), WidgetConfig(WidgetStyle.NUMBERS), DpSize(130.dp, 117.dp), problems, "$theme/edge nums"))
            }
            sheet(extra, "extras-$theme")
        }
        assertTrue("Clipped text:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    /** README gallery: each style (rows) at common sizes (columns), light theme. */
    @Test
    fun renderGallery() {
        RuntimeEnvironment.setQualifiers("+notnight")
        val shapes = listOf(
            "1x1" to DpSize(57.dp, 102.dp),
            "2x1" to DpSize(130.dp, 102.dp),
            "4x1" to DpSize(276.dp, 102.dp),
            "2x2" to DpSize(130.dp, 220.dp),
            "4x2" to DpSize(276.dp, 220.dp),
        )
        val state = sample.copy(sevenDayPct = 63)
        val rows = WidgetStyle.entries.map { style ->
            style.label to shapes.map { (_, size) -> render(state, WidgetConfig(style), size, mutableListOf(), "gallery") }
        }
        val gap = 36
        val labelW = 300
        val header = 70
        val rowH = rows.map { r -> r.second.maxOf { it.height } }
        val width = labelW + rows[0].second.sumOf { it.width + gap } + gap
        val height = header + rowH.sumOf { it + gap } + gap
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        drawWallpaper(c, width, height)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 44f; isFakeBoldText = true }
        val hp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 36f }
        var x = labelW
        rows[0].second.forEachIndexed { i, bmp ->
            c.drawText(shapes[i].first, x.toFloat(), 50f, hp)
            x += bmp.width + gap
        }
        var y = header
        rows.forEachIndexed { r, (label, cells) ->
            c.drawText(label, gap.toFloat(), y + rowH[r] / 2f + 16f, tp)
            var cx = labelW
            for (bmp in cells) {
                c.drawBitmap(bmp, cx.toFloat(), y.toFloat(), null)
                cx += bmp.width + gap
            }
            y += rowH[r] + gap
        }
        File(this.out, "gallery.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Preview image for the widget picker: default style at the default 2x2 drop size. */
    @Test
    fun renderPickerPreview() {
        RuntimeEnvironment.setQualifiers("+notnight")
        val bmp = render(sample.copy(sevenDayPct = 18), WidgetConfig.DEFAULT, DpSize(130.dp, 117.dp), mutableListOf(), "preview", wallpaper = false)
        File(out, "widget_preview.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun render(
        state: WidgetState,
        config: WidgetConfig,
        actual: DpSize,
        problems: MutableList<String>,
        tag: String,
        wallpaper: Boolean = true,
    ): Bitmap {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val density = context.resources.displayMetrics.density
        val bucket = bucketFor(actual)
        val rv = runBlocking {
            GlanceRemoteViews().compose(context, bucket) {
                GlanceTheme { UsageWidgetContent(state, config) }
            }.remoteViews
        }
        val parent = FrameLayout(context)
        val view = rv.apply(context, parent)
        val w = px(actual.width, density)
        val h = px(actual.height, density)
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        checkText(view, problems, "$tag (class ${bucket.width.value.toInt()}x${bucket.height.value.toInt()})", w, h)

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (wallpaper) drawWallpaper(canvas, w, h)
        view.draw(canvas)
        return bmp
    }

    /** Glance responsive mode: the largest size class that fits, or the smallest if none does. */
    private fun bucketFor(actual: DpSize): DpSize =
        WidgetSizes.responsive
            .filter { it.width <= actual.width && it.height <= actual.height }
            .maxByOrNull { it.width.value * 1000 + it.height.value }
            ?: WidgetSizes.responsive.minBy { it.width.value + it.height.value }

    /**
     * Flags text that is cut off: wider or taller than its own view, ellipsized, or extending
     * past the area its ancestors draw (their padded bounds clip it, as on a launcher).
     */
    private fun checkText(root: View, problems: MutableList<String>, tag: String, w: Int, h: Int) {
        fun walk(v: View, ox: Int, oy: Int, clip: android.graphics.Rect) {
            if (v.visibility != View.VISIBLE) return
            val x = ox + v.left
            val y = oy + v.top
            if (v is android.widget.TextView && v.text.isNotEmpty()) {
                val layout = v.layout
                // Full text width (the layout's line width is measured after ellipsizing).
                val needW = kotlin.math.ceil(v.paint.measureText(v.text.toString())).toInt()
                val availW = v.width - v.totalPaddingLeft - v.totalPaddingRight
                val needH = layout?.height ?: 0
                val availH = v.height - v.totalPaddingTop - v.totalPaddingBottom
                val ellipsized = layout?.getEllipsisCount(0)?.let { it > 0 } ?: false
                // Where the glyphs actually are, honoring gravity within the view.
                val textLeft = x + v.totalPaddingLeft + (layout?.getLineLeft(0)?.toInt() ?: 0)
                val textRight = x + v.totalPaddingLeft + (layout?.getLineRight(0)?.let { kotlin.math.ceil(it).toInt() } ?: v.width)
                val textTop = y + v.totalPaddingTop
                val textBottom = textTop + needH
                when {
                    ellipsized || needW > availW + 1 -> problems += "$tag: \"${v.text}\" needs ${needW}px, has ${availW}px"
                    needH > availH + 2 -> problems += "$tag: \"${v.text}\" needs ${needH}px tall, has ${availH}px"
                    textLeft < clip.left - 1 || textRight > clip.right + 1 ||
                        textTop < clip.top - 2 || textBottom > clip.bottom + 2 ->
                        problems += "$tag: \"${v.text}\" is clipped by its container"
                }
            }
            if (v is android.view.ViewGroup) {
                val inner = android.graphics.Rect(clip)
                val own = if (v.clipToPadding) {
                    android.graphics.Rect(x + v.paddingLeft, y + v.paddingTop, x + v.width - v.paddingRight, y + v.height - v.paddingBottom)
                } else {
                    android.graphics.Rect(x, y, x + v.width, y + v.height)
                }
                if (v.clipChildren && !inner.intersect(own)) inner.setEmpty()
                for (i in 0 until v.childCount) walk(v.getChildAt(i), x, y, inner)
            }
        }
        walk(root, -root.left, -root.top, android.graphics.Rect(0, 0, w, h))
    }

    private fun drawWallpaper(c: Canvas, w: Int, h: Int) {
        val p = Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.rgb(64, 96, 140), Color.rgb(196, 140, 110), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    }

    /** Lays the renders out left to right, wrapping, with a label above each. */
    private fun sheet(cells: List<Pair<String, Bitmap>>, name: String) {
        val gap = 24
        val label = 40
        val maxRowW = 2400
        var x = gap
        var y = gap
        var rowH = 0
        val placed = cells.map { (text, bmp) ->
            if (x + bmp.width + gap > maxRowW && x > gap) {
                x = gap; y += rowH + label + gap; rowH = 0
            }
            val pos = Triple(x, y, text)
            x += bmp.width + gap
            rowH = maxOf(rowH, bmp.height)
            pos to bmp
        }
        val sheetW = maxRowW
        val sheetH = y + rowH + label + gap
        val sheet = Bitmap.createBitmap(sheetW, sheetH, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawColor(Color.rgb(40, 40, 44))
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 28f }
        for ((pos, bmp) in placed) {
            val (px, py, text) = pos
            c.drawText(text, px.toFloat(), py + 30f, tp)
            c.drawBitmap(bmp, px.toFloat(), (py + label).toFloat(), null)
        }
        File(out, "$name.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun px(d: Dp, density: Float) = (d.value * density).toInt()
}
