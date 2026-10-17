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
import java.util.zip.ZipInputStream

/**
 * Multi-Category Content Hub, Live Modrinth v2 REST API Browser, Searcher & Auto-Installer
 * for Minecraft 1.20.4 (Fabric):
 * 1. `MODS` -> `.minecraft/mods/*.jar`
 * 2. `SHADERS` -> `.minecraft/shaderpacks/*.zip`
 * 3. `RESOURCE_PACKS` (Tekstur) -> `.minecraft/resourcepacks/*.zip` (+ auto-enable in `options.txt`)
 * 4. `MAPS_WORLDS` (Map & Dunia) -> `.minecraft/saves/<MapFolder>/` (auto-extracted for Singleplayer)
 */
object ModrinthSyncManager {

    enum class ContentCategory(
        val shortTab: String,
        val fullTitle: String,
        val modrinthProjectType: String
    ) {
        MODS("MODS (.JAR)", "MOD FABRIC 1.20.4", "mod"),
        SHADERS("SHADER (.ZIP)", "SHADERPACK VISUAL", "shader"),
        RESOURCE_PACKS("TEKSTUR (RP)", "RESOURCE PACK & TEKSTUR", "resourcepack"),
        MAPS_WORLDS("MAP & DUNIA", "MAP SINGLEPLAYER & DUNIA", "datapack")
    }

    data class ModrinthProjectItem(
        val slug: String,
        val title: String,
        val summary: String,
        val downloadsText: String,
        val categoryTag: String,
        val contentCategory: ContentCategory = ContentCategory.MODS,
        val isInstalled: Boolean = false,
        val isEnabled: Boolean = false,
        val installedFileName: String = ""
    )

    private val networkExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var activeCategory: ContentCategory = ContentCategory.MODS
        private set

    @Volatile
    var currentSearchQuery: String = ""

    @Volatile
    var syncStatusMessage: String = "Katalog Lengkap siap: Pilih tab MODS, SHADER, TEKSTUR, atau MAP & DUNIA."
        private set

    @Volatile
    var activeDownloadSlug: String? = null
        private set

    private var currentOffsetByCategory = mutableMapOf(
        ContentCategory.MODS to 0,
        ContentCategory.SHADERS to 0,
        ContentCategory.RESOURCE_PACKS to 0,
        ContentCategory.MAPS_WORLDS to 0
    )

