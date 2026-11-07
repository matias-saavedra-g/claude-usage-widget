package com.usage.claudewidget.widget

import android.content.Intent
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.usage.claudewidget.R
import com.usage.claudewidget.ui.MainActivity

/** Immutable view state handed to the composable; assembled from Storage on each render. */
data class WidgetState(
    val needsLogin: Boolean,
    val hasData: Boolean,
    val fiveHourPct: Int,
    val fiveHourResets: String,
    val sevenDayPct: Int,
    val sevenDayResets: String,
    val stale: Boolean,
    /** Local time of the last successful fetch, e.g. "14:05", or "" if unknown. */
    val updatedAt: String = "",
)

/** One usage limit as the layouts draw it. */
internal data class Meter(
    /** "5H" / "1W". */
    val short: String,
    /** "5-hour" / "Weekly". */
    val long: String,
    val pct: Int,
    /** "3h 12m"; see [TimeFmt.resetsIn]. */
    val resets: String,
)

internal fun WidgetState.meters(metrics: WidgetMetrics): List<Meter> {
    val five = Meter("5H", "5-hour", fiveHourPct, fiveHourResets)
    val week = Meter("1W", "Weekly", sevenDayPct, sevenDayResets)
    return when (metrics) {
        WidgetMetrics.BOTH -> listOf(five, week)
        WidgetMetrics.FIVE_HOUR -> listOf(five)
        WidgetMetrics.WEEKLY -> listOf(week)
    }
}

/*
 * Size classes. Breakpoints follow the cell sizes in Android's widget sizing guide
 * (portrait width 73n-16 dp, landscape height 66m-15 dp on a typical 5x4 handset):
 * one cell is about 57x51 dp, two cells 130x117 dp, three cells about 203x184 dp.
 * One row in portrait is about 102 dp tall, so it still counts as SHORT.
 */
internal enum class WidthClass { NARROW, MEDIUM, WIDE }

/**
 * LOW and SHORT are both one row: LOW is the landscape case (about 51 dp), SHORT the portrait
 * case (about 102 dp), which has room for larger type.
 */
internal enum class HeightClass { LOW, SHORT, MEDIUM, TALL;
    val oneRow: Boolean get() = this == LOW || this == SHORT
}

internal object WidgetSizes {
    private val widths = listOf(48.dp, 110.dp, 200.dp)
    private val heights = listOf(48.dp, 80.dp, 110.dp, 200.dp)

    /** Every width/height combination: one responsive layout per size class. */
    val responsive: Set<DpSize> = widths.flatMap { w -> heights.map { h -> DpSize(w, h) } }.toSet()

    fun widthClass(w: Dp) = when {
        w >= widths[2] -> WidthClass.WIDE
        w >= widths[1] -> WidthClass.MEDIUM
        else -> WidthClass.NARROW
    }

    fun heightClass(h: Dp) = when {
        h >= heights[3] -> HeightClass.TALL
        h >= heights[2] -> HeightClass.MEDIUM
        h >= heights[1] -> HeightClass.SHORT
        else -> HeightClass.LOW
    }
}

@Composable
fun UsageWidgetContent(state: WidgetState, config: WidgetConfig = WidgetConfig.DEFAULT) {
    val size = LocalSize.current
    val w = WidgetSizes.widthClass(size.width)
    val h = WidgetSizes.heightClass(size.height)
    val tiny = w == WidthClass.NARROW || h.oneRow
    val context = LocalContext.current

    val tap = if (state.needsLogin) {
        actionStartActivity(Intent(context, MainActivity::class.java))
    } else {
        actionStartActivity(Intent(context, TapActivity::class.java))
    }

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .widgetBackground(config.background)
            .widgetCorners()
            .padding(
                horizontal = if (w == WidthClass.NARROW) 6.dp else if (tiny) 8.dp else 12.dp,
                vertical = if (tiny) 8.dp else 12.dp,
            )
            .clickable(tap),
    ) {
        when {
            state.needsLogin -> SignInPrompt(tiny)
            !state.hasData -> Loading()
            else -> {
                val meters = state.meters(config.metrics)
                when (config.style) {
                    WidgetStyle.BARS -> BarsStyle(meters, w, h, state.updatedAt)
                    WidgetStyle.COLUMNS -> ColumnsStyle(meters, w, h)
                    WidgetStyle.RINGS -> RingsStyle(meters, w, h)
                    WidgetStyle.NUMBERS -> NumbersStyle(meters, w, h)
                }
            }
        }
        if (state.hasData && state.stale) StaleDot()
    }
}

@Composable
private fun GlanceModifier.widgetBackground(bg: WidgetBackground): GlanceModifier = when (bg) {
    WidgetBackground.SOLID -> background(GlanceTheme.colors.widgetBackground)
    WidgetBackground.TRANSLUCENT -> background(ImageProvider(R.drawable.widget_bg_translucent))
    WidgetBackground.NONE -> this
}

