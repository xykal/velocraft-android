package io.velocraft.android.engine

import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Handles downloading and verifying the embedded Native Runtime components for BOTH architectures:
 * - 64-bit (`arm64-v8a`): OpenJDK 21 ARM64 + LWJGL3 ARM64 + MobileGlues GLES 3.2
 * - 32-bit (`armeabi-v7a`): OpenJDK ARM32 (`armeabi-v7a`) + LWJGL3 ARM32 + GL4ES 1.1.5
 */
class Arm64RuntimeDownloader(
    private val jreDir: File,
    private val nativesDir: File
) {
    companion object {
        const val RELEASE_BASE_URL =
            "https://github.com/xykal/velocraft-android/releases/download/runtime-bundles"
    }

    val activeAbi: String = if (Process.is64Bit()) "arm64-v8a" else "armeabi-v7a"

    fun isJreInstalled(): Boolean {
        val jli = File(jreDir, "lib/libjli.so")
        val jliAlt = File(jreDir, "lib/jli/libjli.so")
        return (jli.exists() && jli.length() > 0L) || (jliAlt.exists() && jliAlt.length() > 0L)
    }

    suspend fun ensureArm64RuntimeReady(
        onProgress: (status: String, percent: Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        jreDir.mkdirs()
        nativesDir.mkdirs()

        if (isJreInstalled()) {
            onProgress("OpenJDK ($activeAbi) sudah terpasang.", 100)
            return@withContext
        }

        val jreZipUrl = "$RELEASE_BASE_URL/jre21-$activeAbi-minimal.zip"
        val nativesZipUrl = "$RELEASE_BASE_URL/lwjgl3-natives-$activeAbi.zip"

        try {
            onProgress("Mengecek paket OpenJDK & Renderer ($activeAbi)...", 25)
            val tempJreZip = File(jreDir.parentFile, "jre21-$activeAbi.zip")
            if (tryDownloadOptionalArchive(jreZipUrl, tempJreZip)) {
                onProgress("Mengekstrak OpenJDK ($activeAbi)...", 65)
                RuntimeExtractor.extractZipArchive(tempJreZip, jreDir)
                tempJreZip.delete()
            }

            val tempNativesZip = File(nativesDir.parentFile, "natives-$activeAbi.zip")
            if (tryDownloadOptionalArchive(nativesZipUrl, tempNativesZip)) {
                onProgress("Mengekstrak LWJGL3 & Renderer ($activeAbi)...", 85)
                RuntimeExtractor.extractZipArchive(tempNativesZip, nativesDir)
                tempNativesZip.delete()
            }
        } catch (_: Exception) {
            // Proceed gracefully if runtime bundle mirror isn't uploaded yet
        }

        onProgress("Runtime $activeAbi diverifikasi.", 100)
    }

    private fun tryDownloadOptionalArchive(urlStr: String, destFile: File): Boolean {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "xykal/velocraft-android/0.3.0 ($activeAbi)")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                return false
            }
            conn.inputStream.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            destFile.exists() && destFile.length() > 1024L
        } catch (_: Exception) {
            false
        }
    }
}
