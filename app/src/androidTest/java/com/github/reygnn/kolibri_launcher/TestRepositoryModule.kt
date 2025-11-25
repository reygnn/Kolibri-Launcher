package com.github.reygnn.kolibri_launcher

import android.content.pm.ShortcutInfo
import androidx.lifecycle.MutableLiveData
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.di.RepositoryModule
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.BackupPreview
import com.github.reygnn.kolibri_launcher.domain.model.FavoriteAppsResult
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEvent
import com.github.reygnn.kolibri_launcher.domain.repository.AppUsageRepository
import com.github.reygnn.kolibri_launcher.domain.repository.BackupRepository
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.GetDrawerAppsUseCaseRepository
import com.github.reygnn.kolibri_launcher.domain.repository.GetFavoriteAppsUseCaseRepository
import com.github.reygnn.kolibri_launcher.domain.repository.GetOnboardingAppsUseCaseRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.domain.repository.Purgeable
import com.github.reygnn.kolibri_launcher.domain.repository.ResetRepository
import com.github.reygnn.kolibri_launcher.domain.repository.ScreenLockRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.ShortcutRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.TimeBasedEventsRepository
import com.github.reygnn.kolibri_launcher.ui.base.UiState
import com.github.reygnn.kolibri_launcher.ui.swipeactions.SwipeSlot
import com.github.reygnn.kolibri_launcher.ui.util.AppUpdateSignal
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

// =================================================================================
// --- TEST DATA SOURCE: Die zentrale Wahrheit für unsere Tests ---
// =================================================================================

/**
 * Dient als zentrale "In-Memory-Datenbank" für den Testzyklus.
 * Beide Fake-Repositories greifen auf diese eine Datenquelle zu,
 * um Konsistenz zu gewährleisten.
 */
object TestDataSource {
    // Die unveränderliche Liste der "installierten" Apps
    private val rawApps = listOf(
        AppInfo("Alpha Browser", "Alpha Browser", "com.alpha.browser", "com.alpha.browser.Main"),
        AppInfo(
            "Beta Calculator",
            "Beta Calculator",
            "com.beta.calculator",
            "com.beta.calculator.Main"
        ),
        AppInfo("Zeta Clock", "Zeta Clock", "com.zeta.clock", "com.zeta.clock.Main")
    )

    // Die veränderliche Map der benutzerdefinierten Namen
    private val customNames = mutableMapOf<String, String>()

    /**
     * Erstellt die prozessierte und sortierte App-Liste, die die UI anzeigen würde.
     * Sie wendet die benutzerdefinierten Namen auf die Rohdaten an.
     */
    fun getProcessedList(): List<AppInfo> {
        return rawApps.map { app ->
            app.copy(displayName = customNames[app.packageName] ?: app.originalName)
        }.sortedBy { it.displayName.lowercase() }
    }

    /** Setzt die benutzerdefinierten Namen für den nächsten Test zurück. */
    fun clearCustomNames() {
        customNames.clear()
    }

    fun setCustomName(packageName: String, name: String) {
        customNames[packageName] = name
    }

    fun removeCustomName(packageName: String) {
        customNames.remove(packageName)
    }

    fun getDisplayName(packageName: String, originalName: String): String {
        return customNames[packageName] ?: originalName
    }

    fun hasCustomName(packageName: String): Boolean {
        return customNames.containsKey(packageName)
    }

    fun getAllCustomNames(): Map<String, String> {
        return customNames.toMap()
    }
}

// =================================================================================
// --- HILT TEST MODULE: Ersetzt die echten Repositories durch unsere Fakes ---
// =================================================================================

