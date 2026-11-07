package com.usage.claudewidget.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle

/*
 * One composable per style. Each picks a layout for the size class it is rendered at:
 * small sizes keep only the percentages, larger ones add labels, reset times, a header and
 * the update time (Android widget quality guide, WL-4: rearrange to show more content as the
 * widget grows rather than scaling it up). Gauges fill the space they get, so they also use
 * the room above the size class minimum.
 *
 * Text sizes are chosen so every layout fits the smallest size of its class; WidgetRenderTest
 * checks that no text is cut off at common launcher sizes.
 *
 * Glance allows at most 10 children per Row/Column, so repeated groups are wrapped.
 */

// ---- Bars: horizontal progress bars --------------------------------------------------------

@Composable
internal fun BarsStyle(meters: List<Meter>, w: WidthClass, h: HeightClass, updatedAt: String) {
    val single = meters.size == 1
    val low = h == HeightClass.LOW
    when {
        // 1x1: label and percentage over a thin bar.
        h.oneRow && w == WidthClass.NARROW ->
            Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                meters.forEachIndexed { i, m ->
                    if (i > 0) Spacer(GlanceModifier.height(if (low) 2.dp else 6.dp))
                    when {
                        single && low -> BarCompact(m, 10.sp, 12.sp, 4.dp)
                        single -> BarCompact(m, 10.sp, 16.sp, 6.dp, stacked = true)
                        low -> BarCompact(m, 9.sp, 9.sp, 3.dp)
                        else -> BarCompact(m, 10.sp, 13.sp, 5.dp)
                    }
                }
            }

        // One row: "5H [=====    ] 42%" lines.
        h.oneRow ->
            Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                meters.forEachIndexed { i, m ->
                    if (i > 0) Spacer(GlanceModifier.height(if (low) 3.dp else 8.dp))
                    BarInline(m, showResets = w == WidthClass.WIDE, big = single, low = low)
                }
            }

        // Narrow and tall: logo, then each limit stacked.
        w == WidthClass.NARROW ->
            Column(GlanceModifier.fillMaxSize()) {
                Logo(16.dp)
                meters.forEach { m ->
                    Spacer(GlanceModifier.defaultWeight())
                    BarCompact(
                        m,
                        labelSize = 10.sp,
                        pctSize = if (single) 16.sp else 11.sp,
                        bar = 5.dp,
                        resets = if (h == HeightClass.TALL) m.resets else null,
                        stacked = single,
                    )
                }
                Spacer(GlanceModifier.defaultWeight())
            }

        // Two or more rows: header, limits spread over the height, update time when tall.
        else ->
            Column(GlanceModifier.fillMaxSize()) {
                Header(w)
                meters.forEach { m ->
                    Spacer(GlanceModifier.defaultWeight())
                    if (single) BarHero(m, w, h) else BarRow(m, w, h)
                }
                Spacer(GlanceModifier.defaultWeight())
                if (h == HeightClass.TALL && updatedAt.isNotEmpty()) Caption("Updated $updatedAt", 11.sp)
            }
    }
}

/**
 * "5H      42%" over a thin bar, plus an optional reset time. [stacked] puts the label above
 * the percentage, for a large percentage on a narrow widget.
 */
@Composable
private fun BarCompact(
    m: Meter,
    labelSize: TextUnit,
    pctSize: TextUnit,
    bar: Dp,
    resets: String? = null,
    stacked: Boolean = false,
) {
    Column(GlanceModifier.fillMaxWidth()) {
        if (stacked) {
            Caption(m.short, labelSize, bold = true)
            PctText(m.pct, pctSize)
        } else {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Caption(m.short, labelSize, bold = true)
                Spacer(GlanceModifier.width(2.dp))
                Spacer(GlanceModifier.defaultWeight())
                PctText(m.pct, pctSize)
            }
        }
        Spacer(GlanceModifier.height(2.dp))
        HBar(m.pct, bar, GlanceModifier.fillMaxWidth())
        if (resets != null) {
            Spacer(GlanceModifier.height(2.dp))
            Caption(resets, 10.sp)
        }
    }
}