/** System widget corner radius on Android 12+, matching other Material You widgets. */
private fun GlanceModifier.widgetCorners(): GlanceModifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        cornerRadius(android.R.dimen.system_app_widget_background_radius)
    } else {
        cornerRadius(16.dp)
    }

// ---- Shared building blocks -------------------------------------------------------------

/** Usage at or above this is drawn in the error color. */
private const val HIGH_PCT = 90

@Composable
internal fun usageColor(pct: Int, normal: ColorProvider = GlanceTheme.colors.primary): ColorProvider =
    if (pct >= HIGH_PCT) GlanceTheme.colors.error else normal

@Composable
internal fun PctText(
    pct: Int,
    size: TextUnit,
    modifier: GlanceModifier = GlanceModifier,
    align: TextAlign = TextAlign.Start,
) {
    Text(
        "$pct%",
        modifier = modifier,
        maxLines = 1,
        style = TextStyle(
            color = GlanceTheme.colors.onSurface,
            fontSize = size,
            fontWeight = FontWeight.Bold,
            textAlign = align,
        ),
    )
}

/** Secondary text: labels and reset times. */
@Composable
internal fun Caption(
    text: String,
    size: TextUnit = 12.sp,
    modifier: GlanceModifier = GlanceModifier,
    bold: Boolean = false,
    align: TextAlign = TextAlign.Start,
) {
    Text(
        text,
        modifier = modifier,
        maxLines = 1,
        style = TextStyle(
            color = GlanceTheme.colors.onSurfaceVariant,
            fontSize = size,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            textAlign = align,
        ),
    )
}

/** Logo and title. The title is dropped on narrow widgets. */
@Composable
internal fun Header(w: WidthClass) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Logo(18.dp)
        if (w != WidthClass.NARROW) {
            Spacer(GlanceModifier.width(6.dp))
            Text(
                "Claude usage",
                maxLines = 1,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }
}

/** Horizontal bar. Pass a width or weight in [modifier]. */
@Composable
internal fun HBar(pct: Int, thickness: Dp, modifier: GlanceModifier) {
    LinearProgressIndicator(
        progress = pct.coerceIn(0, 100) / 100f,
        modifier = modifier.height(thickness).cornerRadius(thickness / 2),
        color = usageColor(pct),
        backgroundColor = GlanceTheme.colors.secondaryContainer,
    )
}

/** Vertical bar filling from the bottom. Pass a width and a height or weight in [modifier]. */
@Composable
internal fun VBar(pct: Int, thickness: Dp, modifier: GlanceModifier) {
    Box(
        modifier = modifier
            .width(thickness)
            .background(GlanceTheme.colors.secondaryContainer)
            .cornerRadius(thickness / 2),
    ) {
        Image(
            provider = ImageProvider(WidgetBitmaps.columnFill(pct)),
            contentDescription = null,
            modifier = GlanceModifier.fillMaxSize().cornerRadius(thickness / 2),
            contentScale = ContentScale.FillBounds,
            colorFilter = ColorFilter.tint(usageColor(pct)),
        )
    }
}

/**
 * Circular gauge that scales to fill [modifier]'s bounds (it stays round), with optional
 * centered text.
 */
@Composable
internal fun Ring(
    pct: Int,
    modifier: GlanceModifier,
    center: (@Composable () -> Unit)? = null,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        RingLayer(WidgetBitmaps.ringTrack(), GlanceTheme.colors.secondaryContainer)
        RingLayer(WidgetBitmaps.ring(pct), usageColor(pct))
        center?.invoke()
    }
}

@Composable
internal fun RingLayer(bitmap: android.graphics.Bitmap, color: ColorProvider) {
    Image(
        provider = ImageProvider(bitmap),
        contentDescription = null,
        modifier = GlanceModifier.fillMaxSize(),
        contentScale = ContentScale.Fit,
        colorFilter = ColorFilter.tint(color),
    )
}

@Composable
internal fun Logo(s: Dp) {
    Image(
        provider = ImageProvider(R.drawable.ic_star),
        contentDescription = "Claude",
        modifier = GlanceModifier.size(s),
    )
}

@Composable
private fun SignInPrompt(tiny: Boolean) {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Logo(if (tiny) 14.dp else 24.dp)
        Spacer(GlanceModifier.height(if (tiny) 2.dp else 6.dp))
        Text(
            if (tiny) "Sign in" else "Tap to sign in",
            maxLines = 1,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = if (tiny) 12.sp else 14.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

@Composable
private fun Loading() {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("…", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant))
    }
}

@Composable
private fun StaleDot() {
    Box(
        modifier = GlanceModifier.fillMaxSize().padding(2.dp),
        contentAlignment = Alignment.TopEnd,
    ) {
        Image(
            provider = ImageProvider(R.drawable.stale_dot),
            contentDescription = "Out of date",
            modifier = GlanceModifier.size(8.dp),
        )
    }
}