/**
 * Test-Modul für Domain-Level Dependencies (Repositories, UseCases).
 * Ersetzt RepositoryModule in Tests.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [RepositoryModule::class]
)
object TestRepositoryModule {

    @Provides
    @Singleton
    fun provideInstalledAppsRepository(): InstalledAppsRepository {
        // Erstellt die Singleton-Instanz und füllt sie mit den initialen Daten.
        return FakeInstalledAppsRepository().apply {
            appsFlow.value = TestDataSource.getProcessedList()
        }
    }

    @Provides
    @Singleton
    fun provideAppNamesRepository(
        // Hilt injiziert hier die Singleton-Instanz von oben.
        installedAppsRepo: InstalledAppsRepository
    ): CustomNamesRepository {
        // Erstellt das AppNamesRepository und gibt ihm die Fähigkeit, das
        // InstalledAppsRepository zu aktualisieren, wenn sich ein Name ändert.
        return FakeCustomNamesRepository(
            onNameChanged = {
                val newList = TestDataSource.getProcessedList()
                (installedAppsRepo as FakeInstalledAppsRepository).appsFlow.value = newList
            }
        )
    }

    @Provides
    @Singleton
    fun provideFavoritesRepository(): FavoritesRepository = FakeFavoritesRepository()

    @Provides
    @Singleton
    fun provideAppVisibilityRepository(): HiddenAppsRepository = FakeAppVisibilityRepository()

    @Provides
    @Singleton
    fun provideSettingsRepository(): SettingsRepository = FakeSettingsRepository()

    @Provides
    @Singleton
    fun provideAppUsageRepository(): AppUsageRepository = FakeAppUsageRepository()

    @Provides
    @Singleton
    fun provideFavoritesOrderRepository(): FavoritesOrderRepository = FakeFavoritesOrderRepository()

    @Provides
    @Singleton
    fun provideInstalledAppsStateRepository(): InstalledAppsStateRepository =
        FakeInstalledAppsStateRepository()

    @Provides
    @Singleton
    fun provideGetFavoriteAppsUseCaseRepository(): GetFavoriteAppsUseCaseRepository =
        FakeGetFavoriteAppsUseCaseRepository()

    @Provides
    @Singleton
    fun provideGetDrawerAppsUseCaseRepository(): GetDrawerAppsUseCaseRepository =
        FakeGetDrawerAppsUseCaseRepository()

    @Provides
    @Singleton
    fun provideScreenLockRepository(): ScreenLockRepository = FakeScreenLockRepository()

    @Provides
    @Singleton
    fun provideShortcutRepository(): ShortcutRepository = FakeShortcutRepository()

    @Provides
    @Singleton
    fun provideGetOnboardingAppsUseCase(): GetOnboardingAppsUseCaseRepository =
        FakeGetOnboardingAppsUseCaseRepository()

    @Provides
    @Singleton
    fun provideAppUpdateSignal(): AppUpdateSignal = FakeAppUpdateSignal()

    @Provides
    @Singleton
    fun provideSwipeActionsRepository(): SwipeActionsRepository = FakeSwipeActionsRepository()


    @Provides
    @Singleton
    fun provideBackupRepository(): BackupRepository = FakeBackupRepository()

    @Provides
    @Singleton
    fun provideTimeBasedEventsRepository(): TimeBasedEventsRepository =
        FakeTimeBasedEventsRepository()

    @Provides
    @Singleton
    fun provideResetRepository(): ResetRepository = FakeResetRepository()
}


// =================================================================================
// --- FAKE IMPLEMENTATIONS ---
// =================================================================================

/**
 * Hält den Flow der installierten Apps. Wird jetzt reaktiv vom FakeAppNamesRepository
 * aktualisiert, wann immer sich ein Name ändert.
 */
class FakeInstalledAppsRepository : InstalledAppsRepository, Purgeable {
    val appsFlow = MutableStateFlow<List<AppInfo>>(emptyList())
    override fun getInstalledApps(): Flow<List<AppInfo>> = appsFlow
    override suspend fun triggerAppsUpdate() {}
    override suspend fun purgeRepository() {
        appsFlow.value = emptyList()
    }
}

/**
 * Verwaltet die Namensänderungen. Es aktualisiert die zentrale `TestDataSource`
 * und ruft dann den `onNameChanged`-Callback auf, um die reaktive Kette auszulösen.
 */
class FakeCustomNamesRepository(
    private val onNameChanged: () -> Unit
) : CustomNamesRepository, Purgeable {

    override suspend fun getDisplayNameForPackage(
        packageName: String,
        originalName: String
    ): String {
        return TestDataSource.getDisplayName(packageName, originalName)
    }

    override suspend fun setCustomNameForPackage(packageName: String, customName: String): Boolean {
        TestDataSource.setCustomName(packageName, customName)
        onNameChanged()
        return true
    }

    override suspend fun removeCustomNameForPackage(packageName: String): Boolean {
        TestDataSource.removeCustomName(packageName)
        onNameChanged()
        return true
    }

    override suspend fun hasCustomNameForPackage(packageName: String): Boolean {
        return TestDataSource.hasCustomName(packageName)
    }

    override suspend fun getAllCustomNames(): Map<String, String> {
        return TestDataSource.getAllCustomNames()
    }

    override suspend fun setCustomNamesInBatch(names: Map<String, String>): Boolean {
        names.forEach { (packageName, customName) ->
            TestDataSource.setCustomName(packageName, customName)
        }
        onNameChanged()
        return true
    }

    override suspend fun triggerCustomNameUpdate() {
        onNameChanged()
    }

    override suspend fun purgeRepository() {
        TestDataSource.clearCustomNames()
        onNameChanged()
    }
}


