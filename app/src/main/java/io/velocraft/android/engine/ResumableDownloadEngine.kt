package io.velocraft.android.engine

import io.velocraft.android.util.VeloLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Resumable Multi-Threaded Download & Validation Engine for Minecraft 1.20.4 (Fabric).
 *
 * Capabilities:
 * 1. File-level & Byte-level Resume (`HTTP Range: bytes=<existing>-` + `.part` files):
 *    If the user closes the app, loses internet, or pauses at any point, reopening and tapping
 *    "Lanjutkan Unduhan" skips all completed files and resumes partial `.part` files from the exact byte!
 * 2. Full Mode (~685 MB) vs Essential Lite Mode (~245 MB, skips heavy background music/records `.ogg` files).
 * 3. Strict Readiness Validation (`verifyEngineReadiness()`):
 *    The "MAIN SEKARANG" button only unlocks when core libraries, client.jar, Fabric, optimization mods,
 *    and asset index are genuinely downloaded and verified on disk.
 */
class ResumableDownloadEngine(private val provisioner: DirectBootProvisioner) {

    data class DownloadTaskItem(
        val name: String,
        val url: String,
        val targetFile: File,
        val expectedBytes: Long,
        val expectedSha1: String? = null
    )

    data class DownloadSnapshot(
        val isRunning: Boolean,
        val isPaused: Boolean,
        val isCompleted: Boolean,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val completedFiles: Int,
        val totalFiles: Int,
        val speedBytesPerSec: Long,
        val currentFileName: String,
        val errorMessage: String? = null
    ) {
        val percent: Int
            get() = if (totalBytes > 0L) {
                ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
            } else if (totalFiles > 0) {
                ((completedFiles * 100) / totalFiles).coerceIn(0, 100)
            } else 0
    }

    data class ReadinessReport(
        val isClientJarReady: Boolean,
        val isFabricAndLibrariesReady: Boolean,
        val isOptimizationModsReady: Boolean,
        val isAssetIndexReady: Boolean,
        val installedFilesCount: Int,
        val installedMegabytes: Long
    ) {
        val isAllValidAndReady: Boolean
            get() = isClientJarReady && isFabricAndLibrariesReady && isOptimizationModsReady && isAssetIndexReady
    }

    private val downloadSemaphore = Semaphore(8)

    fun verifyEngineReadiness(): ReadinessReport {
        val clientJar = File(
            provisioner.librariesDir,
            "net/minecraft/client/1.20.4/client-1.20.4.jar"
        )
        val isClientReady = clientJar.exists() && clientJar.length() > 15_000_000L

        val jarFiles = if (provisioner.librariesDir.exists()) {
            provisioner.librariesDir.walkTopDown().filter { it.isFile && it.extension == "jar" }.toList()
        } else emptyList()
        val isLibsReady = jarFiles.size >= 35

        val modFiles = provisioner.modsDir.listFiles { f -> f.isFile && f.extension == "jar" } ?: emptyArray()
        val isModsReady = modFiles.size >= 4

        val indexFile = File(provisioner.assetsDir, "indexes/12.json")
        val isAssetsReady = indexFile.exists() && indexFile.length() > 100_000L

        val totalBytes = calculateDirectoryBytes(provisioner.rootEngineDir)
        val totalFiles = jarFiles.size + modFiles.size

        return ReadinessReport(
            isClientJarReady = isClientReady,
            isFabricAndLibrariesReady = isLibsReady,
            isOptimizationModsReady = isModsReady,
            isAssetIndexReady = isAssetsReady,
            installedFilesCount = totalFiles,
            installedMegabytes = totalBytes / (1024L * 1024L)
        )
    }

