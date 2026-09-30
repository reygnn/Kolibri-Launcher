package com.github.reygnn.nyx_launcher.data.home

import android.net.Uri
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.core.IoDispatcher
import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.launcher.core.wallpaper.WallpaperBackdrop
import com.github.reygnn.launcher.core.wallpaper.WallpaperDisplaySettings
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerBackup
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerState
import com.github.reygnn.launcher.core.wallpaper.WallpaperRepository
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.github.reygnn.launcher.core.wallpaper.WallpaperSurfaceMode
import com.github.reygnn.launcher.core.wallpaper.FabPosition
import com.github.reygnn.launcher.feature.backup.container.BlobSource
import com.github.reygnn.launcher.feature.backup.container.ContainerManifest
import com.github.reygnn.launcher.feature.backup.engine.BackupEngine
import com.github.reygnn.launcher.feature.backup.engine.BackupRead
import com.github.reygnn.launcher.feature.backup.engine.StagedBlobs
import com.github.reygnn.nyx_launcher.home.model.IconStyle
import com.github.reygnn.nyx_launcher.home.model.ImportResult
import com.github.reygnn.nyx_launcher.home.repository.DrawerFoldersRepository
import com.github.reygnn.nyx_launcher.home.repository.HiddenAppsRepository
import com.github.reygnn.nyx_launcher.home.repository.HomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.repository.PreferencesRepository
import com.github.reygnn.nyx_launcher.home.transition.DrawerFoldersTransition
import com.github.reygnn.nyx_launcher.home.usecase.ReconcileHomeLayoutUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nyx's backup over the shared [BackupEngine] (SPEC_NYX_REWRITE 2b-1): the E5a container
 * with one `nyx.backup` section ([NyxBackupSchema]) and the wallpaper images as blobs,
 * referenced by hash. The container itself — ZIP, caps, hashing, staging — is the engine's;
 * this class assembles the section from Nyx's repos and applies it.
 *
 * Callers own the streams (the settings UI opens the SAF Uri); this stays
 * ContentResolver-free and testable. Blob restore reuses the shared
 * [WallpaperFileManager.copyFromInputStream] (staged blob → internal file), so imported
 * layers land in internal storage exactly like a fresh pick. Dissolved in 2b-4.
 */
