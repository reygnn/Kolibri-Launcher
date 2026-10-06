package com.github.reygnn.nyx_launcher.data.home

import com.github.reygnn.launcher.feature.wallpaper.WallpaperImageStore
import com.github.reygnn.launcher.feature.wallpaper.WallpaperBackupBlobs
import com.github.reygnn.launcher.core.wallpaper.FabPositionRepository
import android.net.Uri
import com.github.reygnn.launcher.common.data.saf.SafDocuments
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.core.IoDispatcher
import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.launcher.core.coerceInSafe
import com.github.reygnn.launcher.core.wallpaper.WallpaperBackdrop
import com.github.reygnn.launcher.core.wallpaper.WallpaperDisplaySettings
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerBackup
import com.github.reygnn.launcher.core.wallpaper.WallpaperRepository
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.github.reygnn.launcher.core.wallpaper.WallpaperSurfaceMode
import com.github.reygnn.launcher.core.wallpaper.FabPosition
import com.github.reygnn.launcher.feature.backup.container.BlobSource
import com.github.reygnn.launcher.feature.backup.container.ContainerManifest
import com.github.reygnn.launcher.feature.backup.engine.BackupEngine
import com.github.reygnn.launcher.feature.backup.engine.BackupRead
import com.github.reygnn.launcher.feature.backup.engine.StagedBlobs
import com.github.reygnn.launcher.feature.backup.engine.UNKNOWN_SIZE
import com.github.reygnn.launcher.feature.backup.engine.writeOrDiscard
import com.github.reygnn.nyx_launcher.home.model.BackupPreview
import com.github.reygnn.nyx_launcher.home.model.PreviewResult
import com.github.reygnn.nyx_launcher.home.model.ImportOptions
import com.github.reygnn.nyx_launcher.home.model.IconStyle
import com.github.reygnn.nyx_launcher.home.model.ImportResult
import com.github.reygnn.nyx_launcher.home.repository.BackupRepository
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
import javax.inject.Named
import javax.inject.Singleton

/**
 * Nyx's backup over the shared [BackupEngine] (SPEC_NYX_REWRITE 2b-1): the E5a container
 * with one `nyx.backup` section ([NyxBackupSchema]) and the wallpaper images as blobs,
 * referenced by hash. The container itself — ZIP, caps, hashing, staging — is the engine's;
 * this class assembles the section from Nyx's repos and applies it.
 *
 * 2b-3a: the [BackupRepository] for the settings UI — it reaches the SAF document through the
 * shared [SafDocuments] (2b-4a), wraps the export in the shared `writeOrDiscard` (U3) and maps every engine outcome to its
 * own [ImportResult], like Kolibri. The stream-level [writeBackup] / [importFrom] stay
 * internal for tests and the backup contracts. Blob restore reuses the shared
 * [WallpaperFileManager.copyFromInputStream] (staged blob → internal file), so imported
 * layers land in internal storage exactly like a fresh pick. Named like Kolibri's since 2b-4b.
 */
