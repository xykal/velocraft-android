package io.velocraft.android.engine

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Manages the local directory structure, pre-tuned configuration files (`options.txt` &
 * `sodium-options.json`), Android LWJGL 3 GLFW classes jar, and external shaderpack
 * synchronization for Minecraft 1.20.4 (Fabric).
 */
class DirectBootProvisioner(private val context: Context) {

    private val activeAbi: String = if (android.os.Process.is64Bit()) "arm64-v8a" else "armeabi-v7a"
    val rootEngineDir: File = File(context.filesDir, "velocraft-engine").canonicalFile
    val gameDir: File = File(rootEngineDir, "minecraft")
    val modsDir: File = File(gameDir, "mods")
    val configDir: File = File(gameDir, "config")
    val assetsDir: File = File(rootEngineDir, "assets")
    val librariesDir: File = File(rootEngineDir, "libraries")
    val lwjglDir: File = File(rootEngineDir, "lwjgl3")
    val lwjglGlfwJar: File = File(lwjglDir, "lwjgl-glfw-classes.jar")
    val jreDir: File = File(rootEngineDir, "jre21-$activeAbi")
    val nativesDir: File = File(rootEngineDir, "natives-$activeAbi")
    private val readyMarkerFile: File = File(rootEngineDir, ".velocraft_ready_v6_$activeAbi")

    fun isEngineProvisioned(): Boolean = readyMarkerFile.exists()

    fun prepareDirectoriesAndConfigs(includeFullMusicAssets: Boolean = true) {
        listOf(gameDir, modsDir, configDir, assetsDir, librariesDir, lwjglDir, jreDir, nativesDir).forEach {
            it.mkdirs()
        }

        val profileJsonStr = context.assets.open("config/engine-profile.json")
            .bufferedReader()
            .use { it.readText() }
        val profile = JSONObject(profileJsonStr)

        RuntimeExtractor.linkApkNativeLibraries(context, nativesDir)
        extractBundledLwjglGlfwJar()
        writePreTunedOptionsTxt(profile.getJSONObject("defaultOptionsTxt"), includeFullMusicAssets)
        writePreTunedSodiumOptions(profile.getJSONObject("defaultSodiumOptions"))
        extractBundledBridgeMod()
        writeModStackManifest(profile)
        RendererProfileManager.syncExternalShaderpacks(gameDir, configDir)
    }

    fun markEngineProvisioned() {
        readyMarkerFile.writeText("provisioned_mc=1.20.4\nabi=$activeAbi\n")
    }

    private fun extractBundledLwjglGlfwJar() {
        try {
            if (!lwjglGlfwJar.exists() || lwjglGlfwJar.length() < 1_000_000L) {
                lwjglDir.mkdirs()
                context.assets.open("lwjgl3/lwjgl-glfw-classes.jar").use { input ->
                    lwjglGlfwJar.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun writePreTunedOptionsTxt(opts: JSONObject, includeFullMusicAssets: Boolean) {
        val optionsFile = File(gameDir, "options.txt")
        val hw = JvmOptimizer.detectHardware(context)
        val musicVolume = if (includeFullMusicAssets) "1.0" else "0.0"
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
            appendLine("soundCategory_music:$musicVolume")
            appendLine("soundCategory_record:$musicVolume")
            appendLine("# Auto-tuned for ${hw.socModel} (${hw.totalRamMb}MB RAM, ABI=${hw.abi})")
        }
        optionsFile.writeText(lines)
    }

    private fun writePreTunedSodiumOptions(sodiumOpts: JSONObject) {
        val sodiumFile = File(configDir, "sodium-options.json")
        if (sodiumFile.exists()) return
        val hw = JvmOptimizer.detectHardware(context)
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
