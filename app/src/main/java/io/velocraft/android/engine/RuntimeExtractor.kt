package io.velocraft.android.engine

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Extracts embedded or downloaded runtime archives and links APK native shared libraries
 * (`libgl4es_114.so`, `liblwjgl*.so`, `libopenal.so`, `libfreetype.so`, `libjnidispatch.so`,
 * and `libvelocraft_bridge.so` -> `libvelocexec.so`) into `nativesDir`.
 */
object RuntimeExtractor {

    fun extractZipArchive(zipFile: File, targetDir: File) {
        if (!zipFile.exists()) return
        targetDir.mkdirs()

        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
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
     * and aliases `libvelocraft_bridge.so` as `libvelocexec.so` for `lwjgl-glfw-classes.jar`.
     */
    fun linkApkNativeLibraries(context: Context, nativesDir: File) {
        nativesDir.mkdirs()
        val apkLibDir = File(context.applicationInfo.nativeLibraryDir)
        if (!apkLibDir.exists()) return

        apkLibDir.listFiles()?.forEach { soFile ->
            if (soFile.name.endsWith(".so")) {
                val dest = File(nativesDir, soFile.name)
                val isCoreBridge = soFile.name.startsWith("libvelocraft")
                if (isCoreBridge || !dest.exists() || dest.length() != soFile.length()) {
                    soFile.copyTo(dest, overwrite = true)
                    dest.setExecutable(true, false)
                    dest.setReadable(true, false)
                }
                if (soFile.name == "libvelocraft_bridge.so") {
                    val velocExecAlias = File(nativesDir, "libvelocexec.so")
                    soFile.copyTo(velocExecAlias, overwrite = true)
                    velocExecAlias.setExecutable(true, false)
                    velocExecAlias.setReadable(true, false)
                }
            }
        }
    }
}
