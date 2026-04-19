package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import android.net.Uri
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong
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
 * - copyFromInputStream(inputStream): Schreibt Bytes aus Stream → interne URI (für ZIP-Import)
 * - deleteFile(uri): Löscht eine interne Datei (z.B. beim Layer-Entfernen)
 * - clearAll(): Löscht alle Wallpaper-Dateien (z.B. beim Wallpaper-Reset)
 * - gcOrphans(referencedUris): Entfernt verwaiste Dateien, die nicht mehr
 *   von einem aktuellen State referenziert werden
 * - isInternalUri(uri): Prüft ob eine URI auf unseren internen Speicher zeigt
 */
@Singleton
class WallpaperFileManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val WALLPAPER_DIR = "wallpapers"

        // Thread-safe counter. copyToInternal läuft auf Dispatchers.IO — mehrere
        // Imports parallel (z.B. via ZIP-Restore) würden sonst Dateinamen-Kollisionen
        // produzieren, wenn timestamp-Auflösung + nicht-atomarer counter++ kombiniert werden.
        private val counter = AtomicLong(0)

        private fun nextFileName(prefix: String): String =
            "${prefix}_${System.currentTimeMillis()}_${counter.getAndIncrement()}"
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
     */
    suspend fun copyToInternal(sourceUri: Uri): Uri? = withContext(Dispatchers.IO) {
        try {
            if (isInternalUri(sourceUri)) {
                return@withContext sourceUri
            }

            val fileName = nextFileName("wp")
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
     * Schreibt Bytes aus einem InputStream in eine neue interne Datei.
     * Der InputStream wird NICHT geschlossen (wichtig für ZipInputStream).
     *
     * @param inputStream Die Quelle (z.B. ein ZipInputStream-Entry)
     * @return Die interne file:// URI, oder null bei Fehler.
     */
    fun copyFromInputStream(inputStream: InputStream): Uri? {
        return try {
            val fileName = nextFileName("wp")
            val destFile = File(getWallpaperDir(), fileName)

            destFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }

            val internalUri = Uri.fromFile(destFile)
            Timber.d("Wallpaper extracted to internal: ${destFile.name} (${destFile.length() / 1024} KB)")
            internalUri

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error writing wallpaper from input stream")
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
     * Prüft ob die Datei hinter einer internen URI noch existiert.
     * Gibt true zurück für nicht-file URIs (können hier nicht geprüft werden).
     */
    fun fileExists(uri: Uri): Boolean {
        if (uri.scheme != "file") return true
        val path = uri.path ?: return false
        return File(path).exists()
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
     * =====================================================================================
     * ARCHITECTURAL NOTE: Why gcOrphans has an age cutoff, and why the default is 60 seconds
     * =====================================================================================
     *
     * This method DELETES REAL FILES FROM DISK. It is the single most destructive operation
     * in the wallpaper subsystem. Every design decision here is a balance between "clean up
     * leftovers from crashed operations" and "never, ever delete a file the user still
     * needs".
     *
     * The [minAgeMillis] parameter implements a critical safety net: files whose
     * `lastModified()` timestamp is within that window are skipped, regardless of whether
     * they appear in [referencedUris]. If you're tempted to remove this check, read the
     * scenario below first.
     *
     * **The Race Condition We're Guarding Against:**
     * There is a window between "file written to disk" and "state carrying that file's URI
     * is persisted" during two code paths:
     *
     *   1. `copyToInternal(sourceUri)` — user picks an image from Google Photos:
     *      - Step A: File bytes are copied to `filesDir/wallpapers/wp_*.jpg`
     *      - Step B: Delegate updates `_wallpaperState.value` with the new URI
     *      - Step C: `saveWallpaperStateUseCase` persists the new state to DataStore
     *      - Step D: DataStore flow emits the new state to `observeWallpaperStateUseCase`
     *
     *   2. `copyFromInputStream(...)` — ZIP backup restore extracts wallpapers:
     *      - Similar ABCD sequence, but can include MANY files written sequentially
     *        before a single aggregated state save at the end.
     *
     * Between steps A and D, the file exists on disk but is NOT in any version of the state.
     * If `gcOrphans` runs in that window (say, because some other state emission triggered
     * it), the file qualifies as "orphan" and gets deleted — even though the user just
     * picked it two milliseconds ago. The app would then try to render a state pointing at
     * a file that no longer exists, the user would see their wallpaper reset, and they
     * would rightfully curse the developers.
     *
     * **Why 60 Seconds?**
     * - Must be longer than the slowest realistic A→D window. File I/O on a cold device
     *   with heavy load can take a few hundred ms; DataStore persistence adds another few
     *   hundred; a full backup restore with dozens of wallpapers can stretch into seconds.
     * - Must NOT be so long that it keeps obviously-stale files around indefinitely. The
     *   orphan GC exists specifically to clean up after crashes mid-write; if we wait
     *   hours, we waste disk space for no reason.
     * - 60 seconds is a pragmatic compromise that keeps the app safe under all realistic
     *   device conditions I've observed, while still cleaning up after a single crashed
     *   write on the next app start.
     *
     * **Callers Can Override For Testing:**
     * Tests pass `minAgeMillis = 0L` to bypass the cutoff entirely, or small values like
     * `1_000L` to test the cutoff logic itself. Production callers always use the default.
     *
     * **What This Method CANNOT Touch:**
     * - Files outside `filesDir/wallpapers/` — we only `listFiles()` on that exact directory.
     *   User photos in `/sdcard/DCIM/`, `/sdcard/Pictures/`, or Google Photos content://
     *   URIs are physically unreachable from this code path. We have neither the permission
     *   nor the path.
     * - Non-`file://` URIs in [referencedUris] — they are filtered out before the comparison.
     *   A `content://` URI slipping through would at worst fail to protect a file in our
     *   wallpapers/ directory; it would never cause a file OUTSIDE that directory to be
     *   deleted.
     *
     * **Regression Guards:**
     * - `gcOrphans respects the default minAgeMillis — young files survive`
     * - `gcOrphans does not touch files outside wallpapers directory`
     * - `gcOrphans ignores non-file URIs in the referenced set`
     * All in [com.github.reygnn.kolibri_launcher.data.WallpaperFileManagerTest].
     *
     * @param referencedUris The URIs currently held by the authoritative state. Any file
     *     in our wallpapers/ directory whose path matches one of these is a "keep". Any
     *     file that doesn't match AND is older than [minAgeMillis] is deleted.
     * @param minAgeMillis Safety-net lower bound on file age. Files newer than this are
     *     ALWAYS preserved, even if unreferenced — they might be mid-write by another
     *     operation. Default: 60 seconds.
     */

    /**
     * Räumt verwaiste Wallpaper-Dateien weg: alle Dateien im internen
     * Wallpaper-Verzeichnis, die nicht in [referencedUris] vorkommen UND
     * älter als [minAgeMillis] sind, werden gelöscht.
     *
     * == WARUM? ==
     * Wenn die App während eines Commits der Edit-Session abstürzt oder
     * ein `saveWallpaperStateUseCase` nach einem erfolgreichen
     * `copyToInternal` fehlschlägt, kann eine Datei auf der Platte
     * zurückbleiben, die keinen State mehr referenziert. Dieser Garbage
     * Collector räumt sie auf — idealerweise **einmalig** beim App-Start
     * aufrufen, nachdem der Initial-State aus der Persistenz angekommen ist.
     *
     * == WARUM AGE-CUTOFF? ==
     * Während eines laufenden [copyToInternal] oder [copyFromInputStream]
     * (z.B. ZIP-Backup-Restore) existiert die Datei einen kurzen Moment
     * auf Disk, bevor der State sie referenziert. Der Age-Cutoff schützt
     * davor, dass ein gleichzeitig laufender GC frisch erzeugte Dateien
     * versehentlich mitnimmt.
     *
     * @param referencedUris Menge der aktuell referenzierten URIs
     *     (z.B. [com.github.reygnn.kolibri_launcher.domain.model.WallpaperState.referencedUris]).
     * @param minAgeMillis Dateien, deren `lastModified()` jünger als dieser
     *     Wert ist, werden NICHT gelöscht. Default: 60 Sekunden.
     */
    fun gcOrphans(referencedUris: Set<Uri>, minAgeMillis: Long = 60_000L) {
        try {
            val dir = getWallpaperDir()
            if (!dir.exists() || !dir.isDirectory) return

            val files = dir.listFiles() ?: return
            if (files.isEmpty()) return

            // Referenzierte absolute Dateipfade für schnellen Lookup.
            // Non-file URIs (content://...) kommen hier nie als Kandidaten
            // vor, weil sie nicht in unserem wallpapers/ Dir liegen —
            // wir filtern sie trotzdem defensiv raus.
            val referencedPaths = referencedUris
                .asSequence()
                .filter { it.scheme == "file" }
                .mapNotNull { it.path }
                .toSet()

            val ageCutoff = System.currentTimeMillis() - minAgeMillis
            var kept = 0
            var deleted = 0
            var skippedTooYoung = 0

            for (file in files) {
                // Safety net: file might be freshly written by a concurrent
                // copyToInternal / copyFromInputStream whose state-save hasn't
                // landed yet. Never delete recent files.
                if (file.lastModified() > ageCutoff) {
                    skippedTooYoung++
                    continue
                }
                if (file.absolutePath in referencedPaths) {
                    kept++
                    continue
                }
                if (file.delete()) deleted++
            }

            if (deleted > 0 || skippedTooYoung > 0) {
                Timber.d(
                    "GC: scanned=${files.size} kept=$kept " +
                            "deleted=$deleted skipped-too-young=$skippedTooYoung"
                )
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error running wallpaper orphan GC")
        }
    }

    /**
     * Gibt die Gesamtgröße aller internen Wallpaper-Dateien zurück (in Bytes).
     */
    fun getTotalSizeBytes(): Long {
        return try {
            getWallpaperDir().listFiles()?.sumOf { it.length() } ?: 0L
        } catch (e: Throwable) {
            0L
        }
    }
}