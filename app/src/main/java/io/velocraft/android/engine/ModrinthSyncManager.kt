package io.velocraft.android.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.velocraft.android.util.VeloLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Live Modrinth v2 REST API Browser, Searcher & Downloader for Minecraft 1.20.4 (Fabric).
 *
 * Allows searching any Fabric 1.20.4 mod on `api.modrinth.com/v2`, syncing popular community
 * mods, downloading `.jar` files directly into `.minecraft/mods/`, and toggling installed mods.
 */
object ModrinthSyncManager {

    data class ModrinthProjectItem(
        val slug: String,
        val title: String,
        val summary: String,
        val downloadsText: String,
        val categoryTag: String,
        val isInstalled: Boolean,
        val isEnabled: Boolean,
        val installedFileName: String = ""
    )

    private val networkExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var currentSearchQuery: String = ""

    @Volatile
    var syncStatusMessage: String = "Siap sinkronisasi katalog Modrinth (Minecraft 1.20.4 Fabric)"
        private set

    @Volatile
    var activeDownloadSlug: String? = null
        private set

    private val CURATED_MODRINTH_1204 = listOf(
        ModrinthProjectItem(
            slug = "appleskin",
            title = "AppleSkin",
            summary = "Menampilkan indikator saturasi makanan & pemulihan kelaparan di HUD",
            downloadsText = "48M+ Unduhan",
            categoryTag = "HUD & INFO",
            isInstalled = false,
            isEnabled = false
        ),
        ModrinthProjectItem(
            slug = "dynamic-fps",
            title = "Dynamic FPS",
            summary = "Hemat baterai & CPU saat jendela game tidak aktif atau di latar belakang",
            downloadsText = "32M+ Unduhan",
            categoryTag = "OPTIMASI",
            isInstalled = false,
            isEnabled = false
        ),
        ModrinthProjectItem(
            slug = "notenoughanimations",
            title = "Not Enough Animations",
            summary = "Animasi orang ketiga yang mulus saat makan, minum, memanah & mendayung",
            downloadsText = "29M+ Unduhan",
            categoryTag = "VISUAL",
            isInstalled = false,
            isEnabled = false
        ),
        ModrinthProjectItem(
            slug = "3dskinlayers",
            title = "3D Skin Layers",
            summary = "Merender lapisan kedua skin karakter menjadi 3D voxel nyata",
            downloadsText = "26M+ Unduhan",
            categoryTag = "VISUAL",
            isInstalled = false,
            isEnabled = false
        ),
        ModrinthProjectItem(
            slug = "cloth-config",
            title = "Cloth Config API",
            summary = "Pustaka layar konfigurasi mod untuk Mod Menu Fabric 1.20.4",
            downloadsText = "65M+ Unduhan",
            categoryTag = "LIBRARY",
            isInstalled = false,
            isEnabled = false
        ),
        ModrinthProjectItem(
            slug = "zoomify",
            title = "Zoomify",
            summary = "Fitur zoom kamera presisi tinggi dengan transisi sinematik",
            downloadsText = "15M+ Unduhan",
            categoryTag = "UTILITAS",
            isInstalled = false,
            isEnabled = false
        ),
        ModrinthProjectItem(
            slug = "lambdynamiclights",
            title = "LambDynamicLights",
            summary = "Cahaya dinamis saat memegang obor atau item bercahaya di tangan",
            downloadsText = "19M+ Unduhan",
            categoryTag = "VISUAL",
            isInstalled = false,
            isEnabled = false
        ),
        ModrinthProjectItem(
            slug = "jade",
            title = "Jade (WAILA)",
            summary = "Menampilkan informasi blok, isi peti & entitas yang sedang ditatap",
            downloadsText = "42M+ Unduhan",
            categoryTag = "HUD & INFO",
            isInstalled = false,
            isEnabled = false
        ),
        ModrinthProjectItem(
            slug = "mouse-tweaks",
            title = "Mouse Tweaks",
            summary = "Mempercepat drag-and-drop item di dalam Inventory & Crafting Table",
            downloadsText = "35M+ Unduhan",
            categoryTag = "KONTROL",
            isInstalled = false,
            isEnabled = false
        ),
        ModrinthProjectItem(
            slug = "continuity",
            title = "Continuity",
            summary = "Tekstur kaca & blok menyatu (Connected Textures) tanpa OptiFine",
            downloadsText = "18M+ Unduhan",
            categoryTag = "VISUAL",
            isInstalled = false,
            isEnabled = false
        )
    )