@Singleton
class NyxBackupManager @Inject constructor(
    private val homeLayoutRepository: HomeLayoutRepository,
    private val drawerFoldersRepository: DrawerFoldersRepository,
    private val hiddenAppsRepository: HiddenAppsRepository,
    private val preferences: PreferencesRepository,
    private val displaySettings: WallpaperDisplaySettings,
    private val wallpaperRepository: WallpaperRepository,
    private val fabPositionStore: NyxFabPositionStore,
    private val fileManager: WallpaperFileManager,
    private val serializer: NyxBackupSerializer,
    private val reconcileHomeLayout: ReconcileHomeLayoutUseCase,
    private val engine: BackupEngine,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    /** Writes a full backup container to [out] (left open for the caller). Returns true on success. */
    suspend fun export(out: OutputStream, appVersion: String, timestamp: Long): Boolean =
        withContext(ioDispatcher) {
            try {
                val layout = homeLayoutRepository.layout().first().toDto()
                val fab = fabPositionStore.fabPositionFlow.first() // read once (x/y atomic)
                val prefs = NyxBackupPrefs(
                    iconStyle = preferences.iconStyle().first().name,
                    searchAutoLaunch = preferences.searchAutoLaunch().first(),
                    usageSortEnabled = preferences.usageSortEnabled().first(),
                    notificationDots = preferences.notificationDots().first(),
                    showAlarm = preferences.showAlarmFlow.first(),
                    showCalendarEvent = preferences.showCalendarEventFlow.first(),
                    scrimAlpha = displaySettings.wallpaperScrimAlphaStateFlow.first(),
                    backdrop = displaySettings.wallpaperBackdropFlow.first().name,
                    surfaceMode = displaySettings.wallpaperSurfaceModeFlow.first().name,
                    fabXFraction = fab.xFraction,
                    fabYFraction = fab.yFraction,
                )
                // Every layer backed by an existing internal file becomes a blob; the section
                // references it by hash (equal images are stored once). Other layers keep
                // their imageUri and have no blob.
                val state = wallpaperRepository.getWallpaperStateSync()
                val sources = ArrayList<BlobSource>()
                val layerSource = state.layers.map { layer ->
                    layer.imageUri?.let { localFileOrNull(it) }?.let { file ->
                        sources += BlobSource(IMAGE_MEDIA_TYPE) { file.inputStream() }
                        sources.size - 1
                    }
                }
                val drawerFolders = drawerFoldersRepository.folders().first().toDto()
                val hiddenApps = hiddenAppsRepository.hidden().first().map { it.toDto() }

                engine.export(
                    output = out,
                    producer = ContainerManifest.Producer(NyxBackupSchema.APP_ID, appVersion, timestamp),
                    schemaVersion = NyxBackupSchema.SCHEMA_VERSION,
                    blobs = sources,
                ) { hashes ->
                    val layers = state.layers.mapIndexed { index, layer ->
                        val backup = WallpaperLayerBackup.fromLayerState(layer)
                        val source = layerSource[index]
                        if (source != null) backup.copy(imageUri = null, imageFileName = hashes[source]) else backup.copy(imageFileName = null)
                    }
                    val backup = NyxBackup(
                        layout = layout,
                        prefs = prefs,
                        drawerFolders = drawerFolders,
                        hiddenApps = hiddenApps,
                        wallpaperLayers = layers,
                    )
                    mapOf(NyxBackupSchema.SECTION_BACKUP to ContainerManifest.Section(NyxBackupSchema.SECTION_VERSION, serializer.toJson(backup)))
                }
                true
            } catch (e: CancellationException) {
                throw e // cooperative cancellation must propagate, never become `false`
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Nyx backup export failed")
                false
            }
        }

    /**
     * Reads a backup container from [inp] and applies it per [options]. Nyx binds no
     * LegacyFormatReader, so the engine opens the stream exactly once and [inp] can be handed
     * over as is. Every outcome other than a readable Nyx backup is [ImportResult.InvalidData]
     * until 2b-3 gives them their own messages.
     */
    suspend fun import(inp: InputStream, options: NyxBackupOptions): ImportResult =
        withContext(ioDispatcher) {
            try {
                engine.readStaged(
                    open = { inp },
                    appId = NyxBackupSchema.APP_ID,
                    knownSections = NyxBackupSchema.KNOWN_SECTIONS,
                    kind = "import",
                ) { read, staging ->
                    if (read is BackupRead.Ok) apply(read, staging, options) else ImportResult.InvalidData
                }
            } catch (e: CancellationException) {
                throw e // cooperative cancellation must propagate, never become InvalidData
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Nyx backup import failed")
                ImportResult.InvalidData
            }
        }

    private suspend fun apply(read: BackupRead.Ok, staging: File, options: NyxBackupOptions): ImportResult {
        val section = read.manifest.sections[NyxBackupSchema.SECTION_BACKUP] ?: return ImportResult.InvalidData
        val backup = serializer.fromJson(section.data) ?: return ImportResult.InvalidData
        // Layer index → internal file copied from its blob. Every layer gets its OWN file, also
        // when two layers share one blob: removing a layer deletes its file right away, so a
        // file shared between layers would take the other layer's image with it.
        val extracted = HashMap<Int, String>()
        // Copied files the restored wallpaper state references, claimed BEFORE its save is
        // attempted. Every other copied file is deleted in `finally` (§Audit-3 A3-05).
        var adopted: Set<String> = emptySet()
        try {
            if (options.importWallpaper) extractLayerImages(backup.wallpaperLayers, read.blobs, staging, extracted)

            // Apply the home layout LAST. Cross-DataStore atomicity isn't available,
            // so if a settings/wallpaper write throws mid-import, doing layout last
            // leaves the existing (most valuable) home layout intact rather than
            // half-replaced — it is a single write and the least likely to fail.
            if (options.importSettings) applyPrefs(backup.prefs)
            if (options.importWallpaper) {
                restoredWallpaperState(backup.wallpaperLayers, extracted)?.let { state ->
                    // Claim the files BEFORE saving, never after: DataStore can commit the write
                    // and the call still end in a CancellationException (the import runs in the
                    // settings screen's lifecycleScope). Claiming afterwards would let `finally`
                    // delete files the persisted state already points at — a broken wallpaper.
                    // Failing safe keeps them; a save that truly didn't land leaves at most an
                    // orphan for the startup sweep.
                    adopted = state.layers.mapNotNullTo(HashSet()) { it.imageUri }
                    wallpaperRepository.saveWallpaperState(state)
                }
            }
            if (options.importLayout) {
                // Drawer folders are structural organisation too — restore them under the
                // layout toggle. A replace (return the restored value), like the home
                // layout's save; a null field leaves current folders intact.
                // Sanitize first (like the home layout's reconcile): a crafted/cross-device
                // blob can carry 0-1-member or duplicate-member folders, so repair them at
                // rest instead of relying on the read-time projection (§Audit-2 N10).
                backup.drawerFolders?.toDomain()?.let { restored ->
                    val repaired = DrawerFoldersTransition.sanitize(restored)
                    drawerFoldersRepository.update { repaired }
                }
                // Hidden apps are drawer organisation too — restore under the layout toggle
                // (replace; a null field leaves the current set intact).
                backup.hiddenApps?.let { dto ->
                    hiddenAppsRepository.update { dto.mapNotNull { it.toDomain() }.toSet() } // drop invalid keys (§Audit-2 N15)
                }
                backup.layout?.toDomain()?.let {
                    homeLayoutRepository.save(it)
                    // Structural-only cleanup of the restored layout NOW (not just on the
                    // next cold start): a cross-device backup can carry duplicate keys /
                    // 0-1-member folders / an over-capacity dock, which would otherwise render
                    // as duplicate/malformed tiles for the rest of this session. reconcile()
                    // is total (fail-closed) and no-prune, so it never drops uninstalled refs.
                    reconcileHomeLayout()
                }
            }
            return ImportResult.Success
        } finally {
            // A copied file no restored layer ended up referencing (a failed write, cancellation,
            // every layer dropped) must not sit orphaned in internal storage until the next
            // startup sweep. Drop it now (§Audit-3 A3-05).
            (extracted.values - adopted).forEach { fileManager.deleteFile(it) }
        }
    }

    /**
     * Copies each blob-backed layer's image into internal storage, one file per layer. A
     * blob that was rejected (hash/size) or is missing leaves its layer out of [extracted];
     * the layer is then dropped. The staged blob is claimed once and deleted after its copies.
     */
    private fun extractLayerImages(
        layers: List<WallpaperLayerBackup>,
        blobs: StagedBlobs,
        staging: File,
        extracted: MutableMap<Int, String>,
    ) {
        val claimed = HashMap<String, File>()
        try {
            layers.forEachIndexed { index, layer ->
                val hash = layer.imageFileName ?: return@forEachIndexed
                val file = claimed[hash]
                    ?: blobs.claim(hash, File(staging, "claimed-$hash"))?.also { claimed[hash] = it }
                    ?: return@forEachIndexed
                file.inputStream().use { fileManager.copyFromInputStream(it) }?.let { extracted[index] = it.toString() }
            }
        } finally {
            claimed.values.forEach { it.delete() }
        }
    }

    private suspend fun applyPrefs(prefs: NyxBackupPrefs?) {
        prefs ?: return
        prefs.iconStyle?.toEnumOrNull<IconStyle>()?.let { preferences.setIconStyle(it) }
        prefs.searchAutoLaunch?.let { preferences.setSearchAutoLaunch(it) }
        prefs.usageSortEnabled?.let { preferences.setUsageSortEnabled(it) }
        prefs.notificationDots?.let { preferences.setNotificationDots(it) }
        prefs.showAlarm?.let { preferences.setShowAlarm(it) }
        prefs.showCalendarEvent?.let { preferences.setShowCalendarEvent(it) }
        prefs.scrimAlpha?.let { displaySettings.setWallpaperScrimAlpha(it) }
        prefs.backdrop?.toEnumOrNull<WallpaperBackdrop>()?.let { displaySettings.setWallpaperBackdrop(it) }
        prefs.surfaceMode?.toEnumOrNull<WallpaperSurfaceMode>()?.let { displaySettings.setWallpaperSurfaceMode(it) }
        if (prefs.fabXFraction != null && prefs.fabYFraction != null) {
            fabPositionStore.saveFabPosition(FabPosition(prefs.fabXFraction, prefs.fabYFraction))
        }
    }

    /**
     * The wallpaper state to restore from [layers] + their [extracted] files (by layer index),
     * or null to keep the current wallpaper. The caller claims its file URIs and saves it.
     */
    private fun restoredWallpaperState(layers: List<WallpaperLayerBackup>, extracted: Map<Int, String>): WallpaperState? {
        // Replace semantics: a backup with no wallpaper clears the current one.
        if (layers.isEmpty()) return WallpaperState.NONE
        // Rebind each blob-backed layer to its freshly copied internal URI. A blob-backed
        // layer whose blob is missing/failed is DROPPED (its source file:// path is dead on
        // the restore target) — all-or-nothing per layer.
        val restored = layers.mapIndexedNotNull { index, layer ->
            val uri = if (layer.imageFileName != null) extracted[index] else layer.imageUri
            uri?.let {
                WallpaperLayerState(
                    id = layer.id ?: WallpaperLayerState.newId(),
                    imageUri = it,
                    scale = layer.scale,
                    translateX = layer.translateX,
                    translateY = layer.translateY,
                    captureSampleSize = layer.captureSampleSize,
                )
            }
        }
        // Only overwrite when at least one layer survived; if every blob failed
        // (corrupt backup) keep the current wallpaper rather than wiping it.
        return if (restored.isEmpty()) null else WallpaperState.multiLayer(restored)
    }

    /** file:// internal image → its [File], or null (skip non-file / missing). */
    private fun localFileOrNull(uriString: String): File? {
        val uri = Uri.parse(uriString)
        if (uri.scheme != "file") return null
        val file = uri.path?.let { File(it) }
        return file?.takeIf { it.exists() }
    }

    private inline fun <reified E : Enum<E>> String.toEnumOrNull(): E? =
        // no suspension point — enum parse of a backup string.
        runCatching { enumValueOf<E>(this) }.getOrNull()

    private companion object {
        const val IMAGE_MEDIA_TYPE = "image/*"
    }
}
