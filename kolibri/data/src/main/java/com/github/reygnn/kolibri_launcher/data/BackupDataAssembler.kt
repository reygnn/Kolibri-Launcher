package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.coerceInSafe
import com.github.reygnn.kolibri_launcher.domain.model.BackupData
import com.github.reygnn.kolibri_launcher.domain.model.FavoritesAlignment
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.domain.model.LauncherSettings
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerBackup
import com.github.reygnn.launcher.core.wallpaper.WallpaperBackdrop
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.github.reygnn.launcher.core.wallpaper.WallpaperSurfaceMode
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.launcher.core.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.launcher.core.wallpaper.WallpaperRepository
import com.github.reygnn.kolibri_launcher.domain.model.SwipeSlot
import com.github.reygnn.launcher.core.AppLoad
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
        // Belt-and-suspenders symmetry with the restore side (which coerces these same
        // four floats before persisting, below): a non-finite value from a pre-coercion
        // build would otherwise serialize into the backup JSON, which cannot represent
        // NaN/Infinity (the writer would emit invalid JSON or throw). Clamp on export too.
        val layoutScale = settingsRepository.layoutScaleStateFlow.first()
            .coerceInSafe(AppConstants.LAYOUT_SCALE_MIN, AppConstants.LAYOUT_SCALE_MAX)
        val wallpaperScrimAlpha = settingsRepository.wallpaperScrimAlphaStateFlow.first()
            .coerceInSafe(AppConstants.WALLPAPER_SCRIM_ALPHA_MIN, AppConstants.WALLPAPER_SCRIM_ALPHA_MAX)
        val verticalPaddingScale = settingsRepository.verticalPaddingStateFlow.first()
            .coerceInSafe(AppConstants.VERTICAL_PADDING_SCALE_MIN, AppConstants.VERTICAL_PADDING_SCALE_MAX)
        val isFontBold = settingsRepository.isFontBoldStateFlow.first()
        val contentTopMarginScale = settingsRepository.contentTopMarginScaleFlow.first()
            .coerceInSafe(AppConstants.CONTENT_TOP_MARGIN_SCALE_MIN, AppConstants.CONTENT_TOP_MARGIN_SCALE_MAX)
        val favoritesAlignment = settingsRepository.favoritesAlignmentFlow.first()
        val wallpaperSurfaceMode = settingsRepository.wallpaperSurfaceModeFlow.first()
        val wallpaperBackdrop = settingsRepository.wallpaperBackdropFlow.first()

        val wallpaperState = wallpaperRepository.getWallpaperStateSync()

        val showCalendarEvent = settingsRepository.showCalendarEventFlow.first()
        val showAlarm = settingsRepository.showAlarmFlow.first()
        val autoShowKeyboard = settingsRepository.autoShowKeyboardFlow.first()
        val autoLaunchApp = settingsRepository.autoLaunchAppFlow.first()
        val sortOrder = settingsRepository.sortOrderFlow.first()
        val rotationLocked = settingsRepository.rotationLockedFlow.first()

        // ===== Wallpaper export =====
        // A wallpaper is always a layer list now (a single image = one layer), so
        // export the layers array directly. The flat fields are still written as a
        // mirror of layer 0 — a backward-compat convenience so a downgraded / older
        // importer can still restore the first layer from the flat keys.
        val wallpaperLayers: List<WallpaperLayerBackup> =
            wallpaperState.layers.map { WallpaperLayerBackup.fromLayerState(it) }
        val firstLayer = wallpaperState.layers.firstOrNull()
        val wallpaperUri: String? = firstLayer?.imageUri
        val wallpaperScale: Float? = if (firstLayer?.imageUri != null) firstLayer.scale else null
        val wallpaperTranslateX: Float? = if (firstLayer?.imageUri != null) firstLayer.translateX else null
        val wallpaperTranslateY: Float? = if (firstLayer?.imageUri != null) firstLayer.translateY else null

        val settings = LauncherSettings(
            favoriteComponents = favoriteComponents,
            favoritesOrder = favoritesOrder,
            hiddenComponents = hiddenComponents,
            customAppNames = customAppNames,
            swipeLeftApp = swipeLeftApp,
            swipeRightApp = swipeRightApp,
            textColor = textColor,
            layoutScale = layoutScale,
            wallpaperScrimAlpha = wallpaperScrimAlpha,
            verticalPaddingScale = verticalPaddingScale,
            isFontBold = isFontBold,
            contentTopMarginScale = contentTopMarginScale,
            favoritesAlignment = favoritesAlignment.name,
            wallpaperSurfaceMode = wallpaperSurfaceMode.name,
            wallpaperBackdrop = wallpaperBackdrop.name,
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
        // E1: the installed-apps snapshot only REPORTS missing apps; the import neither filters
        // on it nor fails without it (null = not known in time → nothing reported as missing).
        val installedComponents: Set<String>? = withTimeoutOrNull(AppConstants.INSTALLED_APPS_PRIME_TIMEOUT_MS) {
            installedAppsRepository.getInstalledApps()
                .filterIsInstance<AppLoad.Loaded>()
                .first { it.apps.isNotEmpty() }
                .apps
                .mapTo(HashSet()) { it.componentName }
        }
        fun isMissing(component: String) = installedComponents != null && component !in installedComponents

        var importedCount = 0
        var skippedCount = 0
        val missingApps = mutableSetOf<String>()

        // B14: every stored component goes through ComponentKey (short form "pkg/.Cls" →
        // "pkg/pkg.Cls"); malformed entries are the only ones dropped (counted as skipped).
        val favorites: Set<String> = backup.settings.favoriteComponents.normalizedComponents()
        val order: List<String> = backup.settings.favoritesOrder.mapNotNull(::normalizeComponent).distinct()

        // Validate BEFORE any write: a refused import must not leave a half-applied state.
        if (options.importFavorites) {
            val uniquePackages = favorites.mapTo(HashSet()) { it.substringBefore('/') }
            if (uniquePackages.size > AppConstants.MAX_FAVORITES_ON_HOME) {
                return ImportResult.LimitExceeded(
                    packageCount = uniquePackages.size,
                    limit = AppConstants.MAX_FAVORITES_ON_HOME,
                )
            }
        }

        if (options.importHiddenApps) {
            // B13: the backup's hidden set REPLACES the current one (snapshot semantics) — an
            // app hidden on this device but not in the backup becomes visible again.
            val hidden = backup.settings.hiddenComponents.normalizedComponents()
            val currentlyHidden = hiddenAppsRepository.hiddenAppsFlow.first()
            hiddenAppsRepository.updateComponentVisibilities(
                componentsToHide = hidden,
                componentsToShow = currentlyHidden - hidden,
            )
            skippedCount += backup.settings.hiddenComponents.count { normalizeComponent(it) == null }
            Timber.i("Imported hidden apps: ${hidden.size} (replaced ${currentlyHidden.size})")
        }

        if (options.importCustomNames) {
            // E1: names of apps that are not installed are kept — back when the app is.
            val names = backup.settings.customAppNames
            if (names.isNotEmpty()) {
                customNamesRepository.setCustomNamesInBatch(names)
                Timber.i("Imported custom names: ${names.size}")
            }
        }

        if (options.importSwipeActions) {
            // E1: a swipe slot keeps an app that is not installed (the lazy slot shows it greyed).
            val slots = listOf(
                SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT to backup.settings.swipeLeftApp,
                SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT to backup.settings.swipeRightApp,
            )
            for ((slot, raw) in slots) {
                val component = raw?.let(::normalizeComponent) ?: continue
                swipeActionsRepository.setSwipeAction(slot, component)
                if (isMissing(component)) missingApps += component
            }
        }

        var droppedWallpaperLayers = 0
        if (options.importThemeSettings) {
            backup.settings.textColor?.let { settingsRepository.setTextColor(it) }
            backup.settings.textShadowEnabled?.let { settingsRepository.setTextShadowEnabled(it) }
            backup.settings.isFontBold?.let { settingsRepository.setFontBold(it) }

            backup.settings.layoutScale?.let {
                settingsRepository.setLayoutScale(it.coerceInSafe(AppConstants.LAYOUT_SCALE_MIN, AppConstants.LAYOUT_SCALE_MAX))
            }
            backup.settings.wallpaperScrimAlpha?.let {
                settingsRepository.setWallpaperScrimAlpha(it.coerceInSafe(AppConstants.WALLPAPER_SCRIM_ALPHA_MIN, AppConstants.WALLPAPER_SCRIM_ALPHA_MAX))
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

            backup.settings.wallpaperBackdrop?.let { name ->
                try {
                    settingsRepository.setWallpaperBackdrop(WallpaperBackdrop.valueOf(name))
                } catch (e: IllegalArgumentException) {
                    // Same skip-on-unknown semantics as favoritesAlignment above.
                    Timber.w(e, "Unknown wallpaperBackdrop in backup: $name — keeping current value")
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
            // must not trigger a restore attempt against the current wallpaper.
            val backupHasWallpaper = backup.settings.wallpaperLayers.any { !it.imageUri.isNullOrBlank() } ||
                !backup.settings.wallpaperUri.isNullOrBlank()
            if (backupHasWallpaper) {
                // Preserve-on-failure (RC edge-case audit #1): NO pre-clear. The
                // restorer writes the new state via saveWallpaperState, which is a
                // full overwrite of the single KEY_LAYERS_JSON blob — so on SUCCESS
                // a pre-clear is redundant (single↔multi share that one key; no
                // stale-key risk), and on TOTAL failure (backup wallpaper entirely
                // unrestorable: a dead content:// on another device, or a missing
                // ZIP entry) the restorer writes nothing, so NOT clearing keeps the
                // user's CURRENT wallpaper instead of wiping it to NONE. Clearing
                // never deleted files anyway (only DataStore keys), so gcOrphans is
                // unaffected. A partial/failed restore still surfaces via
                // droppedWallpaperLayers.
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

        // U4: the most valuable stores are written LAST — if anything above failed, the
        // current favorites and their order are still intact.
        if (options.importFavorites) {
            favoritesRepository.saveFavoriteComponents(favorites.toList())
            importedCount += favorites.size
            skippedCount += backup.settings.favoriteComponents.count { normalizeComponent(it) == null }
            favorites.filterTo(missingApps, ::isMissing)
            Timber.i("Imported favorites: ${favorites.size}")
        }
        if (options.importOrder) {
            val currentFavorites = if (options.importFavorites) {
                favorites
            } else {
                favoritesRepository.favoriteComponentsFlow.first().toHashSet()
            }
            val validOrder = order.filter { it in currentFavorites }
            favoritesOrderRepository.saveOrder(validOrder)
            Timber.i("Imported order: ${validOrder.size} items")
        }

        return ImportResult.Success(
            importedCount = importedCount,
            skippedCount = skippedCount,
            missingApps = missingApps,
            droppedWallpaperLayers = droppedWallpaperLayers,
        )
    }


    /** B14: the one normalization for a stored component string; null when malformed. */
    private fun normalizeComponent(value: String): String? = ComponentKey.parse(value)?.flat

    private fun Collection<String>.normalizedComponents(): Set<String> =
        mapNotNullTo(LinkedHashSet(), ::normalizeComponent)

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
