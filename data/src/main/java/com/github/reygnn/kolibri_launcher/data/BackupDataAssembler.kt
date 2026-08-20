package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.coerceInSafe
import com.github.reygnn.kolibri_launcher.domain.model.BackupData
import com.github.reygnn.kolibri_launcher.domain.model.FavoritesAlignment
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.domain.model.LauncherSettings
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperLayerBackup
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperSurfaceMode
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.WallpaperRepository
import com.github.reygnn.kolibri_launcher.domain.model.SwipeSlot
import com.github.reygnn.kolibri_launcher.domain.model.AppLoad
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Repository-composition layer for the backup pipeline.
 *
 * One responsibility: gather state from all 8 backup-relevant
 * repositories into a [BackupData] (export side), and apply a [BackupData]
 * back across the same 8 repositories (import side, the phased
 * `performImport`).
 *
 * NO dependencies on:
 *  - JSON serialization (that's [BackupSerializer])
 *  - `Context`, `Uri`, `ContentResolver`, file streams (those live in
 *    [BackupRepositoryImpl]; wallpaper file restoration is delegated via
 *    the [WallpaperRestorer] callback)
 *  - The ZIP file format
 *
 * Repository writes during import:
 *  - Phases 1–7 + 8–10 happen here, directly against the injected
 *    repositories.
 *  - Phase 7b (wallpaper) is a SEPARATE, independently-selectable restore
 *    item (`options.importWallpaper`), not bundled with the theme scalars.
 *    It splits responsibility: this class clears [WallpaperRepository]
 *    state, then hands off to the [WallpaperRestorer] which does the
 *    file-system work and ultimately calls back into
 *    [saveWallpaperStateForRestore] so the [WallpaperRepository] dependency
 *    stays in this class only.
 */
@Singleton
class BackupDataAssembler @Inject constructor(
    private val favoritesRepository: FavoritesRepository,
    private val favoritesOrderRepository: FavoritesOrderRepository,
    private val hiddenAppsRepository: HiddenAppsRepository,
    private val customNamesRepository: CustomNamesRepository,
    private val installedAppsRepository: InstalledAppsRepository,
    private val swipeActionsRepository: SwipeActionsRepository,
    private val settingsRepository: SettingsRepository,
    private val wallpaperRepository: WallpaperRepository,
    @param:Named("appVersionName") private val appVersionName: String,
) {

    // ===========================================
    // EXPORT SIDE — read repos → BackupData
    // ===========================================

    /**
     * Reads every backup-relevant repository and assembles the result
     * into a [BackupData]. Used both for JSON-only export and as the
     * data source for the ZIP exporter (which adds embedded wallpaper
     * images on top of this).
     */
    suspend fun buildBackupData(): BackupData {
        // Favorites + FavoritesOrder are the only hot-shared flows here (shareIn,
        // replay=1, WhileSubscribed); Home never keeps them warm while the backup
        // screen is open, so a .first() replay could be stale. Read them
        // authoritatively (fresh store snapshot). Hidden + the settings flows are
        // COLD flows whose .first() is already a fresh disk read — left unchanged.
        val favoriteComponents = favoritesRepository.getFavoriteComponentsSnapshot()
        val favoritesOrder = favoritesOrderRepository.getFavoriteComponentsOrderSnapshot()
        val hiddenComponents = hiddenAppsRepository.hiddenAppsFlow.first()
        val customAppNames = customNamesRepository.getAllCustomNames()
        // Authoritative fresh reads from the store (getSwipeActionComponent):
        // the backup must capture the swipe assignments exactly as currently
        // stored, independent of any UI cache.
        val swipeLeftApp = swipeActionsRepository.getSwipeActionComponent(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT)
        val swipeRightApp = swipeActionsRepository.getSwipeActionComponent(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT)

        val textColor = settingsRepository.textColorFlow.first()
        val textShadowEnabled = settingsRepository.textShadowEnabledFlow.first()
        val chipBackgroundColor = settingsRepository.chipBackgroundColorFlow.first()
        val layoutScale = settingsRepository.layoutScaleStateFlow.first()
        val verticalPaddingScale = settingsRepository.verticalPaddingStateFlow.first()
        val isFontBold = settingsRepository.isFontBoldStateFlow.first()
        val contentTopMarginScale = settingsRepository.contentTopMarginScaleFlow.first()
        val favoritesAlignment = settingsRepository.favoritesAlignmentFlow.first()
        val wallpaperSurfaceMode = settingsRepository.wallpaperSurfaceModeFlow.first()

        val wallpaperState = wallpaperRepository.getWallpaperStateSync()

        val showCalendarEvent = settingsRepository.showCalendarEventFlow.first()
        val showAlarm = settingsRepository.showAlarmFlow.first()
        val autoShowKeyboard = settingsRepository.autoShowKeyboardFlow.first()
        val autoLaunchApp = settingsRepository.autoLaunchAppFlow.first()
        val doubleTapClipboardEnabled = settingsRepository.doubleTapClipboardEnabledFlow.first()
        val sortOrder = settingsRepository.sortOrderFlow.first()
        val rotationLocked = settingsRepository.rotationLockedFlow.first()

        // ===== Wallpaper: multi-layer export =====
        val wallpaperUri: String?
        val wallpaperScale: Float?
        val wallpaperTranslateX: Float?
        val wallpaperTranslateY: Float?
        val wallpaperLayers: List<WallpaperLayerBackup>

        if (wallpaperState.isMultiLayer) {
            wallpaperLayers = wallpaperState.layers.map { WallpaperLayerBackup.fromLayerState(it) }
            val firstLayer = wallpaperState.layers.firstOrNull()
            wallpaperUri = firstLayer?.imageUri?.toString()
            wallpaperScale = if (firstLayer?.imageUri != null) firstLayer.scale else null
            wallpaperTranslateX = if (firstLayer?.imageUri != null) firstLayer.translateX else null
            wallpaperTranslateY = if (firstLayer?.imageUri != null) firstLayer.translateY else null
        } else {
            wallpaperLayers = emptyList()
            wallpaperUri = wallpaperState.imageUri?.toString()
            wallpaperScale = if (wallpaperState.imageUri != null) wallpaperState.scale else null
            wallpaperTranslateX = if (wallpaperState.imageUri != null) wallpaperState.translateX else null
            wallpaperTranslateY = if (wallpaperState.imageUri != null) wallpaperState.translateY else null
        }

        val settings = LauncherSettings(
            favoriteComponents = favoriteComponents,
            favoritesOrder = favoritesOrder,
            hiddenComponents = hiddenComponents,
            customAppNames = customAppNames,
            swipeLeftApp = swipeLeftApp,
            swipeRightApp = swipeRightApp,
            textColor = textColor,
            layoutScale = layoutScale,
            verticalPaddingScale = verticalPaddingScale,
            isFontBold = isFontBold,
            contentTopMarginScale = contentTopMarginScale,
            favoritesAlignment = favoritesAlignment.name,
            wallpaperSurfaceMode = wallpaperSurfaceMode.name,
            chipBackgroundColor = chipBackgroundColor,
            textShadowEnabled = textShadowEnabled,
            wallpaperUri = wallpaperUri,
            wallpaperScale = wallpaperScale,
            wallpaperTranslateX = wallpaperTranslateX,
            wallpaperTranslateY = wallpaperTranslateY,
            wallpaperLayers = wallpaperLayers,
            showCalendarEvent = showCalendarEvent,
            showAlarm = showAlarm,
            autoShowKeyboard = autoShowKeyboard,
            autoLaunchApp = autoLaunchApp,
            doubleTapClipboardEnabled = doubleTapClipboardEnabled,
            sortOrder = sortOrder.name,
            rotationLocked = rotationLocked,
        )

        return BackupData(
            version = AppConstants.BACKUP_VERSION,
            timestamp = System.currentTimeMillis(),
            appVersion = appVersionName,
            settings = settings,
        )
    }

    // ===========================================
    // IMPORT SIDE — BackupData → repos (10 phases)
    // ===========================================

    /**
     * Applies a [BackupData] back across the 8 repositories, gated by
     * [options]. Wallpaper file restoration is delegated to
     * [wallpaperRestorer] (phase 7).
     */
    internal suspend fun performImport(
        backup: BackupData,
        options: ImportOptions,
        wallpaperRestorer: WallpaperRestorer,
    ): ImportResult {
        // InstalledAppsRepository.getInstalledApps() is a StateFlow shared
        // via WhileSubscribed(FLOW_SHARING_TIMEOUT_MS) with
        // initialValue = AppLoad.Loaded(emptyList()). A bare .first() from a cold
        // subscriber sees the initial (empty) value immediately and unsubscribes
        // before the upstream PackageManager query runs — every restored component
        // would then fail the "is installed?" filter below and the import would
        // silently drop everything. Wait for the first successful, non-empty load
        // (INSTALLED_APPS_LOAD_SPEC: a Failed emission is NOT a populated list, so
        // it must not satisfy the priming), bounded by a timeout.
        val installedApps = withTimeoutOrNull(AppConstants.INSTALLED_APPS_PRIME_TIMEOUT_MS) {
            installedAppsRepository.getInstalledApps()
                .filterIsInstance<AppLoad.Loaded>()
                .first { it.apps.isNotEmpty() }
                .apps
        } ?: error("Timed out waiting for InstalledAppsRepository to populate during backup import")
        val installedComponents = installedApps.map { it.componentName }.toSet()

        val installedComponentsSet = installedComponents.toHashSet()
        val installedPackagesSet = installedComponents
            .mapTo(HashSet()) { it.split('/')[0] }

        var importedCount = 0
        var skippedCount = 0
        val missingApps = mutableSetOf<String>()

        // ===== PHASE 1: Import Favorites =====
        // Holds the exact favorites set Phase 1 writes, so Phase 2 can filter
        // the order against it WITHOUT re-reading favoriteComponentsFlow. In
        // production that flow is a WhileSubscribed(replay = 1) hot share
        // (FavoritesRepositoryImpl): while no UI collector is subscribed — and
        // during an import from the Settings/Backup screen the Home fragment is
        // stopped, so after FLOW_SHARING_TIMEOUT_MS nobody is — the retained
        // replay value lags this Phase-1 edit(). A bare .first() would then see
        // the pre-import favorites and Phase 2 would drop the freshly imported
        // order (worst case: empty replay on a fresh restore → saveOrder(empty),
        // silently discarding the whole imported order). AUDIT-9 #2 — same trap
        // the InstalledApps prime above guards against.
        var importedFavorites: Set<String>? = null
        if (options.importFavorites) {
            val validFavorites = backup.settings.favoriteComponents
                .filterTo(HashSet()) { it in installedComponentsSet }

            skippedCount += backup.settings.favoriteComponents.size - validFavorites.size
            missingApps.addAll(backup.settings.favoriteComponents - installedComponentsSet)

            val uniquePackages = validFavorites
                .mapTo(HashSet()) { it.split('/')[0] }

            if (uniquePackages.size > AppConstants.MAX_FAVORITES_ON_HOME) {
                return ImportResult.LimitExceeded(
                    packageCount = uniquePackages.size,
                    limit = AppConstants.MAX_FAVORITES_ON_HOME,
                )
            }

            favoritesRepository.saveFavoriteComponents(validFavorites.toList())
            importedFavorites = validFavorites
            importedCount += validFavorites.size
            Timber.i("Imported favorites: $importedCount (skipped: ${backup.settings.favoriteComponents.size - validFavorites.size})")
        }

        // ===== PHASE 2: Import Order =====
        if (options.importOrder) {
            // Prefer the set Phase 1 just wrote; otherwise read the current
            // favorites from the flow. Since the hot-share teardown
            // (DATASTORE_READ_SPEC Belang A) favoriteComponentsFlow is cold, so
            // .first() is a fresh read of the store — no replay cache to lag.
            val currentFavoritesSet = importedFavorites
                ?: favoritesRepository.favoriteComponentsFlow.first().toHashSet()

            val validOrder = backup.settings.favoritesOrder
                .filter { it in currentFavoritesSet && it in installedComponentsSet }

            favoritesOrderRepository.saveOrder(validOrder)
            Timber.i("Imported order: ${validOrder.size} items")
        }

        // ===== PHASE 3: Import Hidden Apps =====
        if (options.importHiddenApps) {
            val validHidden = backup.settings.hiddenComponents
                .filterTo(HashSet()) { it in installedComponentsSet }

            val skippedHidden = backup.settings.hiddenComponents.size - validHidden.size
            hiddenAppsRepository.updateComponentVisibilities(
                componentsToHide = validHidden,
                componentsToShow = emptySet(),
            )
            Timber.i("Imported hidden apps: ${validHidden.size} (skipped $skippedHidden)")
        }

        // ===== PHASE 4: Import Custom App Names =====
        if (options.importCustomNames) {
            val validNames = backup.settings.customAppNames
                .filterKeys { it in installedPackagesSet }

            if (validNames.isNotEmpty()) {
                customNamesRepository.setCustomNamesInBatch(validNames)
                Timber.i("Imported custom names: ${validNames.size}")
            }
        }

        // ===== PHASE 5: Import Swipe Actions =====
        if (options.importSwipeActions) {
            var swipeImportedCount = 0
            val leftApp = backup.settings.swipeLeftApp
            if (leftApp != null) {
                if (leftApp in installedComponentsSet) {
                    swipeActionsRepository.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, leftApp)
                    swipeImportedCount++
                } else {
                    swipeActionsRepository.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, null)
                    missingApps.add(leftApp)
                }
            }
            val rightApp = backup.settings.swipeRightApp
            if (rightApp != null) {
                if (rightApp in installedComponentsSet) {
                    swipeActionsRepository.setSwipeAction(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT, rightApp)
                    swipeImportedCount++
                } else {
                    swipeActionsRepository.setSwipeAction(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT, null)
                    missingApps.add(rightApp)
                }
            }
            if (swipeImportedCount > 0) Timber.i("Imported swipe actions")
        }

        // ===== PHASE 7: Import Theme Settings =====
        var droppedWallpaperLayers = 0
        if (options.importThemeSettings) {
            backup.settings.textColor?.let { settingsRepository.setTextColor(it) }
            backup.settings.chipBackgroundColor?.let { settingsRepository.setChipBackgroundColor(it) }
            backup.settings.textShadowEnabled?.let { settingsRepository.setTextShadowEnabled(it) }
            backup.settings.isFontBold?.let { settingsRepository.setFontBold(it) }

            backup.settings.layoutScale?.let {
                settingsRepository.setLayoutScale(it.coerceInSafe(AppConstants.LAYOUT_SCALE_MIN, AppConstants.LAYOUT_SCALE_MAX))
            }
            backup.settings.verticalPaddingScale?.let {
                settingsRepository.setVerticalPadding(it.coerceInSafe(AppConstants.VERTICAL_PADDING_SCALE_MIN, AppConstants.VERTICAL_PADDING_SCALE_MAX))
            }
            backup.settings.contentTopMarginScale?.let {
                settingsRepository.setContentTopMarginScale(it.coerceInSafe(AppConstants.CONTENT_TOP_MARGIN_SCALE_MIN, AppConstants.CONTENT_TOP_MARGIN_SCALE_MAX))
            }

            backup.settings.favoritesAlignment?.let { name ->
                try {
                    settingsRepository.setFavoritesAlignment(FavoritesAlignment.valueOf(name))
                } catch (e: IllegalArgumentException) {
                    // Skip-on-invalid: keep the user's current alignment when
                    // the backup carries an unknown enum name (hand-edited or
                    // produced by a newer build than the importer knows). The
                    // SettingsRepositoryImpl read path has the same fallback,
                    // but persisting the unknown name would leave DataStore in
                    // a state only the read-side fallback rescues.
                    Timber.w(e, "Unknown favoritesAlignment in backup: $name — keeping current value")
                }
            }

            backup.settings.wallpaperSurfaceMode?.let { name ->
                try {
                    settingsRepository.setWallpaperSurfaceMode(WallpaperSurfaceMode.valueOf(name))
                } catch (e: IllegalArgumentException) {
                    // Same skip-on-unknown semantics as favoritesAlignment above.
                    Timber.w(e, "Unknown wallpaperSurfaceMode in backup: $name — keeping current value")
                }
            }

        }

        // ===== PHASE 7b: Import Wallpaper (independent restore item) =====
        // Lifted out of theme settings so the wallpaper is a SEPARATE restore
        // choice: a theme restore never touches the active wallpaper and vice
        // versa. Like every theme field above, skip on "nothing to restore":
        // importing a wallpaper-less backup — OR one where the user DESELECTED
        // the wallpaper (importWallpaper == false) — must leave the user's
        // current wallpaper untouched (dropped stays 0 = no warning), never
        // silently wipe it.
        if (options.importWallpaper) {
            // "Has a wallpaper" means an actual image to restore — a layer with
            // a blank imageUri carries no image, so an all-image-less layer list
            // must not trigger the clear (which would silently wipe the user's
            // current wallpaper with dropped=0, i.e. no warning).
            val backupHasWallpaper = backup.settings.wallpaperLayers.any { !it.imageUri.isNullOrBlank() } ||
                !backup.settings.wallpaperUri.isNullOrBlank()
            if (backupHasWallpaper) {
                // Reset only the DataStore state — files are overwritten by the
                // restorer below via copyToInternal(). Orphaned old files get
                // reaped because only files referenced by the new state are kept.
                wallpaperRepository.clearWallpaper()
                droppedWallpaperLayers = wallpaperRestorer.restoreFromBackup(backup.settings)
            }
        }

        // ===== PHASE 8: Import Time-Based Events =====
        if (options.importTimeBasedEvents) {
            backup.settings.showCalendarEvent?.let { settingsRepository.setShowCalendarEvent(it) }
            backup.settings.showAlarm?.let { settingsRepository.setShowAlarm(it) }
        }

        // ===== PHASE 9: Import Quality-of-Life Settings =====
        if (options.importQualityOfLife) {
            backup.settings.autoShowKeyboard?.let { settingsRepository.setAutoShowKeyboard(it) }
            backup.settings.autoLaunchApp?.let { settingsRepository.setAutoLaunchApp(it) }
            backup.settings.doubleTapClipboardEnabled?.let {
                settingsRepository.setDoubleTapClipboard(it)
            }

            backup.settings.sortOrder?.let { name ->
                try {
                    settingsRepository.setSortOrder(SortOrder.valueOf(name))
                } catch (e: IllegalArgumentException) {
                    // Same skip-on-unknown semantics as favoritesAlignment in Phase 7.
                    Timber.w(e, "Unknown sortOrder in backup: $name — keeping current value")
                }
            }
        }

        // ===== PHASE 10: Import Power-User Settings =====
        if (options.importPowerUserSettings) {
            backup.settings.rotationLocked?.let { settingsRepository.setRotationLocked(it) }
        }

        return ImportResult.Success(
            importedCount = importedCount,
            skippedCount = skippedCount,
            missingApps = missingApps,
            droppedWallpaperLayers = droppedWallpaperLayers,
        )
    }

    /**
     * Single-method bridge that lets the [WallpaperRestorer] write a
     * built [WallpaperState] back through the same [WallpaperRepository]
     * the assembler holds — keeps the dependency in one place rather
     * than splitting it across the assembler and the restorer.
     */
    internal suspend fun saveWallpaperStateForRestore(state: WallpaperState) {
        wallpaperRepository.saveWallpaperState(state)
    }
}
