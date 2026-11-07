package app.gamenative.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import app.gamenative.utils.ContainerUtils
import com.winlator.xenvironment.ImageFs
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Minimal local-only Dragonwilds launch telemetry; no login credentials,
 * file inventories, Steam tokens or full logcat are collected.
 *
 * Most short game sessions finish via Wine's guest termination callback,
 * which does NOT register as an Android process crash.
 */
internal object DragonwildsDiagnostics {
    private const val PREFS = "dragonwilds_mobile_launch_diagnostics"
    private const val LAST_LAUNCH_MS = "last_launch_ms"
    private const val WINDOW_MS = 10L * 60L * 1000L
    private const val GAME_ID = "STEAM_1374490"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun memorySummary(context: Context): String = runCatching {
        val service = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        service.getMemoryInfo(info)
        "free=${info.availMem / 1048576L}MB; lowMemory=${info.lowMemory}; threshold=${info.threshold / 1048576L}MB"
    }.getOrDefault("unavailable")

    fun recordLaunch(context: Context) {
        prefs(context).edit()
            .putString("memory_at_start", memorySummary(context))
            .remove("memory_at_exit")
            .remove("bionic_launch_count")
            .remove("bionic_kill_time")
            .remove("bionic_kill_pid")
            .remove("bionic_kill_reason")
            .putLong(LAST_LAUNCH_MS, System.currentTimeMillis())
            .remove("guest_exit_code")
            .remove("session_event")
            .remove("launch_error")
            .remove("last_steam_stage")
            .putString("steam_watcher", "not started")
            .commit()
    }

    fun recordSteamWatcherStart(context: Context) {
        prefs(context).edit().putString("steam_watcher", "started").apply()
    }

    fun recordSteamStage(context: Context, status: String) {
        val first = status.trim().substringBefore(' ').lowercase()
        if (first !in setOf("starting", "logon", "connecting", "retry", "launching", "launched", "failed")) return
        prefs(context).edit().putString("last_steam_stage", first).apply()
    }

    fun recordGuestExit(context: Context, appId: String, status: Int) {
        if (appId != GAME_ID) return
        prefs(context).edit()
            .putInt("guest_exit_code", status)
            .putLong("guest_exit_time", System.currentTimeMillis())
            .putString("memory_at_exit", memorySummary(context))
            .putString("session_event", "Wine guest launcher exited")
            .commit()
    }

    fun recordSessionEnded(context: Context, appId: String, reason: String) {
        if (appId != GAME_ID) return
        val clean = reason.take(48).filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        prefs(context).edit().putString("session_event", "Game screen closed: " + clean).apply()
    }

    fun recordLaunchError(context: Context, error: String) {
        val clean = error.take(220).replace(Regex("[\\r\\n\\t]"), " ")
        prefs(context).edit().putString("launch_error", clean).commit()
    }

    private fun lastLaunchMs(context: Context): Long =
        prefs(context).getLong(LAST_LAUNCH_MS, 0L)

    fun shouldHoldAutoLaunch(context: Context): Boolean {
        val last = lastLaunchMs(context)
        return last > 0L && (System.currentTimeMillis() - last) in 0L..WINDOW_MS
    }

    // Steamhost may write its status into either the imagefs Wine prefix or
    // the per-game Wine prefix, depending on the container and overlay mode.
    private fun steamhostStage(context: Context): String? {
        val candidates = mutableListOf<File>()
        runCatching {
            val container = ContainerUtils.getContainer(context, GAME_ID)
            candidates.add(File(container.rootDir,
                ".wine/drive_c/Program Files (x86)/Steam/steamhost_status"))
        }
        runCatching {
            candidates.add(File(ImageFs.find(context).wineprefix,
                "drive_c/Program Files (x86)/Steam/steamhost_status"))
        }
        for (candidate in candidates) {
            val stage = runCatching { candidate.readText().trim().substringBefore(' ').lowercase() }
                .getOrNull() ?: continue
            if (stage in setOf("starting", "logon", "connecting", "retry",
                    "launching", "launched", "failed")) return stage
        }
        return null
    }

    private fun androidExit(context: Context, since: Long): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "Android process-exit history unavailable"
        }
        return runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 8)
            val exit = exits.firstOrNull { it.timestamp >= since - 3000L }
                ?: return@runCatching "No recorded Android process exit"
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
        val p = prefs(context)
        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
            .format(Date(last))
        val stage = p.getString("last_steam_stage", null) ?: steamhostStage(context)
            ?: "No Steam stage emitted"
        val watcher = p.getString("steam_watcher", "not started") ?: "unknown"
        val session = p.getString("session_event", "No Wine guest exit callback recorded")
        val code = if (p.contains("guest_exit_code")) p.getInt("guest_exit_code", -1).toString() else "not recorded"
        val error = p.getString("launch_error", null)
        return buildString {
            appendLine("Dragonwilds Mobile launch diagnostic")
            appendLine("Attempt: " + time)
            appendLine("Steam watcher: " + watcher)
            appendLine("Steam stage: " + stage)
            appendLine("Guest exit code: " + code)
            appendLine("Game session: " + session)
            appendLine("Available memory at launch: " + (p.getString("memory_at_start", "not sampled") ?: "not sampled"))
            appendLine("Available memory at exit: " + (p.getString("memory_at_exit", "not sampled") ?: "not sampled"))
            appendLine("Native launcher start count: " + p.getInt("bionic_launch_count", 0))
            if (p.contains("bionic_kill_time")) {
                val killTime = p.getLong("bionic_kill_time", 0L)
                val exitTime = p.getLong("guest_exit_time", 0L)
                appendLine("Native launcher requested SIGKILL: " + (p.getString("bionic_kill_reason", "unknown") ?: "unknown"))
                appendLine("Internal kill relative to guest exit: " + if (exitTime > 0) (killTime - exitTime).toString() + " ms" else "no guest exit")
            } else appendLine("Native launcher SIGKILL recorded: no")
            if (!error.isNullOrBlank()) appendLine("Launch error: " + error)
            append(androidExit(context, last))
        }
    }
}
