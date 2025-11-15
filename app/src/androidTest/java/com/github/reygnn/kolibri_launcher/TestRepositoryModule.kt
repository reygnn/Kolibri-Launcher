package com.github.reygnn.kolibri_launcher

import android.content.pm.ShortcutInfo
import androidx.lifecycle.MutableLiveData
import com.github.reygnn.kolibri_launcher.data.AppInfo
import com.github.reygnn.kolibri_launcher.data.AppUsageRepository
import com.github.reygnn.kolibri_launcher.data.BackupPreview
import com.github.reygnn.kolibri_launcher.data.BackupRepository
import com.github.reygnn.kolibri_launcher.data.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.data.FavoriteAppsResult
import com.github.reygnn.kolibri_launcher.data.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.data.FavoritesRepository
import com.github.reygnn.kolibri_launcher.data.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.data.ImportOptions
import com.github.reygnn.kolibri_launcher.data.ImportResult
import com.github.reygnn.kolibri_launcher.data.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.data.InstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.data.ResetRepository
import com.github.reygnn.kolibri_launcher.domain.Purgeable
import com.github.reygnn.kolibri_launcher.data.ScreenLockRepository
import com.github.reygnn.kolibri_launcher.data.SettingsRepository
import com.github.reygnn.kolibri_launcher.data.ShortcutRepository
import com.github.reygnn.kolibri_launcher.data.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.data.TimeBasedEventsRepository
import com.github.reygnn.kolibri_launcher.di.RepositoryModule
import com.github.reygnn.kolibri_launcher.domain.GetDrawerAppsUseCaseRepository
import com.github.reygnn.kolibri_launcher.domain.GetFavoriteAppsUseCaseRepository
import com.github.reygnn.kolibri_launcher.domain.GetOnboardingAppsUseCaseRepository
import com.github.reygnn.kolibri_launcher.domain.SortOrder
import com.github.reygnn.kolibri_launcher.domain.TimeBasedEvent
import com.github.reygnn.kolibri_launcher.ui.SwipeSlot
import com.github.reygnn.kolibri_launcher.ui.UiState
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

class FakeSettingsRepository : SettingsRepository {

    private val shadowFlow = MutableStateFlow(true) // Default: true
    private val colorFlow = MutableStateFlow(0) // Default: 0 (Auto)
    private val chipBgColorFlow = MutableStateFlow(0) // Default: 0 (Auto)
    private val calendarFlow = MutableStateFlow(false) // Default false
    private val alarmFlow = MutableStateFlow(false) // Default: false
    private val doubleTapFlow = MutableStateFlow(false) // Default false
    private val swipeDownFlow = MutableStateFlow(false) // Default false
    private val sortOrderFlowState = MutableStateFlow(SortOrder.ALPHABETICAL)
    private val readabilityModeFlowState = MutableStateFlow("smart_contrast")
    private val onboardingFlowState = MutableStateFlow(false)
    private val autoShowKeyboardFlowState = MutableStateFlow(false)

    var shadow: Boolean
        get() = shadowFlow.value
        set(value) {
            shadowFlow.value = value
        }

    var color: Int
        get() = colorFlow.value
        set(value) {
            colorFlow.value = value
        }

    var chipBgColor: Int
        get() = chipBgColorFlow.value
        set(value) {
            chipBgColorFlow.value = value
        }

    var showCalendar: Boolean
        get() = calendarFlow.value
        set(value) {
            calendarFlow.value = value
        }

    var showAlarm: Boolean
        get() = alarmFlow.value
        set(value) {
            alarmFlow.value = value
        }

    var doubleTap: Boolean
        get() = doubleTapFlow.value
        set(value) {
            doubleTapFlow.value = value
        }

    var swipeDown: Boolean
        get() = swipeDownFlow.value
        set(value) {
            swipeDownFlow.value = value
        }

    var autoShowKeyboard: Boolean
        get() = autoShowKeyboardFlowState.value
        set(value) {
            autoShowKeyboardFlowState.value = value
        }

    override val textShadowEnabledFlow: Flow<Boolean> = shadowFlow
    override val textColorFlow: Flow<Int> = colorFlow
    override val chipBackgroundColorFlow: Flow<Int> = chipBgColorFlow

    override suspend fun setTextShadowEnabled(isEnabled: Boolean) {
        shadow = isEnabled
    }

    override suspend fun setTextColor(color: Int) {
        this.color = color
    }

    override suspend fun setChipBackgroundColor(color: Int) {
        this.chipBgColor = color
    }

    override val sortOrderFlow: Flow<SortOrder> = sortOrderFlowState
    override suspend fun setSortOrder(sortOrder: SortOrder) {
        sortOrderFlowState.value = sortOrder
    }

    override val doubleTapToLockEnabledFlow: Flow<Boolean> = doubleTapFlow
    override suspend fun setDoubleTapToLock(isEnabled: Boolean) {
        doubleTap = isEnabled
    }

    override val swipeDownToNotificationsEnabledFlow: Flow<Boolean> = swipeDownFlow
    override suspend fun setSwipeDownToNotifications(isEnabled: Boolean) {
        swipeDown = isEnabled
    }

    override val readabilityModeFlow: Flow<String> = readabilityModeFlowState
    override suspend fun setReadabilityMode(mode: String) {
        readabilityModeFlowState.value = mode
    }

    override val onboardingCompletedFlow: Flow<Boolean> = onboardingFlowState
    override suspend fun setOnboardingCompleted() {
        onboardingFlowState.value = true
    }

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

    // ===== HELPER METHODS FÜR TESTS (BLOCKING) =====
    fun setReadabilityModeBlocking(mode: String) {
        readabilityModeFlowState.value = mode
    }

    fun setSortOrderBlocking(sortOrder: SortOrder) {
        sortOrderFlowState.value = sortOrder
    }

    override suspend fun purgeRepository() {
        color = 0
        shadow = true
        chipBgColor = 0
        showCalendar = false
        showAlarm = false
        doubleTap = false
        swipeDown = false
        sortOrderFlowState.value = SortOrder.ALPHABETICAL
        readabilityModeFlowState.value = "smart_contrast"
        onboardingFlowState.value = false
        autoShowKeyboardFlowState.value = false
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

    override suspend fun purgeRepository() {
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
            hasQualityOfLife = false
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