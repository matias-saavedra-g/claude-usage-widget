package com.usage.claudewidget.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Opens the widget style screen for a placed widget, writes a screenshot to
 * app/build/widget-renders/, and checks that choices are saved per widget.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class WidgetConfigRenderTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val provider = ComponentName(context, UsageWidgetReceiver::class.java)

    private fun placeWidget(): Int {
        val manager = AppWidgetManager.getInstance(context)
        val shadow = shadowOf(manager)
        shadow.addInstalledProvider(AppWidgetProviderInfo().apply { this.provider = this@WidgetConfigRenderTest.provider })
        val id = 42
        shadow.bindAppWidgetId(id, provider)
        return id
    }

    @Test
    fun rendersAndSaves() {
        for (night in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers(if (night) "+night" else "+notnight")
            val id = placeWidget()
            WidgetConfigStore.get(context).set(id, WidgetConfig(WidgetStyle.RINGS))
            val intent = Intent(context, WidgetConfigActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            val controller = Robolectric.buildActivity(WidgetConfigActivity::class.java, intent).setup()
            val activity = controller.get()
            assertTrue("activity finished early", !activity.isFinishing)

            // Let the preview composition finish.
            repeat(40) {
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
                Thread.sleep(25)
            }
            val root: View = activity.window.decorView
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            File("build/widget-renders").mkdirs()
            File("build/widget-renders/config-${if (night) "dark" else "light"}.png").outputStream().use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            assertEquals(WidgetStyle.RINGS, WidgetConfigStore.get(context).get(id).style)
            assertEquals(android.app.Activity.RESULT_OK, shadowOf(activity).resultCode)
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun rejectsForeignWidgetIds() {
        val intent = Intent(context, WidgetConfigActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 7)
        val activity = Robolectric.buildActivity(WidgetConfigActivity::class.java, intent).setup().get()
        assertTrue(activity.isFinishing)
    }

    @Test
    fun configStoreDefaultsAndRemoval() {
        val store = WidgetConfigStore.get(context)
        assertEquals(WidgetConfig.DEFAULT, store.get(1234))
        store.set(1234, WidgetConfig(WidgetStyle.NUMBERS, WidgetMetrics.WEEKLY, WidgetBackground.NONE))
        assertEquals(WidgetConfig(WidgetStyle.NUMBERS, WidgetMetrics.WEEKLY, WidgetBackground.NONE), store.get(1234))
        store.remove(intArrayOf(1234))
        assertEquals(WidgetConfig.DEFAULT, store.get(1234))
    }
}
