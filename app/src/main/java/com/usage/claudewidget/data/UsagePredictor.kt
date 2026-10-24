package com.usage.claudewidget.data

import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

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
    /** Names of the models averaged into [curve]. */
    val models: List<String>,
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
 * Those rates describe the current pace. The projected curve averages up to two models with
 * equal weights, since usage is not linear (work sessions, quiet nights):
 *  - Pace into pattern: starts at the current pace and hands over to the recency-weighted
 *    rise for each of the 168 hours of the week, scaled to how busy this window is so far.
 *    Needs a week of history; until then it is a straight line at the current pace.
 *  - Similar past windows: the few past windows that were in the same state at the same
 *    point, replayed forward (analog / nearest-neighbour forecasting).
 * Older history counts less (4-week half-life). Everything is a few passes over at most
 * [FORECAST_HISTORY_MS] of rows, run only when the app screen opens, never in the background.
 */
object UsagePredictor {

    /** History older than this weighs under 2% and is not read for forecasts. */
    const val FORECAST_HISTORY_MS = 26L * 7 * 24 * 3_600_000

    /** Reset times drift by milliseconds between fetches; anything closer than this is the same window. */
    private const val SAME_RESET_MS = 30L * 60_000
    private const val EWMA_ALPHA = 0.3
    private const val HALF_LIFE_MS = 4L * 7 * 24 * 3_600_000
    private const val ANALOGS = 5
    private const val MIN_ANALOGS = 3
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

        // Each model gives a cumulative usage curve on the same time grid; the forecast is
        // their plain average (equal weights tend to beat fitted ones on little data).
        val grid = if (resetMs > now && methods.isNotEmpty()) timeGrid(now, resetMs, kind) else emptyList()
        val models = linkedMapOf<String, DoubleArray>()
        if (grid.isNotEmpty()) {
            val profile = hourOfWeekProfile(samples, kind, now)
            if (profile != null) {
                val scale = patternScale(profile, util, windowStart, now)
                models["Current pace into usual weekly pattern"] =
                    integrate(grid, util) { t -> paceThenPattern(t, now, rate, profile, scale, kind) }
            } else if (rate > 0) {
                models["Current pace"] = integrate(grid, util) { rate }
            }
            analogCurve(past, inWindow, kind, util, windowStart, now, grid)
                ?.let { models["Similar past windows"] = it }
        }
        val curve = mutableListOf<Pair<Long, Double>>()
        var limitAt: Long? = null
        if (models.isNotEmpty()) {
            for (i in grid.indices) {
                val u = models.values.sumOf { it[i] } / models.size
                if (u >= 100 && i > 0) {
                    // Interpolate the crossing inside the last step.
                    val (t0, u0) = curve.last()
                    val f = ((100 - u0) / (u - u0)).coerceIn(0.0, 1.0)
                    limitAt = t0 + ((grid[i] - t0) * f).toLong()
                    curve += limitAt to 100.0
                    break
                }
                curve += grid[i] to u.coerceAtMost(100.0)
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
            models = models.keys.toList(),
            projectedAtReset = projected,
            limitAtMs = if (util >= 100f) now else limitAt,
            avgSpikePct = spikes.takeIf { it.isNotEmpty() }?.average(),
        )
    }

    /** Recency weight: halves every [HALF_LIFE_MS], so the forecast follows changing habits. */
    private fun recency(t: Long, now: Long) = 0.5.pow((now - t).toDouble() / HALF_LIFE_MS)

    /**
     * Rise in percent per hour for each hour of the week (local time), recency-weighted. Each
     * hour is divided by the time actually observed in it, so nights with the phone off or
     * retention gaps don't count as zero usage. Null with less than a week of history.
     */
    private fun hourOfWeekProfile(samples: List<Sample>, kind: WindowKind, now: Long): DoubleArray? {
        val first = samples.firstOrNull() ?: return null
        if (samples.last().t - first.t < WindowKind.WEEKLY.lengthMs) return null
        val rise = DoubleArray(168)
        val seen = DoubleArray(168)
        for ((a, b) in samples.zipWithNext()) {
            // A gap or a reset in between says nothing about when the usage happened.
            if (b.t - a.t > 2 * 3_600_000 || abs(kind.reset(b) - kind.reset(a)) > SAME_RESET_MS) continue
            val w = recency(b.t, now)
            val h = hourOfWeek(b.t)
            seen[h] += w * (b.t - a.t) / MS_PER_HOUR
            val d = kind.util(b) - kind.util(a)
            if (d > 0) rise[h] += w * d
        }
        val totalSeen = seen.sum()
        if (totalSeen <= 0) return null
        val overall = rise.sum() / totalSeen
        // An hour never observed falls back to the overall average rate.
        return DoubleArray(168) { h -> if (seen[h] > 0.05) rise[h] / seen[h] else overall }
    }

