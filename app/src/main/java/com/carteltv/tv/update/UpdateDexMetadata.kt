package com.carteltv.tv.update

import android.os.Build
import java.io.File
import java.util.Properties
import java.util.zip.ZipFile

/**
 * Extracts the per-API ART dex metadata (.dm) embedded under `assets/updater_dm/` inside
 * an update APK so `PackageInstaller` compiles the baseline profile at install time
 * instead of waiting for idle background dexopt.
 */
object UpdateDexMetadata {
    const val ASSET_DIR = "assets/updater_dm/"
    const val INDEX_FILE = "index.properties"

    fun extract(apkFile: File, sdkInt: Int, destination: File): File? {
        if (sdkInt < Build.VERSION_CODES.P) return null
        return runCatching {
            ZipFile(apkFile).use { zip ->
                val indexEntry = zip.getEntry(ASSET_DIR + INDEX_FILE) ?: return null
                val index = Properties().apply { zip.getInputStream(indexEntry).use(::load) }
                val fileName = selectFileName(index, sdkInt) ?: return null
                val entry = zip.getEntry(ASSET_DIR + fileName) ?: return null
                destination.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
                destination
            }
        }.getOrNull()
    }

    /** Picks exact [sdkInt] entry, or open-ended [Int.MAX_VALUE] when above all listed APIs. */
    fun selectFileName(index: Properties, sdkInt: Int): String? {
        index.getProperty(sdkInt.toString())?.takeIf { it.isNotBlank() }?.let { return it }
        val listed = index.stringPropertyNames().mapNotNull { it.toIntOrNull() }
        val highest = listed.filter { it != Int.MAX_VALUE }.maxOrNull() ?: return null
        return if (sdkInt > highest) index.getProperty(Int.MAX_VALUE.toString())?.takeIf { it.isNotBlank() } else null
    }
}
