package com.usage.claudewidget.data

import kotlin.math.abs

/** The two limits the usage endpoint reports. */
enum class WindowKind(val label: String, val lengthMs: Long, val trendLookbackMs: Long) {
    FIVE_HOUR("5-hour", 5L * 3_600_000, 60L * 60_000),
    WEEKLY("Weekly", 7L * 24 * 3_600_000, 24L * 3_600_000);

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

        val hoursLeft = ((resetMs - now) / MS_PER_HOUR).coerceAtLeast(0.0)
        val projected = (util + rate * hoursLeft).coerceAtMost(100.0)
        val limitAt = if (rate > 0 && resetMs > now) {
            val hoursToLimit = (100 - util) / rate
            (now + (hoursToLimit * MS_PER_HOUR).toLong()).takeIf { it < resetMs }
        } else null

        val spikes = all.takeLast(40).flatMap { run ->
            run.samples.zipWithNext { a, b -> (kind.util(b) - kind.util(a)).toDouble() }
        }.filter { it > 0.5 }
        return Prediction(
            kind = kind,
            util = util,
            resetMs = resetMs,
            methods = methods,
            pctPerHour = rate,
            projectedAtReset = projected,
            limitAtMs = if (util >= 100f) now else limitAt,
            avgSpikePct = spikes.takeIf { it.isNotEmpty() }?.average(),
        )
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
