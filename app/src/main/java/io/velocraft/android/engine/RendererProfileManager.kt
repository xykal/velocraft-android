package io.velocraft.android.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import io.velocraft.android.util.VeloLogger
import java.io.File

/**
 * Multi-Renderer, Mod Stack & Shaderpack Manager supporting ALL Android GPUs
 * (Adreno, Mali, PowerVR, Xclipse) across both 64-bit (`arm64-v8a`) and 32-bit (`armeabi-v7a`).
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
            displayName = "AUTO (Deteksi GPU Otomatis)",
            libName = "auto",
            glEsVersionEnv = "3",
            supportsShaders = true,
            shortDesc = "Cocokkan otomatis renderer paling stabil sesuai GPU & kapasitas RAM HP"
        ),
        GL4ES(
            id = "GL4ES",
            displayName = "GL4ES 1.1.5 (Universal Semua HP)",
            libName = "libgl4es_114.so",
            glEsVersionEnv = "2",
            supportsShaders = false,
            shortDesc = "Kompatibilitas 100% semua perangkat (32-bit armeabi-v7a, Mali, PowerVR, Adreno)"
        ),
        MOBILEGLUES(
            id = "MOBILEGLUES",
            displayName = "MobileGlues GLES 3.2 (High-FPS + Shader)",
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
            shortDesc = "Wrapper ringan latensi rendah untuk GPU Mali & Adreno kelas menengah"
        ),
        ZINK_VULKAN(
            id = "ZINK_VULKAN",
            displayName = "Zink Vulkan (Shader PC Berat)",
            libName = "libOSMesa_zink.so",
            glEsVersionEnv = "3",
            supportsShaders = true,
            shortDesc = "Akselerasi Vulkan penuh untuk HP Flagship Snapdragon/Xclipse"
        );

        val nativeLibName: String
            get() = libName
    }

    data class ModInfo(
        val slug: String,
        val title: String,
        val category: String,
        val summary: String,
        val isEnabled: Boolean,
        val isInstalledOnDisk: Boolean
    )

    private const val PREFS_NAME = "velocraft_renderer_prefs"
    private const val KEY_SELECTED_RENDERER = "selected_renderer"
    private const val KEY_SHADERS_ENABLED = "shaders_enabled"
    private const val KEY_ACTIVE_SHADERPACK = "active_shaderpack"

    private val DEFAULT_MOD_CATALOG = listOf(
        Triple("sodium", "Sodium 0.5.8", "Engine Render Chunk Modern (+250% FPS)"),
        Triple("lithium", "Lithium 0.12.1", "Optimasi Fisika, AI Mob & Server Tick"),
        Triple("ferrite-core", "FerriteCore 6.0.3", "Penghematan Penggunaan RAM hingga 45%"),
        Triple("immediatelyfast", "ImmediatelyFast 1.2.11", "Optimasi Render GUI, HUD, Entity & Partikel"),
        Triple("entityculling", "EntityCulling 1.6.2", "Lewati Render Entitas di Balik Dinding"),
        Triple("modernfix", "ModernFix 5.17.0", "Percepatan Waktu Muat Dunia & Perbaikan Memori"),
        Triple("iris", "Iris Shaders 1.7.0", "Pipeline Shader Modern Kompatibel Sodium"),
        Triple("fabric-api", "Fabric API 0.96.4", "Pustaka Inti Ekosistem Mod Fabric 1.20.4")
    )

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

    fun getModStackStatusCompat(context: Context): List<ModInfo> = getModCatalogStatus(context)

    fun getModCatalogStatus(context: Context): List<ModInfo> {
        val provisioner = DirectBootProvisioner(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val files = provisioner.modsDir.listFiles() ?: emptyArray()

        return DEFAULT_MOD_CATALOG.map { (slug, title, summary) ->
            val key = "mod_enabled_$slug"
            val enabledPref = prefs.getBoolean(key, true)
            val cleanSlug = slug.replace("-", "")
            val matchingFile = files.firstOrNull { f ->
                f.name.lowercase().contains(cleanSlug)
            }
            val installed = matchingFile != null && matchingFile.length() > 8000L
            val enabledActual = if (matchingFile != null) {
                !matchingFile.name.endsWith(".disabled")
            } else {
                enabledPref
            }
            val cat = if (slug == "iris") "SHADER" else if (slug == "fabric-api") "CORE" else "FPS BOOST"
            ModInfo(
                slug = slug,
                title = title,
                category = cat,
                summary = summary,
                isEnabled = enabledActual,
                isInstalledOnDisk = installed
            )
        }
    }

    fun toggleModEnabled(context: Context, slug: String): Boolean {
        if (slug == "fabric-api") return true // Core dependency stays enabled
        val provisioner = DirectBootProvisioner(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = "mod_enabled_$slug"
        val current = prefs.getBoolean(key, true)
        val next = !current
        prefs.edit().putBoolean(key, next).apply()

        val cleanSlug = slug.replace("-", "")
        provisioner.modsDir.listFiles()?.forEach { file ->
            if (file.name.lowercase().contains(cleanSlug)) {
                if (!next && file.name.endsWith(".jar")) {
                    file.renameTo(File(file.parentFile, "${file.name}.disabled"))
                } else if (next && file.name.endsWith(".jar.disabled")) {
                    val newName = file.name.removeSuffix(".disabled")
                    file.renameTo(File(file.parentFile, newName))
                }
            }
        }
        VeloLogger.section("MODS", "Mod '$slug' set to enabled=$next")
        return next
    }

    fun getExternalShaderpackNames(): List<String> {
        val dir = VeloLogger.shaderpacksDir ?: return emptyList()
        val zips = dir.listFiles { f -> f.isFile && f.extension.equals("zip", ignoreCase = true) }
            ?: return emptyList()
        return zips.map { it.name }.sorted()
    }

    fun getActiveShaderpackName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_ACTIVE_SHADERPACK, "") ?: ""
        val available = getExternalShaderpackNames()
        if (saved.isNotEmpty() && available.contains(saved)) return saved
        return available.firstOrNull() ?: "(Belum ada file .zip di folder shaderpacks)"
    }

    fun cycleActiveShaderpack(context: Context): String {
        val available = getExternalShaderpackNames()
        if (available.isEmpty()) return "(Belum ada file .zip di folder shaderpacks)"
        val current = getActiveShaderpackName(context)
        val nextIdx = (available.indexOf(current) + 1) % available.size
        val chosen = available[nextIdx]
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_SHADERPACK, chosen)
            .apply()
        return chosen
    }

    fun resolveEffectiveBackend(context: Context): RendererBackend {
        val chosen = getSelectedBackend(context)
        if (chosen != RendererBackend.AUTO) {
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

    fun syncExternalShaderpacks(gameDir: File, configDir: File): Int {
        var syncedCount = 0
        try {
            val externalShaders = VeloLogger.shaderpacksDir ?: return 0
            externalShaders.mkdirs()

            val readmeFile = File(externalShaders, "CARA_PASANG_SHADER.txt")
            if (!readmeFile.exists()) {
                readmeFile.writeText(
                    "CARA PASANG SHADER DI MINECRAFT PORTING EDITION:\n" +
                        "1. Taruh file .zip Shader (contoh: MakeUp-UltraFast.zip, ComplementaryReimagined.zip)\n" +
                        "   langsung ke dalam folder ini: ${externalShaders.absolutePath}\n" +
                        "2. Pastikan opsi 'Shader Support (Iris)' di menu bernilai [ON].\n" +
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