// --- RESTLICHE FAKES (UNVERÄNDERT) ---

class FakeFavoritesRepository : FavoritesRepository, Purgeable {
    val favoritesState: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())
    override val favoriteComponentsFlow: Flow<Set<String>> = favoritesState
    val favorites: Set<String> get() = favoritesState.value
    override suspend fun isFavoriteComponent(componentName: String?): Boolean =
        componentName != null && favoritesState.value.contains(componentName)

    override suspend fun cleanupFavoriteComponents(installedComponentNames: List<String>) {
        favoritesState.value = favoritesState.value.intersect(installedComponentNames.toSet())
    }

    override suspend fun toggleFavoriteComponent(componentName: String): Boolean {
        val isFavorite = favoritesState.value.contains(componentName); if (isFavorite) {
            removeFavoriteComponent(componentName)
        } else {
            addFavoriteComponent(componentName)
        }; return !isFavorite
    }

    override suspend fun addFavoriteComponent(componentName: String): Boolean {
        favoritesState.value = favoritesState.value + componentName; return true
    }

    override suspend fun removeFavoriteComponent(componentName: String): Boolean {
        favoritesState.value = favoritesState.value - componentName; return true
    }

    override suspend fun saveFavoriteComponents(componentNames: List<String>) {
        favoritesState.value = componentNames.toSet()
    }

    override suspend fun purgeRepository() {
        favoritesState.value = emptySet()
    }
}

class FakeAppVisibilityRepository : HiddenAppsRepository, Purgeable {
    val hiddenAppsState: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())
    override val hiddenAppsFlow: Flow<Set<String>> = hiddenAppsState
    val hiddenApps: Set<String> get() = hiddenAppsState.value
    override suspend fun isComponentHidden(componentName: String?): Boolean =
        componentName != null && hiddenAppsState.value.contains(componentName)

    override suspend fun hideComponent(componentName: String?): Boolean {
        if (componentName != null) hiddenAppsState.value =
            hiddenAppsState.value + componentName; return true
    }

    override suspend fun showComponent(componentName: String?): Boolean {
        if (componentName != null) hiddenAppsState.value =
            hiddenAppsState.value - componentName; return true
    }

    override suspend fun updateComponentVisibilities(
        componentsToHide: Set<String>,
        componentsToShow: Set<String>
    ) {
        hiddenAppsState.update { currentHidden ->
            val newHidden = currentHidden.toMutableSet()
            newHidden.addAll(componentsToHide)
            newHidden.removeAll(componentsToShow)
            newHidden.toSet()
        }
    }

    override suspend fun purgeRepository() {
        hiddenAppsState.value = emptySet()
    }
}

class FakeAppUsageRepository : AppUsageRepository, Purgeable {
    val launchedPackages = mutableListOf<String>()
    override suspend fun recordPackageLaunch(packageName: String?) {
        packageName?.let { launchedPackages.add(it) }
    }

    override suspend fun sortAppsByTimeWeightedUsage(apps: List<AppInfo>): List<AppInfo> = apps
    override suspend fun removeUsageDataForPackage(packageName: String?) {
        launchedPackages.removeAll { it == packageName }
    }

    override suspend fun hasUsageDataForPackage(packageName: String?): Boolean =
        launchedPackages.contains(packageName)

    override suspend fun purgeRepository() {
        launchedPackages.clear()
    }
}

class FakeFavoritesOrderRepository : FavoritesOrderRepository, Purgeable {
    private val orderState = MutableStateFlow<List<String>>(emptyList())
    override val favoriteComponentsOrderFlow: Flow<List<String>> = orderState
    var savedOrder: List<String>? = null
        private set
    var saveOrderCallCount = 0
        private set

