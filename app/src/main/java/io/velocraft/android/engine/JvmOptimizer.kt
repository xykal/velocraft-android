package io.velocraft.android.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File

/**
 * Automatically computes the lightest, lowest-latency JVM & Garbage Collector arguments
 * for OpenJDK 21 ARM64 based on the Android device's physical RAM and CPU core count.
 */
object JvmOptimizer {

    data class HardwareProfile(
        val totalRamMb: Int,
        val allocatedHeapMb: Int,
        val totalCores: Int,
        val chunkBuilderThreads: Int,
        val socModel: String
    )

    fun detectHardware(context: Context): HardwareProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val totalRamMb = (memInfo.totalMem / (1024 * 1024)).toInt()
        val totalCores = Runtime.getRuntime().availableProcessors()

        // Thanks to FerriteCore + ModernFix, Minecraft 1.20.4 Fabric needs ~45% less RAM than vanilla.
        // Keeping heap compact reduces GC pause times and avoids Android LMK (Low Memory Killer).
        val allocatedHeapMb = when {
            totalRamMb <= 4096 -> 1408  // 4GB RAM phone -> 1.4 GB Heap
            totalRamMb <= 6144 -> 2048  // 6GB RAM phone -> 2.0 GB Heap
            totalRamMb <= 8192 -> 2816  // 8GB RAM phone -> 2.8 GB Heap
            else -> 3584                // 12GB+ RAM phone -> 3.5 GB Heap
        }

        val chunkThreads = (totalCores / 2).coerceIn(2, 4)
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL
        } else {
            Build.HARDWARE
        }

        return HardwareProfile(
            totalRamMb = totalRamMb,
            allocatedHeapMb = allocatedHeapMb,
            totalCores = totalCores,
            chunkBuilderThreads = chunkThreads,
            socModel = soc
        )
    }

    /**
     * Generates the full command-line array passed directly into `JLI_Launch` (OpenJDK 21 ARM64)
     * to boot `net.fabricmc.loader.impl.launch.knot.KnotClient` without any intermediate launcher.
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

        return arrayOf(
            "java",
            // Memory & Low-Pause Mobile G1GC Tuning
            "-Xms${hw.allocatedHeapMb}M",
            "-Xmx${hw.allocatedHeapMb}M",
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+UseG1GC",
            "-XX:MaxGCPauseMillis=25",
            "-XX:G1NewSizePercent=20",
            "-XX:G1ReservePercent=15",
            "-XX:G1HeapRegionSize=16M",
            "-XX:G1MixedGCCountTarget=4",
            "-XX:InitiatingHeapOccupancyPercent=20",
            "-XX:+AlwaysPreTouch",
            "-XX:+DisableExplicitGC",
            "-XX:+ParallelRefProcEnabled",
            "-XX:+UseStringDeduplication",

            // ARM64 SIMD & Vector Optimizations
            "-XX:+UseNeon",
            "-XX:+UseCRC32Intrinsics",
            "-Dfile.encoding=UTF-8",
            "-Djava.awt.headless=true",

            // LWJGL3-Android & VeloCraft Native Bridge Paths
            "-Djava.library.path=${nativesDir.absolutePath}",
            "-Dorg.lwjgl.librarypath=${nativesDir.absolutePath}",
            "-Dorg.lwjgl.opengl.libname=libmobileglues.so",
            "-Dvelocraft.bridge.port=28465",
            "-Dvelocraft.chunkThreads=${hw.chunkBuilderThreads}",

            // Classpath & Fabric KnotClient Main Entrypoint
            "-cp",
            classpath,
            "net.fabricmc.loader.impl.launch.knot.KnotClient",

            // Minecraft Direct Boot Arguments
            "--username", playerUsername,
            "--version", "1.20.4-VeloCraft",
            "--gameDir", gameDir.absolutePath,
            "--assetsDir", assetsDir.absolutePath,
            "--assetIndex", "12",
            "--uuid", playerUuid,
            "--accessToken", accessToken,
            "--userType", "msa",
            "--versionType", "VeloCraft-Direct"
        )
    }
}
