package com.usage.claudewidget.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Two-tier storage:
 *  - [secure]   EncryptedSharedPreferences for credentials (sessionKey, cf_clearance, UA, orgId).
 *  - [snapshot] Plain prefs for the non-secret usage snapshot the widget renders on every frame.
 */
class Storage private constructor(
    private val secure: SharedPreferences,
    private val snapshot: SharedPreferences,
) {
    // ---- credentials (encrypted) ----
    var sessionKey: String?
        get() = secure.getString("sessionKey", null)
        set(v) = secure.edit().putString("sessionKey", v).apply()

    var cfClearance: String?
        get() = secure.getString("cf_clearance", null)
        set(v) = secure.edit().putString("cf_clearance", v).apply()

    var userAgent: String?
        get() = secure.getString("user_agent", null)
        set(v) = secure.edit().putString("user_agent", v).apply()

    var orgId: String?
        get() = secure.getString("org_id", null)
        set(v) = secure.edit().putString("org_id", v).apply()

    val isLoggedIn: Boolean get() = !sessionKey.isNullOrBlank()

    fun clearCredentials() {
        secure.edit().clear().apply()
    }

    // ---- usage snapshot (plain, read by the widget) ----
    var fiveHourUtil: Float
        get() = snapshot.getFloat("fh_util", -1f)
        set(v) = snapshot.edit().putFloat("fh_util", v).apply()

    var fiveHourReset: Long
        get() = snapshot.getLong("fh_reset", 0L)
        set(v) = snapshot.edit().putLong("fh_reset", v).apply()

    var sevenDayUtil: Float
        get() = snapshot.getFloat("wk_util", -1f)
        set(v) = snapshot.edit().putFloat("wk_util", v).apply()

    var sevenDayReset: Long
        get() = snapshot.getLong("wk_reset", 0L)
        set(v) = snapshot.edit().putLong("wk_reset", v).apply()

    var fetchedAt: Long
        get() = snapshot.getLong("fetched_at", 0L)
        set(v) = snapshot.edit().putLong("fetched_at", v).apply()

    /** AuthState ordinal; widget shows "Tap to sign in" when NEEDS_LOGIN. */
    var authState: AuthState
        get() = AuthState.entries.getOrElse(snapshot.getInt("auth_state", 0)) { AuthState.OK }
        set(v) = snapshot.edit().putInt("auth_state", v.ordinal).apply()

    /** 5-hour reset time we already sent a "limit reset" notification for. */
    var notifiedReset: Long
        get() = snapshot.getLong("notified_reset", 0L)
        set(v) = snapshot.edit().putLong("notified_reset", v).apply()

    /** Minutes between background fetches; WorkManager's floor is 15. */
    var refreshIntervalMin: Int
        get() = snapshot.getInt("refresh_interval_min", Const.REFRESH_INTERVAL_MIN.toInt())
        set(v) = snapshot.edit().putInt("refresh_interval_min", v).apply()

    /** History retention by age, in days; 0 keeps everything. */
    var historyMaxDays: Int
        get() = snapshot.getInt("history_max_days", 0)
        set(v) = snapshot.edit().putInt("history_max_days", v).apply()

    /** History retention by size, in KB; 0 means no cap. */
    var historyMaxKb: Int
        get() = snapshot.getInt("history_max_kb", 0)
        set(v) = snapshot.edit().putInt("history_max_kb", v).apply()

    val retention: Retention
        get() = Retention(historyMaxDays * 86_400_000L, historyMaxKb * 1024L)

    val hasSnapshot: Boolean get() = fiveHourUtil >= 0f

    fun saveSnapshot(s: UsageSnapshot) {
        snapshot.edit()
            .putFloat("fh_util", s.fiveHour.utilization)
            .putLong("fh_reset", s.fiveHour.resetsAtEpochMs)
            .putFloat("wk_util", s.sevenDay.utilization)
            .putLong("wk_reset", s.sevenDay.resetsAtEpochMs)
            .putLong("fetched_at", s.fetchedAtEpochMs)
            .apply()
    }

    companion object {
        @Volatile private var instance: Storage? = null

        fun get(context: Context): Storage = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

        private fun build(app: Context): Storage {
            val masterKey = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val secure = EncryptedSharedPreferences.create(
                app,
                "claude_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            val snapshot = app.getSharedPreferences("claude_usage", Context.MODE_PRIVATE)
            return Storage(secure, snapshot)
        }
    }
}

enum class AuthState { OK, NEEDS_LOGIN }