    /**
     * How busy this window is compared to the pattern: the rise so far over what the pattern
     * expects for the same hours. Clamped so one odd window can't blow the forecast up.
     */
    private fun patternScale(profile: DoubleArray, util: Float, windowStart: Long, now: Long): Double {
        var expected = 0.0
        var t = windowStart
        while (t < now) {
            val next = minOf(t + 3_600_000, now)
            expected += profile[hourOfWeek(t)] * (next - t) / MS_PER_HOUR
            t = next
        }
        if (expected < 1.0) return 1.0
        return (util / expected).coerceIn(0.25, 4.0)
    }

    /** Current pace dominates the near term, the usual pattern the far term. */
    private fun paceThenPattern(
        t: Long, now: Long, rate: Double, profile: DoubleArray, scale: Double, kind: WindowKind,
    ): Double {
        val w = exp(-(t - now).toDouble() / kind.momentumMs)
        return w * rate + (1 - w) * profile[hourOfWeek(t)] * scale
    }

    private fun timeGrid(now: Long, resetMs: Long, kind: WindowKind): List<Long> {
        val grid = mutableListOf(now)
        while (grid.last() < resetMs) grid += minOf(grid.last() + kind.stepMs, resetMs)
        return grid
    }

    /** Cumulative usage on [grid], starting at [util], for a rate in percent per hour. */
    private fun integrate(grid: List<Long>, util: Float, rateAt: (Long) -> Double): DoubleArray {
        val out = DoubleArray(grid.size)
        out[0] = util.toDouble()
        for (i in 1 until grid.size) {
            out[i] = out[i - 1] + rateAt(grid[i - 1]).coerceAtLeast(0.0) * (grid[i] - grid[i - 1]) / MS_PER_HOUR
        }
        return out
    }

    /**
     * Analog forecast: finds past windows that were in a similar state at the same point of
     * the window (usage now and usage one trend-lookback ago), and replays how they continued.
     * The nearest [ANALOGS] are averaged, weighted by closeness and recency.
     */
    private fun analogCurve(
        past: List<WindowRun>, inWindow: List<Sample>, kind: WindowKind,
        util: Float, windowStart: Long, now: Long, grid: List<Long>,
    ): DoubleArray? {
        val elapsed = now - windowStart
        val back = elapsed - kind.trendLookbackMs
        val nowBack = utilAt(inWindow, windowStart, windowStart + back, kind)
        val candidates = past.takeLast(if (kind == WindowKind.FIVE_HOUR) 120 else 26).mapNotNull { run ->
            val start = run.resetMs - kind.lengthMs
            // The run must have been observed at least up to the matching point.
            if (run.samples.last().t < start + elapsed) return@mapNotNull null
            val a = utilAt(run.samples, start, start + elapsed, kind)
            val b = utilAt(run.samples, start, start + back, kind)
            val dist = abs(a - util) + abs(b - nowBack)
            Triple(run, a, dist)
        }.sortedBy { it.third }.take(ANALOGS)
        if (candidates.size < MIN_ANALOGS) return null

        val weights = candidates.map { (run, _, d) -> recency(run.resetMs, now) / (1.0 + d) }
        val total = weights.sum()
        if (total <= 0) return null
        return DoubleArray(grid.size) { i ->
            val off = grid[i] - now
            util + candidates.indices.sumOf { k ->
                val (run, a, _) = candidates[k]
                val start = run.resetMs - kind.lengthMs
                weights[k] * (utilAt(run.samples, start, start + elapsed + off, kind) - a).coerceAtLeast(0.0)
            } / total
        }
    }

    /** Usage at [t] in a window that opened at [start] at 0%, interpolating between fetches. */
    private fun utilAt(samples: List<Sample>, start: Long, t: Long, kind: WindowKind): Double {
        var prevT = start
        var prevU = 0.0
        if (t <= start) return 0.0
        for (s in samples) {
            val u = kind.util(s).toDouble()
            if (s.t >= t) {
                if (s.t == prevT) return u
                return prevU + (u - prevU) * (t - prevT) / (s.t - prevT)
            }
            prevT = s.t
            prevU = u
        }
        return prevU
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