    // =========================================================================================
    // 1. CURATED STARTER CATALOG: 24 POPULAR 1.20.4 FABRIC MODS
    // =========================================================================================
    private val CURATED_MODS_1204 = listOf(
        ModrinthProjectItem("appleskin", "AppleSkin", "Indikator saturasi makanan & pemulihan lapar di HUD", "48M+ Unduhan", "HUD & INFO", ContentCategory.MODS),
        ModrinthProjectItem("dynamic-fps", "Dynamic FPS", "Hemat baterai & CPU saat game di latar belakang", "32M+ Unduhan", "OPTIMASI", ContentCategory.MODS),
        ModrinthProjectItem("moreculling", "MoreCulling", "Culling ekstra untuk daun, item frame & blok tersembunyi (+FPS)", "24M+ Unduhan", "FPS BOOST", ContentCategory.MODS),
        ModrinthProjectItem("memoryleakfix", "Memory Leak Fix", "Memperbaiki kebocoran memori RAM di Minecraft Java", "38M+ Unduhan", "RAM BOOST", ContentCategory.MODS),
        ModrinthProjectItem("notenoughanimations", "Not Enough Animations", "Animasi orang ketiga mulus saat makan, memanah & mendayung", "29M+ Unduhan", "ANIMASI", ContentCategory.MODS),
        ModrinthProjectItem("3dskinlayers", "3D Skin Layers", "Merender lapisan kedua skin karakter menjadi 3D voxel", "26M+ Unduhan", "VISUAL", ContentCategory.MODS),
        ModrinthProjectItem("cloth-config", "Cloth Config API", "Pustaka layar konfigurasi untuk Mod Menu Fabric 1.20.4", "65M+ Unduhan", "LIBRARY", ContentCategory.MODS),
        ModrinthProjectItem("zoomify", "Zoomify", "Fitur zoom kamera optik presisi tinggi dengan transisi halus", "15M+ Unduhan", "UTILITAS", ContentCategory.MODS),
        ModrinthProjectItem("wi-zoom", "WI Zoom", "Zoom jarak jauh ringan hingga 50x tanpa menurunkan FPS", "9M+ Unduhan", "PVP & ZOOM", ContentCategory.MODS),
        ModrinthProjectItem("lambdynamiclights", "LambDynamicLights", "Cahaya dinamis saat memegang obor atau item menyala", "19M+ Unduhan", "CAHAYA", ContentCategory.MODS),
        ModrinthProjectItem("jade", "Jade (WAILA)", "Menampilkan info blok, isi peti & darah mob yang ditatap", "42M+ Unduhan", "HUD & INFO", ContentCategory.MODS),
        ModrinthProjectItem("mouse-tweaks", "Mouse Tweaks", "Drag-and-drop item cepat di dalam Inventory & Chest", "35M+ Unduhan", "INVENTORI", ContentCategory.MODS),
        ModrinthProjectItem("continuity", "Continuity", "Tekstur kaca & pasir menyatu (Connected Textures)", "18M+ Unduhan", "VISUAL", ContentCategory.MODS),
        ModrinthProjectItem("xaeros-minimap", "Xaero's Minimap", "Peta mini sudut layar dengan radar waypoint & entitas", "31M+ Unduhan", "MINIMAP", ContentCategory.MODS),
        ModrinthProjectItem("xaeros-world-map", "Xaero's World Map", "Peta dunia layar penuh otomatis tersimpan saat menjelajah", "27M+ Unduhan", "WORLD MAP", ContentCategory.MODS),
        ModrinthProjectItem("roughly-enough-items", "Roughly Enough Items (REI)", "Daftar seluruh resep crafting & kegunaan item di samping Inventory", "44M+ Unduhan", "RESEP ITEM", ContentCategory.MODS),
        ModrinthProjectItem("betterf3", "BetterF3", "Layar debug F3 berwarna, bersih, dan bisa dikustomisasi", "21M+ Unduhan", "HUD F3", ContentCategory.MODS),
        ModrinthProjectItem("chat-heads", "Chat Heads", "Menampilkan ikon wajah pemain di samping pesan chat", "16M+ Unduhan", "CHAT UI", ContentCategory.MODS),
        ModrinthProjectItem("fallingtree", "FallingTree (Tree Capitator)", "Tebang 1 blok pohon untuk merobohkan seluruh pohon sekaligus", "22M+ Unduhan", "SURVIVAL", ContentCategory.MODS),
        ModrinthProjectItem("veinmining", "VeinMining", "Tambang seluruh bijih tambang (ore vein) sekaligus", "11M+ Unduhan", "SURVIVAL", ContentCategory.MODS),
        ModrinthProjectItem("voicechat", "Simple Voice Chat", "Voice chat kedekatan jarak (proximity voice) untuk server multiplayer", "39M+ Unduhan", "MULTIPLAYER", ContentCategory.MODS),
        ModrinthProjectItem("replaymod", "ReplayMod", "Rekam & putar ulang sesi bermain dengan kamera sinematik", "14M+ Unduhan", "REKAMAN", ContentCategory.MODS),
        ModrinthProjectItem("indium", "Indium", "Addon kompatibilitas Fabric Rendering API untuk mod visual", "28M+ Unduhan", "LIBRARY", ContentCategory.MODS),
        ModrinthProjectItem("exordium", "Exordium", "Merender GUI/HUD pada framerate terpisah untuk menaikkan FPS 3D", "8M+ Unduhan", "FPS BOOST", ContentCategory.MODS)
    )

    // =========================================================================================
    // 2. CURATED STARTER CATALOG: 14 SHADERPACKS (.minecraft/shaderpacks/*.zip)
    // =========================================================================================
    private val CURATED_SHADERS = listOf(
        ModrinthProjectItem("makeup-ultrafast-shaders", "MakeUp - UltraFast Shaders", "Shader paling ringan untuk HP low-end dengan bayangan & air halus", "12M+ Unduhan", "ULTRA RINGAN", ContentCategory.SHADERS),
        ModrinthProjectItem("potato-shaders", "Potato Shaders", "Shader super hemat GPU dengan warna hangat, bloom & air jernih", "6.5M+ Unduhan", "HP KENTANG", ContentCategory.SHADERS),
        ModrinthProjectItem("miniature-shader", "Miniature Shader", "Shader vanilla-plus super ringan dengan refleksi air & bayangan cepat", "3.8M+ Unduhan", "RINGAN", ContentCategory.SHADERS),
        ModrinthProjectItem("complementary-reimagined", "Complementary Reimagined", "Shader kelas studio yang mempertahankan gaya asli Minecraft", "34M+ Unduhan", "POPULER", ContentCategory.SHADERS),
        ModrinthProjectItem("complementary-unbound", "Complementary Unbound", "Pencahayaan realistis, awan volumetrik & ombak air realistis", "26M+ Unduhan", "REALISTIS", ContentCategory.SHADERS),
        ModrinthProjectItem("bsl-shaders", "BSL Shaders", "Pencahayaan sinematik lembut, atmosfer hangat & godrays", "41M+ Unduhan", "SINEMATIK", ContentCategory.SHADERS),
        ModrinthProjectItem("super-duper-vanilla", "Super Duper Vanilla", "Gaya grafis trailer resmi Minecraft dengan warna hidup", "9.2M+ Unduhan", "VANILLA+", ContentCategory.SHADERS),
        ModrinthProjectItem("vanilla-plus-shader", "Vanilla Plus Shader", "Atmosfer dinamis & langit indah dengan beban GPU ringan", "5.4M+ Unduhan", "VANILLA+", ContentCategory.SHADERS),
        ModrinthProjectItem("insanity-shader", "Insanity Shader", "Atmosfer berkabut horor/dramatis untuk map survival & petualangan", "4.9M+ Unduhan", "ATMOSFER", ContentCategory.SHADERS),
        ModrinthProjectItem("solas-shader", "Solas Shader", "Langit malam aurora, nebula kosmik & efek cahaya fantasi", "4.2M+ Unduhan", "FANTASI", ContentCategory.SHADERS),
        ModrinthProjectItem("bliss-shader", "Bliss Shaders", "Kabut atmosferik dinamis & pencahayaan matahari pagi/sore", "3.9M+ Unduhan", "ATMOSFER", ContentCategory.SHADERS),
        ModrinthProjectItem("rethinking-voxels", "Rethinking Voxels", "Bayangan blok tajam dengan pencahayaan voxel berwarna", "5.1M+ Unduhan", "VOXEL GI", ContentCategory.SHADERS)
    )

