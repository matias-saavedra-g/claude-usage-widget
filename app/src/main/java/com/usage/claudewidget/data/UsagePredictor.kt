package com.usage.claudewidget.data

import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.exp

/** The two limits the usage endpoint reports. */
enum class WindowKind(
    val label: String,
    val lengthMs: Long,
    val trendLookbackMs: Long,
    /** Step of the projected curve. */
    val stepMs: Long,
    /** How fast the current pace hands over to the usual pattern, looking ahead. */
    val momentumMs: Long,
) {
    FIVE_HOUR("5-hour", 5L * 3_600_000, 60L * 60_000, 10L * 60_000, 60L * 60_000),
    WEEKLY("Weekly", 7L * 24 * 3_600_000, 24L * 3_600_000, 3_600_000, 12L * 3_600_000);

    fun util(s: Sample) = if (this == FIVE_HOUR) s.fiveHour else s.sevenDay
    fun reset(s: Sample) = if (this == FIVE_HOUR) s.fiveHourReset else s.sevenDayReset
}

/** Samples that belong to one limit window, i.e. share a reset time. */
data class WindowRun(val resetMs: Long, val samples: List<Sample>)

/** One prediction method's burn rate, in percent per hour. */
data class MethodRate(val name: String, val pctPerHour: Double)

data class Prediction(
    val kind: WindowKind,
    val util: Float,
    val resetMs: Long,
    val methods: List<MethodRate>,
    /** Mean of [methods]; 0 when none could be computed. */
    val pctPerHour: Double,
    /** Projected usage from now until the limit or the reset; empty without a forecast. */
    val curve: List<Pair<Long, Double>>,
    /** True when [curve] follows the weekly usage pattern instead of a straight line. */
    val usesPattern: Boolean,
    /** Where usage ends up at reset if the pace holds, capped at 100. */
    val projectedAtReset: Double,
    /** When usage reaches 100% at this pace, or null if it stays under the limit until reset. */
    val limitAtMs: Long?,
    /** Average jump between consecutive fetches when usage went up, in percent. */
    val avgSpikePct: Double?,
) {
    val willHitLimit get() = limitAtMs != null

    /** How many more average-sized bursts fit before the limit. */
    val burstsLeft: Int?
        get() = avgSpikePct?.takeIf { it > 0 }?.let { ((100 - util) / it).toInt().coerceAtLeast(0) }
}

/**
 * Burn-rate forecasts from the fetch history. Every method that has enough data gives a rate,
 * and the forecast uses their mean:
 *  - Window average: usage so far divided by the time since the window opened.
 *  - Recent trend: least-squares slope over the last hour (5-hour) or day (weekly).
 *  - Smoothed rate: exponentially weighted mean of the rate between consecutive fetches.
 *  - Past windows: average rate of earlier windows of the same kind.
 *
 * Usage is not linear: it comes in work sessions and stops at night. Once there is a week of
 * history, the projection follows a usage pattern instead of a straight line: the average rise
 * for each of the 168 hours of the week, scaled by how this window compares to that pattern so
 * far. Near "now" the curve leans on the current pace and hands over to the pattern further out.
 * It is one pass over the history plus at most 168 steps, run only when the app screen opens.
 */
object UsagePredictor {

    /** Reset times drift by milliseconds between fetches; anything closer than this is the same window. */
    private const val SAME_RESET_MS = 30L * 60_000
    private const val EWMA_ALPHA = 0.3
    private const val MS_PER_HOUR = 3_600_000.0

    /** Splits [samples] (oldest first) into consecutive runs that share a reset time. */
    fun windows(samples: List<Sample>, kind: WindowKind): List<WindowRun> {
        val runs = mutableListOf<WindowRun>()
        var cur = mutableListOf<Sample>()
        var curReset = 0L
        for (s in samples) {
            val r = kind.reset(s)
            if (r <= 0L) continue // no active window (nothing used yet)
            if (cur.isNotEmpty() && abs(r - curReset) > SAME_RESET_MS) {
                runs += WindowRun(curReset, cur)
                cur = mutableListOf()
            }
            if (cur.isEmpty()) curReset = r
            cur += s
        }
        if (cur.isNotEmpty()) runs += WindowRun(curReset, cur)
        return runs
    }

