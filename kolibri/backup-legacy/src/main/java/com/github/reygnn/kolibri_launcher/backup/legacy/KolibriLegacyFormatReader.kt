package com.github.reygnn.kolibri_launcher.backup.legacy

import com.github.reygnn.kolibri_launcher.data.BackupSerializer
import com.github.reygnn.kolibri_launcher.data.KolibriBackupSchema
import com.github.reygnn.kolibri_launcher.domain.model.LauncherSettings
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.core.CappedInputStream
import com.github.reygnn.launcher.feature.backup.container.ContainerManifest
import com.github.reygnn.launcher.feature.backup.engine.LegacyConversion
import com.github.reygnn.launcher.feature.backup.engine.LegacyFormatReader
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.inject.Inject

/**
 * Up-converts Kolibri's pre-E5a archive (`backup.json` + `wallpapers/layer_N.img`) into the
 * current `kolibri.backup` section plus staged blobs (SPEC_NYX_REWRITE E5a). The images are
 * hashed while they are staged; a layer's `imageFileName` (the old ZIP path) becomes the
 * blob's SHA-256 — exactly how a new backup references it. Equal images are staged once.
 *
 * Caps are the old reader's: the whole archive and each image at the shared backup budget,
 * at most [MAX_IMAGE_ENTRIES] images. A cap violation throws an IOException with a message
 * the import turns into an error; nothing stays staged. A backup.json that cannot be read
 * yields a conversion without sections, which the import reports as an invalid backup.
 * Unzipped legacy JSON is not read (every valid backup is zipped).
 */
class KolibriLegacyFormatReader @Inject constructor(
    private val serializer: BackupSerializer,
) : LegacyFormatReader {

    override val appId: String = KolibriBackupSchema.APP_ID

    override suspend fun read(open: () -> InputStream, stagingDir: File): LegacyConversion? {
        stagingDir.mkdirs()
        val staged = LinkedHashMap<String, File>() // sha256 → staged file
        val hashOfEntry = HashMap<String, String>() // old ZIP entry name → sha256
        var json: String? = null
        var complete = false
        try {
            open().use { raw ->
                val archive = CappedInputStream(BufferedInputStream(raw), AppConstants.MAX_BACKUP_SIZE_BYTES + 1)
                try {
                    ZipInputStream(archive).use { zip ->
                        var images = 0
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            when {
                                entry.name == LEGACY_MANIFEST -> json = zip.readBytes().toString(Charsets.UTF_8)
                                entry.name.startsWith(LEGACY_IMAGE_DIR) && !entry.isDirectory -> {
                                    if (++images > MAX_IMAGE_ENTRIES) throw IOException("Backup archive has too many images")
                                    val hash = stage(zip, stagingDir, staged)
                                    hashOfEntry[entry.name] = hash
                                }
                            }
                        }
                    }
                } catch (e: IOException) {
                    // The archive cap ends the stream early — also while ZipInputStream skips an
                    // entry it was not asked to read — which it reports as a truncated archive
                    // (EOFException). With the cap reached that is "too large", not "corrupt".
                    if (archive.limitReached) throw IOException("Backup file is too large", e) else throw e
                }
                if (archive.limitReached) throw IOException("Backup file is too large")
            }
            val content = json ?: return null // not a pre-E5a Kolibri archive after all
            val backup = serializer.parseBackupData(content)
            val sections = if (backup == null || !serializer.isVersionSupported(backup.version)) {
                emptyMap() // unreadable or unsupported → the import answers "invalid backup"
            } else {
                mapOf(
                    KolibriBackupSchema.SECTION_BACKUP to ContainerManifest.Section(
                        KolibriBackupSchema.SECTION_VERSION,
                        serializer.settingsToJson(referencingBlobs(backup.settings, hashOfEntry)),
                    ),
                )
            }
            complete = true
            return LegacyConversion(
                producer = ContainerManifest.Producer(appId, backup?.appVersion.orEmpty(), backup?.timestamp ?: 0L),
                schemaVersion = KolibriBackupSchema.SCHEMA_VERSION,
                sections = sections,
                staged = staged.toMap(),
            )
        } finally {
            if (!complete) {
                staged.values.forEach { it.delete() }
                stagingDir.listFiles { file -> file.name.endsWith(PART_SUFFIX) }?.forEach { it.delete() }
            }
        }
    }

    /** Streams one image into the staging dir, hashing it; returns its SHA-256. */
    private fun stage(zip: ZipInputStream, stagingDir: File, staged: MutableMap<String, File>): String {
        val part = File(stagingDir, "legacy-${System.nanoTime()}$PART_SUFFIX")
        val capped = CappedInputStream(zip, AppConstants.MAX_BACKUP_SIZE_BYTES + 1)
        val digest = MessageDigest.getInstance("SHA-256")
        part.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = capped.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                out.write(buffer, 0, n)
            }
        }
        if (capped.limitReached) {
            part.delete()
            throw IOException("Backup image is too large")
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (hash in staged) {
            part.delete() // equal image already staged
        } else {
            val target = File(stagingDir, hash)
            if (!part.renameTo(target)) {
                part.delete()
                throw IOException("Could not stage backup image")
            }
            staged[hash] = target
        }
        return hash
    }

    /**
     * The old ZIP paths become blob references. A layer whose image was in the archive loses
     * its `imageUri` (the blob is the source of truth, as in a new backup); a layer without
     * an embedded image keeps its URI and gets no reference.
     */
    private fun referencingBlobs(settings: LauncherSettings, hashOfEntry: Map<String, String>): LauncherSettings {
        val layers = settings.wallpaperLayers.map { layer ->
            val hash = layer.imageFileName?.let(hashOfEntry::get)
            if (hash != null) layer.copy(imageUri = null, imageFileName = hash) else layer.copy(imageFileName = null)
        }
        val single = settings.wallpaperImageFileName?.let(hashOfEntry::get)
        return settings.copy(wallpaperLayers = layers, wallpaperImageFileName = single)
    }

    private companion object {
        const val LEGACY_MANIFEST = "backup.json"
        const val LEGACY_IMAGE_DIR = "wallpapers/"
        const val PART_SUFFIX = ".part"
        /** A real backup carries one image per wallpaper layer (the old reader's cap). */
        const val MAX_IMAGE_ENTRIES = 64
    }
}