    override suspend fun saveOrder(orderedComponentNames: List<String>): Boolean {
        println(">>> FakeFavoritesOrderRepository.saveOrder CALLED")
        println(">>> Thread: ${Thread.currentThread().name}")
        println(">>> componentNames = $orderedComponentNames")
        println(">>> saveOrderCallCount BEFORE = $saveOrderCallCount")

        savedOrder = orderedComponentNames
        saveOrderCallCount++

        println(">>> saveOrderCallCount AFTER = $saveOrderCallCount")
        orderState.value = orderedComponentNames
        return true
    }

    override suspend fun sortFavoriteComponents(
        favoriteApps: List<AppInfo>,
        order: List<String>
    ): List<AppInfo> {
        if (order.isEmpty()) return favoriteApps.sortedBy { it.displayName };
        val appMap =
            favoriteApps.associateBy { it.componentName }; return order.mapNotNull { appMap[it] } + (favoriteApps - appMap.keys.mapNotNull { appMap[it] }
            .toSet())
    }

    override suspend fun purgeRepository() {
        orderState.value = emptyList(); savedOrder = null; saveOrderCallCount = 0
    }
}

class FakeGetDrawerAppsUseCaseRepository : GetDrawerAppsUseCaseRepository, Purgeable {
    override val drawerApps = MutableLiveData<List<AppInfo>>()
    override suspend fun purgeRepository() {
        drawerApps.postValue(emptyList())
    }
}

class FakeGetFavoriteAppsUseCaseRepository : GetFavoriteAppsUseCaseRepository, Purgeable {
    val favoriteAppsState: MutableStateFlow<UiState<FavoriteAppsResult>> =
        MutableStateFlow(UiState.Loading)
    override val favoriteApps: Flow<UiState<FavoriteAppsResult>> = favoriteAppsState

    // Speichert das Limit, das vom ViewModel (im Test) gesetzt wurde
    var currentDynamicMax: Int = AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME
        private set

    /**
     * NEU: Implementierung der Interface-Methode.
     * Im Test kannst du 'currentDynamicMax' prüfen, um zu sehen,
     * ob der richtige Wert vom ViewModel gesendet wurde.
     */
    override fun setDynamicMaxFavorites(max: Int) {
        currentDynamicMax = max
        // Optional: Du könntest hier auch Logik einfügen, um
        // 'favoriteAppsState' basierend auf dem Limit neu auszugeben,
        // aber für die meisten Tests reicht es, den Wert zu speichern.
    }

    override suspend fun purgeRepository() {
        favoriteAppsState.value = UiState.Loading
        currentDynamicMax = AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME
    }

    // Hilfsfunktion für deine Tests, um den State einfach zu setzen
    fun emitSuccess(apps: List<AppInfo>) {
        favoriteAppsState.value = UiState.Success(
            FavoriteAppsResult(apps = apps, isFallback = false)
        )
    }

    fun emitLoading() {
        favoriteAppsState.value = UiState.Loading
    }
}

class FakeInstalledAppsStateRepository : InstalledAppsStateRepository, Purgeable {
    private val stateFlow = MutableStateFlow<List<AppInfo>>(emptyList())
    private var lastSuccessfulAppList: List<AppInfo> = emptyList()
    override val rawAppsFlow: StateFlow<List<AppInfo>> = stateFlow
    override fun updateApps(newApps: List<AppInfo>) {
        if (newApps.isNotEmpty()) {
            lastSuccessfulAppList = newApps
        }; stateFlow.value = newApps
    }

    override fun getCurrentApps(): List<AppInfo> {
        val currentApps = stateFlow.value; return if (currentApps.isNotEmpty()) {
            currentApps
        } else {
            lastSuccessfulAppList
        }
    }

    override suspend fun purgeRepository() {
        stateFlow.value = emptyList(); lastSuccessfulAppList = emptyList()
    }
}

class FakeScreenLockRepository : ScreenLockRepository, Purgeable {

    override val isLockingAvailableFlow = MutableStateFlow(true)
    private val lockRequest = MutableSharedFlow<Unit>()
    override val lockRequestFlow: Flow<Unit> = lockRequest
    override suspend fun requestLock() {
        lockRequest.emit(Unit)
    }

    private val openNotificationsRequest = MutableSharedFlow<Unit>()
    override val openNotificationsRequestFlow: Flow<Unit> = openNotificationsRequest
    override suspend fun requestOpenNotifications() {
        openNotificationsRequest.emit(Unit)
    }