    // =========================================================================================
    // 3. CURATED STARTER CATALOG: 16 RESOURCE PACKS / TEXTURES (.minecraft/resourcepacks/*.zip)
    // =========================================================================================
    private val CURATED_RESOURCE_PACKS = listOf(
        ModrinthProjectItem("faithful-32x", "Faithful 32x (1.20.4)", "Tekstur vanilla resolusi 2x lipat lebih tajam & detail (32x32)", "19M+ Unduhan", "HD 32X", ContentCategory.RESOURCE_PACKS),
        ModrinthProjectItem("faithful-64x", "Faithful 64x", "Tekstur definisi tinggi 64x64 dengan gaya asli Minecraft", "8.4M+ Unduhan", "HD 64X", ContentCategory.RESOURCE_PACKS),
        ModrinthProjectItem("bare-bones", "Bare Bones Texture Pack", "Tekstur bersih & cerah seperti animasi trailer resmi Mojang (+FPS)", "14M+ Unduhan", "TRAILER / FPS", ContentCategory.RESOURCE_PACKS),
        ModrinthProjectItem("fast-better-grass", "Fast Better Grass", "Sisi blok rumput & salju menyatu penuh tanpa menurunkan FPS", "11M+ Unduhan", "FPS & VISUAL", ContentCategory.RESOURCE_PACKS),
        ModrinthProjectItem("low-on-fire", "Low On Fire (PvP)", "Menurunkan tinggi api di layar saat terbakar agar pandangan PvP jelas", "5.8M+ Unduhan", "PVP KOMBAT", ContentCategory.RESOURCE_PACKS),
        ModrinthProjectItem("default-dark-mode", "Default Dark Mode UI", "Mengubah seluruh menu Inventory, Chest & Crafting menjadi Dark Mode", "12M+ Unduhan", "DARK UI", ContentCategory.RESOURCE_PACKS),
        ModrinthProjectItem("fresh-animations", "Fresh Animations", "Animasi gerakan mob & penduduk desa yang hidup dan ekspresif", "23M+ Unduhan", "ANIMASI MOB", ContentCategory.RESOURCE_PACKS),
        ModrinthProjectItem("dramatic-skys", "Dramatic Skys", "Langit HD dengan awan realistis, matahari terbenam & galaksi malam", "9.6M+ Unduhan", "LANGIT HD", ContentCategory.RESOURCE_PACKS),
        ModrinthProjectItem("stay-true", "Stay True", "Variasi warna daun, rumput & blok bangunan yang lebih alami", "7.3M+ Unduhan", "ALAMI", ContentCategory.RESOURCE_PACKS),
        ModrinthProjectItem("glowing-ores", "Visible / Glowing Ores", "Garis tepi bijih tambang (Diamond, Gold, Iron) menyala di gua gelap", "6.1M+ Unduhan", "MINING", ContentCategory.RESOURCE_PACKS),
        ModrinthProjectItem("clear-glass-pack", "Clean Connected Glass", "Kaca bening tanpa garis buram di tengah untuk bangunan modern", "4.4M+ Unduhan", "BUILDER", ContentCategory.RESOURCE_PACKS),
        ModrinthProjectItem("classic-3d-items", "Classic 3D Items", "Model item 3D saat dijatuhkan ke tanah", "3.5M+ Unduhan", "3D ITEM", ContentCategory.RESOURCE_PACKS)
    )

