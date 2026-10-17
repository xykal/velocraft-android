package io.velocraft.android.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import io.velocraft.android.util.VeloLogger
import java.io.File

/**
 * Automatically computes and manages JVM Memory Allocation (`-Xms` / `-Xmx`),
 * Recommended Default RAM Suggestions, Resolution Scale, and Garbage Collector tuning
 * for both 64-bit (`arm64-v8a`) and 32-bit (`armeabi-v7a`) Android devices.
 *
 * Published by Xyverse Technology Global (Founder/Director: Haekal Saputra).
 */
object JvmOptimizer {

    private const val PREFS_NAME = "xyverse_jvm_optimizer_prefs"
    private const val KEY_CUSTOM_HEAP_MB = "custom_heap_mb" // <= 0 means use Recommended Default
    private const val KEY_RESOLUTION_SCALE = "resolution_scale_pct" // 50, 75, 100
    private const val KEY_USE_ZGC = "use_generational_zgc"

    data class HardwareProfile(
        val abi: String,
        val is64Bit: Boolean,
        val totalRamMb: Int,
        val recommendedHeapMb: Int,
        val allocatedHeapMb: Int,
        val minSafeHeapMb: Int,
        val maxSafeHeapMb: Int,
        val isCustomHeap: Boolean,
        val resolutionScalePct: Int,
        val useGenerationalZgc: Boolean,
        val totalCores: Int,
        val chunkBuilderThreads: Int,
        val recommendedRendererLib: String,
        val socModel: String
    )

