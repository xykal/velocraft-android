package io.velocraft.android.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File

/**
 * Automatically computes the lightest, lowest-latency JVM & Garbage Collector arguments
 * for both 64-bit (`arm64-v8a`) and 32-bit (`armeabi-v7a`) Android devices.
 */
object JvmOptimizer {

    data class HardwareProfile(
        val abi: String,
        val is64Bit: Boolean,
        val totalRamMb: Int,
        val allocatedHeapMb: Int,
        val totalCores: Int,
        val chunkBuilderThreads: Int,
        val recommendedRendererLib: String,
        val socModel: String
    )

    fun detectHardware(context: Context): HardwareProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val is64 = Process.is64Bit()
        val abi = if (is64) "arm64-v8a" else "armeabi-v7a"
        val totalRamMb = (memInfo.totalMem / (1024 * 1024)).toInt()
        val totalCores = Runtime.getRuntime().availableProcessors()

        // 32-bit armeabi-v7a processes have a ~2.7GB virtual address space ceiling,
        // so heap must stay <= 896MB to leave room for OpenGL ES native buffers.
        val allocatedHeapMb = if (!is64) {
            when {
                totalRamMb <= 2048 -> 576
                totalRamMb <= 3072 -> 768
                else -> 896
            }
        } else {
            when {
                totalRamMb <= 4096 -> 1408  // 4GB RAM phone -> 1.4 GB Heap
                totalRamMb <= 6144 -> 2048  // 6GB RAM phone -> 2.0 GB Heap
                totalRamMb <= 8192 -> 2816  // 8GB RAM phone -> 2.8 GB Heap
                else -> 3584                // 12GB+ RAM phone -> 3.5 GB Heap
            }
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
            allocatedHeapMb = allocatedHeapMb,
            totalCores = totalCores,
            chunkBuilderThreads = chunkThreads,
            recommendedRendererLib = rendererLib,
            socModel = soc
        )
    }

    /**
     * Generates the full command-line array passed directly into `JLI_Launch`
     * (supports both `arm64-v8a` and `armeabi-v7a` OpenJDK runtimes).
     */
    fun buildDirectBootJvmArgs(
        context: Context,
        gameDir: File,
        assetsDir: File,
        nativesDir: File,
        classpath: String,
        playerUsername: String,
        playerUuid: String,
        accessToken: String = "0"
    ): Array<String> {
        val hw = detectHardware(context)
        val args = mutableListOf(
            "java",
            "-Xms${hw.allocatedHeapMb}M",
            "-Xmx${hw.allocatedHeapMb}M",
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+UseG1GC",
            "-XX:MaxGCPauseMillis=25",
            "-XX:G1NewSizePercent=20",
            "-XX:G1ReservePercent=15",
            "-XX:+DisableExplicitGC",
            "-XX:+ParallelRefProcEnabled",
            "-Dfile.encoding=UTF-8",
            "-Djava.awt.headless=true"
        )

        if (hw.is64Bit) {
            args.addAll(
                listOf(
                    "-XX:G1HeapRegionSize=16M",
                    "-XX:+AlwaysPreTouch",
                    "-XX:+UseStringDeduplication",
                    "-XX:+UseNeon",
                    "-XX:+UseCRC32Intrinsics"
                )
            )
        } else {
            // 32-bit armeabi-v7a compact heap region & stack size tuning
            args.addAll(
                listOf(
                    "-XX:G1HeapRegionSize=4M",
                    "-Xss512k"
                )
            )
        }

        args.addAll(
            listOf(
                "-Djava.library.path=${nativesDir.absolutePath}",
                "-Dorg.lwjgl.librarypath=${nativesDir.absolutePath}",
                "-Dorg.lwjgl.opengl.libname=${hw.recommendedRendererLib}",
                "-Dvelocraft.bridge.port=28465",
                "-Dvelocraft.abi=${hw.abi}",
                "-Dvelocraft.chunkThreads=${hw.chunkBuilderThreads}",
                "-cp",
                classpath,
                "net.fabricmc.loader.impl.launch.knot.KnotClient",
                "--username", playerUsername,
                "--version", "1.20.4-VeloCraft",
                "--gameDir", gameDir.absolutePath,
                "--assetsDir", assetsDir.absolutePath,
                "--assetIndex", "12",
                "--uuid", playerUuid,
                "--accessToken", accessToken,
                "--userType", "msa",
                "--versionType", "VeloCraft-Direct-${hw.abi}"
            )
        )

        return args.toTypedArray()
    }
}
