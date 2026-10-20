package com.usage.claudewidget.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.usage.claudewidget.data.Const

/** Opens the official Claude Android app, or claude.ai in the browser if it is not installed. */
object ClaudeApp {
    const val PACKAGE = "com.anthropic.claude"

    fun launchIntent(context: Context): Intent =
        (context.packageManager.getLaunchIntentForPackage(PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse(Const.BASE)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun open(context: Context) {
        try {
            context.startActivity(launchIntent(context))
        } catch (_: ActivityNotFoundException) {
            // No app and no browser: nothing sensible to do.
        }
    }
}
