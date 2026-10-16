package io.velocraft.android

import android.app.Application
import android.content.Intent
import android.os.Process
import io.velocraft.android.util.VeloLogger
import kotlin.system.exitProcess

/**
 * Global Application entrypoint for Minecraft (Java Porting Edition).
 * Initializes `/storage/emulated/0/Android/media/io.velocraft.android/log/` before any Activity runs
 * and installs a global UncaughtExceptionHandler so any crash is written to `crash.log` and shown on screen.
 */
class VeloCraftApp : Application() {

    override fun onCreate() {
        super.onCreate()
        VeloLogger.init(this)

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val crashReport = VeloLogger.recordCrash(thread.name, throwable)
            try {
                val intent = Intent(this@VeloCraftApp, CrashReportActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra("crash_report", crashReport)
                    putExtra("log_path", VeloLogger.logDir?.absolutePath ?: "/sdcard/Android/media/$packageName/log")
                }
                startActivity(intent)
                Thread.sleep(400)
            } catch (_: Throwable) {
                defaultHandler?.uncaughtException(thread, throwable)
            } finally {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }
}
