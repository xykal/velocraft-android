package io.velocraft.android.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import io.velocraft.android.util.VeloLogger
import java.io.File

/**
 * Multi-Renderer & Shader Manager supporting ALL Android GPUs (Adreno, Mali, PowerVR, Xclipse)
 * across both 64-bit (`arm64-v8a`) and 32-bit (`armeabi-v7a`).
 *
 * Automatically matches the optimal renderer (`AUTO_MATCH`) to the phone's hardware,
 * or allows manual selection in the Custom Settings Modal.
 */
object RendererProfileManager {

    enum class RendererBackend(
        val id: String,
        val displayName: String,
        val libName: String,
        val glEsVersionEnv: String,
        val supportsShaders: Boolean,
        val shortDesc: String
    ) {
        AUTO(
            id = "AUTO",
            displayName = "AUTO (Match Device GPU)",
            libName = "auto",
            glEsVersionEnv = "3",
            supportsShaders = true,
            shortDesc = "Deteksi otomatis GPU & RAM HP untuk memilih renderer paling stabil dan kencang"
        ),
        GL4ES(
            id = "GL4ES",
            displayName = "GL4ES 1.1.5 (Universal All-Device)",
            libName = "libgl4es_114.so",
            glEsVersionEnv = "2",
            supportsShaders = false,
            shortDesc = "Kompatibilitas 100% semua HP (32-bit armeabi-v7a, Mali, PowerVR, Adreno lama)"
        ),
        MOBILEGLUES(
            id = "MOBILEGLUES",
            displayName = "MobileGlues GLES 3.2 (High-FPS + Shaders)",
            libName = "libmobileglues.so",
            glEsVersionEnv = "3",
            supportsShaders = true,
            shortDesc = "Renderer multidraw modern untuk Sodium + Iris Shaders di HP 64-bit"
        ),
        LTW(
            id = "LTW",
            displayName = "LTW (LightThinWrapper GLES 3.x)",
            libName = "libltw.so",
            glEsVersionEnv = "3",
            supportsShaders = false,
            shortDesc = "Wrapper ringan latensi rendah untuk GPU Mali & Adreno menengah"
        ),
        ZINK_VULKAN(
            id = "ZINK_VULKAN",
            displayName = "Zink Vulkan (Flagship PC Shaders)",
            libName = "libOSMesa_zink.so",
            glEsVersionEnv = "3",
            supportsShaders = true,
            shortDesc = "Akselerasi Vulkan penuh untuk HP Flagship Snapdragon/Xclipse dengan Shader berat"
        );

        val nativeLibName: String
            get() = libName
    }

    private const val PREFS_NAME = "velocraft_renderer_prefs"
    private const val KEY_SELECTED_RENDERER = "selected_renderer"
    private const val KEY_SHADERS_ENABLED = "shaders_enabled"

