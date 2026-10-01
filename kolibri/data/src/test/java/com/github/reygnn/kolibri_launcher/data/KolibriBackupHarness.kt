package com.github.reygnn.kolibri_launcher.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.github.reygnn.kolibri_launcher.domain.model.FavoritesAlignment
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.domain.model.PreviewResult
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.kolibri_launcher.domain.model.SwipeSlot
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeCustomNamesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeHiddenAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSettingsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSwipeActionsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeWallpaperRepository
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.launcher.core.installedapps.FakeInstalledAppsRepository
import com.github.reygnn.launcher.core.wallpaper.WallpaperBackdrop
import com.github.reygnn.launcher.core.wallpaper.WallpaperRepository
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.github.reygnn.launcher.core.wallpaper.WallpaperSurfaceMode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.InputStream

/**
 * Kolibri's backup through its REAL export and import (`saveBackupToFile` /
 * `loadBackupFromFile` over the engine) on fresh fakes, for the shared contract subclasses
 * (2b-2c). The content resolver serves one backup document and real files for `file://`
 * images; the file manager copies imported images into real files under [dir]. Every store
 * write is logged by part. Needs Robolectric (`Uri`).
 */
internal class KolibriBackupHarness(private val dir: File) {

    val favorites = FakeFavoritesRepository()
    val order = FakeFavoritesOrderRepository()
    val hidden = FakeHiddenAppsRepository()
    val names = FakeCustomNamesRepository()
    val swipe = FakeSwipeActionsRepository()
    val settings = FakeSettingsRepository()
    val wallpaper = FakeWallpaperRepository()

    /** Parts written by the last [import], in order. */
    val log = ArrayList<String>()

    private val backupUri = Uri.parse("content://kolibri.test/backup")
    private var document = ByteArray(0)

    /** The size the platform reports for the document; null reports the real size. */
    var declaredSize: Long? = null
    private val written = ByteArrayOutputStream()

    private val descriptor = mockk<ParcelFileDescriptor>(relaxed = true) {
        every { statSize } answers { declaredSize ?: document.size.toLong() }
        every { fileDescriptor } returns FileDescriptor()
    }
    private val contentResolver = mockk<ContentResolver> {
        every { openOutputStream(backupUri) } answers { written.reset(); written }
        every { openInputStream(any()) } answers {
            val uri = firstArg<Uri>()
            if (uri == backupUri) ByteArrayInputStream(document) else File(uri.path!!).inputStream()
        }
        every { openFileDescriptor(eq(backupUri), any()) } returns descriptor
    }
    private val context = mockk<Context> { every { contentResolver } returns this@KolibriBackupHarness.contentResolver }

    private val fileManager = mockk<WallpaperFileManager>(relaxed = true) {
        every { copyFromInputStream(any()) } answers {
            val file = File(dir, "internal-${System.nanoTime()}.img")
            file.writeBytes(firstArg<InputStream>().readBytes())
            Uri.fromFile(file)
        }
        // Like the real one: an internal file comes back unchanged.
        coEvery { copyToInternal(any()) } answers { firstArg() }
    }

    private val repository = BackupRepositoryImplTestFactory.create(
        favoritesRepository = object : FavoritesRepository by favorites {
            override suspend fun saveFavoriteComponents(componentNames: List<String>) {
                log += FAVORITES
                favorites.saveFavoriteComponents(componentNames)
            }
        },
        favoritesOrderRepository = object : FavoritesOrderRepository by order {
            override suspend fun saveOrder(orderedComponentNames: List<String>): Boolean {
                log += ORDER
                return order.saveOrder(orderedComponentNames)
            }
        },
        hiddenAppsRepository = object : HiddenAppsRepository by hidden {
            override suspend fun updateComponentVisibilities(componentsToHide: Set<String>, componentsToShow: Set<String>) {
                log += HIDDEN
                hidden.updateComponentVisibilities(componentsToHide, componentsToShow)
            }
        },
        customNamesRepository = object : CustomNamesRepository by names {
            override suspend fun setCustomNamesInBatch(names: Map<String, String>): Boolean {
                log += NAMES
                return this@KolibriBackupHarness.names.setCustomNamesInBatch(names)
            }
        },
        // Cold-path gate: the import waits for a non-empty installed-apps emission. Only the
        // sentinel is installed, so every app the backup names is "not installed".
        installedAppsRepository = FakeInstalledAppsRepository().apply {
            installedApps = listOf(
                AppInfo(
                    originalName = "Sentinel",
                    displayName = "Sentinel",
                    packageName = "kolibri.test.sentinel",
                    className = "kolibri.test.sentinel.Main",
                ),
            )
        },
        swipeActionsRepository = object : SwipeActionsRepository by swipe {
            override suspend fun setSwipeAction(slot: SwipeSlot, componentName: String?) {
                log += SWIPE
                swipe.setSwipeAction(slot, componentName)
            }
        },
        settingsRepository = RecordingSettings(settings, log),
        wallpaperRepository = object : WallpaperRepository by wallpaper {
            override suspend fun saveWallpaperState(state: WallpaperState) {
                log += WALLPAPER
                wallpaper.saveWallpaperState(state)
            }
            override suspend fun clearWallpaper() {
                log += WALLPAPER
                wallpaper.clearWallpaper()
            }
        },
        wallpaperFileManager = fileManager,
        context = context,
    )

