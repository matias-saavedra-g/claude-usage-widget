package com.usage.claudewidget.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.lifecycle.lifecycleScope
import com.usage.claudewidget.ui.AppTheme
import kotlinx.coroutines.launch

/**
 * Per-widget style screen. The launcher opens it from the widget's long-press menu
 * ("Reconfigure" / widget settings). With `configuration_optional`, Android 12+ skips it when
 * the widget is placed; older versions open it right away.
 *
 * Every choice is saved and applied to the placed widget immediately, and the result is
 * RESULT_OK from the start, so leaving with Back keeps the widget with its current settings.
 */
class WidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // The activity is exported for the launcher; only accept IDs of this app's widget.
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)?.provider
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID ||
            provider != ComponentName(this, UsageWidgetReceiver::class.java)
        ) {
            finish()
            return
        }
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))

        // The host sends no update for a widget that has a configuration activity, so draw it now.
        lifecycleScope.launch { applyToWidget() }

        val store = WidgetConfigStore.get(this)
        val actualSize = widgetSize(this, appWidgetId)
        setContent {
            AppTheme {
                ConfigScreen(
                    initial = store.get(appWidgetId),
                    actualSize = actualSize,
                    onChange = { config ->
                        store.set(appWidgetId, config)
                        lifecycleScope.launch { applyToWidget() }
                    },
                    onDone = {
                        lifecycleScope.launch {
                            applyToWidget()
                            finish()
                        }
                    },
                )
            }
        }
    }

    private suspend fun applyToWidget() {
        runCatching {
            val glanceId = GlanceAppWidgetManager(this).getGlanceIdBy(appWidgetId)
            UsageWidget().update(this, glanceId)
        }
    }

    companion object {
        /** Current size of a placed widget in the current orientation, or null if the launcher doesn't say. */
        fun widgetSize(context: Context, appWidgetId: Int): DpSize? {
            val o = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
            val minW = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val maxW = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
            val minH = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            val maxH = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            if (minW <= 0 || minH <= 0) return null
            // Portrait: narrowest width, tallest height. Landscape: the opposite.
            val portrait = context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
            return if (portrait) DpSize(minW.dp, maxH.coerceAtLeast(minH).dp)
            else DpSize(maxW.coerceAtLeast(minW).dp, minH.dp)
        }
    }
}

/** Shapes to preview, in dp, roughly matching common launcher cells. */
private val PREVIEW_SIZES = listOf(
    "1×1" to DpSize(68.dp, 68.dp),
    "2×1" to DpSize(150.dp, 68.dp),
    "4×1" to DpSize(300.dp, 68.dp),
    "1×2" to DpSize(68.dp, 150.dp),
    "2×2" to DpSize(150.dp, 150.dp),
    "4×2" to DpSize(300.dp, 150.dp),
    "2×3" to DpSize(150.dp, 230.dp),
    "4×3" to DpSize(300.dp, 230.dp),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigScreen(
    initial: WidgetConfig,
    actualSize: DpSize?,
    onChange: (WidgetConfig) -> Unit,
    onDone: () -> Unit,
) {
    var config by remember { mutableStateOf(initial) }
    fun update(c: WidgetConfig) {
        config = c
        onChange(c)
    }
    val sizes = remember(actualSize) {
        listOfNotNull(actualSize?.let { "This widget" to it }) + PREVIEW_SIZES
    }
    var previewSize by remember { mutableStateOf(sizes.first()) }

    Scaffold(topBar = { TopAppBar(title = { Text("Widget style") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            WidgetPreview(config, previewSize.second)
            ChipRow(sizes, previewSize, label = { it.first }) { previewSize = it }

            OptionCard("Style") {
                Column(Modifier.selectableGroup()) {
                    WidgetStyle.entries.forEach { style ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = config.style == style,
                                    onClick = { update(config.copy(style = style)) },
                                    role = Role.RadioButton,
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = config.style == style, onClick = null)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(style.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    style.blurb,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            OptionCard("Show") {
                ChipRow(WidgetMetrics.entries, config.metrics, label = { it.label }) {
                    update(config.copy(metrics = it))
                }
                Text(
                    "Showing one limit leaves more room for it, which helps on 1×1 widgets.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OptionCard("Background") {
                ChipRow(WidgetBackground.entries, config.background, label = { it.label }) {
                    update(config.copy(background = it))
                }
                if (config.background == WidgetBackground.NONE) {
                    Text(
                        "Text follows the system light or dark theme, so it is easiest to read on a plain wallpaper.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                "Change this later by long-pressing the widget and choosing its settings. Resize the widget to see more or less detail.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            Box(Modifier.height(16.dp))
        }
    }
}

private val SAMPLE_STATE = WidgetState(
    needsLogin = false, hasData = true,
    fiveHourPct = 42, fiveHourResets = "2h 13m",
    sevenDayPct = 18, sevenDayResets = "4d 6h",
    stale = false, updatedAt = "",
)

/** Renders the real widget content with Glance at [size], over a wallpaper-like backdrop. */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@Composable
private fun WidgetPreview(config: WidgetConfig, size: DpSize) {
    val context = LocalContext.current
    var views by remember { mutableStateOf<RemoteViews?>(null) }
    LaunchedEffect(config, size) {
        views = runCatching {
            // Real numbers when available; sample numbers before the first fetch (or if the
            // stored data can't be read), so the preview always shows the layout.
            val state = runCatching { UsageWidget.readState(context) }.getOrNull()
                ?.takeIf { it.hasData && !it.needsLogin }
                ?: SAMPLE_STATE
            GlanceRemoteViews().compose(context, size) {
                GlanceTheme { UsageWidgetContent(state, config) }
            }.remoteViews
        }.onFailure { android.util.Log.w("WidgetConfig", "Preview failed", it) }.getOrNull()
    }
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(270.dp)
            .background(
                Brush.linearGradient(listOf(scheme.primaryContainer, scheme.tertiaryContainer, scheme.secondary)),
                RoundedCornerShape(24.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        val rv = views
        if (rv != null) {
            AndroidView(
                modifier = Modifier.size(size.width, size.height),
                factory = { ctx -> TouchBlockingFrame(ctx) },
                update = { frame ->
                    frame.removeAllViews()
                    runCatching { frame.addView(rv.apply(frame.context, frame)) }
                },
            )
        }
    }
}

/** Swallows taps so the preview doesn't fire the widget's refresh action. */
private class TouchBlockingFrame(context: Context) : FrameLayout(context) {
    override fun onInterceptTouchEvent(ev: MotionEvent?) = true
}

@Composable
private fun OptionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** Single-choice chips that wrap onto more lines instead of running off screen. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) })
        }
    }
}