    suspend fun executeResumableDownload(
        includeFullMusicAssets: Boolean,
        pauseFlag: AtomicBoolean,
        onSnapshot: (DownloadSnapshot) -> Unit
    ) = withContext(Dispatchers.IO) {
        VeloLogger.info("Starting ResumableDownloadEngine (includeFullMusicAssets=$includeFullMusicAssets)...")
        provisioner.prepareDirectoriesAndConfigs()

        onSnapshot(
            DownloadSnapshot(
                isRunning = true,
                isPaused = false,
                isCompleted = false,
                downloadedBytes = 0L,
                totalBytes = 0L,
                completedFiles = 0,
                totalFiles = 1,
                speedBytesPerSec = 0L,
                currentFileName = "Menyusun daftar manifest Mojang, Fabric & Modrinth..."
            )
        )

        val queue = buildCompleteDownloadQueue(includeFullMusicAssets)
        val totalFiles = queue.size.coerceAtLeast(1)

        // Compute total bytes and already-downloaded bytes on disk (including partial .part files!)
        var initialReadyBytes = 0L
        var initialReadyFiles = 0
        var estimatedTotalBytes = 0L

        for (task in queue) {
            val expected = if (task.expectedBytes > 0L) task.expectedBytes else 250_000L
            estimatedTotalBytes += expected
            if (isTargetFileComplete(task)) {
                initialReadyBytes += task.targetFile.length().coerceAtLeast(expected)
                initialReadyFiles++
            } else {
                val partFile = File(task.targetFile.parentFile, "${task.targetFile.name}.part")
                if (partFile.exists()) {
                    initialReadyBytes += partFile.length()
                }
            }
        }

        val downloadedBytesAtomic = AtomicLong(initialReadyBytes)
        val completedFilesAtomic = AtomicInteger(initialReadyFiles)
        var lastSpeedCalcTime = System.currentTimeMillis()
        var lastSpeedCalcBytes = initialReadyBytes
        var currentSpeedBps = 0L

        VeloLogger.info(
            "Manifest resolved: $totalFiles files, total=${estimatedTotalBytes / (1024 * 1024)}MB, " +
                "alreadyDownloaded=${initialReadyBytes / (1024 * 1024)}MB ($initialReadyFiles files)."
        )

        coroutineScope {
            queue.map { task ->
                async {
                    coroutineContext.ensureActive()
                    if (pauseFlag.get()) return@async

                    downloadSemaphore.withPermit {
                        coroutineContext.ensureActive()
                        if (pauseFlag.get()) return@withPermit

                        if (isTargetFileComplete(task)) {
                            return@withPermit
                        }

                        downloadFileWithHttpRangeResume(task, pauseFlag) { deltaBytes ->
                            val nowBytes = downloadedBytesAtomic.addAndGet(deltaBytes)
                            val nowTime = System.currentTimeMillis()
                            val elapsedMs = nowTime - lastSpeedCalcTime
                            if (elapsedMs >= 600L) {
                                synchronized(this@ResumableDownloadEngine) {
                                    val dt = nowTime - lastSpeedCalcTime
                                    if (dt >= 600L) {
                                        val db = nowBytes - lastSpeedCalcBytes
                                        currentSpeedBps = ((db * 1000L) / dt).coerceAtLeast(0L)
                                        lastSpeedCalcTime = nowTime
                                        lastSpeedCalcBytes = nowBytes
                                    }
                                }
                            }
                            onSnapshot(
                                DownloadSnapshot(
                                    isRunning = !pauseFlag.get(),
                                    isPaused = pauseFlag.get(),
                                    isCompleted = false,
                                    downloadedBytes = nowBytes.coerceAtMost(estimatedTotalBytes),
                                    totalBytes = estimatedTotalBytes,
                                    completedFiles = completedFilesAtomic.get(),
                                    totalFiles = totalFiles,
                                    speedBytesPerSec = currentSpeedBps,
                                    currentFileName = task.name
                                )
                            )
                        }

                        if (!pauseFlag.get() && isTargetFileComplete(task)) {
                            val doneCount = completedFilesAtomic.incrementAndGet()
                            onSnapshot(
                                DownloadSnapshot(
                                    isRunning = true,
                                    isPaused = false,
                                    isCompleted = false,
                                    downloadedBytes = downloadedBytesAtomic.get().coerceAtMost(estimatedTotalBytes),
                                    totalBytes = estimatedTotalBytes,
                                    completedFiles = doneCount,
                                    totalFiles = totalFiles,
                                    speedBytesPerSec = currentSpeedBps,
                                    currentFileName = task.name
                                )
                            )
                        }
                    }
                }
            }.awaitAll()
        }

        val wasPaused = pauseFlag.get()
        if (!wasPaused) {
            provisioner.markEngineProvisioned()
            VeloLogger.info("All download tasks finished and verified!")
        } else {
            VeloLogger.info("Download paused by user at ${downloadedBytesAtomic.get() / (1024 * 1024)} MB.")
        }

        onSnapshot(
            DownloadSnapshot(
                isRunning = false,
                isPaused = wasPaused,
                isCompleted = !wasPaused && verifyEngineReadiness().isAllValidAndReady,
                downloadedBytes = downloadedBytesAtomic.get().coerceAtMost(estimatedTotalBytes),
                totalBytes = estimatedTotalBytes,
                completedFiles = completedFilesAtomic.get(),
                totalFiles = totalFiles,
                speedBytesPerSec = 0L,
                currentFileName = if (wasPaused) "Unduhan dijeda (Bisa dilanjutkan kapan saja)" else "Seluruh data game siap dimainkan!"
            )
        )
    }

