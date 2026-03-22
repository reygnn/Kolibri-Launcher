package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Verwaltet Wallpaper-Bilddateien im internen App-Speicher.
 *
 * == WARUM? ==
 * Content-URIs (z.B. von Google Photos) verlieren ihre Berechtigung
 * bei App-Reinstall/Update. Deshalb werden Bilder beim Setzen/Import
 * in filesDir/wallpapers/ kopiert. Die internen file:// URIs überleben
 * Reinstalls und benötigen keine Persistable Permissions.
 *
 * == VERWENDUNG ==
 * - copyToInternal(sourceUri): Kopiert ein Bild → gibt interne URI zurück
 * - deleteFile(uri): Löscht eine interne Datei (z.B. beim Layer-Entfernen)
 * - clearAll(): Löscht alle Wallpaper-Dateien (z.B. beim Wallpaper-Reset)
 * - isInternalUri(uri): Prüft ob eine URI auf unseren internen Speicher zeigt
 */
@Singleton
class WallpaperFileManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val WALLPAPER_DIR = "wallpapers"
        private var counter = 0L
    }

    private fun getWallpaperDir(): File {
        return File(context.filesDir, WALLPAPER_DIR).also {
            if (!it.exists()) it.mkdirs()
        }
    }

    /**
     * Kopiert ein Bild von einer externen URI in den internen App-Speicher.
     *
     * @param sourceUri Die Quell-URI (content:// oder file://)
     * @return Die interne file:// URI, oder null bei Fehler.
     *
     * Wenn die URI bereits intern ist (isInternalUri), wird sie unverändert zurückgegeben.
     * Die Datei wird byte-für-byte kopiert – Format bleibt erhalten.
     */
    suspend fun copyToInternal(sourceUri: Uri): Uri? = withContext(Dispatchers.IO) {
        try {
            // Bereits intern? Nicht erneut kopieren.
            if (isInternalUri(sourceUri)) {
                return@withContext sourceUri
            }

            val fileName = "wp_${System.currentTimeMillis()}_${counter++}"
            val destFile = File(getWallpaperDir(), fileName)

            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                Timber.w("Could not open input stream for: $sourceUri")
                return@withContext null
            }

            val internalUri = Uri.fromFile(destFile)
            Timber.d("Wallpaper copied to internal: ${destFile.name} (${destFile.length() / 1024} KB)")
            internalUri

        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error copying wallpaper to internal storage")
            null
        }
    }

    /**
     * Prüft ob eine URI auf unseren internen Wallpaper-Speicher zeigt.
     */
    fun isInternalUri(uri: Uri): Boolean {
        if (uri.scheme != "file") return false
        val path = uri.path ?: return false
        return path.startsWith(getWallpaperDir().absolutePath)
    }

    /**
     * Löscht eine interne Wallpaper-Datei.
     * Ignoriert URIs die nicht auf unseren internen Speicher zeigen.
     */
    fun deleteFile(uri: Uri) {
        if (!isInternalUri(uri)) return
        try {
            val path = uri.path ?: return
            val file = File(path)
            if (file.exists() && file.delete()) {
                Timber.d("Deleted wallpaper file: ${file.name}")
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error deleting wallpaper file: $uri")
        }
    }

    /**
     * Löscht ALLE Wallpaper-Dateien im internen Speicher.
     * Wird aufgerufen bei "Wallpaper entfernen" und Factory Reset.
     */
    fun clearAll() {
        try {
            val dir = getWallpaperDir()
            val files = dir.listFiles() ?: return
            var count = 0
            for (file in files) {
                if (file.delete()) count++
            }
            if (count > 0) {
                Timber.d("Cleared $count wallpaper files from internal storage")
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error clearing wallpaper files")
        }
    }

    /**
     * Gibt die Gesamtgröße aller internen Wallpaper-Dateien zurück (in Bytes).
     * Nützlich für Diagnostik / Settings-Anzeige.
     */
    fun getTotalSizeBytes(): Long {
        return try {
            getWallpaperDir().listFiles()?.sumOf { it.length() } ?: 0L
        } catch (e: Throwable) {
            0L
        }
    }
}