/** "5H [=====    ] 42%  2h 13m" on one line. */
@Composable
private fun BarInline(m: Meter, showResets: Boolean, big: Boolean, low: Boolean) {
    val text = if (low) 11.sp else 12.sp
    val pct = when {
        big -> if (low) 15.sp else if (showResets) 20.sp else 18.sp
        else -> if (low) 11.sp else 14.sp
    }
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Caption(m.short, text, GlanceModifier.width(26.dp), bold = true)
        HBar(m.pct, if (big) 8.dp else if (low) 5.dp else 6.dp, GlanceModifier.defaultWeight())
        PctText(m.pct, pct, GlanceModifier.width(if (big && !low && showResets) 58.dp else 48.dp), align = TextAlign.End)
        if (showResets) Caption(m.resets, text, GlanceModifier.width(58.dp), align = TextAlign.End)
    }
}

/** "5H 42%        resets in 2h 13m" over a bar. */
@Composable
private fun BarRow(m: Meter, w: WidthClass, h: HeightClass) {
    val tall = h == HeightClass.TALL
    Column(GlanceModifier.fillMaxWidth()) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Caption(m.short, 12.sp, GlanceModifier.width(26.dp), bold = true)
            // Medium width (2 cells) has about 106 dp for label, percentage and reset time.
            PctText(m.pct, if (tall && w == WidthClass.WIDE) 20.sp else if (tall) 16.sp else 14.sp)
            Spacer(GlanceModifier.width(4.dp))
            Spacer(GlanceModifier.defaultWeight())
            Caption(if (w == WidthClass.WIDE) "resets in ${m.resets}" else m.resets, 12.sp)
        }
        Spacer(GlanceModifier.height(if (tall) 6.dp else 4.dp))
        HBar(m.pct, if (tall) 10.dp else 6.dp, GlanceModifier.fillMaxWidth())
    }
}

/** Single limit: name and large percentage over a thick bar, reset time below. */
@Composable
private fun BarHero(m: Meter, w: WidthClass, h: HeightClass) {
    val tall = h == HeightClass.TALL
    Column(GlanceModifier.fillMaxWidth()) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Caption(m.long, 13.sp, bold = true)
            Spacer(GlanceModifier.defaultWeight())
            PctText(m.pct, if (tall) 32.sp else 22.sp)
        }
        Spacer(GlanceModifier.height(4.dp))
        HBar(m.pct, if (tall) 12.dp else 8.dp, GlanceModifier.fillMaxWidth())
        Spacer(GlanceModifier.height(4.dp))
        Caption(if (w == WidthClass.NARROW) m.resets else "resets in ${m.resets}", 12.sp)
    }
}

// ---- Columns: vertical bars ----------------------------------------------------------------

@Composable
internal fun ColumnsStyle(meters: List<Meter>, w: WidthClass, h: HeightClass) {
    val single = meters.size == 1
    when {
        // 1x1: bars side by side with the percentage under each.
        h.oneRow && w == WidthClass.NARROW && !single ->
            Row(GlanceModifier.fillMaxSize()) {
                meters.forEach { m ->
                    Column(
                        GlanceModifier.defaultWeight().fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        VBar(m.pct, 10.dp, GlanceModifier.defaultWeight())
                        PctText(m.pct, 9.sp, align = TextAlign.Center)
                        Caption(m.short, 8.sp, bold = true, align = TextAlign.Center)
                    }
                }
            }

        // One row, or a single limit with room beside it: each bar with its numbers beside it.
        h.oneRow || (single && w != WidthClass.NARROW) ->
            Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                meters.forEachIndexed { i, m ->
                    if (i > 0) Spacer(GlanceModifier.width(8.dp))
                    ColumnBeside(m, w, h, single, GlanceModifier.defaultWeight())
                }
            }

        // Narrow and tall, single limit: percentage, tall bar, label.
        single ->
            Column(GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                val m = meters[0]
                PctText(m.pct, 14.sp, align = TextAlign.Center)
                Spacer(GlanceModifier.height(4.dp))
                VBar(m.pct, 20.dp, GlanceModifier.defaultWeight())
                Spacer(GlanceModifier.height(4.dp))
                Caption(m.short, 11.sp, bold = true, align = TextAlign.Center)
                if (h == HeightClass.TALL) Caption(m.resets, 10.sp, align = TextAlign.Center)
            }

        // Two or more rows: bars standing side by side.
        else ->
            Column(GlanceModifier.fillMaxSize()) {
                if (h == HeightClass.TALL && w != WidthClass.NARROW) {
                    Header(w)
                    Spacer(GlanceModifier.height(8.dp))
                }
                Row(GlanceModifier.defaultWeight().fillMaxWidth()) {
                    meters.forEach { m ->
                        ColumnStanding(m, w, h, GlanceModifier.defaultWeight())
                    }
                }
            }
    }
}

