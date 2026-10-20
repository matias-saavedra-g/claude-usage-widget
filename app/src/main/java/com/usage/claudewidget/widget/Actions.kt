package com.usage.claudewidget.widget

import android.content.Context
import android.os.SystemClock
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.usage.claudewidget.ui.ClaudeApp
import com.usage.claudewidget.work.RefreshScheduler
import kotlinx.coroutines.delay

/**
 * Tap action when the widget is healthy. Taps close together are grouped:
 *  - 1 tap (or 2): refresh now, once no further tap arrives within [TapCounter.WINDOW_MS].
 *  - 3 taps: open the Claude app immediately.
 */
class RefreshAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val tap = TapCounter.register(SystemClock.uptimeMillis())
        if (tap.count >= 3) {
            TapCounter.reset()
            ClaudeApp.open(context)
            return
        }
        // Wait to see whether this tap is the start of a triple tap.
        delay(TapCounter.WINDOW_MS)
        if (TapCounter.isLatest(tap.seq)) {
            TapCounter.reset()
            RefreshScheduler.refreshNow(context)
        }
    }
}

/** In-memory tap grouping; each widget tap arrives as its own broadcast. */
object TapCounter {
    /** Max gap between consecutive taps of one gesture. */
    const val WINDOW_MS = 600L

    data class Tap(val seq: Long, val count: Int)

    private var seq = 0L
    private var count = 0
    private var lastTapAt = 0L

    @Synchronized
    fun register(now: Long): Tap {
        if (now - lastTapAt > WINDOW_MS) count = 0
        lastTapAt = now
        count++
        seq++
        return Tap(seq, count)
    }

    @Synchronized
    fun isLatest(tapSeq: Long): Boolean = seq == tapSeq && count > 0

    @Synchronized
    fun reset() {
        count = 0
    }
}