    @Volatile
    private var cachedResults: List<ModrinthProjectItem> = CURATED_MODRINTH_1204

    fun getCatalogWithDiskState(context: Context): List<ModrinthProjectItem> {
        val provisioner = DirectBootProvisioner(context)
        val diskFiles = provisioner.modsDir.listFiles() ?: emptyArray()

        return cachedResults.map { item ->
            val cleanSlug = item.slug.lowercase().replace("-", "").replace("_", "")
            val matched = diskFiles.firstOrNull { f ->
                val cleanName = f.name.lowercase().replace("-", "").replace("_", "")
                cleanName.contains(cleanSlug) && (f.name.endsWith(".jar") || f.name.endsWith(".jar.disabled"))
            }
            val installed = matched != null && matched.length() > 4096L
            val enabled = matched != null && !matched.name.endsWith(".disabled")
            item.copy(
                isInstalled = installed,
                isEnabled = enabled,
                installedFileName = matched?.name ?: ""
            )
        }
    }

    /**
     * Searches Modrinth v2 API for Minecraft 1.20.4 Fabric mods matching `query`.
     */
    fun searchModsOnModrinth(
        context: Context,
        query: String,
        onComplete: () -> Unit
    ) {
        currentSearchQuery = query.trim()
        if (currentSearchQuery.isBlank()) {
            cachedResults = CURATED_MODRINTH_1204
            syncStatusMessage = "Menampilkan katalog kurasi Modrinth populer (1.20.4 Fabric)"
            mainHandler.post { onComplete() }
            return
        }

        syncStatusMessage = "Mencari '${currentSearchQuery}' di Modrinth API (1.20.4 Fabric)..."
        mainHandler.post { onComplete() }

        networkExecutor.execute {
            try {
                val facets = """[["versions:1.20.4"],["categories:fabric"],["project_type:mod"]]"""
                val url = "https://api.modrinth.com/v2/search" +
                    "?query=${URLEncoder.encode(currentSearchQuery, "UTF-8")}" +
                    "&facets=${URLEncoder.encode(facets, "UTF-8")}" +
                    "&limit=16"

                val rawJson = httpGet(url)
                val root = JSONObject(rawJson)
                val hits = root.optJSONArray("hits") ?: JSONArray()
                val found = mutableListOf<ModrinthProjectItem>()

                for (i in 0 until hits.length()) {
                    val obj = hits.getJSONObject(i)
                    val slug = obj.optString("slug", "")
                    val title = obj.optString("title", slug)
                    val desc = obj.optString("description", "")
                    val downloads = obj.optLong("downloads", 0L)
                    val cats = obj.optJSONArray("categories")
                    val catTag = if (cats != null && cats.length() > 0) {
                        cats.optString(0, "FABRIC").uppercase()
                    } else {
                        "FABRIC"
                    }
                    if (slug.isNotBlank()) {
                        found.add(
                            ModrinthProjectItem(
                                slug = slug,
                                title = title,
                                summary = desc,
                                downloadsText = formatDownloads(downloads),
                                categoryTag = catTag,
                                isInstalled = false,
                                isEnabled = false
                            )
                        )
                    }
                }

                if (found.isNotEmpty()) {
                    cachedResults = found
                    syncStatusMessage = "Ditemukan ${found.size} mod 1.20.4 Fabric dari Modrinth untuk '${currentSearchQuery}'."
                } else {
                    syncStatusMessage = "Tidak ditemukan mod 1.20.4 Fabric untuk '${currentSearchQuery}'."
                }
            } catch (t: Throwable) {
                syncStatusMessage = "Gagal terhubung ke Modrinth API: ${t.message?.take(48)}"
                VeloLogger.warn("Modrinth search error: ${t.message}")
            }
            mainHandler.post { onComplete() }
        }
    }

