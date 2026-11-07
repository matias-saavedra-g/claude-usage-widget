package com.usage.claudewidget.widget

import android.content.Context
import android.content.SharedPreferences

/** How usage is drawn. Each style adapts its layout to the widget's size. */
enum class WidgetStyle(val label: String, val blurb: String) {
    BARS("Bars", "Horizontal progress bars"),
    COLUMNS("Columns", "Vertical bars, good for tall widgets"),
    RINGS("Rings", "Circular gauges"),
    NUMBERS("Numbers", "Large percentages, no graphics"),
}

/** Which limits the widget shows. */
enum class WidgetMetrics(val label: String) {
    BOTH("5-hour and weekly"),
    FIVE_HOUR("5-hour only"),
    WEEKLY("Weekly only"),
}

/** Widget container background. */
enum class WidgetBackground(val label: String) {
    SOLID("Solid"),
    TRANSLUCENT("Translucent"),
    NONE("None"),
}

data class WidgetConfig(
    val style: WidgetStyle = WidgetStyle.BARS,
    val metrics: WidgetMetrics = WidgetMetrics.BOTH,
    val background: WidgetBackground = WidgetBackground.SOLID,
) {
    companion object {
        val DEFAULT = WidgetConfig()
    }
}

/**
 * Per-widget settings, keyed by appWidgetId. A widget without saved settings (for example
 * one placed before this feature existed) uses [WidgetConfig.DEFAULT], which matches the
 * original look.
 */
class WidgetConfigStore private constructor(private val prefs: SharedPreferences) {

    fun get(appWidgetId: Int): WidgetConfig = WidgetConfig(
        style = enumOr(prefs.getString(key(appWidgetId, "style"), null), WidgetConfig.DEFAULT.style),
        metrics = enumOr(prefs.getString(key(appWidgetId, "metrics"), null), WidgetConfig.DEFAULT.metrics),
        background = enumOr(prefs.getString(key(appWidgetId, "bg"), null), WidgetConfig.DEFAULT.background),
    )

    fun set(appWidgetId: Int, config: WidgetConfig) {
        prefs.edit()
            .putString(key(appWidgetId, "style"), config.style.name)
            .putString(key(appWidgetId, "metrics"), config.metrics.name)
            .putString(key(appWidgetId, "bg"), config.background.name)
            .apply()
    }

    fun remove(appWidgetIds: IntArray) {
        val e = prefs.edit()
        for (id in appWidgetIds) {
            e.remove(key(id, "style")).remove(key(id, "metrics")).remove(key(id, "bg"))
        }
        e.apply()
    }

    private fun key(id: Int, name: String) = "w${id}_$name"

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: fallback

    companion object {
        @Volatile private var instance: WidgetConfigStore? = null

        fun get(context: Context): WidgetConfigStore = instance ?: synchronized(this) {
            instance ?: WidgetConfigStore(
                context.applicationContext.getSharedPreferences("widget_config", Context.MODE_PRIVATE)
            ).also { instance = it }
        }
    }
}