    // =========================================================================================
    // 4. CURATED STARTER CATALOG: MAPS, WORLDS & ADVENTURE PACKS (.minecraft/saves/)
    // =========================================================================================
    private val CURATED_MAPS_WORLDS = listOf(
        ModrinthProjectItem("oneblock-classic", "OneBlock Survival 1.20.4", "Bertahan hidup dari 1 blok melayang yang terus beregenerasi menjadi 10 fase", "1.8M+ Unduhan", "SURVIVAL MAP", ContentCategory.MAPS_WORLDS),
        ModrinthProjectItem("skyblock-infinite", "SkyBlock Classic Island", "Dunia pulau melayang klasik dengan tantangan generator Cobblestone", "2.4M+ Unduhan", "SKYBLOCK", ContentCategory.MAPS_WORLDS),
        ModrinthProjectItem("terralith", "Terralith World Generation", "Menambahkan 95+ bioma baru spektakuler menggunakan blok vanilla 1.20.4", "28M+ Unduhan", "DUNIA & BIOMA", ContentCategory.MAPS_WORLDS),
        ModrinthProjectItem("incendium", "Incendium Nether Overhaul", "Mengubah dimensi Nether dengan 8 bioma baru & kastil boss", "15M+ Unduhan", "NETHER MAP", ContentCategory.MAPS_WORLDS),
        ModrinthProjectItem("nullscape", "Nullscape End Dimension", "Generasi pulau dimensi End yang megah dan penuh struktur", "11M+ Unduhan", "END MAP", ContentCategory.MAPS_WORLDS),
        ModrinthProjectItem("dungeons-and-taverns", "Dungeons and Taverns", "Ratusan struktur dungeon, kuil, dan penginapan baru di seluruh dunia", "9.5M+ Unduhan", "DUNGEON", ContentCategory.MAPS_WORLDS),
        ModrinthProjectItem("structory", "Structory", "Reruntuhan, menara, dan bangunan petualangan атмосферik di seluruh bioma", "8.2M+ Unduhan", "STRUKTUR", ContentCategory.MAPS_WORLDS),
        ModrinthProjectItem("explorify", "Explorify Structures", "Struktur petualangan baru yang menyatu alami dengan dunia survival", "6.1M+ Unduhan", "PETUALANGAN", ContentCategory.MAPS_WORLDS)
    )

    @Volatile
    private var cachedMods: MutableList<ModrinthProjectItem> = CURATED_MODS_1204.toMutableList()

    @Volatile
    private var cachedShaders: MutableList<ModrinthProjectItem> = CURATED_SHADERS.toMutableList()

    @Volatile
    private var cachedResourcePacks: MutableList<ModrinthProjectItem> = CURATED_RESOURCE_PACKS.toMutableList()

    @Volatile
    private var cachedMaps: MutableList<ModrinthProjectItem> = CURATED_MAPS_WORLDS.toMutableList()

    private fun getActiveMutableList(): MutableList<ModrinthProjectItem> = when (activeCategory) {
        ContentCategory.MODS -> cachedMods
        ContentCategory.SHADERS -> cachedShaders
        ContentCategory.RESOURCE_PACKS -> cachedResourcePacks
        ContentCategory.MAPS_WORLDS -> cachedMaps
    }

    fun setCategory(context: Context, category: ContentCategory, onComplete: () -> Unit) {
        activeCategory = category
        syncStatusMessage = "Kategori aktif: ${category.fullTitle} (${getActiveMutableList().size} item siap dipasang)."
        mainHandler.post { onComplete() }
    }

    private fun getTargetDirectory(provisioner: DirectBootProvisioner, category: ContentCategory): File {
        return when (category) {
            ContentCategory.MODS -> provisioner.modsDir.apply { mkdirs() }
            ContentCategory.SHADERS -> File(provisioner.gameDir, "shaderpacks").apply { mkdirs() }
            ContentCategory.RESOURCE_PACKS -> File(provisioner.gameDir, "resourcepacks").apply { mkdirs() }
            ContentCategory.MAPS_WORLDS -> File(provisioner.gameDir, "saves").apply { mkdirs() }
        }
    }

