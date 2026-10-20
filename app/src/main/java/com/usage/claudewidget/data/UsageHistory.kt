package com.usage.claudewidget.data

import android.content.Context
import java.io.File

/** One recorded fetch. Utilizations are percent, 0..100; times are epoch ms. */
data class Sample(
    val t: Long,
    val fiveHour: Float,
    val fiveHourReset: Long,
    val sevenDay: Float,
    val sevenDayReset: Long,
)

/**
 * Append-only CSV log of every successful fetch, used for the charts and predictions.
 *
 * A row is ~50 bytes and the background job writes about 96 rows a day, so the 12 weeks kept
 * live come to roughly 400 KB. Older rows move to an archive file that is never read back
 * by the UI, so it costs nothing at runtime.
 */
class UsageHistory private constructor(dir: File) {

    private val live = File(dir, "usage_history.csv")
    private val archive = File(dir, "usage_history_archive.csv")

    @Synchronized
    fun append(s: UsageSnapshot) {
        val row = Sample(
            t = s.fetchedAtEpochMs,
            fiveHour = s.fiveHour.utilization,
            fiveHourReset = s.fiveHour.resetsAtEpochMs,
            sevenDay = s.sevenDay.utilization,
            sevenDayReset = s.sevenDay.resetsAtEpochMs,
        )
        live.appendText(encode(row) + "\n")
        archiveOld(s.fetchedAtEpochMs)
    }

    /** All live samples, oldest first. */
    @Synchronized
    fun load(): List<Sample> {
        if (!live.exists()) return emptyList()
        return live.readLines().mapNotNull(::decode).sortedBy { it.t }
    }

    /** Moves rows older than [RETENTION_MS] to the archive; cheap when there is nothing to move. */
    private fun archiveOld(now: Long) {
        val cutoff = now - RETENTION_MS
        val first = live.bufferedReader().use { it.readLine() }?.let(::decode) ?: return
        if (first.t >= cutoff) return
        val (old, keep) = live.readLines().filter { it.isNotBlank() }
            .partition { (decode(it)?.t ?: 0L) < cutoff }
        archive.appendText(old.joinToString("\n", postfix = "\n"))
        live.writeText(if (keep.isEmpty()) "" else keep.joinToString("\n", postfix = "\n"))
    }

    private fun encode(s: Sample) =
        "${s.t},${s.fiveHour},${s.fiveHourReset},${s.sevenDay},${s.sevenDayReset}"

    private fun decode(line: String): Sample? {
        val p = line.split(',')
        if (p.size != 5) return null
        return try {
            Sample(p[0].toLong(), p[1].toFloat(), p[2].toLong(), p[3].toFloat(), p[4].toLong())
        } catch (_: NumberFormatException) {
            null
        }
    }

    companion object {
        const val RETENTION_MS = 12L * 7 * 24 * 3_600_000

        @Volatile private var instance: UsageHistory? = null

        fun get(context: Context): UsageHistory = instance ?: synchronized(this) {
            instance ?: UsageHistory(context.applicationContext.filesDir).also { instance = it }
        }
    }
}