    override fun setServiceState(isAvailable: Boolean) {
        isLockingAvailableFlow.value = isAvailable
    }

    override suspend fun purgeRepository() {
        isLockingAvailableFlow.value = true
    }
}

class FakeShortcutRepository : ShortcutRepository, Purgeable {
    override fun getShortcutsForPackage(packageName: String): List<ShortcutInfo> = emptyList()
    override suspend fun purgeRepository() {}
}

class FakeGetOnboardingAppsUseCaseRepository : GetOnboardingAppsUseCaseRepository {
    val mutableOnboardingAppsFlow = MutableStateFlow<List<AppInfo>>(emptyList())
    override val onboardingAppsFlow: Flow<List<AppInfo>>
        get() = mutableOnboardingAppsFlow

    override suspend fun purgeRepository() {
        mutableOnboardingAppsFlow.value = emptyList()
    }
}

open class FakeAppUpdateSignal : AppUpdateSignal(), Purgeable {
    var signalSentCount = 0
        private set

    fun reset() {
        runBlocking { purgeRepository() }
    }

    override suspend fun purgeRepository() {
        signalSentCount = 0
    }

    override suspend fun sendUpdateSignal() {
        signalSentCount++; super.sendUpdateSignal()
    }
}

class FakeSwipeActionsRepository : SwipeActionsRepository, Purgeable {
    private val swipeLeftState = MutableStateFlow<String?>(null)
    private val swipeRightState = MutableStateFlow<String?>(null)

    override val swipeLeftAppFlow: Flow<String?> = swipeLeftState
    override val swipeRightAppFlow: Flow<String?> = swipeRightState

    override suspend fun setSwipeAction(slot: SwipeSlot, componentName: String?) {
        when (slot) {
            SwipeSlot.LEFT -> swipeLeftState.value = componentName
            SwipeSlot.RIGHT -> swipeRightState.value = componentName
            SwipeSlot.NONE -> {
                // Ignore, wie im echten Manager
            }
        }
    }

    override suspend fun purgeRepository() {
        swipeLeftState.value = null
        swipeRightState.value = null
    }
}

class FakeBackupRepository : BackupRepository, Purgeable {
    var lastExportedJson: String? = null
        private set
    var lastImportedJson: String? = null
        private set
    var lastImportOptions: ImportOptions? = null
        private set

    override suspend fun exportToJson(): String {
        val json = """
        {
            "version": "1.0.0",
            "timestamp": ${System.currentTimeMillis()},
            "settings": {
                "favoriteComponents": [],
                "favoritesOrder": [],
                "hiddenComponents": [],
                "customAppNames": {},
                "swipe_left_app": null,
                "swipe_right_app": null,
                "text_color": 0,
                "chip_bg_color": 0,
                "text_shadow_enabled": true,
                "double_tap_to_lock_enabled": false,
                "swipe_down_to_notifications_enabled": false,
                "show_calendar_event": false,
                "show_alarm": false
            }
        }
        """.trimIndent()
        lastExportedJson = json
        return json
    }

    override suspend fun importFromJson(jsonString: String, options: ImportOptions): ImportResult {
        lastImportedJson = jsonString
        lastImportOptions = options
        return ImportResult.Success(
            importedCount = 0,
            skippedCount = 0,
            missingApps = emptySet()
        )
    }

    override suspend fun saveBackupToFile(uriString: String): Boolean {
        return true
    }

    override suspend fun loadBackupFromFile(
        uriString: String,
        options: ImportOptions
    ): ImportResult {
        lastImportOptions = options
        return ImportResult.Success(
            importedCount = 0,
            skippedCount = 0,
            missingApps = emptySet()
        )
    }

    override suspend fun previewBackup(uriString: String): BackupPreview? {
        return BackupPreview(
            version = "1.0.0",
            timestamp = System.currentTimeMillis(),
            favoriteCount = 0,
            orderCount = 0,
            hiddenCount = 0,
            customNamesCount = 0,
            hasSwipeLeft = false,
            hasSwipeRight = false,
            hasThemeSettings = false,
            hasTimeBasedEvents = false,
            hasGestureSettings = false,
            hasQualityOfLife = false,
            hasPowerUserSettings = false
        )
    }