    fun getCatalogWithDiskState(context: Context): List<ModrinthProjectItem> {
        val provisioner = DirectBootProvisioner(context)
        val cat = activeCategory
        val targetDir = getTargetDirectory(provisioner, cat)
        val diskEntries = targetDir.listFiles() ?: emptyArray()
        val enabledResourcePacks = if (cat == ContentCategory.RESOURCE_PACKS) {
            readEnabledResourcePacksFromOptions(provisioner)
        } else {
            emptySet()
        }
        val activeShaderName = if (cat == ContentCategory.SHADERS) {
            RendererProfileManager.getSelectedShaderpack(context)
        } else {
            ""
        }

        val currentList = getActiveMutableList().toList()
        return currentList.map { item ->
            val cleanSlug = item.slug.lowercase().replace("-", "").replace("_", "")
            val matched = diskEntries.firstOrNull { f ->
                val cleanName = f.name.lowercase().replace("-", "").replace("_", "")
                cleanName.contains(cleanSlug)
            }
            val installed = matched != null && (matched.isDirectory || matched.length() > 1024L)
            val enabled = when (cat) {
                ContentCategory.MODS -> matched != null && !matched.name.endsWith(".disabled")
                ContentCategory.SHADERS -> matched != null && (activeShaderName.equals(matched.name, ignoreCase = true) || !matched.name.endsWith(".disabled"))
                ContentCategory.RESOURCE_PACKS -> matched != null && (enabledResourcePacks.contains(matched.name) || !matched.name.endsWith(".disabled"))
                ContentCategory.MAPS_WORLDS -> matched != null
            }
            item.copy(
                contentCategory = cat,
                isInstalled = installed,
                isEnabled = enabled,
                installedFileName = matched?.name ?: ""
            )
        }
    }

    /**
     * Loads more items from the live Modrinth v2 API for the currently active category
     * (`MODS`, `SHADERS`, `RESOURCE_PACKS`, `MAPS_WORLDS`).
     */
    fun loadMoreFromModrinth(context: Context, onComplete: () -> Unit) {
        val cat = activeCategory
        val nextOffset = (currentOffsetByCategory[cat] ?: 0)
        syncStatusMessage = "Memuat katalog populer ${cat.fullTitle} dari Modrinth API (offset $nextOffset)..."
        mainHandler.post { onComplete() }

        networkExecutor.execute {
            try {
                val facets = when (cat) {
                    ContentCategory.MODS -> """[["versions:1.20.4"],["categories:fabric"],["project_type:mod"]]"""
                    ContentCategory.SHADERS -> """[["project_type:shader"]]"""
                    ContentCategory.RESOURCE_PACKS -> """[["project_type:resourcepack"]]"""
                    ContentCategory.MAPS_WORLDS -> """[["project_type:datapack"]]"""
                }
                val url = "https://api.modrinth.com/v2/search" +
                    "?query=${URLEncoder.encode(currentSearchQuery, "UTF-8")}" +
                    "&facets=${URLEncoder.encode(facets, "UTF-8")}" +
                    "&index=downloads" +
                    "&offset=$nextOffset" +
                    "&limit=30"

                val rawJson = httpGet(url)
                val root = JSONObject(rawJson)
                val hits = root.optJSONArray("hits") ?: JSONArray()
                val targetList = getActiveMutableList()
                val existingSlugs = targetList.map { it.slug.lowercase() }.toMutableSet()
                var addedCount = 0

                for (i in 0 until hits.length()) {
                    val obj = hits.getJSONObject(i)
                    val slug = obj.optString("slug", "")
                    if (slug.isNotBlank() && !existingSlugs.contains(slug.lowercase())) {
                        existingSlugs.add(slug.lowercase())
                        val title = obj.optString("title", slug)
                        val desc = obj.optString("description", "")
                        val downloads = obj.optLong("downloads", 0L)
                        val cats = obj.optJSONArray("categories")
                        val catTag = if (cats != null && cats.length() > 0) {
                            cats.optString(0, cat.shortTab).uppercase()
                        } else {
                            cat.shortTab
                        }
                        targetList.add(
                            ModrinthProjectItem(
                                slug = slug,
                                title = title,
                                summary = desc,
                                downloadsText = formatDownloads(downloads),
                                categoryTag = catTag,
                                contentCategory = cat
                            )
                        )
                        addedCount++
                    }
                }

                currentOffsetByCategory[cat] = nextOffset + 30
                syncStatusMessage = if (addedCount > 0) {
                    "Berhasil memuat +$addedCount ${cat.fullTitle} tambahan (Total: ${targetList.size} item)."
                } else {
                    "Seluruh item populer untuk ${cat.fullTitle} sudah dimuat (${targetList.size} item)."
                }
            } catch (t: Throwable) {
                syncStatusMessage = "Gagal memuat dari Modrinth: ${t.message?.take(48)}"
            }
            mainHandler.post { onComplete() }
        }
    }