@Composable
private fun ColumnBeside(m: Meter, w: WidthClass, h: HeightClass, single: Boolean, modifier: GlanceModifier) {
    val low = h == HeightClass.LOW
    val narrow = w == WidthClass.NARROW
    val roomy = !h.oneRow
    val bar = when {
        narrow -> 10.dp
        single && roomy -> if (h == HeightClass.TALL) 32.dp else 24.dp
        single -> 14.dp
        else -> 10.dp
    }
    val pct = when {
        narrow -> if (low) 12.sp else 14.sp
        single && roomy -> if (h == HeightClass.TALL && w == WidthClass.WIDE) 44.sp else 28.sp
        single -> if (low) 16.sp else 24.sp
        else -> if (low) 14.sp else 18.sp
    }
    val caption = if (low || narrow) 10.sp else 12.sp
    Row(modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
        VBar(m.pct, bar, GlanceModifier.fillMaxHeight())
        Spacer(GlanceModifier.width(if (narrow) 4.dp else 8.dp))
        Column {
            PctText(m.pct, pct)
            when {
                single && roomy -> {
                    Caption(m.long, 12.sp, bold = true)
                    Caption(if (w == WidthClass.WIDE) "resets in ${m.resets}" else m.resets, 12.sp)
                }
                w == WidthClass.WIDE -> Caption("${m.long} · ${m.resets}", caption)
                else -> Caption(m.short, caption, bold = true)
            }
        }
    }
}

@Composable
private fun ColumnStanding(m: Meter, w: WidthClass, h: HeightClass, modifier: GlanceModifier) {
    val tall = h == HeightClass.TALL
    val (pctSize, bar) = when (w) {
        WidthClass.NARROW -> 9.sp to 10.dp
        WidthClass.MEDIUM -> (if (tall) 16.sp else 14.sp) to 18.dp
        WidthClass.WIDE -> (if (tall) 20.sp else 18.sp) to 26.dp
    }
    Column(modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        PctText(m.pct, pctSize, align = TextAlign.Center)
        Spacer(GlanceModifier.height(4.dp))
        VBar(m.pct, bar, GlanceModifier.defaultWeight())
        Spacer(GlanceModifier.height(4.dp))
        Caption(m.short, 11.sp, bold = true, align = TextAlign.Center)
        if (tall && w != WidthClass.NARROW) Caption(m.resets, 11.sp, align = TextAlign.Center)
    }
}

// ---- Rings: circular gauges ----------------------------------------------------------------

