package io.velocraft.android.util

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent File & Crash Logger that writes directly to:
 * `/storage/emulated/0/Android/media/<packageName>/log/`
 *
 * Why `Android/media/<pkgname>/log/`?
 * On Android 11–15 (Scoped Storage), `Android/data/` is hidden from normal file managers,
 * whereas `Android/media/<pkgname>/log/` (via `context.externalMediaDirs`) requires NO special
 * storage permissions to write AND is 100% visible in ZArchiver, MT Manager, and Files by Google!
 */
object VeloLogger {

    private const val TAG = "MinecraftPort"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    var logDir: File? = null
        private set

    @Volatile
    var latestLogFile: File? = null
        private set

    @Volatile
    var crashLogFile: File? = null
        private set

    @Suppress("DEPRECATION")
    fun init(context: Context) {
        try {
            // Primary target requested by user: /storage/emulated/0/Android/media/<pkgname>/log
            val mediaBase = context.externalMediaDirs?.firstOrNull()
                ?: File(Environment.getExternalStorageDirectory(), "Android/media/${context.packageName}")

            val dir = File(mediaBase, "log")
            if (!dir.exists()) {
                dir.mkdirs()
            }

            // Fallback to externalFilesDir if externalMediaDirs is unmounted
            val activeDir = if (dir.exists() && dir.canWrite()) {
                dir
            } else {
                File(context.getExternalFilesDir(null) ?: context.filesDir, "log").apply { mkdirs() }
            }

            logDir = activeDir
            latestLogFile = File(activeDir, "latest.log")
            crashLogFile = File(activeDir, "crash.log")

            // Write session header to latest.log
            val header = buildString {
                appendLine("================================================================")
                appendLine("Minecraft (Java Porting Edition v0.4.0) — Boot Diagnostic Log")
                appendLine("Time       : ${dateFormat.format(Date())}")
                appendLine("Package    : ${context.packageName}")
                appendLine("Log Path   : ${activeDir.absolutePath}")
                appendLine("Device     : ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
                appendLine("Android OS : Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("Process ABI: ${if (Process.is64Bit()) "arm64-v8a (64-bit)" else "armeabi-v7a (32-bit)"}")
                appendLine("Supported  : ${Build.SUPPORTED_ABIS.joinToString(", ")}")
                appendLine("================================================================")
            }
            latestLogFile?.writeText(header)
            info("VeloLogger initialized successfully at ${activeDir.absolutePath}")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize VeloLogger", t)
        }
    }

    @Synchronized
    fun info(message: String) {
        Log.i(TAG, message)
        appendLineToFile("INFO", message)
    }

    @Synchronized
    fun warn(message: String) {
        Log.w(TAG, message)
        appendLineToFile("WARN", message)
    }

    @Synchronized
    fun error(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
        val fullMsg = if (throwable != null) {
            "$message\n${stackTraceToString(throwable)}"
        } else {
            message
        }
        appendLineToFile("ERROR", fullMsg)
    }

    @Synchronized
    fun recordCrash(threadName: String, throwable: Throwable): String {
        val report = buildString {
            appendLine("================ FATAL CRASH REPORT ================")
            appendLine("Time       : ${dateFormat.format(Date())}")
            appendLine("Thread     : $threadName")
            appendLine("Device     : ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABI        : ${if (Process.is64Bit()) "arm64-v8a" else "armeabi-v7a"}")
            appendLine("Exception  : ${throwable.javaClass.name}: ${throwable.message}")
            appendLine("----------------------------------------------------")
            appendLine(stackTraceToString(throwable))
            appendLine("====================================================")
        }
        Log.e(TAG, report)
        try {
            appendLineToFile("FATAL", report)
            crashLogFile?.let { file ->
                FileOutputStream(file, true).use { fos ->
                    fos.write((report + "\n").toByteArray(Charsets.UTF_8))
                    fos.flush()
                }
            }
        } catch (_: Throwable) {
        }
        return report
    }

    fun readLatestLogsTail(maxChars: Int = 8000): String {
        return try {
            val text = latestLogFile?.readText(Charsets.UTF_8) ?: "Log file belum tersedia."
            if (text.length <= maxChars) text else text.takeLast(maxChars)
        } catch (t: Throwable) {
            "Gagal membaca log: ${t.message}"
        }
    }

    private fun appendLineToFile(level: String, message: String) {
        val file = latestLogFile ?: return
        try {
            val timestamp = dateFormat.format(Date())
            val line = "[$timestamp] [$level] $message\n"
            FileOutputStream(file, true).use { fos ->
                fos.write(line.toByteArray(Charsets.UTF_8))
                fos.flush()
            }
        } catch (_: Throwable) {
        }
    }

    private fun stackTraceToString(t: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        t.printStackTrace(pw)
        pw.flush()
        return sw.toString()
    }
}
