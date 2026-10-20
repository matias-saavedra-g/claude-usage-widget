package com.usage.claudewidget.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.WebSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.usage.claudewidget.auth.LoginActivity
import com.usage.claudewidget.data.AuthState
import com.usage.claudewidget.data.FetchResult
import com.usage.claudewidget.data.Storage
import com.usage.claudewidget.data.UsageRepository
import com.usage.claudewidget.widget.TimeFmt
import com.usage.claudewidget.widget.UsageWidget
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                SetupScreen()
            }
        }
    }
}

/** Snapshot of Storage for the UI; re-read after every action that can change it. */
private data class UiStatus(
    val signedIn: Boolean,
    val orgId: String?,
    val hasData: Boolean,
    val fiveHourPct: Int,
    val fiveHourResets: String,
    val sevenDayPct: Int,
    val sevenDayResets: String,
)

private fun readStatus(s: Storage): UiStatus = UiStatus(
    signedIn = s.isLoggedIn && s.authState == AuthState.OK,
    orgId = s.orgId,
    hasData = s.hasSnapshot,
    fiveHourPct = s.fiveHourUtil.coerceAtLeast(0f).roundToInt(),
    fiveHourResets = TimeFmt.resetsIn(s.fiveHourReset),
    sevenDayPct = s.sevenDayUtil.coerceAtLeast(0f).roundToInt(),
    sevenDayResets = TimeFmt.resetsIn(s.sevenDayReset),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupScreen() {
    val context = LocalContext.current
    val storage = remember { Storage.get(context) }
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf(readStatus(storage)) }
    var message by remember { mutableStateOf("") }

    val loginLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        status = readStatus(storage)
    }

    // The 5-hour reset notification needs this on Android 13+.
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Claude Usage") },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCard(status, message) {
                scope.launch {
                    message = "Fetching…"
                    message = when (val r = UsageRepository(context).refresh()) {
                        is FetchResult.Success -> {
                            UsageWidget.updateAll(context)
                            "Updated."
                        }
                        is FetchResult.NeedsLogin -> "Session expired. Sign in again."
                        is FetchResult.Soft -> "Temporary error: ${r.reason}"
                    }
                    status = readStatus(storage)
                }
            }

            Section(
                title = "Session key",
                body = "In a desktop browser signed in to claude.ai, copy the sessionKey cookie " +
                    "(DevTools → Application → Cookies) and paste it here.",
            ) {
                // Manual alternative to the WebView login.
                var sessionInput by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = sessionInput,
                    onValueChange = { sessionInput = it },
                    label = { Text("sessionKey (sk-ant-sid…)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = sessionInput.isNotBlank(),
                        onClick = {
                            saveSessionKey(context, storage, sessionInput.trim())
                            sessionInput = ""
                            message = "Session key saved."
                            status = readStatus(storage)
                        },
                    ) { Text("Save") }
                    OutlinedButton(onClick = {
                        loginLauncher.launch(Intent(context, LoginActivity::class.java))
                    }) {
                        Text(if (storage.isLoggedIn) "Re-sign in (WebView)" else "Sign in (WebView)")
                    }
                }
            }

            Section(
                title = "Background refresh",
                body = "The widget refreshes every 15 minutes and notifies you when the 5-hour " +
                    "window resets. Tap it once to refresh, three times to open Claude.",
            ) {
                FilledTonalButton(
                    onClick = { requestBatteryExemption(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Disable battery optimization") }
                if (needsExactAlarmGrant(context)) {
                    FilledTonalButton(
                        onClick = { requestExactAlarms(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Allow on-time reset notification") }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(status: UiStatus, message: String, onRefresh: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (status.signedIn) "Signed in" else "Not signed in",
                style = MaterialTheme.typography.titleMedium,
            )
            if (status.hasData) {
                UsageMeter("5-hour", status.fiveHourPct, status.fiveHourResets)
                UsageMeter("Weekly", status.sevenDayPct, status.sevenDayResets)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    message.ifBlank { "Org: ${status.orgId ?: "-"}" },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onRefresh) { Text("Refresh") }
            }
        }
    }
}

@Composable
private fun UsageMeter(label: String, pct: Int, resets: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text("$label  $pct%", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text("resets in $resets", style = MaterialTheme.typography.labelMedium)
        }
        LinearProgressIndicator(
            progress = { pct.coerceIn(0, 100) / 100f },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Section(title: String, body: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

private fun saveSessionKey(context: Context, storage: Storage, key: String) {
    storage.clearCredentials()
    storage.sessionKey = key
    // OkHttp and the Cloudflare-solving WebView must present the same UA, since
    // cf_clearance is bound to it.
    storage.userAgent = WebSettings.getDefaultUserAgent(context)
    storage.authState = AuthState.OK
}

private fun requestBatteryExemption(context: Context) {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )
        context.startActivity(intent)
    }
}

private fun needsExactAlarmGrant(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        !context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

private fun requestExactAlarms(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
        )
    }
}
