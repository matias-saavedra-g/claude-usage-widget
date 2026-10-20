package com.usage.claudewidget.widget

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import com.usage.claudewidget.ui.ClaudeApp
import com.usage.claudewidget.work.RefreshScheduler

/**
 * Invisible trampoline for widget taps (Theme.NoDisplay, finishes in onCreate).
 * A widget tap may always start an activity, while a broadcast from the widget may not
 * start another app in the background, so the triple tap has to go through here.
 *  - 1st tap: refresh now.
 *  - 3rd tap: open the Claude app.
 */
class TapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (TapCounter.register(SystemClock.uptimeMillis())) {
            1 -> RefreshScheduler.refreshNow(this)
            3 -> {
                TapCounter.reset()
                ClaudeApp.open(this)
            }
        }
        finish()
    }
}

/** In-memory tap grouping; each widget tap starts its own [TapActivity]. */
object TapCounter {
    /** Max gap between consecutive taps of one gesture. */
    private const val WINDOW_MS = 700L

    private var count = 0
    private var lastTapAt = 0L

    /** Returns this tap's position in the current gesture (1, 2, 3, ...). */
    @Synchronized
    fun register(now: Long): Int {
        if (now - lastTapAt > WINDOW_MS) count = 0
        lastTapAt = now
        return ++count
    }

    @Synchronized
    fun reset() {
        count = 0
    }
}
