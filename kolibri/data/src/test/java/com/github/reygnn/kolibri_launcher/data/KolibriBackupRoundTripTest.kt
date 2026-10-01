package com.github.reygnn.kolibri_launcher.data

import android.net.Uri
import com.github.reygnn.kolibri_launcher.data.KolibriBackupHarness.Companion.FAVORITES
import com.github.reygnn.kolibri_launcher.data.KolibriBackupHarness.Companion.HIDDEN
import com.github.reygnn.kolibri_launcher.data.KolibriBackupHarness.Companion.NAMES
import com.github.reygnn.kolibri_launcher.data.KolibriBackupHarness.Companion.ORDER
import com.github.reygnn.kolibri_launcher.data.KolibriBackupHarness.Companion.POWER
import com.github.reygnn.kolibri_launcher.data.KolibriBackupHarness.Companion.QOL
import com.github.reygnn.kolibri_launcher.data.KolibriBackupHarness.Companion.SWIPE
import com.github.reygnn.kolibri_launcher.data.KolibriBackupHarness.Companion.THEME
import com.github.reygnn.kolibri_launcher.data.KolibriBackupHarness.Companion.TIME
import com.github.reygnn.kolibri_launcher.data.KolibriBackupHarness.Companion.WALLPAPER
import com.github.reygnn.kolibri_launcher.domain.model.FavoritesAlignment
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.launcher.core.wallpaper.WallpaperBackdrop
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerState
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.github.reygnn.launcher.core.wallpaper.WallpaperSurfaceMode
import com.github.reygnn.launcher.feature.backup.contract.BackupRoundTripContract
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Kolibri's run of the shared [BackupRoundTripContract] (2b-2c) through `saveBackupToFile` /
 * `loadBackupFromFile` on fresh fakes ([KolibriBackupHarness]). Custom names and swipe slots
 * are not in [replacingParts]: importing them over an existing state merges or skips today,
 * which is open question O3, not agreed semantics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KolibriBackupRoundTripTest : BackupRoundTripContract<ImportOptions>() {

    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val timberRule = TimberRule()

    private lateinit var harness: KolibriBackupHarness

    override val allOptions = ImportOptions()
    override val singleOptions = mapOf(
        NONE.copy(importFavorites = true) to setOf(FAVORITES),
        NONE.copy(importOrder = true) to setOf(ORDER),
        NONE.copy(importHiddenApps = true) to setOf(HIDDEN),
        NONE.copy(importCustomNames = true) to setOf(NAMES),
        NONE.copy(importSwipeActions = true) to setOf(SWIPE),
        NONE.copy(importThemeSettings = true) to setOf(THEME),
        NONE.copy(importWallpaper = true) to setOf(WALLPAPER),
        NONE.copy(importTimeBasedEvents = true) to setOf(TIME),
        NONE.copy(importQualityOfLife = true) to setOf(QOL),
        NONE.copy(importPowerUserSettings = true) to setOf(POWER),
    )

    // Not ORDER: imported alone it is filtered to the current favorites. Not NAMES/SWIPE: O3.
    override val replacingParts = setOf(FAVORITES, HIDDEN, THEME, WALLPAPER, TIME, QOL, POWER)
    override val writeLog: List<String> get() = harness.log.toList()
    override val mostValuableParts = setOf(FAVORITES, ORDER)
    override val wallpaperPart = WALLPAPER

    override suspend fun seed(variant: Variant) {
        freshStores()
        val h = harness
        when (variant) {
            Variant.A -> {
                h.favorites.favorites = setOf(flat("com.a1"), flat("com.a2"))
                h.order.order = listOf(flat("com.a2"), flat("com.a1"))
                h.hidden.hiddenApps = setOf(flat("com.ah"))
                h.names.setCustomNamesInBatch(mapOf("com.a1" to "Alpha"))
                h.swipe.swipeLeftApp = flat("com.a1")
                h.swipe.swipeRightApp = flat("com.a2")
                with(h.settings) {
                    color = 0xFF112233.toInt(); shadow = false; layoutScale = 1.2f; wallpaperScrimAlpha = 0.3f
                    verticalPadding = 1.1f; isFontBold = false; contentTopMargin = 0.5f
                    favoritesAlignment = FavoritesAlignment.CENTER
                    wallpaperSurfaceMode = WallpaperSurfaceMode.DARK; wallpaperBackdrop = WallpaperBackdrop.BLACK
                    showCalendar = true; showAlarm = true
                    autoShowKeyboard = true; autoLaunchApp = true; setSortOrderForTest(SortOrder.ALPHABETICAL)
                    rotationLocked = true
                }
                h.wallpaper.currentState = WallpaperState.multiLayer(
                    listOf(layer("la0", image(1), scale = 1.5f), layer("la1", image(2), translateX = 7f)),
                )
            }
            Variant.B -> {
                h.favorites.favorites = setOf(flat("com.b1"))
                h.order.order = listOf(flat("com.b1"))
                h.hidden.hiddenApps = setOf(flat("com.bh"))
                h.names.setCustomNamesInBatch(mapOf("com.b1" to "Beta"))
                h.swipe.swipeLeftApp = flat("com.b2")
                h.swipe.swipeRightApp = flat("com.b3")
                with(h.settings) {
                    color = 0xFF445566.toInt(); shadow = true; layoutScale = 0.9f; wallpaperScrimAlpha = 0.1f
                    verticalPadding = 0.8f; isFontBold = true; contentTopMargin = 0.2f
                    favoritesAlignment = FavoritesAlignment.END
                    wallpaperSurfaceMode = WallpaperSurfaceMode.LIGHT; wallpaperBackdrop = WallpaperBackdrop.SYSTEM_WALLPAPER
                    showCalendar = false; showAlarm = true
                    autoShowKeyboard = false; autoLaunchApp = true; setSortOrderForTest(SortOrder.TIME_WEIGHTED_USAGE)
                    rotationLocked = false
                }
                h.wallpaper.currentState = WallpaperState.multiLayer(listOf(layer("lb0", image(3), translateY = -4f)))
            }
        }
    }

    override suspend fun freshStores() {
        harness = KolibriBackupHarness(tmp.root)
    }

    override suspend fun snapshot(): Map<String, Any?> {
        val h = harness
        val s = h.settings
        return mapOf(
            FAVORITES to h.favorites.favorites,
            ORDER to h.order.order,
            HIDDEN to h.hidden.hiddenApps,
            NAMES to h.names.getAllCustomNames(),
            SWIPE to listOf(h.swipe.swipeLeftApp, h.swipe.swipeRightApp),
            THEME to listOf(
                s.color, s.shadow, s.layoutScale, s.wallpaperScrimAlpha, s.verticalPadding, s.isFontBold,
                s.contentTopMargin, s.favoritesAlignment, s.wallpaperSurfaceMode, s.wallpaperBackdrop,
            ),
            // Id, transform and image bytes — never the URI, which the import rebinds.
            WALLPAPER to h.wallpaper.currentState.layers.map { layer ->
                listOf(
                    layer.id, layer.scale, layer.translateX, layer.translateY, layer.captureSampleSize,
                    layer.imageUri?.let { File(Uri.parse(it).path!!).readBytes().toList() },
                )
            },
            TIME to listOf(s.showCalendar, s.showAlarm),
            QOL to listOf(s.autoShowKeyboard, s.autoLaunchApp, s.currentSortOrder),
            POWER to s.rotationLocked,
        )
    }

    override suspend fun export(withWallpaper: Boolean): ByteArray {
        if (!withWallpaper) harness.wallpaper.currentState = WallpaperState.NONE
        return harness.export()
    }

    override suspend fun import(bytes: ByteArray, options: ImportOptions): Boolean =
        harness.import(bytes, options) is ImportResult.Success

    private fun flat(pkg: String) = "$pkg/$pkg.Main"

    private fun image(seed: Int) = ByteArray(256) { (it * 13 + seed).toByte() }

    private fun layer(id: String, bytes: ByteArray, scale: Float = 1f, translateX: Float = 0f, translateY: Float = 0f): WallpaperLayerState {
        val file = File(tmp.root, "seed-$id-${System.nanoTime()}.img").apply { writeBytes(bytes) }
        return WallpaperLayerState(id = id, imageUri = Uri.fromFile(file).toString(), scale = scale, translateX = translateX, translateY = translateY)
    }

    private companion object {
        val NONE = ImportOptions(
            importFavorites = false, importOrder = false, importHiddenApps = false, importCustomNames = false,
            importSwipeActions = false, importThemeSettings = false, importWallpaper = false,
            importTimeBasedEvents = false, importQualityOfLife = false, importPowerUserSettings = false,
        )
    }
}