    override suspend fun purgeRepository() {
        lastExportedJson = null
        lastImportedJson = null
        lastImportOptions = null
    }
}

@Singleton
class FakeTimeBasedEventsRepository @Inject constructor() : TimeBasedEventsRepository, Purgeable {
    private var events = emptyList<TimeBasedEvent>()

    /**
     * Gibt die gespeicherten Events zurück, sortiert und limitiert,
     * genau wie die echte Implementierung es tun würde.
     */
    override suspend fun getUpcomingTimeBasedEvents(maxCount: Int): List<TimeBasedEvent> {
        return events.sortedBy { it.triggerTimeMillis }.take(maxCount)
    }

    /**
     * Setzt den Zustand des Fakes für den nächsten Test zurück.
     */
    override suspend fun purgeRepository() {
        events = emptyList()
    }

    /**
     * Eine Helferfunktion, um den Zustand des Fakes vorzubereiten.
     */
    fun setEvents(events: List<TimeBasedEvent>) {
        this.events = events
    }
}

@Singleton
class FakeResetRepository @Inject constructor() : ResetRepository, Purgeable {

    var resetAllDataCalled = false
    var resetUserDataCalled = false
    var resetSettingsCalled = false
    var resetAppUsageDataCalled = false

    override suspend fun resetAllData(): Boolean {
        resetAllDataCalled = true
        return true
    }

    override suspend fun resetUserData(): Boolean {
        resetUserDataCalled = true
        return true
    }

    override suspend fun resetSettings(): Boolean {
        resetSettingsCalled = true
        return true
    }

    override suspend fun resetAppUsageData(): Boolean {
        resetAppUsageDataCalled = true
        return true
    }

    override suspend fun purgeRepository() {
        resetAllDataCalled = false
        resetUserDataCalled = false
        resetSettingsCalled = false
        resetAppUsageDataCalled = false
    }
}

class FakeSettingsRepository : SettingsRepository {

    private val shadowFlow = MutableStateFlow(true)
    private val colorFlow = MutableStateFlow(0)
    private val chipBgColorFlow = MutableStateFlow(0)
    private val layoutScaleFlow = MutableStateFlow(AppConstants.DEFAULT_LAYOUT_SCALE)
    private val verticalPaddingFlow = MutableStateFlow(AppConstants.DEFAULT_VERTICAL_PADDING_FACTOR)
    private val isFontBoldFlow = MutableStateFlow(AppConstants.DEFAULT_FONT_BOLD)
    private val contentTopMarginFlow = MutableStateFlow(0f)

    private val calendarFlow = MutableStateFlow(false)
    private val alarmFlow = MutableStateFlow(false)
    private val doubleTapFlow = MutableStateFlow(false)
    private val swipeDownFlow = MutableStateFlow(false)
    private val autoShowKeyboardFlowState = MutableStateFlow(false)
    private val autoLaunchAppFlowState = MutableStateFlow(false)
    private val splitModeThresholdFlowState = MutableStateFlow(0)

    private val readabilityModeState = MutableStateFlow("smart_contrast")

    override val readabilityModeFlow: Flow<String> = readabilityModeState

    var shadow: Boolean
        get() = shadowFlow.value
        set(value) { shadowFlow.value = value }

    var color: Int
        get() = colorFlow.value
        set(value) { colorFlow.value = value }

    var chipBgColor: Int
        get() = chipBgColorFlow.value
        set(value) { chipBgColorFlow.value = value }

    var layoutScale: Float
        get() = layoutScaleFlow.value
        set(value) { layoutScaleFlow.value = value }

    var verticalPadding: Float
        get() = verticalPaddingFlow.value
        set(value) { verticalPaddingFlow.value = value }

    var isFontBold: Boolean
        get() = isFontBoldFlow.value
        set(value) { isFontBoldFlow.value = value }

    var contentTopMargin: Float
        get() = contentTopMarginFlow.value
        set(value) { contentTopMarginFlow.value = value }

    var showCalendar: Boolean
        get() = calendarFlow.value
        set(value) { calendarFlow.value = value }

    var showAlarm: Boolean
        get() = alarmFlow.value
        set(value) { alarmFlow.value = value }

    var doubleTap: Boolean
        get() = doubleTapFlow.value
        set(value) { doubleTapFlow.value = value }

    var swipeDown: Boolean
        get() = swipeDownFlow.value
        set(value) { swipeDownFlow.value = value }