@Composable
internal fun RingsStyle(meters: List<Meter>, w: WidthClass, h: HeightClass) {
    val single = meters.size == 1
    when {
        w == WidthClass.NARROW && h.oneRow && !single -> ConcentricRings(meters[0], meters[1])

        single -> RingHero(meters[0], w, h)

        // One row: rings side by side; wide widgets add the name and reset time beside each.
        h.oneRow ->
            Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                val low = h == HeightClass.LOW
                meters.forEachIndexed { i, m ->
                    if (i > 0) Spacer(GlanceModifier.width(8.dp))
                    if (w == WidthClass.WIDE) {
                        Row(GlanceModifier.defaultWeight().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                            Ring(m.pct, GlanceModifier.width(if (low) 44.dp else 72.dp).fillMaxHeight()) {
                                PctText(m.pct, if (low) 11.sp else 15.sp, align = TextAlign.Center)
                            }
                            Spacer(GlanceModifier.width(8.dp))
                            Column {
                                Caption(m.long, 12.sp, bold = true)
                                Caption(m.resets, 11.sp)
                            }
                        }
                    } else {
                        Ring(m.pct, GlanceModifier.defaultWeight().fillMaxHeight()) {
                            if (low) RingLabel(m, 11.sp, 8.sp) else RingLabel(m, 13.sp, 9.sp)
                        }
                    }
                }
            }

        // Narrow, or a medium-width widget that is taller than wide: rings stacked.
        w == WidthClass.NARROW || (h == HeightClass.TALL && w == WidthClass.MEDIUM) ->
            Column(GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                val narrow = w == WidthClass.NARROW
                meters.forEachIndexed { i, m ->
                    if (i > 0) Spacer(GlanceModifier.height(6.dp))
                    Ring(m.pct, GlanceModifier.defaultWeight().fillMaxWidth()) {
                        if (narrow) RingLabel(m, 11.sp, 8.sp) else RingLabel(m, 18.sp, 11.sp)
                    }
                    if (!narrow) Caption(m.resets, 11.sp, align = TextAlign.Center)
                }
            }

        // Side by side; larger rings also show the reset time inside.
        else ->
            Column(GlanceModifier.fillMaxSize()) {
                val tall = h == HeightClass.TALL
                if (tall) {
                    Header(w)
                    Spacer(GlanceModifier.height(8.dp))
                }
                Row(GlanceModifier.defaultWeight().fillMaxWidth()) {
                    meters.forEachIndexed { i, m ->
                        if (i > 0) Spacer(GlanceModifier.width(8.dp))
                        Ring(m.pct, GlanceModifier.defaultWeight().fillMaxHeight()) {
                            when {
                                w == WidthClass.WIDE && tall -> RingLabel(m, 22.sp, 12.sp, m.resets)
                                w == WidthClass.WIDE || tall -> RingLabel(m, 16.sp, 10.sp, m.resets)
                                else -> RingLabel(m, 12.sp, 9.sp)
                            }
                        }
                    }
                }
            }
    }
}

/** Percentage over the short label (and optionally the reset time), centered inside a ring. */
@Composable
private fun RingLabel(m: Meter, pctSize: TextUnit, labelSize: TextUnit, resets: String? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PctText(m.pct, pctSize, align = TextAlign.Center)
        Caption(m.short, labelSize, bold = true, align = TextAlign.Center)
        if (resets != null) Caption(resets, labelSize, align = TextAlign.Center)
    }
}

