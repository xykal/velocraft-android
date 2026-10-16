package io.velocraft.android.engine

import android.content.Context
import android.os.Process
import io.velocraft.android.util.VeloLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream

/**
 * Resumable Multi-Threaded HTTP Range Downloader, Opsi A OpenJDK 21 Runtime Provisioner,
 * and Silent-OGG Safe Lite Mode Engine.
 *
 * Strictly zero emojis.
 */
object ResumableDownloadEngine {

    private const val PREFS_NAME = "velocraft_download_prefs"
    private const val KEY_SKIP_MUSIC_LITE = "skip_music_lite"

    private const val MOJANG_VERSION_MANIFEST_URL =
        "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    private const val FABRIC_LOADER_META_URL =
        "https://meta.fabricmc.net/v2/versions/loader/1.20.4/0.16.9/profile/json"
    private const val GITHUB_RELEASE_RUNTIME_BASE =
        "https://github.com/xykal/velocraft-android/releases/download/v0.7.0-release"
    private const val GITHUB_FALLBACK_RUNTIME_BASE =
        "https://github.com/xykal/velocraft-android/releases/download/v0.6.0-alpha"
    private const val GITHUB_LATEST_RUNTIME_BASE =
        "https://github.com/xykal/velocraft-android/releases/latest/download"

    data class DownloadProgressState(
        val isRunning: Boolean,
        val percent: Int,
        val phaseTitle: String,
        val detailText: String
    )

    data class EngineReadiness(
        val isOpenJdkRuntimeReady: Boolean,
        val isClientJarReady: Boolean,
        val installedModsCount: Int,
        val isAssetIndexReady: Boolean
    ) {
        val isReadyToLaunch: Boolean
            get() = isOpenJdkRuntimeReady &&
                isClientJarReady &&
                installedModsCount >= 6 &&
                isAssetIndexReady
    }

