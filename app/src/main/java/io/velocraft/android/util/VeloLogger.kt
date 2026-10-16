package io.velocraft.android.util

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Structured, human-readable diagnostic logger writing to:
 *   `/storage/emulated/0/Android/media/id.xyverse.minecraft/log/latest.log`
 *   `/storage/emulated/0/Android/media/id.xyverse.minecraft/log/crash.log`
 * Also manages `/storage/emulated/0/Android/media/id.xyverse.minecraft/shaderpacks/`.
 *
 * Strictly zero emojis for clean monospace log readability.
 */
object VeloLogger {

    private const val TAG = "MinecraftPort"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    var mediaRootDir: File? = null
        private set

    @Volatile
    var logDir: File? = null
        private set

    @Volatile
    var shaderpacksDir: File? = null
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
            val mediaBase = context.externalMediaDirs?.firstOrNull()
                ?: File(Environment.getExternalStorageDirectory(), "Android/media/${context.packageName}")

            val logsFolder = File(mediaBase, "log")
            val shadersFolder = File(mediaBase, "shaderpacks")
            logsFolder.mkdirs()
            shadersFolder.mkdirs()

            val activeLogDir = if (logsFolder.exists() && logsFolder.canWrite()) {
                logsFolder
            } else {
                File(context.getExternalFilesDir(null) ?: context.filesDir, "log").apply { mkdirs() }
            }

            mediaRootDir = mediaBase
            logDir = activeLogDir
            shaderpacksDir = shadersFolder
            latestLogFile = File(activeLogDir, "latest.log")
            crashLogFile = File(activeLogDir, "crash.log")

            val freeDiskMb = getAvailableDiskSpaceMb(activeLogDir)
            val abi = if (Process.is64Bit()) "arm64-v8a (64-bit)" else "armeabi-v7a (32-bit)"

            val header = buildString {
                appendLine("========================================================================")
                appendLine("MINECRAFT JAVA PORTING EDITION (v0.7.6-release) - DIAGNOSTIC LOG")
                appendLine("========================================================================")
                appendLine("Timestamp        : ${dateFormat.format(Date())}")
                appendLine("Package Name     : ${context.packageName}")
                appendLine("Log Directory    : ${activeLogDir.absolutePath}")
                appendLine("Shaderpacks Dir  : ${shadersFolder.absolutePath}")
                appendLine("Internal DataDir : ${context.filesDir.absolutePath}")
                appendLine("Free Disk Space  : $freeDiskMb MB")
                appendLine("Device Model     : ${Build.MANUFACTURER} ${Build.MODEL} (Board: ${Build.BOARD})")
                appendLine("Android Version  : Android ${Build.VERSION.RELEASE} (API Level ${Build.VERSION.SDK_INT})")
                appendLine("Active Process   : $abi")
                appendLine("Supported ABIs   : ${Build.SUPPORTED_ABIS.joinToString(", ")}")
                appendLine("========================================================================")
            }
            latestLogFile?.writeText(header)
            section("BOOT", "Logger initialized. Ready to record engine, renderer, download, and JVM events.")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize VeloLogger", t)
        }
    }

    @Synchronized
    fun section(category: String, message: String) {
        val cat = category.uppercase(Locale.US).padEnd(8, ' ')
        Log.i(TAG, "[$cat] $message")
        appendLineToFile("INFO ", cat, message)
    }

    @Synchronized
    fun info(message: String) {
        Log.i(TAG, message)
        appendLineToFile("INFO ", "ENGINE  ", message)
    }

    @Synchronized
    fun warn(message: String) {
        Log.w(TAG, message)
        appendLineToFile("WARN ", "ENGINE  ", message)
    }

    @Synchronized
    fun error(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
        val fullMsg = if (throwable != null) {
            "$message\nCaused by: ${throwable.javaClass.name}: ${throwable.message}\n${stackTraceToString(throwable)}"
        } else {
            message
        }
        appendLineToFile("ERROR", "FAULT   ", fullMsg)
    }

    @Synchronized
    fun recordCrash(threadName: String, throwable: Throwable): String {
        val report = buildString {
            appendLine("========================================================================")
            appendLine("FATAL UNCAUGHT EXCEPTION REPORT")
            appendLine("========================================================================")
            appendLine("Time        : ${dateFormat.format(Date())}")
            appendLine("Thread      : $threadName")
            appendLine("Device      : ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
            appendLine("Process ABI : ${if (Process.is64Bit()) "arm64-v8a" else "armeabi-v7a"}")
            appendLine("Exception   : ${throwable.javaClass.name}")
            appendLine("Message     : ${throwable.message ?: "(no message)"}")
            appendLine("------------------------------------------------------------------------")
            appendLine("STACK TRACE:")
            appendLine(stackTraceToString(throwable))
            appendLine("========================================================================")
        }
        Log.e(TAG, report)
        try {
            appendLineToFile("FATAL", "CRASH   ", report)
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

    fun readLatestLogsTail(maxChars: Int = 12000): String {
        return try {
            val text = latestLogFile?.readText(Charsets.UTF_8) ?: "Log file not yet created."
            if (text.length <= maxChars) text else "...(truncated)...\n" + text.takeLast(maxChars)
        } catch (t: Throwable) {
            "Failed to read log file: ${t.message}"
        }
    }

    private fun getAvailableDiskSpaceMb(path: File): Long {
        return try {
            val stat = StatFs(path.absolutePath)
            (stat.availableBlocksLong * stat.blockSizeLong) / (1024L * 1024L)
        } catch (_: Throwable) {
            -1L
        }
    }

    private fun appendLineToFile(level: String, category: String, message: String) {
        val file = latestLogFile ?: return
        try {
            val timestamp = dateFormat.format(Date())
            val line = "[$timestamp] [$level] [$category] $message\n"
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
