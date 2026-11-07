package com.usage.claudewidget.widget

import android.content.Context
import android.text.format.DateFormat
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import com.usage.claudewidget.data.AuthState
import com.usage.claudewidget.data.Storage
import java.util.Date
import kotlin.math.roundToInt

class UsageWidget : GlanceAppWidget() {

    // One layout per size class (see WidgetSizes); Glance picks the largest that fits.
    override val sizeMode = SizeMode.Responsive(WidgetSizes.responsive)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        provideContent {
            GlanceTheme {
                // Read inside the composition so updates pick up new data and settings.
                UsageWidgetContent(readState(context), WidgetConfigStore.get(context).get(appWidgetId))
            }
        }
    }

    companion object {
        suspend fun updateAll(context: Context) = UsageWidget().updateAll(context)

        fun readState(context: Context): WidgetState {
            val s = Storage.get(context)
            val now = System.currentTimeMillis()
            return WidgetState(
                needsLogin = !s.isLoggedIn || s.authState == AuthState.NEEDS_LOGIN,
                hasData = s.hasSnapshot,
                fiveHourPct = s.fiveHourUtil.coerceAtLeast(0f).roundToInt(),
                fiveHourResets = TimeFmt.resetsIn(s.fiveHourReset, now),
                sevenDayPct = s.sevenDayUtil.coerceAtLeast(0f).roundToInt(),
                sevenDayResets = TimeFmt.resetsIn(s.sevenDayReset, now),
                stale = TimeFmt.isStale(s.fetchedAt, now),
                updatedAt = if (s.fetchedAt > 0L) DateFormat.getTimeFormat(context).format(Date(s.fetchedAt)) else "",
            )
        }
    }
}