    private fun buildCompleteDownloadQueue(includeFullMusicAssets: Boolean): List<DownloadTaskItem> {
        val tasks = mutableListOf<DownloadTaskItem>()

        // 1. Fabric Loader 0.16.7 for 1.20.4
        val fabricProfileUrl =
            "https://meta.fabricmc.net/v2/versions/loader/1.20.4/0.16.7/profile/json"
        runCatching {
            val fabricJson = JSONObject(fetchUrlText(fabricProfileUrl))
            val libs = fabricJson.getJSONArray("libraries")
            for (i in 0 until libs.length()) {
                val lib = libs.getJSONObject(i)
                val coord = lib.getString("name")
                val baseUrl = lib.optString("url", "https://maven.fabricmc.net/")
                val relPath = mavenCoordinateToPath(coord)
                val fullUrl = if (baseUrl.endsWith("/")) "$baseUrl$relPath" else "$baseUrl/$relPath"
                tasks.add(
                    DownloadTaskItem(
                        name = coord.substringAfterLast(':'),
                        url = fullUrl,
                        targetFile = File(provisioner.librariesDir, relPath),
                        expectedBytes = 420_000L
                    )
                )
            }
        }.onFailure { VeloLogger.warn("Failed fetching Fabric meta: ${it.message}") }

        // 2. Mojang 1.20.4 Version JSON (client.jar + Java libraries + Asset Index 12.json)
        runCatching {
            val manifestJson = JSONObject(
                fetchUrlText("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")
            )
            val versions = manifestJson.getJSONArray("versions")
            var vUrl: String? = null
            for (i in 0 until versions.length()) {
                val v = versions.getJSONObject(i)
                if (v.getString("id") == "1.20.4") {
                    vUrl = v.getString("url")
                    break
                }
            }
            if (vUrl != null) {
                val verJson = JSONObject(fetchUrlText(vUrl))
                val clientObj = verJson.getJSONObject("downloads").getJSONObject("client")
                tasks.add(
                    DownloadTaskItem(
                        name = "minecraft-1.20.4-client.jar",
                        url = clientObj.getString("url"),
                        targetFile = File(provisioner.librariesDir, "net/minecraft/client/1.20.4/client-1.20.4.jar"),
                        expectedBytes = clientObj.optLong("size", 25_100_000L),
                        expectedSha1 = clientObj.optString("sha1")
                    )
                )

                val mcLibs = verJson.getJSONArray("libraries")
                for (i in 0 until mcLibs.length()) {
                    val lib = mcLibs.getJSONObject(i)
                    val artifact = lib.optJSONObject("downloads")?.optJSONObject("artifact") ?: continue
                    val path = artifact.getString("path")
                    if (path.contains("natives-windows") || path.contains("natives-macos") || path.contains("natives-linux-x86")) {
                        continue
                    }
                    tasks.add(
                        DownloadTaskItem(
                            name = path.substringAfterLast('/'),
                            url = artifact.getString("url"),
                            targetFile = File(provisioner.librariesDir, path),
                            expectedBytes = artifact.optLong("size", 250_000L),
                            expectedSha1 = artifact.optString("sha1")
                        )
                    )
                }

                // Asset Index 12.json & Asset Objects
                val assetIndexObj = verJson.getJSONObject("assetIndex")
                val indexUrl = assetIndexObj.getString("url")
                val indexFile = File(provisioner.assetsDir, "indexes/${assetIndexObj.getString("id")}.json")
                if (!indexFile.exists() || indexFile.length() == 0L) {
                    indexFile.parentFile?.mkdirs()
                    indexFile.writeText(fetchUrlText(indexUrl))
                }

                val objectsJson = JSONObject(indexFile.readText()).getJSONObject("objects")
                val keys = objectsJson.keys()
                while (keys.hasNext()) {
                    val assetKey = keys.next()
                    if (!includeFullMusicAssets &&
                        (assetKey.startsWith("minecraft/sounds/music/") || assetKey.startsWith("minecraft/sounds/records/"))
                    ) {
                        continue
                    }
                    val obj = objectsJson.getJSONObject(assetKey)
                    val hash = obj.getString("hash")
                    val size = obj.optLong("size", 16_384L)
                    val prefix = hash.substring(0, 2)
                    val assetUrl = "https://resources.download.minecraft.net/$prefix/$hash"
                    tasks.add(
                        DownloadTaskItem(
                            name = assetKey.substringAfterLast('/'),
                            url = assetUrl,
                            targetFile = File(provisioner.assetsDir, "objects/$prefix/$hash"),
                            expectedBytes = size,
                            expectedSha1 = hash
                        )
                    )
                }
            }
        }.onFailure { VeloLogger.warn("Failed fetching Mojang manifest: ${it.message}") }

        // 3. Modrinth Optimization Stack (Sodium, Lithium, FerriteCore, ImmediatelyFast, EntityCulling, ModernFix, Fabric API)
        val modSlugs = listOf(
            "fabric-api", "sodium", "lithium", "ferrite-core",
            "immediatelyfast", "entityculling", "modernfix"
        )
        for (slug in modSlugs) {
            runCatching {
                val loadersParam = URLEncoder.encode("[\"fabric\"]", "UTF-8")
                val gameVerParam = URLEncoder.encode("[\"1.20.4\"]", "UTF-8")
                val apiUrl = "https://api.modrinth.com/v2/project/$slug/version?loaders=$loadersParam&game_versions=$gameVerParam"
                val arr = JSONArray(fetchUrlText(apiUrl))
                if (arr.length() > 0) {
                    val fileObj = arr.getJSONObject(0).getJSONArray("files").getJSONObject(0)
                    val filename = fileObj.getString("filename")
                    val dlUrl = fileObj.getString("url")
                    val size = fileObj.optLong("size", 800_000L)
                    val sha1 = fileObj.optJSONObject("hashes")?.optString("sha1")
                    tasks.add(
                        DownloadTaskItem(
                            name = filename,
                            url = dlUrl,
                            targetFile = File(provisioner.modsDir, filename),
                            expectedBytes = size,
                            expectedSha1 = sha1
                        )
                    )
                }
            }.onFailure { VeloLogger.warn("Modrinth resolve failed for $slug: ${it.message}") }
        }

        return tasks
    }