    var autoShowKeyboard: Boolean
        get() = autoShowKeyboardFlowState.value
        set(value) { autoShowKeyboardFlowState.value = value }

    var autoLaunchApp: Boolean
        get() = autoLaunchAppFlowState.value
        set(value) { autoLaunchAppFlowState.value = value }

    var splitModeThreshold: Int
        get() = splitModeThresholdFlowState.value
        set(value) {
            splitModeThresholdFlowState.value = value.coerceIn(0, 512)
        }

    override val textShadowEnabledFlow: Flow<Boolean> = shadowFlow
    override val textColorFlow: Flow<Int> = colorFlow
    override val chipBackgroundColorFlow: Flow<Int> = chipBgColorFlow
    override val layoutScaleStateFlow: Flow<Float> = layoutScaleFlow
    override val verticalPaddingStateFlow: Flow<Float> = verticalPaddingFlow
    override val isFontBoldStateFlow: Flow<Boolean> = isFontBoldFlow
    override val contentTopMarginScaleFlow: Flow<Float> = contentTopMarginFlow


    override suspend fun setTextShadowEnabled(isEnabled: Boolean) {
        shadow = isEnabled
    }

    override suspend fun setTextColor(color: Int) {
        this.color = color
    }

    override suspend fun setChipBackgroundColor(color: Int) {
        this.chipBgColor = color
    }

    override suspend fun setLayoutScale(scale: Float) {
        layoutScale = scale
    }

    override suspend fun setVerticalPadding(scale: Float) {
        verticalPadding = scale
    }

    override suspend fun setFontBold(isBold: Boolean) {
        isFontBold = isBold
    }

    override suspend fun setContentTopMarginScale(scale: Float) {
        contentTopMargin = scale
    }

    override val sortOrderFlow: Flow<SortOrder> = flowOf(SortOrder.TIME_WEIGHTED_USAGE)
    override suspend fun setSortOrder(sortOrder: SortOrder) {}

    override val doubleTapToLockEnabledFlow: Flow<Boolean> = doubleTapFlow
    override suspend fun setDoubleTapToLock(isEnabled: Boolean) {
        doubleTap = isEnabled
    }

    override val swipeDownToNotificationsEnabledFlow: Flow<Boolean> = swipeDownFlow
    override suspend fun setSwipeDownToNotifications(isEnabled: Boolean) {
        swipeDown = isEnabled
    }

    override suspend fun setReadabilityMode(mode: String) {
        readabilityModeState.value = mode
    }
    fun setReadabilityModeBlocking(mode: String) {
        readabilityModeState.value = mode
    }

    override val onboardingCompletedFlow: Flow<Boolean> = flowOf(false)
    override suspend fun setOnboardingCompleted() {}

    override val showCalendarEventFlow: Flow<Boolean> = calendarFlow
    override suspend fun setShowCalendarEvent(isEnabled: Boolean) {
        showCalendar = isEnabled
    }

    override val showAlarmFlow: Flow<Boolean> = alarmFlow
    override suspend fun setShowAlarm(isEnabled: Boolean) {
        showAlarm = isEnabled
    }

    override val autoShowKeyboardFlow: Flow<Boolean> = autoShowKeyboardFlowState
    override suspend fun setAutoShowKeyboard(isEnabled: Boolean) {
        autoShowKeyboard = isEnabled
    }

    override val autoLaunchAppFlow: Flow<Boolean> = autoLaunchAppFlowState
    override suspend fun setAutoLaunchApp(isEnabled: Boolean) {
        autoLaunchApp = isEnabled
    }

    override val splitModeThresholdFlow: Flow<Int> = splitModeThresholdFlowState

    override suspend fun setSplitModeThreshold(thresholdPixels: Int) {
        splitModeThreshold = thresholdPixels
    }

    override suspend fun purgeRepository() {
        color = 0
        shadow = true
        chipBgColor = 0
        layoutScale = AppConstants.DEFAULT_LAYOUT_SCALE
        verticalPadding = AppConstants.DEFAULT_VERTICAL_PADDING_FACTOR
        isFontBold = AppConstants.DEFAULT_FONT_BOLD
        contentTopMargin = 0f

        showCalendar = false
        showAlarm = false
        doubleTap = false
        swipeDown = false
        autoShowKeyboard = false
        autoLaunchApp = false
        splitModeThreshold = 0
    }
}