    /**
     * Downloads or toggles a Modrinth mod for Minecraft 1.20.4 (Fabric).
     */
    fun installOrToggleMod(
        context: Context,
        item: ModrinthProjectItem,
        onUpdate: () -> Unit
    ) {
        val provisioner = DirectBootProvisioner(context)
        provisioner.modsDir.mkdirs()

        // If already installed on disk, toggle enabled/disabled state!
        val cleanSlug = item.slug.lowercase().replace("-", "").replace("_", "")
        val existing = provisioner.modsDir.listFiles()?.firstOrNull { f ->
            val cleanName = f.name.lowercase().replace("-", "").replace("_", "")
            cleanName.contains(cleanSlug) && (f.name.endsWith(".jar") || f.name.endsWith(".jar.disabled"))
        }

        if (existing != null && existing.length() > 4096L) {
            if (existing.name.endsWith(".jar.disabled")) {
                val enabledFile = File(existing.parentFile, existing.name.removeSuffix(".disabled"))
                existing.renameTo(enabledFile)
                syncStatusMessage = "Mod '${item.title}' diaktifkan (ON)."
            } else {
                val disabledFile = File(existing.parentFile, "${existing.name}.disabled")
                existing.renameTo(disabledFile)
                syncStatusMessage = "Mod '${item.title}' dinonaktifkan (OFF)."
            }
            mainHandler.post { onUpdate() }
            return
        }

        if (activeDownloadSlug != null) {
            syncStatusMessage = "Sedang mengunduh '${activeDownloadSlug}', harap tunggu..."
            mainHandler.post { onUpdate() }
            return
        }

        activeDownloadSlug = item.slug
        syncStatusMessage = "Mengambil rilis '${item.title}' (1.20.4 Fabric) dari Modrinth..."
        mainHandler.post { onUpdate() }

        networkExecutor.execute {
            try {
                val loadersParam = URLEncoder.encode("""["fabric"]""", "UTF-8")
                val versionsParam = URLEncoder.encode("""["1.20.4"]""", "UTF-8")
                val verUrl = "https://api.modrinth.com/v2/project/${URLEncoder.encode(item.slug, "UTF-8")}/version" +
                    "?loaders=$loadersParam&game_versions=$versionsParam"

                val verJsonStr = httpGet(verUrl)
                val verArr = JSONArray(verJsonStr)
                if (verArr.length() == 0) {
                    throw IllegalStateException("Versi 1.20.4 Fabric tidak ditemukan untuk ${item.slug}")
                }

                val latestVer = verArr.getJSONObject(0)
                val filesArr = latestVer.getJSONArray("files")
                var chosenFileObj: JSONObject? = null
                for (i in 0 until filesArr.length()) {
                    val fObj = filesArr.getJSONObject(i)
                    if (fObj.optBoolean("primary", false) || chosenFileObj == null) {
                        chosenFileObj = fObj
                    }
                }

                val fileObj = chosenFileObj ?: throw IllegalStateException("File .jar tidak tersedia")
                val downloadUrl = fileObj.getString("url")
                val rawFileName = fileObj.optString("filename", "${item.slug}-1.20.4-fabric.jar")
                val outFileName = if (rawFileName.lowercase().contains(cleanSlug)) {
                    rawFileName
                } else {
                    "${item.slug}-$rawFileName"
                }

                val targetFile = File(provisioner.modsDir, outFileName)
                val tmpFile = File(provisioner.modsDir, "$outFileName.part")

                syncStatusMessage = "Mengunduh $outFileName dari CDN Modrinth..."
                mainHandler.post { onUpdate() }

                downloadBinaryFile(downloadUrl, tmpFile)
                if (targetFile.exists()) targetFile.delete()
                tmpFile.renameTo(targetFile)

                val kb = targetFile.length() / 1024L
                syncStatusMessage = "Berhasil memasang '${item.title}' ($kb KB) ke .minecraft/mods/!"
                VeloLogger.section("MODRINTH", "Installed Modrinth mod '${item.title}' -> ${targetFile.name} ($kb KB)")
            } catch (t: Throwable) {
                syncStatusMessage = "Gagal mengunduh '${item.title}': ${t.message?.take(52)}"
                VeloLogger.warn("Modrinth download failed for ${item.slug}: ${t.message}")
            } finally {
                activeDownloadSlug = null
                mainHandler.post { onUpdate() }
            }
        }
    }

    private fun formatDownloads(count: Long): String {
        return when {
            count >= 1_000_000L -> String.format("%.1fM Unduhan", count / 1_000_000.0)
            count >= 1_000L -> String.format("%.1fK Unduhan", count / 1_000.0)
            else -> "$count Unduhan"
        }
    }

    private fun httpGet(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 12000
            setRequestProperty("User-Agent", "XyverseTechnologyGlobal/MinecraftPortingEdition/0.7.8 (id.xyverse.minecraft)")
            setRequestProperty("Accept", "application/json")
        }
        return conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    private fun downloadBinaryFile(urlStr: String, destFile: File) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "XyverseTechnologyGlobal/MinecraftPortingEdition/0.7.8 (id.xyverse.minecraft)")
        }
        conn.inputStream.use { input ->
            FileOutputStream(destFile).use { output ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                }
                output.flush()
            }
        }
    }
}
