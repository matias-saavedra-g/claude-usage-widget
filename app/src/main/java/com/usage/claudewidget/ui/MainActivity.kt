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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.usage.claudewidget.auth.LoginActivity
import com.usage.claudewidget.data.AuthState
import com.usage.claudewidget.data.FetchResult
import com.usage.claudewidget.data.Storage
import com.usage.claudewidget.data.UsageHistory
import com.usage.claudewidget.data.UsageRepository
import java.time.LocalDate
import com.usage.claudewidget.data.Retention
import com.usage.claudewidget.work.RefreshScheduler
import com.usage.claudewidget.widget.TimeFmt
import com.usage.claudewidget.widget.UsageWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val fiveHourUtil: Float,
    val fiveHourResetMs: Long,
    val sevenDayUtil: Float,
    val sevenDayResetMs: Long,
    val fetchedAt: Long,
)

private fun readStatus(s: Storage): UiStatus = UiStatus(
    signedIn = s.isLoggedIn && s.authState == AuthState.OK,
    orgId = s.orgId,
    hasData = s.hasSnapshot,
    fiveHourPct = s.fiveHourUtil.coerceAtLeast(0f).roundToInt(),
    fiveHourResets = TimeFmt.resetsIn(s.fiveHourReset),
    sevenDayPct = s.sevenDayUtil.coerceAtLeast(0f).roundToInt(),
    sevenDayResets = TimeFmt.resetsIn(s.sevenDayReset),
    fiveHourUtil = s.fiveHourUtil.coerceAtLeast(0f),
    fiveHourResetMs = s.fiveHourReset,
    sevenDayUtil = s.sevenDayUtil.coerceAtLeast(0f),
    sevenDayResetMs = s.sevenDayReset,
    fetchedAt = s.fetchedAt,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupScreen() {
    val context = LocalContext.current
    val storage = remember { Storage.get(context) }
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf(readStatus(storage)) }
    var message by remember { mutableStateOf("") }

    // Re-read the history whenever a fetch lands, including ones the background job made.
    val insights by produceState<Insights?>(null, status) {
        if (!status.hasData) { value = null; return@produceState }
        value = withContext(Dispatchers.IO) {
            computeInsights(
                UsageHistory.get(context).load(),
                status.fiveHourUtil, status.fiveHourResetMs,
                status.sevenDayUtil, status.sevenDayResetMs,
            )
        }
    }

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

            insights?.let { InsightsSection(it) }

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
                OrganizationRow(status.orgId, status.signedIn)
            }

            RetentionSection(storage)

            Section(
                title = "Background refresh",
                body = "The widget refreshes in the background and notifies you when the 5-hour " +
                    "window resets. Tap it once to refresh, three times to open Claude. Longer " +
                    "intervals save battery and requests; Android may delay any of them a little.",
            ) {
                var interval by remember { mutableStateOf(storage.refreshIntervalMin) }
                Text("Refresh every", style = MaterialTheme.typography.labelLarge)
                ChipRow(INTERVAL_OPTIONS, interval) {
                    interval = it
                    storage.refreshIntervalMin = it
                    RefreshScheduler.ensurePeriodic(context, reschedule = true)
                }
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

            ExportButton()
        }
    }
}

private val INTERVAL_OPTIONS = listOf(15 to "15 min", 30 to "30 min", 60 to "1 hour", 120 to "2 hours")
private val AGE_OPTIONS = listOf(7 to "1 week", 28 to "4 weeks", 84 to "12 weeks", 365 to "1 year", 0 to "Forever")
private val SIZE_OPTIONS = listOf(256 to "256 KB", 1024 to "1 MB", 5120 to "5 MB", 0 to "No limit")

/** Lets the user cap the stored history by age and by size; whichever is hit first applies. */
@Composable
private fun RetentionSection(storage: Storage) {
    val context = LocalContext.current
    val history = remember { UsageHistory.get(context) }
    var maxDays by remember { mutableStateOf(storage.historyMaxDays) }
    var maxKb by remember { mutableStateOf(storage.historyMaxKb) }
    var size by remember { mutableLongStateOf(0L) }
    // A tighter limit that would delete rows waits here until the user confirms it.
    var pending by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val scope = rememberCoroutineScope()

    fun choose(days: Int, kb: Int) {
        val r = Retention(days * 86_400_000L, kb * 1024L)
        scope.launch {
            if (withContext(Dispatchers.IO) { history.wouldDrop(r) }) {
                pending = days to kb
            } else {
                maxDays = days; maxKb = kb
                storage.historyMaxDays = days; storage.historyMaxKb = kb
            }
        }
    }

    LaunchedEffect(maxDays, maxKb) {
        size = withContext(Dispatchers.IO) {
            history.enforce(storage.retention)
            history.sizeBytes()
        }
    }

    Section(
        title = "Data retention",
        body = "Choose how much usage history to keep. Older rows are deleted once either " +
            "limit is reached. Charts use the last 12 weeks. Using ${formatSize(size)}.",
    ) {
        Text("Keep data for", style = MaterialTheme.typography.labelLarge)
        ChipRow(AGE_OPTIONS, maxDays) { choose(it, maxKb) }
        Text("Maximum size", style = MaterialTheme.typography.labelLarge)
        ChipRow(SIZE_OPTIONS, maxKb) { choose(maxDays, it) }
    }

    pending?.let { (days, kb) ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Delete old data?") },
            text = {
                Text("This limit is smaller than the history you have now. The rows past it " +
                    "will be deleted permanently. Export a CSV first if you want to keep them.")
            },
            confirmButton = {
                TextButton(onClick = {
                    maxDays = days; maxKb = kb
                    storage.historyMaxDays = days; storage.historyMaxKb = kb
                    pending = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ChipRow(options: List<Pair<Int, String>>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((value, label) in options) {
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "${(bytes + 1023) / 1024} KB"
}

/** Saves the whole history as a CSV file wherever the user picks, for use in other apps. */
@Composable
private fun ExportButton() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use {
                        UsageHistory.get(context).exportCsv(it)
                    }
                }.isSuccess
            }
            val text = if (ok) "Usage history exported." else "Export failed."
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }
    OutlinedButton(
        onClick = { launcher.launch("claude-usage-${LocalDate.now()}.csv") },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Export data as CSV") }
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
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onRefresh) { Text("Refresh") }
            }
        }
    }
}

/** The org ID stays masked until the user taps it and confirms; tapping again hides it. */
@Composable
private fun OrganizationRow(orgId: String?, connected: Boolean) {
    var revealed by remember(orgId) { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Organization ID", style = MaterialTheme.typography.labelLarge)
            Text(
                when {
                    orgId == null -> "-"
                    revealed -> orgId
                    else -> maskOrgId(orgId)
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable(enabled = orgId != null) {
                    if (revealed) revealed = false else confirming = true
                },
            )
        }
        if (connected && orgId != null) {
            Text(
                "✓",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    if (confirming && orgId != null) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Show organization ID?") },
            text = { Text("The organization ID identifies your Claude account. Make sure no one else can see your screen.") },
            confirmButton = {
                TextButton(onClick = { revealed = true; confirming = false }) { Text("Show") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            },
        )
    }
}

private fun maskOrgId(id: String): String = "*".repeat(id.length)

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
