package com.usage.claudewidget.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.usage.claudewidget.data.Prediction
import com.usage.claudewidget.data.Sample
import com.usage.claudewidget.data.UsagePredictor
import com.usage.claudewidget.data.WindowKind
import com.usage.claudewidget.widget.TimeFmt
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Everything the charts and forecasts need, computed off the main thread from the history file. */
data class Insights(
    val now: Long,
    val samples: List<Sample>,
    val fiveHour: Prediction,
    val weekly: Prediction,
)

fun computeInsights(
    samples: List<Sample>,
    fiveHourUtil: Float,
    fiveHourReset: Long,
    weeklyUtil: Float,
    weeklyReset: Long,
    now: Long = System.currentTimeMillis(),
) = Insights(
    now = now,
    samples = samples,
    fiveHour = UsagePredictor.predict(samples, WindowKind.FIVE_HOUR, fiveHourUtil, fiveHourReset, now),
    weekly = UsagePredictor.predict(samples, WindowKind.WEEKLY, weeklyUtil, weeklyReset, now),
)

private val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
private val dayClock = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.getDefault())
private val day = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())

private fun fmt(f: DateTimeFormatter, ms: Long) =
    f.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

@Composable
fun InsightsSection(insights: Insights) {
    ForecastCard(insights.fiveHour, insights)
    ForecastCard(insights.weekly, insights)
    SessionsCard(insights)
    WeeklyTrendCard(insights)
}

@Composable
private fun ForecastCard(p: Prediction, insights: Insights) {
    val now = insights.now
    val active = p.resetMs > now
    val start = p.resetMs - p.kind.lengthMs
    InsightCard("${p.kind.label} window") {
        if (!active) {
            Text("No active window. It starts with your next message.", style = MaterialTheme.typography.bodyMedium)
            return@InsightCard
        }
        val run = UsagePredictor.windows(insights.samples, p.kind).lastOrNull()
            ?.takeIf { kotlin.math.abs(it.resetMs - p.resetMs) <= 30 * 60_000L }
        val points = run?.samples.orEmpty().map { ChartPoint(it.t, p.kind.util(it)) } + ChartPoint(now, p.util)
        val projection = p.curve.map { (t, u) -> ChartPoint(t, u.toFloat()) }
        val f = if (p.kind == WindowKind.FIVE_HOUR) clock else dayClock
        UsageLineChart(
            series = listOf(points),
            startMs = start,
            endMs = p.resetMs,
            startLabel = fmt(f, start),
            endLabel = "resets ${fmt(f, p.resetMs)}",
            projection = projection,
            nowMs = now,
        )
        Verdict(p, now)
        if (p.methods.isNotEmpty()) {
            HorizontalDivider()
            p.methods.forEach { MethodRow(it.name, it.pctPerHour, p.kind) }
            MethodRow(if (p.usesPattern) "Current pace" else "Mean (used for forecast)", p.pctPerHour, p.kind, emphasize = true)
            if (p.usesPattern) {
                Text(
                    "The forecast starts at the current pace and blends into your usual usage " +
                        "for each hour of the week, so quiet nights and busy days are accounted for.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Verdict(p: Prediction, now: Long) {
    val (headline, color) = when {
        p.util >= 100f -> "Limit reached. Resets in ${TimeFmt.resetsIn(p.resetMs, now)}." to MaterialTheme.colorScheme.error
        p.methods.isEmpty() -> "Not enough history yet. Forecasts appear after a few refreshes." to MaterialTheme.colorScheme.onSurfaceVariant
        p.limitAtMs != null -> "At this pace you run out in ${TimeFmt.resetsIn(p.limitAtMs, now)} " +
            "(${fmt(if (p.kind == WindowKind.FIVE_HOUR) clock else dayClock, p.limitAtMs)}), " +
            "${TimeFmt.resetsIn(p.resetMs, p.limitAtMs)} before the reset." to MaterialTheme.colorScheme.error
        else -> "You're OK. At this pace you reach about ${p.projectedAtReset.toInt()}% by the reset, " +
            "so you can keep working like this." to MaterialTheme.colorScheme.primary
    }
    Text(headline, style = MaterialTheme.typography.bodyMedium, color = color)
    if (p.util < 100f) {
        p.burstsLeft?.let {
            Text(
                "Your usage rises about ${"%.1f".format(p.avgSpikePct)}% per active refresh interval; " +
                    "roughly $it more bursts like that fit before the limit.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MethodRow(name: String, pctPerHour: Double, kind: WindowKind, emphasize: Boolean = false) {
    val style = if (emphasize) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodySmall
    // Weekly rates are tiny per hour; per day reads better.
    val value = if (kind == WindowKind.WEEKLY) "%.1f%%/day".format(pctPerHour * 24) else "%.1f%%/h".format(pctPerHour)
    Row(Modifier.fillMaxWidth()) {
        Text(name, style = style, modifier = Modifier.weight(1f))
        Text(value, style = style)
    }
}

/** The 5-hour usage over the last week: each session shows as a ramp that drops at its reset. */
@Composable
private fun SessionsCard(insights: Insights) {
    val start = insights.now - WindowKind.WEEKLY.lengthMs
    val runs = UsagePredictor.windows(insights.samples.filter { it.t >= start }, WindowKind.FIVE_HOUR)
    InsightCard("5-hour sessions, last 7 days") {
        if (runs.isEmpty()) {
            Text("No sessions recorded yet.", style = MaterialTheme.typography.bodyMedium)
            return@InsightCard
        }
        UsageLineChart(
            series = runs.map { r -> r.samples.map { ChartPoint(it.t, it.fiveHour) } },
            startMs = start,
            endMs = insights.now,
            startLabel = fmt(day, start),
            endLabel = "now",
        )
        val peaks = runs.map { r -> r.samples.maxOf { it.fiveHour } }
        Text(
            "${runs.size} sessions · average peak ${peaks.average().toInt()}% · " +
                "${peaks.count { it >= 100f }} hit the limit",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Peak weekly usage of each recorded week, up to the 12 weeks kept. */
@Composable
private fun WeeklyTrendCard(insights: Insights) {
    val runs = UsagePredictor.windows(insights.samples, WindowKind.WEEKLY)
    InsightCard("Weekly trend") {
        if (runs.size < 2) {
            Text(
                "The trend appears once more than one week is recorded. History is kept for 12 weeks.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (runs.isEmpty()) return@InsightCard
        }
        val peaks = runs.map { r -> r.samples.maxOf { it.sevenDay } }
        UsageBarChart(
            bars = peaks,
            startLabel = fmt(day, runs.first().resetMs - WindowKind.WEEKLY.lengthMs),
            endLabel = "this week",
        )
        val past = peaks.dropLast(1)
        if (past.isNotEmpty()) {
            Text(
                "Past weeks averaged ${past.average().toInt()}% · this week so far ${peaks.last().toInt()}% · " +
                    "forecast ${insights.weekly.projectedAtReset.toInt()}% by reset",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InsightCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
