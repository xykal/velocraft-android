package io.velocraft.android.engine

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Extracts embedded or downloaded ARM64 runtime archives (OpenJDK 21 ARM64 + LWJGL3-Android
 * + MobileGlues / GL4ES renderer shared objects) and marks native binaries executable.
 */
object RuntimeExtractor {

    fun extractZipArchive(zipFile: File, targetDir: File) {
        if (!zipFile.exists()) return
        targetDir.mkdirs()

        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                // Prevent Zip Slip vulnerability
                if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    throw SecurityException("Blocked Zip Slip path traversal: ${entry.name}")
                }

                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { output ->
                        zis.copyTo(output)
                    }
                    if (outFile.name.endsWith(".so") || outFile.parentFile?.name == "bin") {
                        outFile.setExecutable(true, false)
                        outFile.setReadable(true, false)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /**
     * Copies native shared libraries from the APK's nativeLibraryDir into the LWJGL natives path
     * if needed by dynamic dlopen lookups.
     */
    fun linkApkNativeLibraries(context: Context, nativesDir: File) {
        nativesDir.mkdirs()
        val apkLibDir = File(context.applicationInfo.nativeLibraryDir)
        if (!apkLibDir.exists()) return

        apkLibDir.listFiles()?.forEach { soFile ->
            if (soFile.name.endsWith(".so")) {
                val dest = File(nativesDir, soFile.name)
                if (!dest.exists() || dest.length() != soFile.length()) {
                    soFile.copyTo(dest, overwrite = true)
                    dest.setExecutable(true, false)
                }
            }
        }
    }
}
