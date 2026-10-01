package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import com.github.reygnn.kolibri_launcher.domain.model.SwipeSlot
import com.github.reygnn.kolibri_launcher.domain.usecase.FactoryResetUseCase
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperRepositoryImpl
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.core.InstalledAppsRepository
import com.github.reygnn.launcher.core.InstalledAppsStateRepository
import com.github.reygnn.launcher.core.ResetCompletenessContract
import com.github.reygnn.launcher.core.timeinfo.TimeBasedEventsRepository
import com.github.reygnn.launcher.core.wallpaper.FabPosition
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Kolibri's run of the shared [ResetCompletenessContract] (2b-4c): the factory reset as the UI
 * runs it — `FactoryResetUseCase(includeUsageData = true)` over the real `ResetRepositoryImpl` —
 * on real file-backed `settings` and `usage` DataStores and the real wallpaper directory
 * (Robolectric). `ONBOARDING_COMPLETED` is the one key Kolibri keeps on purpose (`purge-exempt`
 * in `SettingsRepositoryImpl`). Installed-apps state and time-based events hold nothing in these
 * stores; their purges are stubbed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KolibriResetCompletenessTest : ResetCompletenessContract() {

    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val timberRule = TimberRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val wallpaperDir get() = File(context.filesDir, "wallpapers")

    private lateinit var settingsStore: DataStore<Preferences>
    private lateinit var usageStore: DataStore<Preferences>
    private lateinit var resetUseCase: FactoryResetUseCase

    /** Runs the DataStores; cancelled after each test so no store outlives it. */
    private var storeScope: CoroutineScope? = null

    @After
    fun closeStores() {
        storeScope?.cancel()
    }

    override val purgeExempt = mapOf(SETTINGS to setOf(AppConstants.PrefKeys.ONBOARDING_COMPLETED))

    override suspend fun seedEverything() {
        wallpaperDir.deleteRecursively()
        val scope = CoroutineScope(mainDispatcherRule.testDispatcher + SupervisorJob()).also { storeScope = it }
        settingsStore = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "settings.preferences_pb") }
        usageStore = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "usage.preferences_pb") }
        val fileManager = WallpaperFileManager(context)

        val favorites = FavoritesRepositoryImpl(settingsStore)
        val order = FavoritesOrderRepositoryImpl(settingsStore)
        val hidden = HiddenAppsRepositoryImpl(settingsStore)
        val names = CustomNamesRepositoryImpl(settingsStore)
        val swipe = SwipeActionsRepositoryImpl(settingsStore)
        val settings = SettingsRepositoryImpl(settingsStore)
        val fab = FabPositionRepositoryImpl(settingsStore)
        val wallpaper = WallpaperRepositoryImpl(settingsStore, fileManager, mainDispatcherRule.testDispatcher)
        val usage = AppUsageRepositoryImpl(usageStore, context)

        favorites.saveFavoriteComponents(listOf("com.a/com.a.Main", "com.b/com.b.Main"))
        order.saveOrder(listOf("com.b/com.b.Main", "com.a/com.a.Main"))
        hidden.updateComponentVisibilities(componentsToHide = setOf("com.h/com.h.Main"), componentsToShow = emptySet())
        names.setCustomNamesInBatch(mapOf("com.a" to "Alpha"))
        swipe.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, "com.a/com.a.Main")
        settings.setTextColor(0xFF123456.toInt())
        settings.setRotationLocked(true)
        settings.setOnboardingCompleted()
        fab.saveFabPosition(FabPosition(0.2f, 0.3f))
        val image = checkNotNull(fileManager.copyFromInputStream(ByteArrayInputStream(ByteArray(256) { it.toByte() })))
        wallpaper.saveWallpaperState(WallpaperState.single(image.toString()))
        usage.recordPackageLaunch("com.used")

        val resetRepository = ResetRepositoryImpl(
            favoritesRepository = favorites,
            hiddenAppsRepository = hidden,
            customNamesRepository = names,
            appUsageRepository = usage,
            favoritesOrderRepository = order,
            swipeActionsRepository = swipe,
            wallpaperRepository = wallpaper,
            fabPositionRepository = fab,
            settingsRepository = settings,
            installedAppsStateRepository = mockk<InstalledAppsStateRepository>(relaxed = true),
            timeBasedEventsRepository = mockk<TimeBasedEventsRepository>(relaxed = true),
        )
        resetUseCase = FactoryResetUseCase(resetRepository, mockk<InstalledAppsRepository>(relaxed = true))
    }

    override suspend fun factoryReset(): Boolean =
        resetUseCase(includeUsageData = true) == FactoryResetUseCase.Result.Success

    override suspend fun storedKeys(): Map<String, Set<String>> = mapOf(
        SETTINGS to settingsStore.data.first().asMap().keys.mapTo(HashSet()) { it.name },
        USAGE to usageStore.data.first().asMap().keys.mapTo(HashSet()) { it.name },
    )

    override fun wallpaperFiles(): Set<String> = wallpaperDir.list().orEmpty().toSet()

    private companion object {
        const val SETTINGS = "settings"
        const val USAGE = "usage"
    }
}