@Singleton
class BackupRepositoryImpl @Inject constructor(
    /** The Android half of SAF documents, shared with Kolibri (2b-4a). */
    private val safDocuments: SafDocuments,
    private val homeLayoutRepository: HomeLayoutRepository,
    private val drawerFoldersRepository: DrawerFoldersRepository,
    private val hiddenAppsRepository: HiddenAppsRepository,
    private val preferences: PreferencesRepository,
    private val displaySettings: WallpaperDisplaySettings,
    private val wallpaperRepository: WallpaperRepository,
    private val fabPositionStore: FabPositionRepository,
    // The shared wallpaper parts of an import (3b-5): blob extraction, own file per layer and the
    // cleanup through the store; the store also copies in a layer that has no blob.
    private val wallpaperBlobs: WallpaperBackupBlobs,
    private val imageStore: WallpaperImageStore,
    private val serializer: NyxBackupSerializer,
    private val reconcileHomeLayout: ReconcileHomeLayoutUseCase,
    private val engine: BackupEngine,
    @param:Named("appVersionName") private val appVersionName: String,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : BackupRepository {

    override suspend fun saveBackupToFile(uriString: String): Boolean = withContext(ioDispatcher) {
        try {
            val uri = safDocuments.documentUri(uriString)
            // U3: a failure throws out of the write, never returns false, so writeOrDiscard
            // removes the half-written document (2b-3a).
            writeOrDiscard(open = { safDocuments.openOutput(uri) }, discard = { safDocuments.discard(uri) }) { out -> writeBackup(out) }
            true
        } catch (e: CancellationException) {
            throw e // cooperative cancellation must propagate, never become `false`
        } catch (e: Throwable) {
            // Catch kept (Expected error, four-category frame): assembling and writing the
            // container allocates per wallpaper blob; OOM extends Error → Throwable.
            TimberWrapper.silentError(e, "Nyx backup export failed")
            false
        }
    }

    override suspend fun loadBackupFromFile(uriString: String, options: ImportOptions): ImportResult =
        withContext(ioDispatcher) {
            if (options.importNothing) return@withContext ImportResult.Error("No import options selected")
            try {
                val uri = safDocuments.documentUri(uriString)
                importFrom(open = { safDocuments.openInput(uri) }, options = options, declaredSize = safDocuments.declaredSize(uri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Catch kept (Expected error, four-category frame): an invalid URI or a
                // provider failure before the engine runs. OOM extends Error → Throwable.
                TimberWrapper.silentError(e, "Error loading backup")
                ImportResult.Error("Failed to load backup: ${e.message}")
            }
        }

    override suspend fun previewBackup(uriString: String): PreviewResult = withContext(ioDispatcher) {
        try {
            val uri = safDocuments.documentUri(uriString)
            val fileSize = safDocuments.declaredSize(uri)
            engine.readStaged(
                open = { safDocuments.openInput(uri) },
                appId = NyxBackupSchema.APP_ID,
                knownSections = NyxBackupSchema.KNOWN_SECTIONS,
                kind = "preview",
                declaredSize = fileSize,
            ) { read, _ ->
                // A refusal names its reason, mapped exactly like the import's (2b-3b).
                refusalOf(read)?.let { PreviewResult.Refused(it) } ?: previewOf(read as BackupRead.Ok)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Catch kept (Expected error, four-category frame): reading the whole container
            // stages its blobs; OOM extends Error → Throwable. A failed preview is a refusal
            // with the reason, never a missing result.
            TimberWrapper.silentError(e, "Error previewing backup")
            PreviewResult.Refused(ImportResult.Error("Failed to load backup: ${e.message}"))
        }
    }

    /**
     * Writes a full backup container to [out] (left open for the caller). Throws on any
     * failure — the caller's writeOrDiscard needs the exception to remove the document.
     */
    internal suspend fun writeBackup(out: OutputStream, timestamp: Long = System.currentTimeMillis()) {
        withContext(ioDispatcher) {
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
                producer = ContainerManifest.Producer(NyxBackupSchema.APP_ID, appVersionName, timestamp),
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
        }
    }

    /**
     * Reads a backup container and applies it per [options]. Every engine outcome has its own
     * result, mapped exactly like Kolibri's (pinned by the backup contracts, 2b-3c); a refused
     * backup writes nothing.
     */
    internal suspend fun importFrom(
        open: () -> InputStream,
        options: ImportOptions,
        declaredSize: Long = UNKNOWN_SIZE,
    ): ImportResult = withContext(ioDispatcher) {
        try {
            engine.readStaged(
                open = open,
                appId = NyxBackupSchema.APP_ID,
                knownSections = NyxBackupSchema.KNOWN_SECTIONS,
                kind = "import",
                declaredSize = declaredSize,
            ) { read, staging ->
                refusalOf(read) ?: apply(read as BackupRead.Ok, staging, options)
            }
        } catch (e: CancellationException) {
            throw e // cooperative cancellation must propagate, never become an error result
        } catch (e: Throwable) {
            // Catch kept (Expected error, four-category frame): reading the container and
            // applying it allocate per blob and per store; OOM extends Error → Throwable.
            TimberWrapper.silentError(e, "Nyx backup import failed")
            ImportResult.Error("Failed to load backup: ${e.message}")
        }
    }

    /**
     * The one mapping of a refused engine outcome, shared by import and preview (2b-3b) so the
     * two can never disagree on the reason; mapped like Kolibri's (pinned by the backup
     * contracts). Null for [BackupRead.Ok] — not a refusal.
     */
    private fun refusalOf(read: BackupRead): ImportResult? = when (read) {
        is BackupRead.Ok -> null
        // Nyx binds no LegacyFormatReader (E5a): every pre-container archive lands here.
        BackupRead.OutdatedFormat -> ImportResult.OutdatedBackup
        is BackupRead.ForeignApp -> ImportResult.ForeignBackup(read.appId)
        is BackupRead.UnsupportedFormat -> ImportResult.UnsupportedVersion(read.formatVersion)
        is BackupRead.TooLarge -> ImportResult.Error("Backup file is too large")
        is BackupRead.Invalid -> ImportResult.InvalidFormat
    }

    /** The Nyx section of a readable container, or null when it is missing or can't be decoded. */
    private fun backupOf(read: BackupRead.Ok): NyxBackup? =
        read.manifest.sections[NyxBackupSchema.SECTION_BACKUP]?.let { serializer.fromJson(it.data) }

    /** A readable container whose section does not decode is refused like its import: invalid. */
    private fun previewOf(read: BackupRead.Ok): PreviewResult {
        val backup = backupOf(read) ?: return PreviewResult.Refused(ImportResult.InvalidFormat)
        return PreviewResult.Readable(previewOf(read.manifest.producer, backup))
    }

    private suspend fun apply(read: BackupRead.Ok, staging: File, options: ImportOptions): ImportResult {
        val backup = backupOf(read) ?: return ImportResult.InvalidFormat
        // Layer index → its own internal file (blob extracted, or a layer without blob copied in).
        val extracted = HashMap<Int, String>()
        // Every internal copy this import made — the cleanup candidates.
        val copies = HashSet<String>()
        // Copied files the restored wallpaper state references, claimed BEFORE its save is
        // attempted. Every other copy goes to the store's cleanup in `finally` (§Audit-3 A3-05).
        var adopted: Set<String> = emptySet()
        try {
            if (options.importWallpaper) restoreLayerImages(backup.wallpaperLayers, read.blobs, staging, extracted, copies)

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
            }
            if (options.importHiddenApps) {
                // Own switch since 2b-3, like Kolibri's. B13: the set is replaced; a backup without
                // the field (null) leaves the current set standing.
                backup.hiddenApps?.let { dto ->
                    hiddenAppsRepository.update { dto.mapNotNull { it.toDomain() }.toSet() } // drop invalid keys (§Audit-2 N15)
                }
            }
            if (options.importLayout) {
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
            // B9 (3b-5, as in Kolibri): every layer that carried an image — a blob or an imageUri —
            // and could not be restored is reported, not silently lost.
            val dropped = if (options.importWallpaper) {
                backup.wallpaperLayers.indices.count { index ->
                    val layer = backup.wallpaperLayers[index]
                    (layer.imageFileName != null || !layer.imageUri.isNullOrBlank()) && index !in extracted
                }
            } else {
                0
            }
            return ImportResult.Success(droppedWallpaperLayers = dropped)
        } finally {
            // A copy no restored layer ended up referencing (a failed write, cancellation, every
            // layer dropped) must not sit orphaned until the next startup sweep (§Audit-3 A3-05):
            // ONLY the unclaimed copies, through the store (3b-5, as Kolibri since 3a-7) — against
            // what is persisted, fail closed, under NonCancellable. Claimed copies may be missing
            // from the persisted state after a silently failed save and must never be passed in.
            wallpaperBlobs.release(copies - adopted)
        }
    }

    /**
     * Gives every restorable layer its own internal file (3b-5, the shared parts). First the file of
     * every layer is settled, then [WallpaperBackupBlobs.assignOwnFiles] runs ONCE over all layers,
     * so "one file per layer" (O2) holds for both sources, as in Kolibri (3b-5b):
     *  - a layer with a blob: [WallpaperBackupBlobs.extract] copies each referenced blob once;
     *  - a layer without a blob but with an `imageUri` (a file of another device, a content URI, or
     *    a path of this device): copied in through the store — `copyIn` hands back a source that
     *    already lies in the wallpaper directory UNCHANGED, so two such layers can share one file
     *    until assignOwnFiles gives the second its own copy; if the copy-in fails (dead path,
     *    unreadable) the layer is dropped and reported (B9), never stored as a missing reference.
     * A layer left out of [extracted] is dropped. Every file of this import is added to [copies] —
     * possibly an internal source URI from copyIn's early exit too, which is safe: the cleanup
     * (release) decides through the store against what is persisted, so a still-referenced file stays.
     */
    private suspend fun restoreLayerImages(
        layers: List<WallpaperLayerBackup>,
        blobs: StagedBlobs,
        staging: File,
        extracted: MutableMap<Int, String>,
        copies: MutableSet<String>,
    ) {
        val byHash = HashMap<String, String>()
        wallpaperBlobs.extract(layers.mapNotNull { it.imageFileName }, blobs::claim, staging, into = byHash)
        copies += byHash.values
        val perLayer: List<String?> = layers.map { layer ->
            val hash = layer.imageFileName
            val uri = layer.imageUri
            when {
                hash != null -> byHash[hash]
                !uri.isNullOrBlank() -> imageStore.copyIn(Uri.parse(uri))?.toString()?.also { copies += it }
                else -> null
            }
        }
        val own = wallpaperBlobs.assignOwnFiles(perLayer) { uri -> localFileOrNull(uri)?.inputStream() }
        own.forEachIndexed { index, uri ->
            if (uri != null) {
                extracted[index] = uri
                copies += uri
            }
        }
    }

    private fun previewOf(producer: ContainerManifest.Producer, backup: NyxBackup) = BackupPreview(
        appVersion = producer.appVersion,
        timestamp = producer.createdAtEpochMillis,
        homeItemCount = backup.layout?.let { it.items.size + it.dock.size },
        drawerFolderCount = backup.drawerFolders?.folders?.size ?: 0,
        hiddenAppCount = backup.hiddenApps?.size,
        hasSettings = backup.prefs != null,
        wallpaperLayerCount = backup.wallpaperLayers.size,
    )

    private suspend fun applyPrefs(prefs: NyxBackupPrefs?) {
        prefs ?: return
        prefs.iconStyle?.toEnumOrNull<IconStyle>()?.let { preferences.setIconStyle(it) }
        prefs.searchAutoLaunch?.let { preferences.setSearchAutoLaunch(it) }
        prefs.usageSortEnabled?.let { preferences.setUsageSortEnabled(it) }
        prefs.notificationDots?.let { preferences.setNotificationDots(it) }
        prefs.showAlarm?.let { preferences.setShowAlarm(it) }
        prefs.showCalendarEvent?.let { preferences.setShowCalendarEvent(it) }
        // B11: an imported value outside its valid range is clamped, never stored as is.
        prefs.scrimAlpha?.let {
            displaySettings.setWallpaperScrimAlpha(it.coerceInSafe(AppConstants.WALLPAPER_SCRIM_ALPHA_MIN, AppConstants.WALLPAPER_SCRIM_ALPHA_MAX))
        }
        prefs.backdrop?.toEnumOrNull<WallpaperBackdrop>()?.let { displaySettings.setWallpaperBackdrop(it) }
        prefs.surfaceMode?.toEnumOrNull<WallpaperSurfaceMode>()?.let { displaySettings.setWallpaperSurfaceMode(it) }
        if (prefs.fabXFraction != null && prefs.fabYFraction != null) {
            fabPositionStore.saveFabPosition(
                FabPosition(
                    prefs.fabXFraction.coerceInSafe(FAB_FRACTION_MIN, FAB_FRACTION_MAX),
                    prefs.fabYFraction.coerceInSafe(FAB_FRACTION_MIN, FAB_FRACTION_MAX),
                ),
            )
        }
    }

    /**
     * The wallpaper state to restore from [layers] + their [extracted] files (by layer index),
     * or null to keep the current wallpaper. The caller claims its file URIs and saves it.
     */
    private fun restoredWallpaperState(layers: List<WallpaperLayerBackup>, extracted: Map<Int, String>): WallpaperState? {
        // E2: a backup without wallpaper leaves the current one standing. Removing it is the
        // wallpaper switch of the import options, not an empty list in a backup.
        if (layers.isEmpty()) return null
        // Rebind each blob-backed layer to its freshly copied internal URI. A blob-backed
        // layer whose blob is missing/failed is DROPPED (its source file:// path is dead on
        // the restore target) — all-or-nothing per layer.
        // A layer without blob is only restored with its copied-in file (3b-5), never with its
        // raw imageUri. A layer that carried no image at all is dropped, as before.
        val restored = layers.mapIndexedNotNull { index, layer ->
            extracted[index]?.let { layer.toLayerState().copy(imageUri = it) }
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
        /** [FabPosition] stores the FAB centre as a fraction of the parent, both in `[0, 1]`. */
        const val FAB_FRACTION_MIN = 0f
        const val FAB_FRACTION_MAX = 1f
    }
}
