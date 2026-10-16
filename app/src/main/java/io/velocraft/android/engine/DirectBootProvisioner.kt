package io.velocraft.android.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * DMCA-Safe Autonomous First-Boot Provisioner ("Tanpa Launcher").
 *
 * Orchestrates:
 * 1. Linking APK native ARM64 libraries (`libvelocraft_bridge.so`, `libmobileglues.so`).
 * 2. Writing hardware-tuned `options.txt` and `sodium-options.json`.
 * 3. Extracting bundled `velocraft-bridge-mod.jar` into `mods/`.
 * 4. Fetching official Modrinth optimization mods (Sodium, Lithium, FerriteCore, ImmediatelyFast, EntityCulling, ModernFix).
 * 5. Resolving Fabric Loader + official Minecraft 1.20.4 libraries from Mojang & Fabric CDNs.
 */
class DirectBootProvisioner(private val context: Context) {

    data class ProvisionProgress(
        val stepIndex: Int,
        val totalSteps: Int,
        val statusText: String,
        val percent: Int
    )

    private val activeAbi: String = if (android.os.Process.is64Bit()) "arm64-v8a" else "armeabi-v7a"
    val rootEngineDir: File = File(context.filesDir, "velocraft-engine")
    val gameDir: File = File(rootEngineDir, "minecraft")
    val modsDir: File = File(gameDir, "mods")
    val configDir: File = File(gameDir, "config")
    val assetsDir: File = File(rootEngineDir, "assets")
    val librariesDir: File = File(rootEngineDir, "libraries")
    val jreDir: File = File(rootEngineDir, "jre21-$activeAbi")
    val nativesDir: File = File(rootEngineDir, "natives-$activeAbi")
    private val readyMarkerFile: File = File(rootEngineDir, ".velocraft_ready_v3_$activeAbi")

    fun isEngineProvisioned(): Boolean = readyMarkerFile.exists()

    suspend fun ensureProvisioned(
        syncOnlineAssets: Boolean = true,
        onProgress: (ProvisionProgress) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (isEngineProvisioned()) return@withContext

        listOf(gameDir, modsDir, configDir, assetsDir, librariesDir, jreDir, nativesDir).forEach {
            it.mkdirs()
        }

        val profileJsonStr = context.assets.open("config/engine-profile.json")
            .bufferedReader()
            .use { it.readText() }
        val profile = JSONObject(profileJsonStr)
        val mcVersion = profile.optString("minecraftVersion", "1.20.4")
        val fabricVersion = profile.optString("fabricLoaderVersion", "0.16.7")

        // Step 1: Link Native ARM64 Bridges & Verify OpenJDK 21 ARM64 Runtime
        onProgress(ProvisionProgress(1, 5, "Menyiapkan Native Bridge ARM64 & OpenJDK 21...", 12))
        RuntimeExtractor.linkApkNativeLibraries(context, nativesDir)
        val runtimeDownloader = Arm64RuntimeDownloader(jreDir, nativesDir)
        runtimeDownloader.ensureArm64RuntimeReady { status, _ ->
            onProgress(ProvisionProgress(1, 5, status, 18))
        }
        writePreTunedOptionsTxt(profile.getJSONObject("defaultOptionsTxt"))

        // Step 2: Write Pre-Tuned Sodium & FerriteCore Config
        onProgress(ProvisionProgress(2, 5, "Mengoptimalkan konfigurasi Sodium & FerriteCore...", 28))
        writePreTunedSodiumOptions(profile.getJSONObject("defaultSodiumOptions"))

        // Step 3: Extract Companion VeloCraft Fabric Bridge Mod
        onProgress(ProvisionProgress(3, 5, "Memasang VeloCraft Native Bridge Mod (Fabric)...", 45))
        extractBundledBridgeMod()
        writeModStackManifest(profile)

        // Step 4: Fetch Modrinth Optimization Stack (Sodium, Lithium, FerriteCore, ImmediatelyFast, etc.)
        if (syncOnlineAssets) {
            val modInstaller = ModrinthStackInstaller(modsDir)
            modInstaller.installOptimizationModpack(mcVersion) { modName, idx, total ->
                val pct = 45 + ((idx * 25) / total.coerceAtLeast(1))
                onProgress(
                    ProvisionProgress(
                        4,
                        5,
                        "Mengunduh Mod Optimasi [$idx/$total]: $modName...",
                        pct
                    )
                )
            }

            // Step 5: Resolve Fabric Loader & Official Minecraft Libraries
            try {
                val resolver = MojangManifestResolver(librariesDir, gameDir)
                resolver.resolveAndDownloadCoreLibraries(mcVersion, fabricVersion) { done, total, name ->
                    val pct = 70 + ((done * 28) / total.coerceAtLeast(1))
                    onProgress(
                        ProvisionProgress(
                            5,
                            5,
                            "Menyiapkan Engine [$done/$total]: $name",
                            pct.coerceAtMost(99)
                        )
                    )
                }
            } catch (_: Exception) {
                // Offline fallback: allow local cached libraries to proceed
            }
        }

        onProgress(ProvisionProgress(5, 5, "VeloCraft Direct siap! Masuk ke dalam game...", 100))
        readyMarkerFile.writeText("provisioned_mc=$mcVersion\nfabric=$fabricVersion\n")
    }