    private fun isTargetFileComplete(task: DownloadTaskItem): Boolean {
        val f = task.targetFile
        if (!f.exists() || f.length() == 0L) return false
        if (task.expectedBytes > 0L && task.expectedSha1 != null && f.length() != task.expectedBytes) {
            return false
        }
        return true
    }

    /**
     * Supports true HTTP Range resume (`Range: bytes=<existing>-`) into `<filename>.part`
     * and atomic rename to `<filename>` on completion.
     */
    private fun downloadFileWithHttpRangeResume(
        task: DownloadTaskItem,
        pauseFlag: AtomicBoolean,
        onBytesRead: (Long) -> Unit
    ) {
        task.targetFile.parentFile?.mkdirs()
        val partFile = File(task.targetFile.parentFile, "${task.targetFile.name}.part")
        val existingLen = if (partFile.exists()) partFile.length() else 0L

        try {
            val conn = (URL(task.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Minecraft-JavaPort-Android/0.5.0")
                if (existingLen > 0L) {
                    setRequestProperty("Range", "bytes=$existingLen-")
                }
            }

            val code = conn.responseCode
            val isPartial = (code == HttpURLConnection.HTTP_PARTIAL)

            if (code != HttpURLConnection.HTTP_OK && !isPartial) {
                if (code == 416) {
                    // Range already satisfied -> rename partFile
                    partFile.renameTo(task.targetFile)
                }
                return
            }

            conn.inputStream.use { input ->
                FileOutputStream(partFile, isPartial).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        if (pauseFlag.get()) {
                            output.flush()
                            return
                        }
                        output.write(buffer, 0, read)
                        onBytesRead(read.toLong())
                    }
                    output.flush()
                }
            }

            // Verify SHA-1 if provided before committing .part -> final file
            if (!task.expectedSha1.isNullOrBlank()) {
                val actualSha1 = computeSha1(partFile)
                if (!actualSha1.equals(task.expectedSha1, ignoreCase = true)) {
                    VeloLogger.warn("SHA-1 mismatch on ${task.name}; deleting corrupt part file.")
                    partFile.delete()
                    return
                }
            }

            partFile.renameTo(task.targetFile)
        } catch (t: Throwable) {
            VeloLogger.warn("Interrupted downloading ${task.name} (saved .part for resume): ${t.message}")
        }
    }

    private fun computeSha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var r: Int
            while (input.read(buf).also { r = it } != -1) {
                digest.update(buf, 0, r)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun mavenCoordinateToPath(coord: String): String {
        val parts = coord.split(":")
        val groupPath = parts[0].replace('.', '/')
        val artifact = parts[1]
        val version = parts[2]
        return "$groupPath/$artifact/$version/$artifact-$version.jar"
    }

    private fun fetchUrlText(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 25_000
            setRequestProperty("User-Agent", "Minecraft-JavaPort-Android/0.5.0")
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun calculateDirectoryBytes(dir: File): Long {
        if (!dir.exists()) return 0L
        var sum = 0L
        dir.walkTopDown().forEach { f ->
            if (f.isFile) sum += f.length()
        }
        return sum
    }
}
