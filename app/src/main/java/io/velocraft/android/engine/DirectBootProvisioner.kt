package io.velocraft.android.engine

import android.content.Context
import io.velocraft.android.util.VeloLogger
import org.json.JSONObject
import java.io.File

/**
 * Manages the local directory structure, pre-tuned configuration files (`options.txt`,
 * `sodium-options.json`, `immediatelyfast.json`, `modernfix-mixins.properties`),
 * renderer-aware mod compatibility guards (preventing GL4ES 1.1.5 `GL_COPY_WRITE_BUFFER`
 * `__memcpy_a53` crashes on world join), Android LWJGL 3 GLFW classes jar,
 * and external shaderpack synchronization for Minecraft 1.20.4 (Fabric).
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
    private val lwjglMarkerFile: File = File(lwjglDir, ".lwjgl_v083_veloc")

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
        cleanupConflictingDesktopLibraries()
        writePreTunedOptionsTxt(profile.getJSONObject("defaultOptionsTxt"), includeFullMusicAssets)
        writePreTunedSodiumOptions(profile.getJSONObject("defaultSodiumOptions"))
        writePreTunedImmediatelyFastOptions()
        writePreTunedModernFixOptions()
        extractBundledBridgeMod()
        enforceRendererModCompatibility()
        writeModStackManifest(profile)
        RendererProfileManager.syncExternalShaderpacks(gameDir, configDir)
    }

    private fun cleanupConflictingDesktopLibraries() {
        try {
            val desktopLwjglDir = File(librariesDir, "org/lwjgl")
            if (desktopLwjglDir.exists()) {
                desktopLwjglDir.deleteRecursively()
            }
            val oldLoaderDir = File(librariesDir, "net/fabricmc/fabric-loader/0.16.7")
            if (oldLoaderDir.exists()) {
                oldLoaderDir.deleteRecursively()
            }
        } catch (_: Exception) {
        }
    }

    fun markEngineProvisioned() {
        readyMarkerFile.writeText("provisioned_mc=1.20.4\nabi=$activeAbi\n")
    }

    private fun extractBundledLwjglGlfwJar() {
        try {
            if (!lwjglGlfwJar.exists() || !lwjglMarkerFile.exists() || lwjglGlfwJar.length() < 1_000_000L) {
                lwjglDir.mkdirs()
                context.assets.open("lwjgl3/lwjgl-glfw-classes.jar").use { input ->
                    lwjglGlfwJar.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                lwjglMarkerFile.writeText("v0.7.8-nocheck\n")
            }
        } catch (_: Exception) {
        }
    }

    private fun writePreTunedOptionsTxt(opts: JSONObject, includeFullMusicAssets: Boolean) {
        val optionsFile = File(gameDir, "options.txt")
        val turboMarker = File(configDir, ".velocraft_turbo_v082")
        val hw = JvmOptimizer.detectHardware(context)
        val isLowEndDevice = !hw.is64Bit || hw.totalRamMb <= 3600

        if (optionsFile.exists() && optionsFile.length() > 50L && turboMarker.exists()) {
            return
        }

        val defaultRenderDist = when {
            !hw.is64Bit -> 2
            isLowEndDevice -> 3
            else -> opts.optInt("renderDistance", 6)
        }
        val defaultSimDist = if (isLowEndDevice) 5 else 6
        val musicVolume = if (includeFullMusicAssets) "1.0" else "0.0"
        val lines = buildString {
            appendLine("version:3700")
            appendLine("renderDistance:$defaultRenderDist")
            appendLine("simulationDistance:$defaultSimDist")
            appendLine("entityDistanceScaling:${if (isLowEndDevice) 0.5 else opts.optDouble("entityDistanceScaling", 0.75)}")
            appendLine("maxFps:${opts.optInt("maxFps", 120)}")
            appendLine("enableVsync:false")
            appendLine("graphicsMode:0")
            appendLine("prioritizeChunkUpdates:0")
            appendLine("ao:false")
            appendLine("renderClouds:\"false\"")
            appendLine("particles:2")
            appendLine("entityShadows:false")
            appendLine("biomeBlendRadius:0")
            appendLine("mipmapLevels:0")
            appendLine("bobView:false")
            appendLine("screenEffectScale:0.0")
            appendLine("fovEffectScale:0.0")
            appendLine("rawMouseInput:${opts.optBoolean("rawMouseInput", true)}")
            appendLine("autoJump:${opts.optBoolean("autoJump", false)}")
            appendLine("guiScale:${opts.optInt("guiScale", 2)}")
            appendLine("onboardAccessibility:false")
            appendLine("soundCategory_music:$musicVolume")
            appendLine("soundCategory_record:$musicVolume")
        }
        optionsFile.writeText(lines)
        turboMarker.writeText("v0.8.2\n")
    }

    /**
     * Configures Sodium 0.5.8 (`sodium-options.json`) to pair with `libvelocraft_turbo.so` C++
     * MultiDraw + DSA Staging Buffer acceleration for maximum FPS on Redmi A2 / Helio G36 / PowerVR.
     */
    private fun writePreTunedSodiumOptions(sodiumOpts: JSONObject) {
        val sodiumFile = File(configDir, "sodium-options.json")
        val hw = JvmOptimizer.detectHardware(context)
        val root = JSONObject().apply {
            put("quality", JSONObject().apply {
                put("weather_quality", sodiumOpts.optString("quality.weather_quality", "FAST"))
                put("leaves_quality", sodiumOpts.optString("quality.leaves_quality", "FAST"))
                put("enable_vignette", false)
                put("enable_clouds", false)
            })
            put("performance", JSONObject().apply {
                put("chunk_builder_threads", hw.chunkBuilderThreads)
                put("always_defer_chunk_updates", true)
                put("animate_only_visible_textures", true)
                put("use_entity_culling", true)
                put("use_block_face_culling", true)
                put("use_fog_occlusion", true)
                put("use_compact_vertex_format", true)
                put("use_no_error_g_l_context", true)
            })
            put("advanced", JSONObject().apply {
                put("enable_memory_tracing", false)
                put("use_advanced_staging_buffers", false)
                put("cpu_render_ahead_limit", 2)
                put("max_pre_rendered_frames", 2)
            })
        }
        sodiumFile.writeText(root.toString(2))
    }

    /**
     * Disables ImmediatelyFast's persistent ring buffer (`fast_buffer_upload: false`) which
     * otherwise crashes PowerVR / Mali GPUs on world join.
     */
    private fun writePreTunedImmediatelyFastOptions() {
        val ifFile = File(configDir, "immediatelyfast.json")
        val root = JSONObject().apply {
            put("font_atlas_resizing", false)
            put("map_atlas_generation", true)
            put("hud_batching", true)
            put("fast_text_lookup", true)
            put("fast_buffer_upload", false)
            put("experimental_screen_batching", false)
        }
        ifFile.writeText(root.toString(2))
    }

    /**
     * Enables ModernFix's `dynamic_resources` and `class_search_cache` so Minecraft 1.20.4
     * skips pre-baking 30,000+ block/item models during startup, cutting Mojang Splash time by ~60%.
     */
    private fun writePreTunedModernFixOptions() {
        val mfFile = File(configDir, "modernfix-mixins.properties")
        val content = buildString {
            appendLine("# Auto-tuned for fast Android boot & low memory footprint")
            appendLine("mixin.perf.dynamic_resources=true")
            appendLine("mixin.perf.faster_item_rendering=true")
            appendLine("mixin.perf.deduplicate_location=true")
            appendLine("mixin.perf.compact_bit_storage=true")
            appendLine("mixin.launch.class_search_cache=true")
        }
        mfFile.writeText(content)
    }

    /**
     * Prevents Mojang Splash (`GL32C.glFenceSync` stub in `libgl4es_114.so`) and 100% World Join
     * (`GL_COPY_WRITE_BUFFER` + `uvec4` GLSL 330 chunk shaders) crashes on `GL4ES 1.1.5` by
     * keeping GL32/GL33-only mods (`sodium`, `iris`, `immediatelyfast`) disabled on `GL4ES 1.1.5`,
     * while ensuring CPU/memory/culling mods (`lithium`, `ferritecore`, `modernfix`, `entityculling`)
     * remain active alongside `libvelocraft_turbo.so` hardware resolution scaling.
     */
    private fun enforceRendererModCompatibility() {
        try {
            val effectiveRenderer = RendererProfileManager.resolveEffectiveBackend(context)
            val isGl4es = effectiveRenderer == RendererProfileManager.RendererBackend.GL4ES
            val shadersActive = effectiveRenderer.supportsShaders && RendererProfileManager.isShaderSupportEnabled(context)

            val files = modsDir.listFiles() ?: return
            for (file in files) {
                val lower = file.name.lowercase()
                val isGl32OnlyMod = lower.contains("sodium") ||
                    lower.contains("iris") ||
                    lower.contains("immediatelyfast")
                val isIrisOnly = lower.contains("iris")

                if (isGl4es && isGl32OnlyMod) {
                    if (file.name.endsWith(".jar")) {
                        val disabled = File(file.parentFile, "${file.name}.disabled")
                        if (disabled.exists()) disabled.delete()
                        file.renameTo(disabled)
                        VeloLogger.section(
                            "KOMPATIBILITAS-MOD",
                            "GL4ES 1.1.5 aktif: Menonaktifkan sementara ${file.name} untuk mencegah crash Splash Mojang & 100% masuk dunia."
                        )
                    }
                } else if (!isGl4es) {
                    if (isIrisOnly && !shadersActive) {
                        if (file.name.endsWith(".jar")) {
                            val disabled = File(file.parentFile, "${file.name}.disabled")
                            if (disabled.exists()) disabled.delete()
                            file.renameTo(disabled)
                        }
                    } else if (isGl32OnlyMod && file.name.endsWith(".jar.disabled")) {
                        val cleanName = file.name.removeSuffix(".disabled")
                        val restored = File(file.parentFile, cleanName)
                        if (restored.exists()) restored.delete()
                        file.renameTo(restored)
                    }
                }
            }
        } catch (_: Exception) {
        }
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
        try {
            val outModMenu = File(modsDir, "modmenu-9.2.0.jar")
            val disabledModMenu = File(modsDir, "modmenu-9.2.0.jar.disabled")
            if (!disabledModMenu.exists() && (!outModMenu.exists() || outModMenu.length() < 500_000L)) {
                context.assets.open("mods/modmenu-9.2.0.jar").use { input ->
                    outModMenu.outputStream().use { output ->
                        input.copyTo(output)
                    }
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