    fun predict(
        samples: List<Sample>,
        kind: WindowKind,
        util: Float,
        resetMs: Long,
        now: Long = System.currentTimeMillis(),
    ): Prediction {
        val all = windows(samples, kind)
        val current = all.lastOrNull()?.takeIf { resetMs > 0 && abs(it.resetMs - resetMs) <= SAME_RESET_MS }
        val past = all.filter { it !== current && it.resetMs <= now }
        val inWindow = current?.samples.orEmpty()
        val windowStart = resetMs - kind.lengthMs

        val methods = buildList {
            if (resetMs > now) {
                val elapsed = now - windowStart
                if (elapsed > 60_000) add(MethodRate("Window average", util / (elapsed / MS_PER_HOUR)))
            }
            recentTrend(inWindow, kind, now)?.let { add(MethodRate("Recent trend", it)) }
            smoothed(inWindow, kind)?.let { add(MethodRate("Smoothed rate", it)) }
            pastWindows(past, kind)?.let { add(MethodRate("Past windows", it)) }
        }
        val rate = if (methods.isEmpty()) 0.0 else methods.sumOf { it.pctPerHour } / methods.size

        val profile = if (resetMs > now && methods.isNotEmpty()) hourOfWeekProfile(samples, kind) else null
        val scale = profile?.let { patternScale(it, inWindow, kind, util, windowStart, now) } ?: 1.0
        val curve = mutableListOf<Pair<Long, Double>>()
        var limitAt: Long? = null
        if (resetMs > now && (rate > 0 || profile != null)) {
            var t = now
            var u = util.toDouble()
            curve += t to u
            while (t < resetMs && u < 100) {
                val next = minOf(t + kind.stepMs, resetMs)
                val r = if (profile == null) rate else {
                    // Current pace dominates the near term, the usual pattern the far term.
                    val w = exp(-(t - now).toDouble() / kind.momentumMs)
                    w * rate + (1 - w) * profile[hourOfWeek(t)] * scale
                }
                val du = r * (next - t) / MS_PER_HOUR
                if (u + du >= 100) {
                    limitAt = t + ((100 - u) / r * MS_PER_HOUR).toLong()
                    u = 100.0
                    t = limitAt
                } else {
                    u += du
                    t = next
                }
                curve += t to u
            }
        }
        val projected = curve.lastOrNull()?.second ?: util.toDouble()

        val spikes = all.takeLast(40).flatMap { run ->
            run.samples.zipWithNext { a, b -> (kind.util(b) - kind.util(a)).toDouble() }
        }.filter { it > 0.5 }
        return Prediction(
            kind = kind,
            util = util,
            resetMs = resetMs,
            methods = methods,
            pctPerHour = rate,
            curve = curve,
            usesPattern = profile != null,
            projectedAtReset = projected,
            limitAtMs = if (util >= 100f) now else limitAt,
            avgSpikePct = spikes.takeIf { it.isNotEmpty() }?.average(),
        )
    }

    /**
     * Average rise in percent per hour for each hour of the week (local time), from every
     * fetch-to-fetch increase in the history. Null with less than a week of history.
     */
    private fun hourOfWeekProfile(samples: List<Sample>, kind: WindowKind): DoubleArray? {
        val first = samples.firstOrNull() ?: return null
        val spanWeeks = (samples.last().t - first.t).toDouble() / WindowKind.WEEKLY.lengthMs
        if (spanWeeks < 1.0) return null
        val rise = DoubleArray(168)
        for ((a, b) in samples.zipWithNext()) {
            // A gap or a reset in between says nothing about when the usage happened.
            if (b.t - a.t > 2 * 3_600_000 || abs(kind.reset(b) - kind.reset(a)) > SAME_RESET_MS) continue
            val d = kind.util(b) - kind.util(a)
            if (d > 0) rise[hourOfWeek(b.t)] += d.toDouble()
        }
        for (h in rise.indices) rise[h] /= spanWeeks
        return rise
    }

    /**
     * How busy this window is compared to the pattern: the rise so far over what the pattern
     * expects for the same hours. Clamped so one odd window can't blow the forecast up.
     */
    private fun patternScale(
        profile: DoubleArray, inWindow: List<Sample>, kind: WindowKind,
        util: Float, windowStart: Long, now: Long,
    ): Double {
        var expected = 0.0
        var t = windowStart
        while (t < now) {
            val next = minOf(t + 3_600_000, now)
            expected += profile[hourOfWeek(t)] * (next - t) / MS_PER_HOUR
            t = next
        }
        if (expected < 1.0 || inWindow.isEmpty()) return 1.0
        return (util / expected).coerceIn(0.25, 4.0)
    }

    private fun hourOfWeek(t: Long): Int {
        val local = t + TimeZone.getDefault().getOffset(t)
        // Epoch day 0 was a Thursday; shift so bin 0 is Monday 00:00.
        val hours = Math.floorDiv(local, 3_600_000L) + 3 * 24
        return Math.floorMod(hours, 168L).toInt()
    }

    private fun recentTrend(s: List<Sample>, kind: WindowKind, now: Long): Double? {
        val pts = s.filter { it.t >= now - kind.trendLookbackMs }
        if (pts.size < 2 || pts.last().t - pts.first().t < 5 * 60_000) return null
        val xs = pts.map { (it.t - pts.first().t) / MS_PER_HOUR }
        val ys = pts.map { kind.util(it).toDouble() }
        val mx = xs.average()
        val my = ys.average()
        val den = xs.sumOf { (it - mx) * (it - mx) }
        if (den == 0.0) return null
        val slope = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / den
        return slope.coerceAtLeast(0.0)
    }

    private fun smoothed(s: List<Sample>, kind: WindowKind): Double? {
        var ewma: Double? = null
        for ((a, b) in s.zipWithNext()) {
            val hours = (b.t - a.t) / MS_PER_HOUR
            if (hours <= 0) continue
            val r = ((kind.util(b) - kind.util(a)) / hours).coerceAtLeast(0.0)
            ewma = ewma?.let { EWMA_ALPHA * r + (1 - EWMA_ALPHA) * it } ?: r
        }
        return ewma
    }

    /**
     * Mean rate of earlier windows: peak usage over the time it took to reach it. A weekly
     * window always runs its full length, so its peak is spread over the whole week.
     */
    private fun pastWindows(past: List<WindowRun>, kind: WindowKind): Double? {
        val rates = past.takeLast(if (kind == WindowKind.FIVE_HOUR) 20 else 12).mapNotNull { run ->
            val peak = run.samples.maxByOrNull { kind.util(it) } ?: return@mapNotNull null
            val peakUtil = kind.util(peak).toDouble()
            if (peakUtil <= 0) return@mapNotNull null
            val start = run.resetMs - kind.lengthMs
            val hours = if (kind == WindowKind.WEEKLY) kind.lengthMs / MS_PER_HOUR
            else (peak.t - start) / MS_PER_HOUR
            if (hours <= 0) null else peakUtil / hours
        }
        return rates.takeIf { it.isNotEmpty() }?.average()
    }
}
