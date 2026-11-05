package app.gamenative.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.winlator.xenvironment.ImageFs
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Minimal, local-only diagnostics for the dedicated Dragonwilds launcher.
 * No Steam credentials, usernames, file lists, or full logcat are collected.
 */
internal object DragonwildsDiagnostics {
    private const val PREFS = "dragonwilds_mobile_launch_diagnostics"
    private const val LAST_LAUNCH_MS = "last_launch_ms"
    private const val WINDOW_MS = 10L * 60L * 1000L

    fun recordLaunch(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(LAST_LAUNCH_MS, System.currentTimeMillis()).apply()
    }

    private fun lastLaunchMs(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(LAST_LAUNCH_MS, 0L)

    // A recently attempted run must not launch again immediately on app restart
    // until the user sees the failure and presses Play. Avoid crash-restart loops.
    fun shouldHoldAutoLaunch(context: Context): Boolean {
        val last = lastLaunchMs(context)
        return last > 0 && (System.currentTimeMillis() - last) in 0..WINDOW_MS
    }

    private fun steamhostStage(context: Context): String? {
        val path = runCatching {
            File(ImageFs.find(context).wineprefix,
                "drive_c/Program Files (x86)/Steam/steamhost_status")
        }.getOrNull() ?: return null
        val stage = runCatching { path.readText().trim().substringBefore(' ') }
            .getOrNull()?.lowercase().orEmpty()
        return stage.takeIf {
            it in setOf("starting", "logon", "connecting", "retry",
                "launching", "launched", "failed")
        }
    }

    private fun androidExit(context: Context, since: Long): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "Android process-exit history unavailable"
        }
        return runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            val history = am.getHistoricalProcessExitReasons(context.packageName, 0, 8)
            val exit = history.firstOrNull { it.timestamp >= since - 3000L }
            if (exit == null) return@runCatching "No recorded Android process exit"
            when (exit.reason) {
                android.app.ApplicationExitInfo.REASON_CRASH -> "Android: Java crash"
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "Android: native crash"
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "Android: low-memory termination"
                android.app.ApplicationExitInfo.REASON_ANR -> "Android: app not responding"
                android.app.ApplicationExitInfo.REASON_SIGNALED -> "Android: process terminated by signal"
                android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "Android: app closed by user"
                else -> "Android: exit reason " + exit.reason
            }
        }.getOrDefault("Unable to read Android exit history")
    }

    fun report(context: Context): String? {
        val last = lastLaunchMs(context)
        if (last <= 0L) return null
        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
            .format(Date(last))
        val stage = steamhostStage(context) ?: "No Steam stage recorded"
        return "Dragonwilds Mobile launch diagnostic\n" +
            "Attempt: " + time + "\n" +
            "Steam stage: " + stage + "\n" +
            androidExit(context, last)
    }
}