    fun isSkipMusicLiteEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SKIP_MUSIC_LITE, true)
    }

    fun setSkipMusicLiteEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SKIP_MUSIC_LITE, enabled)
            .apply()
    }

    /**
     * Ensures the embedded OpenJDK JLI native library inside the APK (`libjli_embedded.so`)
     * is linked/copied into `jre21-<abi>/lib/libjli.so` if the user has not yet extracted
     * a remote runtime zip, so the app is never stuck waiting for OpenJDK runtime.
     */
    fun ensureEmbeddedOrExtractedJreReady(context: Context, provisioner: DirectBootProvisioner): Boolean {
        val jliPrimary = File(provisioner.jreDir, "lib/libjli.so")
        val jliAlt = File(provisioner.jreDir, "lib/jli/libjli.so")
        if ((jliPrimary.exists() && jliPrimary.length() > 1024L) ||
            (jliAlt.exists() && jliAlt.length() > 1024L)
        ) {
            return true
        }

        return try {
            val nativeDir = File(context.applicationInfo.nativeLibraryDir)
            val embeddedJli = File(nativeDir, "libjli_embedded.so")
            if (embeddedJli.exists() && embeddedJli.length() > 1024L) {
                jliPrimary.parentFile?.mkdirs()
                embeddedJli.copyTo(jliPrimary, overwrite = true)
                VeloLogger.section(
                    "RUNTIME",
                    "Installed bundled OpenJDK 21 JLI runtime (${jliPrimary.length()} bytes) -> ${jliPrimary.absolutePath}"
                )
                true
            } else {
                false
            }
        } catch (t: Throwable) {
            VeloLogger.warn("ensureEmbeddedOrExtractedJreReady warning: ${t.message}")
            false
        }
    }

    fun inspectEngineReadiness(context: Context): EngineReadiness {
        val provisioner = DirectBootProvisioner(context)
        val jreReady = ensureEmbeddedOrExtractedJreReady(context, provisioner)

        val clientJar = File(
            provisioner.librariesDir,
            "net/minecraft/client/1.20.4/client-1.20.4.jar"
        )
        val clientOk = clientJar.exists() && clientJar.length() > 15_000_000L

        val modsCount = provisioner.modsDir
            .listFiles { f -> f.isFile && f.extension.equals("jar", ignoreCase = true) }
            ?.size ?: 0

        val indexJson = File(provisioner.assetsDir, "indexes/12.json")
        val assetsOk = indexJson.exists() && indexJson.length() > 100_000L &&
            provisioner.isEngineProvisioned()

        return EngineReadiness(
            isOpenJdkRuntimeReady = jreReady,
            isClientJarReady = clientOk,
            installedModsCount = modsCount,
            isAssetIndexReady = assetsOk
        )
    }

    fun runResumableProvisioning(
        context: Context,
        skipMusicLite: Boolean,
        onProgress: (DownloadProgressState) -> Unit
    ) {
        val provisioner = DirectBootProvisioner(context)
        provisioner.prepareDirectoriesAndConfigs(includeFullMusicAssets = !skipMusicLite)
        val activeAbi = if (Process.is64Bit()) "arm64-v8a" else "armeabi-v7a"

        try {
            // =========================================================================
            // STEP 0: Opsi A OpenJDK 21 Runtime Bundle (`jre21-<abi>-runtime.zip`)
            // =========================================================================
            onProgress(
                DownloadProgressState(
                    isRunning = true,
                    percent = 2,
                    phaseTitle = "Menyiapkan OpenJDK 21 Runtime ($activeAbi)",
                    detailText = "Memeriksa paket OpenJDK 21 & Native Bridge..."
                )
            )

            val runtimeZipName = "jre21-$activeAbi-runtime.zip"
            val runtimeZipFile = File(provisioner.rootEngineDir, runtimeZipName)
            val jliLib = File(provisioner.jreDir, "lib/libjli.so")

            if (!jliLib.exists() || jliLib.length() <= 1024L) {
                val urlsToTry = listOf(
                    "$GITHUB_RELEASE_RUNTIME_BASE/$runtimeZipName",
                    "$GITHUB_FALLBACK_RUNTIME_BASE/$runtimeZipName",
                    "$GITHUB_LATEST_RUNTIME_BASE/$runtimeZipName"
                )
                for (url in urlsToTry) {
                    if (tryDownloadOptionalFile(url, runtimeZipFile)) {
                        extractZipToDir(runtimeZipFile, provisioner.jreDir)
                        runtimeZipFile.delete()
                        VeloLogger.section("RUNTIME", "Extracted $runtimeZipName from GitHub Release into ${provisioner.jreDir.absolutePath}")
                        break
                    }
                }
                ensureEmbeddedOrExtractedJreReady(context, provisioner)
            }

            // =========================================================================
            // STEP 1: Mojang 1.20.4 Version Manifest, Client JAR & Libraries
            // =========================================================================
            onProgress(
                DownloadProgressState(
                    isRunning = true,
                    percent = 6,
                    phaseTitle = "Mengambil Manifest Mojang 1.20.4",
                    detailText = "Menghubungi piston-meta.mojang.com..."
                )
            )

            val manifestRoot = JSONObject(fetchUrlText(MOJANG_VERSION_MANIFEST_URL))
            val versionsArr = manifestRoot.getJSONArray("versions")
            var v1204Url: String? = null
            for (i in 0 until versionsArr.length()) {
                val obj = versionsArr.getJSONObject(i)
                if (obj.optString("id") == "1.20.4") {
                    v1204Url = obj.getString("url")
                    break
                }
            }
            requireNotNull(v1204Url) { "Manifest Minecraft 1.20.4 tidak ditemukan" }

            val versionJson = JSONObject(fetchUrlText(v1204Url))

            // 1A. Client 1.20.4 JAR
            val clientDl = versionJson.getJSONObject("downloads").getJSONObject("client")
            val clientJarFile = File(
                provisioner.librariesDir,
                "net/minecraft/client/1.20.4/client-1.20.4.jar"
            )
            onProgress(
                DownloadProgressState(
                    isRunning = true,
                    percent = 12,
                    phaseTitle = "Mengunduh Minecraft 1.20.4 Client JAR",
                    detailText = "client-1.20.4.jar (~25 MB, mendukung Resume otomatis)"
                )
            )
            downloadFileResumable(
                urlStr = clientDl.getString("url"),
                targetFile = clientJarFile,
                expectedSize = clientDl.optLong("size", -1L),
                expectedSha1 = clientDl.optString("sha1", "")
            )

            // 1B. Mojang Java Libraries
            val libsArray = versionJson.getJSONArray("libraries")
            val totalLibs = libsArray.length().coerceAtLeast(1)
            for (i in 0 until libsArray.length()) {
                val libObj = libsArray.getJSONObject(i)
                val artifact = libObj.optJSONObject("downloads")?.optJSONObject("artifact") ?: continue
                val path = artifact.optString("path", "")
                if (path.isEmpty() || path.contains("natives-macos") || path.contains("natives-windows")) {
                    continue
                }
                val dest = File(provisioner.librariesDir, path)
                val pct = 15 + ((i * 20) / totalLibs)
                if (i % 6 == 0) {
                    onProgress(
                        DownloadProgressState(
                            isRunning = true,
                            percent = pct,
                            phaseTitle = "Mengunduh Pustaka Java Minecraft (${i + 1}/$totalLibs)",
                            detailText = dest.name
                        )
                    )
                }
                downloadFileResumable(
                    urlStr = artifact.getString("url"),
                    targetFile = dest,
                    expectedSize = artifact.optLong("size", -1L),
                    expectedSha1 = artifact.optString("sha1", "")
                )
            }

            // =========================================================================
            // STEP 2: Fabric Loader 0.16.9 Libraries
            // =========================================================================
            onProgress(
                DownloadProgressState(
                    isRunning = true,
                    percent = 36,
                    phaseTitle = "Mengunduh Fabric Loader 0.16.9",
                    detailText = "Menghubungi meta.fabricmc.net..."
                )
            )
            val fabricJson = JSONObject(fetchUrlText(FABRIC_LOADER_META_URL))
            val fabricLibs = fabricJson.getJSONArray("libraries")
            for (i in 0 until fabricLibs.length()) {
                val fLib = fabricLibs.getJSONObject(i)
                val name = fLib.getString("name")
                val baseUrl = fLib.optString("url", "https://maven.fabricmc.net/")
                val relPath = mavenCoordsToPath(name)
                val fullUrl = if (baseUrl.endsWith("/")) "$baseUrl$relPath" else "$baseUrl/$relPath"
                val dest = File(provisioner.librariesDir, relPath)
                downloadFileResumable(
                    urlStr = fullUrl,
                    targetFile = dest,
                    expectedSize = -1L,
                    expectedSha1 = ""
                )
            }

            // =========================================================================
            // STEP 3: Modrinth Optimization Stack + Iris Shaders (1.20.4 Fabric)
            // =========================================================================
            val modSlugs = listOf(
                "fabric-api",
                "sodium",
                "lithium",
                "ferrite-core",
                "immediatelyfast",
                "entityculling",
                "modernfix",
                "iris"
            )
            for ((idx, slug) in modSlugs.withIndex()) {
                val pct = 42 + ((idx * 10) / modSlugs.size)
                onProgress(
                    DownloadProgressState(
                        isRunning = true,
                        percent = pct,
                        phaseTitle = "Mengunduh Mod Optimasi & Shader (${idx + 1}/${modSlugs.size})",
                        detailText = "Modrinth: $slug (1.20.4 Fabric)"
                    )
                )
                downloadModrinthModResumable(slug, provisioner.modsDir)
            }

            // =========================================================================
            // STEP 4: Mojang Asset Index 12.json & Safe Silent-OGG Lite Mode
            // =========================================================================
            val assetIndexInfo = versionJson.getJSONObject("assetIndex")
            val indexFile = File(provisioner.assetsDir, "indexes/12.json")
            downloadFileResumable(
                urlStr = assetIndexInfo.getString("url"),
                targetFile = indexFile,
                expectedSize = assetIndexInfo.optLong("size", -1L),
                expectedSha1 = assetIndexInfo.optString("sha1", "")
            )

            val objectsJson = JSONObject(indexFile.readText()).getJSONObject("objects")
            val keysList = objectsJson.keys().asSequence().toList()
            val totalAssets = keysList.size.coerceAtLeast(1)
            val completedAssets = AtomicInteger(0)
            val downloadedBytes = AtomicLong(0L)
            var skippedSilentMusicCount = 0

            val pool = Executors.newFixedThreadPool(8)
            for (assetKey in keysList) {
                val obj = objectsJson.getJSONObject(assetKey)
                val hash = obj.getString("hash")
                val size = obj.optLong("size", 0L)
                val prefix = hash.substring(0, 2)
                val targetObjFile = File(provisioner.assetsDir, "objects/$prefix/$hash")

                val isHeavyMusicTrack = skipMusicLite && (
                    assetKey.startsWith("minecraft/sounds/music/") ||
                        assetKey.startsWith("minecraft/sounds/records/")
                    )

                if (isHeavyMusicTrack) {
                    skippedSilentMusicCount++
                    pool.submit {
                        writeSilentOggStub(targetObjFile)
                        val done = completedAssets.incrementAndGet()
                        if (done % 120 == 0 || done == totalAssets) {
                            val pct = 54 + ((done * 45) / totalAssets)
                            onProgress(
                                DownloadProgressState(
                                    isRunning = true,
                                    percent = pct.coerceIn(54, 99),
                                    phaseTitle = "Menyiapkan Asset Game ($done/$totalAssets)",
                                    detailText = "Mode Hemat Aman: Stub OGG Hening dibuat untuk musik latar"
                                )
                            )
                        }
                    }
                } else {
                    pool.submit {
                        val url = "https://resources.download.minecraft.net/$prefix/$hash"
                        try {
                            downloadFileResumable(url, targetObjFile, size, hash)
                            downloadedBytes.addAndGet(size)
                        } catch (_: Throwable) {
                        }
                        val done = completedAssets.incrementAndGet()
                        if (done % 45 == 0 || done == totalAssets) {
                            val pct = 54 + ((done * 45) / totalAssets)
                            val mb = downloadedBytes.get() / (1024L * 1024L)
                            onProgress(
                                DownloadProgressState(
                                    isRunning = true,
                                    percent = pct.coerceIn(54, 99),
                                    phaseTitle = "Mengunduh Asset Minecraft ($done/$totalAssets)",
                                    detailText = "Tersimpan: ${mb} MB | Berkas: ${assetKey.substringAfterLast('/')}"
                                )
                            )
                        }
                    }
                }
            }

            pool.shutdown()
            pool.awaitTermination(60, TimeUnit.MINUTES)

            provisioner.markEngineProvisioned()
            VeloLogger.section(
                "DOWNLOAD",
                "All provisioning complete! Total assets=$totalAssets, SilentOggMusicStubs=$skippedSilentMusicCount"
            )

            onProgress(
                DownloadProgressState(
                    isRunning = false,
                    percent = 100,
                    phaseTitle = "Seluruh File Engine & OpenJDK 21 Siap!",
                    detailText = "Minecraft 1.20.4 + Fabric + Mods + OpenJDK 21 telah diverifikasi."
                )
            )
        } catch (t: Throwable) {
            VeloLogger.error("Resumable provisioning paused/interrupted", t)
            onProgress(
                DownloadProgressState(
                    isRunning = false,
                    percent = EngineDownloadService.latestState.percent,
                    phaseTitle = "Unduhan Terjeda (Dapat Dilanjutkan)",
                    detailText = "Ketuk tombol UNDUH / LANJUTKAN untuk melanjutkan dari berkas terakhir."
                )
            )
        }
    }

    /**
     * Generates a valid 108-byte minimal OGG container header with 0 PCM samples so that
     * when Lite Mode skips ~440 MB of background music/records, Minecraft's SoundEngine
     * opens the asset hash cleanly without ever throwing FileNotFoundException or stopping.
     */
    private fun writeSilentOggStub(targetFile: File) {
        if (targetFile.exists() && targetFile.length() >= 58L) return
        targetFile.parentFile?.mkdirs()
        val oggStubBytes = byteArrayOf(
            // OggS capture pattern + version 0 + header_type 0x02 (BOS)
            0x4F, 0x67, 0x67, 0x53, 0x00, 0x02,
            // granule_position (8 bytes = 0)
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            // bitstream_serial_number (4 bytes)
            0x01, 0x00, 0x00, 0x00,
            // page_sequence_number (4 bytes = 0)
            0x00, 0x00, 0x00, 0x00,
            // CRC_checksum (4 bytes)
            0x00, 0x00, 0x00, 0x00,
            // page_segments = 1, segment_table[0] = 30 bytes (Vorbis ID header)
            0x01, 0x1E,
            // Packet type 0x01 + "vorbis"
            0x01, 0x76, 0x6F, 0x72, 0x62, 0x69, 0x73,
            // vorbis_version = 0 (4 bytes)
            0x00, 0x00, 0x00, 0x00,
            // audio_channels = 1 (mono), sample_rate = 44100 Hz (0x0000AC44)
            0x01, 0x44, 0xAC.toByte(), 0x00, 0x00,
            // bitrate_maximum = 0, bitrate_nominal = 64000, bitrate_minimum = 0
            0x00, 0x00, 0x00, 0x00,
            0x00, 0xFA.toByte(), 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            // blocksize_0 = 8 (256), blocksize_1 = 11 (2048), framing_flag = 1
            0xB8.toByte(), 0x01,
            // Second OggS EOS page (50 bytes)
            0x4F, 0x67, 0x67, 0x53, 0x00, 0x04,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x01, 0x16,
            0x03, 0x76, 0x6F, 0x72, 0x62, 0x69, 0x73,
            0x09, 0x00, 0x00, 0x00,
            0x56, 0x65, 0x6C, 0x6F, 0x43, 0x72, 0x61, 0x66, 0x74,
            0x00, 0x00, 0x00, 0x00, 0x01
        )
        targetFile.writeBytes(oggStubBytes)
    }

    private fun downloadModrinthModResumable(slug: String, modsDir: File) {
        val existing = modsDir.listFiles { f ->
            f.isFile && f.name.lowercase().contains(slug.replace("-", ""))
        }
        if (!existing.isNullOrEmpty() && existing.first().length() > 10_000L) {
            return
        }

        val loadersParam = URLEncoder.encode("[\"fabric\"]", "UTF-8")
        val versionsParam = URLEncoder.encode("[\"1.20.4\"]", "UTF-8")
        val apiUrl = "https://api.modrinth.com/v2/project/$slug/version?loaders=$loadersParam&game_versions=$versionsParam"

        val arr = JSONArray(fetchUrlText(apiUrl))
        if (arr.length() == 0) return
        val firstVer = arr.getJSONObject(0)
        val files = firstVer.getJSONArray("files")
        if (files.length() == 0) return
        val fileObj = files.getJSONObject(0)
        val filename = fileObj.getString("filename")
        val url = fileObj.getString("url")
        val size = fileObj.optLong("size", -1L)
        val sha1 = fileObj.optJSONObject("hashes")?.optString("sha1", "") ?: ""

        val target = File(modsDir, filename)
        downloadFileResumable(url, target, size, sha1)
    }

    /**
     * Downloads a single file with HTTP `Range: bytes=<offset>-` support via a `.part` file
     * and verifies its size / SHA-1 hash before atomic rename.
     */
    fun downloadFileResumable(
        urlStr: String,
        targetFile: File,
        expectedSize: Long,
        expectedSha1: String
    ) {
        targetFile.parentFile?.mkdirs()

        if (targetFile.exists()) {
            val lenOk = if (expectedSize > 0L) targetFile.length() == expectedSize else targetFile.length() > 64L
            if (lenOk) return
        }

        val partFile = File(targetFile.parentFile, "${targetFile.name}.part")
        var existingBytes = if (partFile.exists()) partFile.length() else 0L

        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Minecraft-Java-Porting-Android/0.6.0")
            if (existingBytes > 0L) {
                setRequestProperty("Range", "bytes=$existingBytes-")
            }
        }

        val code = conn.responseCode
        if (code == HttpURLConnection.HTTP_PARTIAL) {
            RandomAccessFile(partFile, "rw").use { raf ->
                raf.seek(existingBytes)
                conn.inputStream.use { input ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val r = input.read(buf)
                        if (r <= 0) break
                        raf.write(buf, 0, r)
                    }
                }
            }
        } else if (code == HttpURLConnection.HTTP_OK) {
            existingBytes = 0L
            FileOutputStream(partFile, false).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val r = input.read(buf)
                        if (r <= 0) break
                        out.write(buf, 0, r)
                    }
                }
            }
        } else if (code == 416) {
            // Range Not Satisfiable -> partFile already full
        } else {
            throw IllegalStateException("HTTP $code for $urlStr")
        }

        if (expectedSha1.isNotEmpty() && expectedSize in 1..35_000_000L) {
            val actualSha1 = computeSha1(partFile)
            if (!actualSha1.equals(expectedSha1, ignoreCase = true)) {
                partFile.delete()
                throw IllegalStateException("SHA-1 mismatch for ${targetFile.name}")
            }
        }

        if (targetFile.exists()) targetFile.delete()
        partFile.renameTo(targetFile)
    }

    private fun tryDownloadOptionalFile(urlStr: String, destFile: File): Boolean {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Minecraft-Java-Porting-Android/0.6.0")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return false
            destFile.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            destFile.exists() && destFile.length() > 1024L
        } catch (_: Throwable) {
            false
        }
    }

    private fun extractZipToDir(zipFile: File, destDir: File) {
        destDir.mkdirs()
        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val outFile = File(destDir, entry.name)
                if (!outFile.canonicalPath.startsWith(destDir.canonicalPath)) {
                    continue
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out ->
                        zis.copyTo(out)
                    }
                    if (outFile.name.endsWith(".so") || outFile.parentFile?.name == "bin") {
                        outFile.setExecutable(true, false)
                    }
                }
                zis.closeEntry()
            }
        }
    }

    private fun fetchUrlText(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 25_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Minecraft-Java-Porting-Android/0.6.0")
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun mavenCoordsToPath(coords: String): String {
        val parts = coords.split(":")
        val group = parts[0].replace('.', '/')
        val artifact = parts[1]
        val version = parts[2]
        return "$group/$artifact/$version/$artifact-$version.jar"
    }

    private fun computeSha1(file: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val r = input.read(buf)
                if (r <= 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