    private fun writePreTunedOptionsTxt(opts: JSONObject) {
        val optionsFile = File(gameDir, "options.txt")
        val hw = JvmOptimizer.detectHardware(context)
        val lines = buildString {
            appendLine("version:3700")
            appendLine("renderDistance:${opts.optInt("renderDistance", 6)}")
            appendLine("simulationDistance:${opts.optInt("simulationDistance", 5)}")
            appendLine("entityDistanceScaling:${opts.optDouble("entityDistanceScaling", 0.75)}")
            appendLine("maxFps:${opts.optInt("maxFps", 120)}")
            appendLine("enableVsync:${opts.optBoolean("enableVsync", false)}")
            appendLine("graphicsMode:0")
            appendLine("renderClouds:\"false\"")
            appendLine("particles:2")
            appendLine("biomeBlendRadius:${opts.optInt("biomeBlendRadius", 1)}")
            appendLine("mipmapLevels:${opts.optInt("mipmapLevels", 1)}")
            appendLine("rawMouseInput:${opts.optBoolean("rawMouseInput", true)}")
            appendLine("autoJump:${opts.optBoolean("autoJump", false)}")
            appendLine("guiScale:${opts.optInt("guiScale", 2)}")
            appendLine("# Auto-tuned by VeloCraft Direct for ${hw.socModel} (${hw.totalRamMb}MB RAM)")
        }
        optionsFile.writeText(lines)
    }

    private fun writePreTunedSodiumOptions(sodiumOpts: JSONObject) {
        val hw = JvmOptimizer.detectHardware(context)
        val sodiumFile = File(configDir, "sodium-options.json")
        val root = JSONObject().apply {
            put("quality", JSONObject().apply {
                put("weather_quality", sodiumOpts.optString("quality.weather_quality", "FAST"))
                put("leaves_quality", sodiumOpts.optString("quality.leaves_quality", "FAST"))
                put("enable_vignette", false)
            })
            put("performance", JSONObject().apply {
                put("chunk_builder_threads", hw.chunkBuilderThreads)
                put("always_defer_chunk_updates", true)
                put("animate_only_visible_textures", true)
                put("use_entity_culling", true)
                put("use_fog_occlusion", true)
                put("use_compact_vertex_format", true)
            })
            put("advanced", JSONObject().apply {
                put("enable_memory_tracing", false)
                put("use_advanced_staging_buffers", true)
            })
        }
        sodiumFile.writeText(root.toString(2))
    }

    private fun extractBundledBridgeMod() {
        try {
            val outMod = File(modsDir, "velocraft-bridge-mod.jar")
            context.assets.open("mods/velocraft-bridge-mod.jar").use { input ->
                outMod.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun writeModStackManifest(profile: JSONObject) {
        val manifestOut = File(modsDir, "velocraft-modstack.json")
        manifestOut.writeText(profile.getJSONArray("optimizationMods").toString(2))
    }
}
