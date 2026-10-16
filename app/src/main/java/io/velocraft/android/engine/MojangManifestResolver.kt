package io.velocraft.android.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Resolves and downloads official Minecraft Java 1.20.4 libraries, client.jar, and Fabric Loader
 * directly from Mojang (piston-meta.mojang.com) and Fabric (meta.fabricmc.net) CDNs.
 *
 * Uses bounded parallel coroutines (8 concurrent streams) and SHA-1 verification.
 */
class MojangManifestResolver(
    private val librariesDir: File,
    private val gameDir: File
) {
    private val downloadSemaphore = Semaphore(8)

    data class LibraryArtifact(
        val name: String,
        val url: String,
        val sha1: String?,
        val targetFile: File
    )

    suspend fun resolveAndDownloadCoreLibraries(
        minecraftVersion: String = "1.20.4",
        fabricLoaderVersion: String = "0.16.7",
        onProgress: (downloaded: Int, total: Int, currentArtifact: String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val artifacts = mutableListOf<LibraryArtifact>()

        // 1. Fetch Fabric Loader Profile JSON (includes Fabric Loader, Intermediary, Mixin, ASM)
        val fabricProfileUrl =
            "https://meta.fabricmc.net/v2/versions/loader/$minecraftVersion/$fabricLoaderVersion/profile/json"
        val fabricJson = JSONObject(fetchString(fabricProfileUrl))
        val fabricLibs = fabricJson.getJSONArray("libraries")
        for (i in 0 until fabricLibs.length()) {
            val lib = fabricLibs.getJSONObject(i)
            val coord = lib.getString("name")
            val baseUrl = lib.optString("url", "https://maven.fabricmc.net/")
            val relPath = mavenCoordinateToPath(coord)
            val fullUrl = if (baseUrl.endsWith("/")) "$baseUrl$relPath" else "$baseUrl/$relPath"
            artifacts.add(
                LibraryArtifact(
                    name = coord.substringAfterLast(':'),
                    url = fullUrl,
                    sha1 = null,
                    targetFile = File(librariesDir, relPath)
                )
            )
        }

        // 2. Fetch Mojang Version Manifest v2 to locate official 1.20.4 version JSON
        val manifestJson = JSONObject(
            fetchString("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")
        )
        val versionsArr = manifestJson.getJSONArray("versions")
        var versionDetailUrl: String? = null
        for (i in 0 until versionsArr.length()) {
            val v = versionsArr.getJSONObject(i)
            if (v.getString("id") == minecraftVersion) {
                versionDetailUrl = v.getString("url")
                break
            }
        }

        if (versionDetailUrl != null) {
            val verJson = JSONObject(fetchString(versionDetailUrl))

            // Add official Minecraft client.jar
            val clientDownload = verJson.getJSONObject("downloads").getJSONObject("client")
            artifacts.add(
                LibraryArtifact(
                    name = "minecraft-$minecraftVersion-client.jar",
                    url = clientDownload.getString("url"),
                    sha1 = clientDownload.optString("sha1"),
                    targetFile = File(librariesDir, "net/minecraft/client/$minecraftVersion/client-$minecraftVersion.jar")
                )
            )

            // Add non-native Java libraries required by Minecraft
            val mcLibs = verJson.getJSONArray("libraries")
            for (i in 0 until mcLibs.length()) {
                val lib = mcLibs.getJSONObject(i)
                if (!isLibraryAllowedOnLinuxArm64(lib.optJSONArray("rules"))) continue
                val downloads = lib.optJSONObject("downloads") ?: continue
                val artifact = downloads.optJSONObject("artifact") ?: continue
                val path = artifact.getString("path")
                // Skip desktop x86 LWJGL native jars; VeloCraft uses Android ARM64 LWJGL3 natives
                if (path.contains("natives-windows") || path.contains("natives-macos") || path.contains("natives-linux-x86")) {
                    continue
                }
                artifacts.add(
                    LibraryArtifact(
                        name = path.substringAfterLast('/'),
                        url = artifact.getString("url"),
                        sha1 = artifact.optString("sha1"),
                        targetFile = File(librariesDir, path)
                    )
                )
            }
        }

        // 3. Download all missing or corrupted artifacts concurrently
        var completed = 0
        val total = artifacts.size.coerceAtLeast(1)

        coroutineScope {
            artifacts.map { item ->
                async {
                    downloadSemaphore.withPermit {
                        if (!isFileValid(item.targetFile, item.sha1)) {
                            downloadToFile(item.url, item.targetFile)
                        }
                        synchronized(this@MojangManifestResolver) {
                            completed++
                            onProgress(completed, total, item.name)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private fun isLibraryAllowedOnLinuxArm64(rules: JSONArray?): Boolean {
        if (rules == null || rules.length() == 0) return true
        var allowed = false
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            val action = rule.getString("action") == "allow"
            val os = rule.optJSONObject("os")
            if (os == null) {
                allowed = action
            } else if (os.optString("name") == "linux" || os.optString("name") == "osx") {
                if (os.optString("name") == "linux") allowed = action
            }
        }
        return allowed
    }

    private fun mavenCoordinateToPath(coord: String): String {
        val parts = coord.split(":")
        require(parts.size >= 3) { "Invalid Maven coordinate: $coord" }
        val groupPath = parts[0].replace('.', '/')
        val artifact = parts[1]
        val version = parts[2]
        return "$groupPath/$artifact/$version/$artifact-$version.jar"
    }

    private fun isFileValid(file: File, expectedSha1: String?): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        if (expectedSha1.isNullOrBlank()) return true
        return computeSha1(file).equals(expectedSha1, ignoreCase = true)
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

    private fun fetchString(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 25_000
            setRequestProperty("User-Agent", "VeloCraft-Android/0.2.0")
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun downloadToFile(urlStr: String, target: File) {
        target.parentFile?.mkdirs()
        val tempFile = File(target.parentFile, "${target.name}.part")
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "VeloCraft-Android/0.2.0")
        }
        conn.inputStream.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        tempFile.renameTo(target)
    }
}