    suspend fun export(): ByteArray {
        check(repository.saveBackupToFile(backupUri.toString())) { "export failed" }
        return written.toByteArray()
    }

    suspend fun import(bytes: ByteArray, options: ImportOptions): ImportResult {
        log.clear()
        document = bytes
        return repository.loadBackupFromFile(backupUri.toString(), options)
    }

    suspend fun preview(bytes: ByteArray): PreviewResult {
        document = bytes
        return repository.previewBackup(backupUri.toString())
    }

    /** Names of the image files the import copied into [dir]. */
    fun storedImages(): Set<String> = dir.list { _, name -> name.startsWith("internal-") }.orEmpty().toSet()

    /** Every setter the import calls, logged by the import option that covers it. */
    private class RecordingSettings(
        private val inner: FakeSettingsRepository,
        private val log: MutableList<String>,
    ) : SettingsRepository by inner {
        override suspend fun setTextColor(color: Int) { log += THEME; inner.setTextColor(color) }
        override suspend fun setTextShadowEnabled(isEnabled: Boolean) { log += THEME; inner.setTextShadowEnabled(isEnabled) }
        override suspend fun setFontBold(isBold: Boolean) { log += THEME; inner.setFontBold(isBold) }
        override suspend fun setLayoutScale(scale: Float) { log += THEME; inner.setLayoutScale(scale) }
        override suspend fun setWallpaperScrimAlpha(alpha: Float) { log += THEME; inner.setWallpaperScrimAlpha(alpha) }
        override suspend fun setVerticalPadding(scale: Float) { log += THEME; inner.setVerticalPadding(scale) }
        override suspend fun setContentTopMarginScale(scale: Float) { log += THEME; inner.setContentTopMarginScale(scale) }
        override suspend fun setFavoritesAlignment(alignment: FavoritesAlignment) { log += THEME; inner.setFavoritesAlignment(alignment) }
        override suspend fun setWallpaperSurfaceMode(mode: WallpaperSurfaceMode) { log += THEME; inner.setWallpaperSurfaceMode(mode) }
        override suspend fun setWallpaperBackdrop(backdrop: WallpaperBackdrop) { log += THEME; inner.setWallpaperBackdrop(backdrop) }
        override suspend fun setShowCalendarEvent(isEnabled: Boolean) { log += TIME; inner.setShowCalendarEvent(isEnabled) }
        override suspend fun setShowAlarm(isEnabled: Boolean) { log += TIME; inner.setShowAlarm(isEnabled) }
        override suspend fun setAutoShowKeyboard(isEnabled: Boolean) { log += QOL; inner.setAutoShowKeyboard(isEnabled) }
        override suspend fun setAutoLaunchApp(isEnabled: Boolean) { log += QOL; inner.setAutoLaunchApp(isEnabled) }
        override suspend fun setSortOrder(sortOrder: SortOrder) { log += QOL; inner.setSortOrder(sortOrder) }
        override suspend fun setRotationLocked(isEnabled: Boolean) { log += POWER; inner.setRotationLocked(isEnabled) }
    }

    companion object {
        const val FAVORITES = "favorites"
        const val ORDER = "order"
        const val HIDDEN = "hiddenApps"
        const val NAMES = "customNames"
        const val SWIPE = "swipeActions"
        const val THEME = "theme"
        const val WALLPAPER = "wallpaper"
        const val TIME = "timeBasedEvents"
        const val QOL = "qualityOfLife"
        const val POWER = "powerUser"
    }
}