    fun isCustomHeapEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_CUSTOM_HEAP_MB, -1) > 0
    }

    fun setCustomHeapMb(context: Context, heapMb: Int) {
        val hw = detectHardware(context)
        val clamped = heapMb.coerceIn(hw.minSafeHeapMb, hw.maxSafeHeapMb)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_CUSTOM_HEAP_MB, clamped)
            .apply()
        VeloLogger.section(
            "RUNTIME",
            "User set custom JVM RAM allocation: ${clamped}MB (Recommended default: ${hw.recommendedHeapMb}MB of ${hw.totalRamMb}MB)"
        )
    }

    fun resetToRecommendedHeap(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_CUSTOM_HEAP_MB, -1)
            .apply()
        val hw = detectHardware(context)
        VeloLogger.section(
            "RUNTIME",
            "Reset JVM RAM allocation to Recommended Default: ${hw.recommendedHeapMb}MB"
        )
    }

    fun getResolutionScalePct(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_RESOLUTION_SCALE, 100).coerceIn(50, 100)
    }

    fun setResolutionScalePct(context: Context, pct: Int) {
        val valid = when {
            pct <= 55 -> 50
            pct <= 80 -> 75
            else -> 100
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_RESOLUTION_SCALE, valid)
            .apply()
        VeloLogger.section("RENDERER", "Render Resolution Scale set to: ${valid}%")
    }

    fun isZgcModeEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_USE_ZGC, false) && Process.is64Bit()
    }

    fun setZgcModeEnabled(context: Context, enabled: Boolean) {
        val safe = enabled && Process.is64Bit()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_USE_ZGC, safe)
            .apply()
        VeloLogger.section("RUNTIME", "JVM Garbage Collector set to: ${if (safe) "Generational ZGC" else "G1GC Low-Pause"}")
    }

    fun detectHardware(context: Context): HardwareProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val is64 = Process.is64Bit()
        val abi = if (is64) "arm64-v8a" else "armeabi-v7a"
        val totalRamMb = (memInfo.totalMem / (1024 * 1024)).toInt().coerceAtLeast(1536)
        val totalCores = Runtime.getRuntime().availableProcessors()

        // Recommended Default Heap Allocation based on CPU ABI and physical device RAM
        val recommendedHeapMb = if (!is64) {
            when {
                totalRamMb <= 2048 -> 640
                totalRamMb <= 3072 -> 768
                else -> 1024
            }
        } else {
            when {
                totalRamMb <= 3500 -> 1152  // 3GB RAM -> 1.15 GB
                totalRamMb <= 4500 -> 1536  // 4GB RAM -> 1.5 GB
                totalRamMb <= 6500 -> 2048  // 6GB RAM -> 2.0 GB
                totalRamMb <= 8600 -> 3072  // 8GB RAM -> 3.0 GB
                else -> 4096                // 12GB+ RAM -> 4.0 GB
            }
        }

        val minSafeHeapMb = if (!is64) 512 else 768
        val maxSafeHeapMb = if (!is64) {
            1152
        } else {
            ((totalRamMb * 0.68f).toInt() / 128 * 128).coerceIn(1536, 8192)
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedCustomHeap = prefs.getInt(KEY_CUSTOM_HEAP_MB, -1)
        val isCustom = savedCustomHeap > 0
        val allocatedHeapMb = if (isCustom) {
            savedCustomHeap.coerceIn(minSafeHeapMb, maxSafeHeapMb)
        } else {
            recommendedHeapMb
        }

        val chunkThreads = if (!is64) {
            (totalCores / 2).coerceIn(1, 2)
        } else {
            (totalCores / 2).coerceIn(2, 4)
        }

        val effectiveRenderer = RendererProfileManager.resolveEffectiveBackend(context)
        val rendererLib = effectiveRenderer.libName

        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL
        } else {
            Build.HARDWARE
        }

        return HardwareProfile(
            abi = abi,
            is64Bit = is64,
            totalRamMb = totalRamMb,
            recommendedHeapMb = recommendedHeapMb,
            allocatedHeapMb = allocatedHeapMb,
            minSafeHeapMb = minSafeHeapMb,
            maxSafeHeapMb = maxSafeHeapMb,
            isCustomHeap = isCustom,
            resolutionScalePct = getResolutionScalePct(context),
            useGenerationalZgc = isZgcModeEnabled(context),
            totalCores = totalCores,
            chunkBuilderThreads = chunkThreads,
            recommendedRendererLib = rendererLib,
            socModel = soc
        )
    }

    /**
     * Generates the full command-line array passed directly into `JLI_Launch`.
     */
    fun buildDirectBootJvmArgs(
        context: Context,
        gameDir: File,
        assetsDir: File,
        nativesDir: File,
        classpath: String,
        playerUsername: String,
        playerUuid: String,
        accessToken: String = "0",
        userType: String = "msa"
    ): Array<String> {
        val hw = detectHardware(context)
        val initialHeapMb = (hw.allocatedHeapMb / 2).coerceAtLeast(384)
        val jreDir = File(gameDir.parentFile, "jre21-${hw.abi}").canonicalFile
        val canonGameDir = gameDir.canonicalFile
        val canonAssetsDir = assetsDir.canonicalFile
        val canonNativesDir = nativesDir.canonicalFile
        val canonCacheDir = context.cacheDir.canonicalFile

        val dm = context.resources.displayMetrics
        val scaleFactor = hw.resolutionScalePct / 100.0f
        val winW = ((dm.widthPixels * scaleFactor).toInt()).coerceAtLeast(640)
        val winH = ((dm.heightPixels * scaleFactor).toInt()).coerceAtLeast(360)

        val args = mutableListOf(
            "java",
            "-Xms${initialHeapMb}M",
            "-Xmx${hw.allocatedHeapMb}M",
            "-Xverify:none",
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:-BytecodeVerificationLocal",
            "-XX:-BytecodeVerificationRemote",
            "-XX:ActiveProcessorCount=${hw.totalCores}"
        )

        if (hw.useGenerationalZgc && hw.is64Bit) {
            args.addAll(
                listOf(
                    "-XX:+UseZGC",
                    "-XX:+DisableExplicitGC"
                )
            )
        } else {
            args.addAll(
                listOf(
                    "-XX:+UseG1GC",
                    "-XX:MaxGCPauseMillis=25",
                    "-XX:G1NewSizePercent=20",
                    "-XX:G1ReservePercent=15",
                    "-XX:+DisableExplicitGC",
                    "-XX:+ParallelRefProcEnabled"
                )
            )
        }

        if (hw.is64Bit) {
            args.addAll(
                listOf(
                    "-XX:G1HeapRegionSize=16M",
                    "-XX:+AlwaysPreTouch",
                    "-XX:+UseStringDeduplication"
                )
            )
        } else {
            args.addAll(
                listOf(
                    "-XX:G1HeapRegionSize=4M",
                    "-Xss768k"
                )
            )
        }

        args.addAll(
            listOf(
                "-Dfile.encoding=UTF-8",
                "-Djava.awt.headless=true",
                "-Djava.security.egd=file:/dev/./urandom",
                "-Dsun.java.launcher.diag=true",
                "-Djava.home=${jreDir.absolutePath}",
                "-Djava.io.tmpdir=${canonCacheDir.absolutePath}",
                "-Duser.home=${canonGameDir.absolutePath}",
                "-Duser.dir=${canonGameDir.absolutePath}",
                "-Dos.name=Linux",
                "-Dos.version=Android-${Build.VERSION.RELEASE}",
                "-Duser.language=en",
                "-Djna.boot.library.path=${canonNativesDir.absolutePath}",
                "-Djava.library.path=${canonNativesDir.absolutePath}:${jreDir.absolutePath}/lib",
                "-Dorg.lwjgl.librarypath=${canonNativesDir.absolutePath}",
                "-Dorg.lwjgl.system.SharedLibraryExtractPath=${canonNativesDir.absolutePath}",
                "-Dorg.lwjgl.system.allocator=system",
                "-Dorg.lwjgl.util.NoChecks=true",
                "-Dorg.lwjgl.util.Debug=false",
                "-Dorg.lwjgl.util.DebugLoader=false",
                "-Dorg.lwjgl.opengl.libname=${hw.recommendedRendererLib}",
                "-Dorg.lwjgl.freetype.libname=${canonNativesDir.absolutePath}/libfreetype.so",
                "-Dglfwstub.windowWidth=$winW",
                "-Dglfwstub.windowHeight=$winH",
                "-Dglfwstub.initEgl=false",
                "-Dmixin.debug.verify=false",
                "-Dmixin.checks=false",
                "-Dsodium.checks.issue2561=false",
                "-Dlog4j2.formatMsgNoLookups=true",
                "-Dfml.earlyprogresswindow=false",
                "-Dloader.disable_forked_guis=true",
                "-Djdk.lang.Process.launchMechanism=FORK",
                "-Dvelocraft.bridge.port=28465",
                "-Dvelocraft.abi=${hw.abi}",
                "-Dvelocraft.chunkThreads=${hw.chunkBuilderThreads}",
                "-Dvelocraft.resolutionScale=${hw.resolutionScalePct}",
                "-cp",
                classpath,
                "net.fabricmc.loader.impl.launch.knot.KnotClient",
                "--username", playerUsername,
                "--version", "1.20.4",
                "--gameDir", canonGameDir.absolutePath,
                "--assetsDir", canonAssetsDir.absolutePath,
                "--assetIndex", "12",
                "--uuid", playerUuid,
                "--accessToken", accessToken,
                "--userType", userType,
                "--versionType", "release"
            )
        )

        return args.toTypedArray()
    }
}
