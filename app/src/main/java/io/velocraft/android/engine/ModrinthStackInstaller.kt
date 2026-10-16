package io.velocraft.android.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Automatically fetches the latest compatible Fabric 1.20.4 optimization mods directly
 * from the Modrinth v2 REST API (`https://api.modrinth.com/v2/`):
 * - Sodium (Chunk multidraw renderer)
 * - Lithium (Physics & mob AI optimization)
 * - FerriteCore (-45% RAM usage)
 * - ImmediatelyFast (HUD & entity batching)
 * - EntityCulling (Async occlusion culling)
 * - ModernFix (Dynamic resource loading & fast boot)
 */
class ModrinthStackInstaller(private val modsDir: File) {

    data class ModSpec(
        val slug: String,
        val displayName: String
    )

    private val defaultStack = listOf(
        ModSpec("fabric-api", "Fabric API"),
        ModSpec("sodium", "Sodium"),
        ModSpec("lithium", "Lithium"),
        ModSpec("ferrite-core", "FerriteCore"),
        ModSpec("immediatelyfast", "ImmediatelyFast"),
        ModSpec("entityculling", "EntityCulling"),
        ModSpec("modernfix", "ModernFix")
    )

    suspend fun installOptimizationModpack(
        minecraftVersion: String = "1.20.4",
        onModProgress: (modName: String, index: Int, total: Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        modsDir.mkdirs()

        coroutineScope {
            defaultStack.mapIndexed { idx, spec ->
                async {
                    onModProgress(spec.displayName, idx + 1, defaultStack.size)
                    try {
                        downloadModFromModrinth(spec.slug, minecraftVersion)
                    } catch (_: Exception) {
                        // Offline or rate-limited fallback: skip gracefully so cached mods still boot
                    }
                }
            }.awaitAll()
        }
    }

    private fun downloadModFromModrinth(slug: String, mcVersion: String) {
        val loadersParam = URLEncoder.encode("[\"fabric\"]", "UTF-8")
        val gameVersionsParam = URLEncoder.encode("[\"$mcVersion\"]", "UTF-8")
        val apiUrl =
            "https://api.modrinth.com/v2/project/$slug/version?loaders=$loadersParam&game_versions=$gameVersionsParam"

        val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "xykal/velocraft-android/0.2.0 (OpenSource Direct Engine)")
        }

        val responseStr = conn.inputStream.bufferedReader().use { it.readText() }
        val versionsArr = JSONArray(responseStr)
        if (versionsArr.length() == 0) return

        val latestVersion = versionsArr.getJSONObject(0)
        val filesArr = latestVersion.getJSONArray("files")
        if (filesArr.length() == 0) return

        val primaryFile = filesArr.getJSONObject(0)
        val filename = primaryFile.getString("filename")
        val downloadUrl = primaryFile.getString("url")
        val targetFile = File(modsDir, filename)

        if (targetFile.exists() && targetFile.length() > 1024L) {
            return // Already installed
        }

        val dlConn = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "xykal/velocraft-android/0.2.0")
        }
        val tempFile = File(modsDir, "$filename.part")
        dlConn.inputStream.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        tempFile.renameTo(targetFile)
    }
}