    /**
     * Searches Modrinth v2 API for the currently active category (`MODS`, `SHADERS`, `RESOURCE_PACKS`, `MAPS_WORLDS`).
     */
    fun searchModsOnModrinth(
        context: Context,
        query: String,
        onComplete: () -> Unit
    ) {
        currentSearchQuery = query.trim()
        val cat = activeCategory
        if (currentSearchQuery.isBlank()) {
            when (cat) {
                ContentCategory.MODS -> cachedMods = CURATED_MODS_1204.toMutableList()
                ContentCategory.SHADERS -> cachedShaders = CURATED_SHADERS.toMutableList()
                ContentCategory.RESOURCE_PACKS -> cachedResourcePacks = CURATED_RESOURCE_PACKS.toMutableList()
                ContentCategory.MAPS_WORLDS -> cachedMaps = CURATED_MAPS_WORLDS.toMutableList()
            }
            currentOffsetByCategory[cat] = 0
            syncStatusMessage = "Menampilkan katalog kurasi ${cat.fullTitle} (${getActiveMutableList().size} item)."
            mainHandler.post { onComplete() }
            return
        }

        syncStatusMessage = "Mencari '${currentSearchQuery}' pada kategori ${cat.fullTitle}..."
        mainHandler.post { onComplete() }

        networkExecutor.execute {
            try {
                val facets = when (cat) {
                    ContentCategory.MODS -> """[["versions:1.20.4"],["categories:fabric"],["project_type:mod"]]"""
                    ContentCategory.SHADERS -> """[["project_type:shader"]]"""
                    ContentCategory.RESOURCE_PACKS -> """[["project_type:resourcepack"]]"""
                    ContentCategory.MAPS_WORLDS -> """[["project_type:datapack"]]"""
                }
                val url = "https://api.modrinth.com/v2/search" +
                    "?query=${URLEncoder.encode(currentSearchQuery, "UTF-8")}" +
                    "&facets=${URLEncoder.encode(facets, "UTF-8")}" +
                    "&limit=30"

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
                        cats.optString(0, cat.shortTab).uppercase()
                    } else {
                        cat.shortTab
                    }
                    if (slug.isNotBlank()) {
                        found.add(
                            ModrinthProjectItem(
                                slug = slug,
                                title = title,
                                summary = desc,
                                downloadsText = formatDownloads(downloads),
                                categoryTag = catTag,
                                contentCategory = cat
                            )
                        )
                    }
                }

                if (found.isNotEmpty()) {
                    when (cat) {
                        ContentCategory.MODS -> cachedMods = found
                        ContentCategory.SHADERS -> cachedShaders = found
                        ContentCategory.RESOURCE_PACKS -> cachedResourcePacks = found
                        ContentCategory.MAPS_WORLDS -> cachedMaps = found
                    }
                    syncStatusMessage = "Ditemukan ${found.size} hasil di ${cat.fullTitle} untuk '${currentSearchQuery}'."
                } else {
                    syncStatusMessage = "Tidak ditemukan hasil di ${cat.fullTitle} untuk '${currentSearchQuery}'."
                }
            } catch (t: Throwable) {
                syncStatusMessage = "Gagal terhubung ke Modrinth API: ${t.message?.take(48)}"
            }
            mainHandler.post { onComplete() }
        }
    }

    /**
     * Downloads, installs, or toggles an item in `.minecraft/mods/`, `.minecraft/shaderpacks/`,
     * `.minecraft/resourcepacks/`, or `.minecraft/saves/`.
     */
    fun installOrToggleMod(
        context: Context,
        item: ModrinthProjectItem,
        onUpdate: () -> Unit
    ) {
        val provisioner = DirectBootProvisioner(context)
        val cat = item.contentCategory
        val targetDir = getTargetDirectory(provisioner, cat)
        val cleanSlug = item.slug.lowercase().replace("-", "").replace("_", "")

        val existing = targetDir.listFiles()?.firstOrNull { f ->
            val cleanName = f.name.lowercase().replace("-", "").replace("_", "")
            cleanName.contains(cleanSlug)
        }

        if (existing != null && (existing.isDirectory || existing.length() > 1024L)) {
            when (cat) {
                ContentCategory.MODS -> {
                    if (existing.name.endsWith(".disabled")) {
                        val enabledFile = File(existing.parentFile, existing.name.removeSuffix(".disabled"))
                        existing.renameTo(enabledFile)
                        syncStatusMessage = "Mod '${item.title}' diaktifkan (ON)."
                    } else {
                        val disabledFile = File(existing.parentFile, "${existing.name}.disabled")
                        existing.renameTo(disabledFile)
                        syncStatusMessage = "Mod '${item.title}' dinonaktifkan (OFF)."
                    }
                }
                ContentCategory.SHADERS -> {
                    RendererProfileManager.setSelectedShaderpack(context, existing.name)
                    RendererProfileManager.setShadersEnabled(context, true)
                    RendererProfileManager.syncExternalShaderpacks(provisioner.gameDir, provisioner.configDir)
                    syncStatusMessage = "Shaderpack '${item.title}' dipilih & diaktifkan!"
                }
                ContentCategory.RESOURCE_PACKS -> {
                    val nowEnabled = toggleResourcePackInOptions(provisioner, existing.name)
                    syncStatusMessage = if (nowEnabled) {
                        "Tekstur '${item.title}' diaktifkan di options.txt!"
                    } else {
                        "Tekstur '${item.title}' dinonaktifkan dari options.txt."
                    }
                }
                ContentCategory.MAPS_WORLDS -> {
                    syncStatusMessage = "Map/Dunia '${item.title}' sudah terpasang di .minecraft/saves/${existing.name}!"
                }
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
        syncStatusMessage = "Mengambil rilis '${item.title}' (${cat.shortTab})..."
        mainHandler.post { onUpdate() }

        networkExecutor.execute {
            try {
                val verUrl = when (cat) {
                    ContentCategory.MODS -> {
                        val loadersParam = URLEncoder.encode("""["fabric"]""", "UTF-8")
                        val versionsParam = URLEncoder.encode("""["1.20.4"]""", "UTF-8")
                        "https://api.modrinth.com/v2/project/${URLEncoder.encode(item.slug, "UTF-8")}/version?loaders=$loadersParam&game_versions=$versionsParam"
                    }
                    else -> {
                        "https://api.modrinth.com/v2/project/${URLEncoder.encode(item.slug, "UTF-8")}/version"
                    }
                }

                var verJsonStr = httpGet(verUrl)
                var verArr = JSONArray(verJsonStr)
                if (verArr.length() == 0 && cat == ContentCategory.MODS) {
                    // Fallback to latest version if strict 1.20.4 tag is missing on universal fabric mod
                    verJsonStr = httpGet("https://api.modrinth.com/v2/project/${URLEncoder.encode(item.slug, "UTF-8")}/version")
                    verArr = JSONArray(verJsonStr)
                }
                if (verArr.length() == 0) {
                    throw IllegalStateException("File rilis tidak ditemukan untuk ${item.slug}")
                }

                // Find best matching version (prefer 1.20.4 if available)
                var chosenVer = verArr.getJSONObject(0)
                for (vIdx in 0 until verArr.length()) {
                    val vObj = verArr.getJSONObject(vIdx)
                    val gVers = vObj.optJSONArray("game_versions")
                    if (gVers != null) {
                        var has1204 = false
                        for (gIdx in 0 until gVers.length()) {
                            if (gVers.optString(gIdx).startsWith("1.20")) {
                                has1204 = true
                                break
                            }
                        }
                        if (has1204) {
                            chosenVer = vObj
                            break
                        }
                    }
                }

                val filesArr = chosenVer.getJSONArray("files")
                var chosenFileObj: JSONObject? = null
                for (i in 0 until filesArr.length()) {
                    val fObj = filesArr.getJSONObject(i)
                    if (fObj.optBoolean("primary", false) || chosenFileObj == null) {
                        chosenFileObj = fObj
                    }
                }

                val fileObj = chosenFileObj ?: throw IllegalStateException("File unduhan tidak tersedia")
                val downloadUrl = fileObj.getString("url")
                val defaultExt = if (cat == ContentCategory.MODS) ".jar" else ".zip"
                val rawFileName = fileObj.optString("filename", "${item.slug}$defaultExt")
                val outFileName = if (rawFileName.lowercase().contains(cleanSlug)) {
                    rawFileName
                } else {
                    "${item.slug}-$rawFileName"
                }

                val targetFile = File(targetDir, outFileName)
                val tmpFile = File(targetDir, "$outFileName.part")

                syncStatusMessage = "Mengunduh $outFileName..."
                mainHandler.post { onUpdate() }

                downloadBinaryFile(downloadUrl, tmpFile)
                if (targetFile.exists()) targetFile.delete()
                tmpFile.renameTo(targetFile)

                val kb = targetFile.length() / 1024L
                when (cat) {
                    ContentCategory.MODS -> {
                        // Also check renderer compatibility so GL4ES doesn't crash if user manually downloads Sodium
                        provisioner.prepareDirectoriesAndConfigs()
                        syncStatusMessage = "Berhasil memasang Mod '${item.title}' ($kb KB) ke .minecraft/mods/!"
                    }
                    ContentCategory.SHADERS -> {
                        // Copy to external shaderpacks dir as well and auto-select
                        VeloLogger.shaderpacksDir?.let { extDir ->
                            extDir.mkdirs()
                            targetFile.copyTo(File(extDir, targetFile.name), overwrite = true)
                        }
                        RendererProfileManager.setSelectedShaderpack(context, targetFile.name)
                        RendererProfileManager.setShadersEnabled(context, true)
                        RendererProfileManager.syncExternalShaderpacks(provisioner.gameDir, provisioner.configDir)
                        syncStatusMessage = "Shader '${item.title}' ($kb KB) terpasang di .minecraft/shaderpacks/ & siap dipakai!"
                    }
                    ContentCategory.RESOURCE_PACKS -> {
                        toggleResourcePackInOptions(provisioner, targetFile.name, forceEnable = true)
                        syncStatusMessage = "Tekstur '${item.title}' ($kb KB) terpasang di .minecraft/resourcepacks/ & diaktifkan!"
                    }
                    ContentCategory.MAPS_WORLDS -> {
                        val worldFolder = extractOrInstallMapArchive(targetFile, targetDir, item.slug)
                        // Also copy datapack/worldgen jar/zip to mods if it is a worldgen mod/datapack
                        if (targetFile.name.endsWith(".jar", ignoreCase = true)) {
                            targetFile.copyTo(File(provisioner.modsDir, targetFile.name), overwrite = true)
                        }
                        syncStatusMessage = "Map/Dunia '${item.title}' ($kb KB) terpasang di .minecraft/saves/${worldFolder.name}!"
                    }
                }
                VeloLogger.section("CONTENT-HUB", "Installed ${cat.name} '${item.title}' -> ${targetFile.absolutePath} ($kb KB)")
            } catch (t: Throwable) {
                syncStatusMessage = "Gagal mengunduh '${item.title}': ${t.message?.take(52)}"
                VeloLogger.warn("Content download failed for ${item.slug}: ${t.message}")
            } finally {
                activeDownloadSlug = null
                mainHandler.post { onUpdate() }
            }
        }
    }

    private fun readEnabledResourcePacksFromOptions(provisioner: DirectBootProvisioner): Set<String> {
        val optionsFile = File(provisioner.gameDir, "options.txt")
        if (!optionsFile.exists()) return emptySet()
        return try {
            val line = optionsFile.readLines().firstOrNull { it.startsWith("resourcePacks:") } ?: return emptySet()
            val result = mutableSetOf<String>()
            val regex = Regex("file/([^\"]+)")
            regex.findAll(line).forEach { m ->
                result.add(m.groupValues[1])
            }
            result
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun toggleResourcePackInOptions(
        provisioner: DirectBootProvisioner,
        fileName: String,
        forceEnable: Boolean = false
    ): Boolean {
        val optionsFile = File(provisioner.gameDir, "options.txt")
        val currentEnabled = readEnabledResourcePacksFromOptions(provisioner).toMutableSet()
        val nowEnabled = if (forceEnable || !currentEnabled.contains(fileName)) {
            currentEnabled.add(fileName)
            true
        } else {
            currentEnabled.remove(fileName)
            false
        }

        val packListEntries = mutableListOf("\"vanilla\"", "\"fabric\"")
        currentEnabled.forEach { pack ->
            packListEntries.add("\"file/$pack\"")
        }
        val newLine = "resourcePacks:[${packListEntries.joinToString(",")}]"

        try {
            val lines = if (optionsFile.exists()) optionsFile.readLines().toMutableList() else mutableListOf()
            val idx = lines.indexOfFirst { it.startsWith("resourcePacks:") }
            if (idx >= 0) {
                lines[idx] = newLine
            } else {
                lines.add(newLine)
            }
            optionsFile.writeText(lines.joinToString("\n") + "\n")
        } catch (_: Exception) {
        }
        return nowEnabled
    }

    private fun extractOrInstallMapArchive(archiveFile: File, savesDir: File, slug: String): File {
        val outWorldDir = File(savesDir, slug)
        try {
            var hasLevelDat = false
            ZipInputStream(archiveFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name.endsWith("level.dat")) {
                        hasLevelDat = true
                        break
                    }
                    entry = zis.nextEntry
                }
            }
            if (hasLevelDat) {
                outWorldDir.mkdirs()
                ZipInputStream(archiveFile.inputStream().buffered()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val cleanRel = entry.name.substringAfter("/", entry.name).ifEmpty { entry.name }
                        val dest = File(outWorldDir, cleanRel)
                        if (dest.canonicalPath.startsWith(outWorldDir.canonicalPath)) {
                            if (entry.isDirectory) {
                                dest.mkdirs()
                            } else {
                                dest.parentFile?.mkdirs()
                                dest.outputStream().use { zis.copyTo(it) }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                return outWorldDir
            }
        } catch (_: Exception) {
        }
        return archiveFile
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
            setRequestProperty("User-Agent", "XyverseTechnologyGlobal/MinecraftPortingEdition/0.8.3 (id.xyverse.minecraft)")
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
            setRequestProperty("User-Agent", "XyverseTechnologyGlobal/MinecraftPortingEdition/0.8.3 (id.xyverse.minecraft)")
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