    fun getSelectedBackend(context: Context): RendererBackend {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_SELECTED_RENDERER, RendererBackend.AUTO.id) ?: RendererBackend.AUTO.id
        return RendererBackend.values().firstOrNull { it.id == saved } ?: RendererBackend.AUTO
    }

    fun setSelectedBackend(context: Context, backend: RendererBackend) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_RENDERER, backend.id)
            .apply()
        val resolved = resolveEffectiveBackend(context)
        VeloLogger.section(
            "RENDERER",
            "User selected renderer mode: ${backend.displayName} -> Effective library: ${resolved.libName}"
        )
    }

    fun isShaderSupportEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_SHADERS_ENABLED)) {
            // Default enable shader support on 64-bit phones with >= 6GB RAM
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mem = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mem)
            val ramMb = mem.totalMem / (1024L * 1024L)
            return Process.is64Bit() && ramMb >= 5500L
        }
        return prefs.getBoolean(KEY_SHADERS_ENABLED, false)
    }

    fun setShaderSupportEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHADERS_ENABLED, enabled)
            .apply()
        VeloLogger.section("RENDERER", "Shader pipeline (Iris Shaders + shaderpacks sync) set to: $enabled")
    }

    /**
     * Resolves `AUTO` into the concrete `RendererBackend` best suited for the device's CPU ABI, RAM, and SoC.
     */
    fun resolveEffectiveBackend(context: Context): RendererBackend {
        val chosen = getSelectedBackend(context)
        if (chosen != RendererBackend.AUTO) {
            // Safety guard: 32-bit armeabi-v7a always falls back safely if Zink/MobileGlues isn't available
            if (!Process.is64Bit() && (chosen == RendererBackend.ZINK_VULKAN || chosen == RendererBackend.MOBILEGLUES)) {
                VeloLogger.section("RENDERER", "32-bit armeabi-v7a detected; auto-falling back from ${chosen.id} to GL4ES safe mode.")
                return RendererBackend.GL4ES
            }
            return chosen
        }

        val is64 = Process.is64Bit()
        if (!is64) {
            return RendererBackend.GL4ES
        }

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)
        val totalRamMb = (mem.totalMem / (1024L * 1024L)).toInt()

        val soc = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else Build.HARDWARE).lowercase()

        return when {
            totalRamMb < 3800 -> RendererBackend.GL4ES
            soc.contains("mt67") || soc.contains("helio") || soc.contains("unisoc") -> RendererBackend.LTW
            else -> RendererBackend.MOBILEGLUES
        }
    }

    fun syncExternalShaderpacks(context: Context, gameDir: File): Int {
        val configDir = File(gameDir, "config").apply { mkdirs() }
        return syncExternalShaderpacks(gameDir, configDir)
    }

    /**
     * Syncs `.zip` shaderpacks placed by the user inside
     * `/storage/emulated/0/Android/media/io.velocraft.android/shaderpacks/`
     * into the game's internal `.minecraft/shaderpacks/` folder and configures `iris.properties`.
     */
    fun syncExternalShaderpacks(gameDir: File, configDir: File): Int {
        var syncedCount = 0
        try {
            val externalShaders = VeloLogger.shaderpacksDir ?: return 0
            externalShaders.mkdirs()

            // Write a helpful README inside Android/media/io.velocraft.android/shaderpacks/
            val readmeFile = File(externalShaders, "CARA_PASANG_SHADER.txt")
            if (!readmeFile.exists()) {
                readmeFile.writeText(
                    "CARA PASANG SHADER DI MINECRAFT PORTING EDITION:\n" +
                        "1. Taruh file .zip Shader (contoh: MakeUp-UltraFast.zip, ComplementaryReimagined.zip)\n" +
                        "   langsung ke dalam folder ini: ${externalShaders.absolutePath}\n" +
                        "2. Pastikan opsi 'Shader Support (Iris)' di menu CFG bernilai [ON].\n" +
                        "3. Saat game dijalankan, seluruh shader .zip di folder ini otomatis disinkronkan ke dalam game!\n"
                )
            }

            val internalShadersDir = File(gameDir, "shaderpacks").apply { mkdirs() }
            var firstShaderName: String? = null

            externalShaders.listFiles()?.forEach { file ->
                if (file.isFile && file.extension.equals("zip", ignoreCase = true)) {
                    val target = File(internalShadersDir, file.name)
                    if (!target.exists() || target.length() != file.length()) {
                        file.copyTo(target, overwrite = true)
                    }
                    if (firstShaderName == null) {
                        firstShaderName = file.name
                    }
                    syncedCount++
                }
            }

            if (syncedCount > 0 && firstShaderName != null) {
                val irisProps = File(configDir, "iris.properties")
                if (!irisProps.exists()) {
                    irisProps.writeText("shaderPack=$firstShaderName\nenableShaders=true\n")
                }
                VeloLogger.section("RENDERER", "Synced $syncedCount shaderpack(s) from ${externalShaders.absolutePath} (active: $firstShaderName).")
            }
        } catch (t: Throwable) {
            VeloLogger.warn("Shaderpack sync warning: ${t.message}")
        }
        return syncedCount
    }
}
