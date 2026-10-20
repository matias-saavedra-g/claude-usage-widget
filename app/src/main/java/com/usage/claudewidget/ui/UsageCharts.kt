package com.usage.claudewidget.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** A point on a usage chart: time in epoch ms and usage in percent. */
data class ChartPoint(val t: Long, val pct: Float)

/**
 * Usage over [startMs]..[endMs] on a fixed 0..100% scale. Each inner list of [series] is drawn
 * as its own line, so separate windows don't join up. [projection] is drawn dashed.
 */
@Composable
fun UsageLineChart(
    series: List<List<ChartPoint>>,
    startMs: Long,
    endMs: Long,
    startLabel: String,
    endLabel: String,
    modifier: Modifier = Modifier,
    projection: List<ChartPoint> = emptyList(),
    nowMs: Long? = null,
) {
    val line = MaterialTheme.colorScheme.primary
    val fill = line.copy(alpha = 0.15f)
    val warn = MaterialTheme.colorScheme.error
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            val span = (endMs - startMs).coerceAtLeast(1).toFloat()
            fun at(p: ChartPoint) = Offset(
                size.width * ((p.t - startMs) / span).coerceIn(0f, 1f),
                size.height * (1f - p.pct.coerceIn(0f, 100f) / 100f),
            )
            drawGrid(grid)
            nowMs?.let {
                val x = size.width * ((it - startMs) / span).coerceIn(0f, 1f)
                drawLine(grid, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
            }
            for (pts in series) {
                if (pts.isEmpty()) continue
                val path = Path().apply {
                    pts.forEachIndexed { i, p -> at(p).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
                }
                val area = Path().apply {
                    addPath(path)
                    lineTo(at(pts.last()).x, size.height)
                    lineTo(at(pts.first()).x, size.height)
                    close()
                }
                drawPath(area, fill)
                drawPath(path, line, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                if (pts.size == 1) drawCircle(line, 3.dp.toPx(), at(pts[0]))
            }
            if (projection.size >= 2) {
                val path = Path().apply {
                    projection.forEachIndexed { i, p -> at(p).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
                }
                val hits = projection.last().pct >= 100f
                drawPath(
                    path,
                    if (hits) warn else line,
                    style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))),
                )
                if (hits) drawCircle(warn, 4.dp.toPx(), at(projection.last()))
            }
        }
        AxisLabels(startLabel, endLabel)
    }
}

/** Peak usage of each past window as a bar, oldest on the left. */
@Composable
fun UsageBarChart(bars: List<Float>, startLabel: String, endLabel: String, modifier: Modifier = Modifier) {
    val bar = MaterialTheme.colorScheme.primary
    val last = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            drawGrid(grid)
            if (bars.isEmpty()) return@Canvas
            val slot = size.width / bars.size
            val w = slot * 0.6f
            bars.forEachIndexed { i, v ->
                val h = size.height * v.coerceIn(0f, 100f) / 100f
                drawRect(
                    if (i == bars.lastIndex) last else bar,
                    topLeft = Offset(slot * i + (slot - w) / 2, size.height - h),
                    size = Size(w, h),
                )
            }
        }
        AxisLabels(startLabel, endLabel)
    }
}

private fun DrawScope.drawGrid(color: Color) {
    for (pct in listOf(0f, 25f, 50f, 75f, 100f)) {
        val y = size.height * (1f - pct / 100f)
        drawLine(
            color, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(),
            pathEffect = if (pct == 100f || pct == 0f) null else PathEffect.dashPathEffect(floatArrayOf(4f, 6f)),
        )
    }
}

@Composable
private fun AxisLabels(start: String, end: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(start, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(end, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
