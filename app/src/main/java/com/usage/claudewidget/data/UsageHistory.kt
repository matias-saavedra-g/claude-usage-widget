package com.usage.claudewidget.data

import android.content.Context
import java.io.File
import java.io.Writer

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
 * by the UI, so it costs nothing at runtime. How much of the whole log is kept is up to the
 * user's [Retention]; both files are included in [exportCsv].
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

    /** Total bytes on disk, live plus archive. */
    @Synchronized
    fun sizeBytes(): Long = archive.length() + live.length()

    /** True when [enforce] with [r] would delete at least one row. */
    @Synchronized
    fun wouldDrop(r: Retention, now: Long = System.currentTimeMillis()): Boolean {
        if (r.maxBytes > 0 && sizeBytes() > r.maxBytes) return true
        if (r.maxAgeMs <= 0) return false
        val oldest = listOf(archive, live).firstNotNullOfOrNull { f ->
            if (f.exists()) f.bufferedReader().use { it.readLine() }?.let(::decode) else null
        }
        return oldest != null && oldest.t < now - r.maxAgeMs
    }

    /**
     * Drops rows older than [Retention.maxAgeMs], then the oldest rows until both files fit in
     * [Retention.maxBytes]. Reads nothing when no limit is set or both are already met.
     */
    @Synchronized
    fun enforce(r: Retention, now: Long = System.currentTimeMillis()) {
        val cutoff = if (r.maxAgeMs > 0) now - r.maxAgeMs else Long.MIN_VALUE
        val oldest = listOf(archive, live).firstNotNullOfOrNull { f ->
            if (f.exists()) f.bufferedReader().use { it.readLine() }?.let(::decode) else null
        }
        val tooOld = oldest != null && oldest.t < cutoff
        val tooBig = r.maxBytes > 0 && sizeBytes() > r.maxBytes
        if (!tooOld && !tooBig) return

        fun rows(f: File) = if (f.exists()) {
            f.readLines().filter { (decode(it)?.t ?: Long.MIN_VALUE) >= cutoff }
        } else emptyList()
        val oldRows = ArrayDeque(rows(archive))
        val liveRows = ArrayDeque(rows(live))
        if (r.maxBytes > 0) {
            var size = (oldRows.sumOf { it.length + 1L }) + liveRows.sumOf { it.length + 1L }
            while (size > r.maxBytes && (oldRows.isNotEmpty() || liveRows.isNotEmpty())) {
                val dropped = oldRows.removeFirstOrNull() ?: liveRows.removeFirst()
                size -= dropped.length + 1
            }
        }
        write(archive, oldRows)
        write(live, liveRows)
    }

    /** Writes every stored row, archive and live, oldest first, as CSV with a header. */
    @Synchronized
    fun exportCsv(out: Writer) {
        out.write("timestamp_utc,epoch_ms,five_hour_pct,five_hour_resets_utc,weekly_pct,weekly_resets_utc\n")
        val all = listOf(archive, live).filter { it.exists() }
            .flatMap { it.readLines() }.mapNotNull(::decode).sortedBy { it.t }
        for (s in all) {
            out.write(
                "${iso(s.t)},${s.t},${s.fiveHour},${iso(s.fiveHourReset)},${s.sevenDay},${iso(s.sevenDayReset)}\n"
            )
        }
        out.flush()
    }

    private fun iso(ms: Long) = if (ms > 0) java.time.Instant.ofEpochMilli(ms).toString() else ""

    private fun write(f: File, rows: Collection<String>) {
        if (rows.isEmpty()) { if (f.exists()) f.writeText(""); return }
        f.writeText(rows.joinToString("\n", postfix = "\n"))
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
        /** How long rows stay in the live file the charts read. */
        const val RETENTION_MS = 12L * 7 * 24 * 3_600_000

        @Volatile private var instance: UsageHistory? = null

        fun get(context: Context): UsageHistory = instance ?: synchronized(this) {
            instance ?: UsageHistory(context.applicationContext.filesDir).also { instance = it }
        }
    }
}

/** User-chosen storage limits for the history; 0 means no limit. */
data class Retention(val maxAgeMs: Long, val maxBytes: Long)
