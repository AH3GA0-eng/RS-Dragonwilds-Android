package app.gamenative

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Debug
import android.os.Process
import android.util.Log

/** Memory telemetry. Exit 137 alone does not establish the cause of SIGKILL. */
object DragonwildsLaunchDiagnostics {
    private const val TAG = "DragonwildsLaunch"
    @Volatile private var installed = false

    @Synchronized fun install(context: Context) {
        if (installed) return
        val app = context.applicationContext
        installed = true
        snapshot(app, "app_start")
        app.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) = snapshot(app, "trim_$level")
            override fun onLowMemory() = snapshot(app, "low_memory")
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
        })
    }

    fun snapshot(context: Context, stage: String) {
        try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val system = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(system)
            val process = Debug.MemoryInfo()
            Debug.getMemoryInfo(process)
            Log.i(TAG, "stage=$stage pid=${Process.myPid()} " +
                "systemAvailMiB=${system.availMem / (1024L * 1024L)} " +
                "systemLow=${system.lowMemory} thresholdMiB=${system.threshold / (1024L * 1024L)} " +
                "appPssKiB=${process.totalPss} appPrivateDirtyKiB=${process.totalPrivateDirty}")
        } catch (e: Exception) {
            Log.w(TAG, "Unable to sample memory at $stage", e)
        }
    }
}