/** 1x1 with both limits: 5-hour outer ring, weekly inner ring, 5-hour percentage centered. */
@Composable
private fun ConcentricRings(outer: Meter, inner: Meter) {
    val inset = WidgetBitmaps.RING_STROKE + 0.035f
    Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        RingLayer(WidgetBitmaps.ringTrack(), GlanceTheme.colors.secondaryContainer)
        RingLayer(WidgetBitmaps.ring(outer.pct), usageColor(outer.pct))
        RingLayer(WidgetBitmaps.ringTrack(inset), GlanceTheme.colors.secondaryContainer)
        RingLayer(WidgetBitmaps.ring(inner.pct, inset), usageColor(inner.pct, GlanceTheme.colors.tertiary))
        Text(
            "${outer.pct}",
            maxLines = 1,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

/** Single limit: one large ring, with the name and reset time where there is room. */
@Composable
private fun RingHero(m: Meter, w: WidthClass, h: HeightClass) {
    if (w == WidthClass.WIDE && h != HeightClass.TALL) {
        Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            val ringW = when (h) {
                HeightClass.LOW -> 44.dp
                HeightClass.SHORT -> 72.dp
                else -> 86.dp
            }
            Ring(m.pct, GlanceModifier.width(ringW).fillMaxHeight()) {
                PctText(m.pct, if (h == HeightClass.LOW) 11.sp else 18.sp, align = TextAlign.Center)
            }
            Spacer(GlanceModifier.width(12.dp))
            Column {
                Caption(m.long, 13.sp, bold = true)
                Caption("resets in ${m.resets}", 12.sp)
            }
        }
        return
    }
    val (pctSize, labelSize) = when {
        w == WidthClass.NARROW -> (if (h == HeightClass.LOW) 11.sp else 13.sp) to 8.sp
        h == HeightClass.LOW -> 12.sp to 9.sp
        h == HeightClass.SHORT -> 16.sp to 10.sp
        w == WidthClass.WIDE -> 36.sp to 14.sp
        else -> 18.sp to 11.sp
    }
    Column(GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Ring(m.pct, GlanceModifier.defaultWeight().fillMaxWidth()) { RingLabel(m, pctSize, labelSize) }
        if (!h.oneRow) {
            Spacer(GlanceModifier.height(4.dp))
            Caption(if (w == WidthClass.NARROW) m.resets else "resets in ${m.resets}", 11.sp, align = TextAlign.Center)
        }
    }
}

// ---- Numbers: large type only --------------------------------------------------------------

@Composable
internal fun NumbersStyle(meters: List<Meter>, w: WidthClass, h: HeightClass) {
    val single = meters.size == 1
    val low = h == HeightClass.LOW
    when {
        w == WidthClass.NARROW && h.oneRow ->
            if (single) {
                Column(
                    GlanceModifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BigPct(meters[0].pct, if (low) 16.sp else 20.sp, TextAlign.Center)
                    Caption(meters[0].short, if (low) 9.sp else 11.sp, bold = true, align = TextAlign.Center)
                }
            } else {
                Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    meters.forEachIndexed { i, m ->
                        if (i > 0 && !low) Spacer(GlanceModifier.height(6.dp))
                        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Caption(m.short, 9.sp, bold = true)
                            Spacer(GlanceModifier.defaultWeight())
                            BigPct(m.pct, if (low) 12.sp else 14.sp)
                        }
                    }
                }
            }

        h.oneRow ->
            Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                val wide = w == WidthClass.WIDE
                val size = when {
                    low -> 16.sp
                    single || wide -> 28.sp
                    else -> 22.sp
                }
                meters.forEachIndexed { i, m ->
                    if (i > 0) Spacer(GlanceModifier.width(8.dp))
                    Column(GlanceModifier.defaultWeight()) {
                        BigPct(m.pct, size)
                        Caption(if (wide) "${m.long} · ${m.resets}" else m.short, if (low) 10.sp else 12.sp, bold = !wide)
                    }
                }
            }

        else -> {
            val tall = h == HeightClass.TALL
            val stacked = w == WidthClass.NARROW || (tall && w == WidthClass.MEDIUM) || single
            val size = when {
                single && w == WidthClass.NARROW -> if (tall) 18.sp else 16.sp
                single -> if (w == WidthClass.WIDE) (if (tall) 56.sp else 44.sp) else 36.sp
                w == WidthClass.NARROW -> if (tall) 16.sp else 15.sp
                stacked -> 30.sp
                w == WidthClass.WIDE -> if (tall) 40.sp else 32.sp
                else -> 20.sp
            }
            Column(GlanceModifier.fillMaxSize()) {
                if (tall && w != WidthClass.NARROW) Header(w)
                Spacer(GlanceModifier.defaultWeight())
                if (stacked) {
                    meters.forEachIndexed { i, m ->
                        if (i > 0) Spacer(GlanceModifier.height(if (tall) 10.dp else 6.dp))
                        NumberBlock(m, size, w, GlanceModifier.fillMaxWidth())
                    }
                } else {
                    Row(GlanceModifier.fillMaxWidth()) {
                        meters.forEach { m -> NumberBlock(m, size, w, GlanceModifier.defaultWeight()) }
                    }
                }
                Spacer(GlanceModifier.defaultWeight())
            }
        }
    }
}

@Composable
private fun NumberBlock(m: Meter, size: TextUnit, w: WidthClass, modifier: GlanceModifier) {
    val narrow = w == WidthClass.NARROW
    Column(modifier) {
        BigPct(m.pct, size)
        Caption(if (narrow) m.short else m.long, if (narrow) 11.sp else 12.sp, bold = true)
        Caption(if (w == WidthClass.WIDE) "resets in ${m.resets}" else m.resets, 11.sp)
    }
}

/** Percentage in the usage color (primary, or error when high). */
@Composable
private fun BigPct(pct: Int, size: TextUnit, align: TextAlign = TextAlign.Start) {
    Text(
        "$pct%",
        maxLines = 1,
        style = TextStyle(
            color = usageColor(pct),
            fontSize = size,
            fontWeight = FontWeight.Bold,
            textAlign = align,
        ),
    )
}
