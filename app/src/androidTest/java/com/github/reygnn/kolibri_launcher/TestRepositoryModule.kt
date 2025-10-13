package com.github.reygnn.kolibri_launcher

import android.content.pm.ShortcutInfo
import androidx.lifecycle.MutableLiveData
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
        AppInfo("Beta Calculator", "Beta Calculator", "com.beta.calculator", "com.beta.calculator.Main"),
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
}

// =================================================================================
// --- HILT TEST MODULE: Ersetzt die echten Repositories durch unsere Fakes ---
// =================================================================================

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [RepositoryModule::class]
)
object TestRepositoryModule {

    // --- Kern-Repositories für das aktuelle Problem (reaktiv verbunden) ---

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
    ): AppNamesRepository {
        // Erstellt das AppNamesRepository und gibt ihm die Fähigkeit, das
        // InstalledAppsRepository zu aktualisieren, wenn sich ein Name ändert.
        return FakeAppNamesRepository(
            onNameChanged = {
                val newList = TestDataSource.getProcessedList()
                (installedAppsRepo as FakeInstalledAppsRepository).appsFlow.value = newList
            }
        )
    }

    // --- Restliche Fake-Provider ---

    @Provides
    @Singleton
    fun provideFavoritesRepository(): FavoritesRepository = FakeFavoritesRepository()

    @Provides
    @Singleton
    fun provideAppVisibilityRepository(): AppVisibilityRepository = FakeAppVisibilityRepository()

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
}


// =================================================================================
// --- FAKE IMPLEMENTATIONS ---
// =================================================================================

// Diese data class ist hier, weil sie in GetFavoriteAppsUseCase.kt definiert ist.
data class FavoriteAppsResult(val apps: List<AppInfo>, val isFallback: Boolean)

// --- ANGEPASSTE FAKES FÜR DIE REAKTIVE LÖSUNG ---

/**
 * Hält den Flow der installierten Apps. Wird jetzt reaktiv vom FakeAppNamesRepository
 * aktualisiert, wann immer sich ein Name ändert.
 */
class FakeInstalledAppsRepository : InstalledAppsRepository, Purgeable {
    val appsFlow = MutableStateFlow<List<AppInfo>>(emptyList())
    override fun getInstalledApps(): Flow<List<AppInfo>> = appsFlow

    override fun purgeRepository() {
        appsFlow.value = emptyList()
    }
}

/**
 * Verwaltet die Namensänderungen. Es aktualisiert die zentrale `TestDataSource`
 * und ruft dann den `onNameChanged`-Callback auf, um die reaktive Kette auszulösen.
 */
class FakeAppNamesRepository(
    private val onNameChanged: () -> Unit
) : AppNamesRepository, Purgeable {

    override suspend fun getDisplayNameForPackage(packageName: String, originalName: String): String {
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

    override suspend fun triggerCustomNameUpdate() {
        onNameChanged()
    }

    override fun purgeRepository() {
        TestDataSource.clearCustomNames()
        onNameChanged()
    }
}


// --- RESTLICHE FAKES (UNVERÄNDERT) ---

class FakeFavoritesRepository : FavoritesRepository, Purgeable {
    val favoritesState = MutableStateFlow(emptySet<String>())
    override val favoriteComponentsFlow: Flow<Set<String>> = favoritesState
    val favorites: Set<String> get() = favoritesState.value
    override suspend fun isFavoriteComponent(componentName: String?): Boolean = componentName != null && favoritesState.value.contains(componentName)
    override suspend fun cleanupFavoriteComponents(installedComponentNames: List<String>) { favoritesState.value = favoritesState.value.intersect(installedComponentNames.toSet()) }
    override suspend fun toggleFavoriteComponent(componentName: String): Boolean { val isFavorite = favoritesState.value.contains(componentName); if (isFavorite) { removeFavoriteComponent(componentName) } else { addFavoriteComponent(componentName) }; return !isFavorite }
    override suspend fun addFavoriteComponent(componentName: String): Boolean { favoritesState.value = favoritesState.value + componentName; return true }
    override suspend fun removeFavoriteComponent(componentName: String): Boolean { favoritesState.value = favoritesState.value - componentName; return true }
    override suspend fun saveFavoriteComponents(componentNames: List<String>) { favoritesState.value = componentNames.toSet() }
    override fun purgeRepository() { favoritesState.value = emptySet() }
}

class FakeAppVisibilityRepository : AppVisibilityRepository, Purgeable {
    val hiddenAppsState = MutableStateFlow(emptySet<String>())
    override val hiddenAppsFlow: Flow<Set<String>> = hiddenAppsState
    val hiddenApps: Set<String> get() = hiddenAppsState.value
    override suspend fun isComponentHidden(componentName: String?): Boolean = componentName != null && hiddenAppsState.value.contains(componentName)
    override suspend fun hideComponent(componentName: String?): Boolean { if (componentName != null) hiddenAppsState.value = hiddenAppsState.value + componentName; return true }
    override suspend fun showComponent(componentName: String?): Boolean { if (componentName != null) hiddenAppsState.value = hiddenAppsState.value - componentName; return true }
    override fun purgeRepository() { hiddenAppsState.value = emptySet() }
}

class FakeSettingsRepository : SettingsRepository, Purgeable {
    override val sortOrderFlow = MutableStateFlow(SortOrder.ALPHABETICAL)
    override val doubleTapToLockEnabledFlow = MutableStateFlow(false)
    override val readabilityModeFlow = MutableStateFlow("smart_contrast")
    override val onboardingCompletedFlow = MutableStateFlow(false)
    override suspend fun setSortOrder(sortOrder: SortOrder) { sortOrderFlow.value = sortOrder }
    override suspend fun setDoubleTapToLock(isEnabled: Boolean) { doubleTapToLockEnabledFlow.value = isEnabled }
    override suspend fun setReadabilityMode(mode: String) { readabilityModeFlow.value = mode }
    override suspend fun setOnboardingCompleted() { onboardingCompletedFlow.value = true }
    override fun purgeRepository() { sortOrderFlow.value = SortOrder.ALPHABETICAL; doubleTapToLockEnabledFlow.value = false; readabilityModeFlow.value = "smart_contrast"; onboardingCompletedFlow.value = false }
}

class FakeAppUsageRepository : AppUsageRepository, Purgeable {
    val launchedPackages = mutableListOf<String>()
    override suspend fun recordPackageLaunch(packageName: String?) { packageName?.let { launchedPackages.add(it) } }
    override suspend fun sortAppsByTimeWeightedUsage(apps: List<AppInfo>): List<AppInfo> = apps
    override suspend fun removeUsageDataForPackage(packageName: String?) { launchedPackages.removeAll { it == packageName } }
    override suspend fun hasUsageDataForPackage(packageName: String?): Boolean = launchedPackages.contains(packageName)
    override fun purgeRepository() { launchedPackages.clear() }
}

class FakeFavoritesOrderRepository : FavoritesOrderRepository, Purgeable {
    private val orderState = MutableStateFlow<List<String>>(emptyList())
    override val favoriteComponentsOrderFlow: Flow<List<String>> = orderState
    var savedOrder: List<String>? = null
        private set
    var saveOrderCallCount = 0
        private set
    override suspend fun saveOrder(componentNames: List<String>): Boolean { savedOrder = componentNames; saveOrderCallCount++; orderState.value = componentNames; return true }
    override suspend fun sortFavoriteComponents(favoriteApps: List<AppInfo>, order: List<String>): List<AppInfo> { if (order.isEmpty()) return favoriteApps.sortedBy { it.displayName }; val appMap = favoriteApps.associateBy { it.componentName }; return order.mapNotNull { appMap[it] } + (favoriteApps - appMap.keys.mapNotNull { appMap[it] }.toSet()) }
    override fun purgeRepository() { orderState.value = emptyList(); savedOrder = null; saveOrderCallCount = 0 }
}

class FakeGetDrawerAppsUseCaseRepository : GetDrawerAppsUseCaseRepository, Purgeable {
    override val drawerApps = MutableLiveData<List<AppInfo>>()
    override fun purgeRepository() { drawerApps.postValue(emptyList()) }
}

class FakeGetFavoriteAppsUseCaseRepository : GetFavoriteAppsUseCaseRepository, Purgeable {
    override val favoriteApps = MutableStateFlow<UiState<FavoriteAppsResult>>(UiState.Loading)
    override fun purgeRepository() { favoriteApps.value = UiState.Loading }
}

class FakeInstalledAppsStateRepository : InstalledAppsStateRepository, Purgeable {
    private val stateFlow = MutableStateFlow<List<AppInfo>>(emptyList())
    private var lastSuccessfulAppList: List<AppInfo> = emptyList()
    override val rawAppsFlow: StateFlow<List<AppInfo>> = stateFlow
    override fun updateApps(newApps: List<AppInfo>) { if (newApps.isNotEmpty()) { lastSuccessfulAppList = newApps }; stateFlow.value = newApps }
    override fun getCurrentApps(): List<AppInfo> { val currentApps = stateFlow.value; return if (currentApps.isNotEmpty()) { currentApps } else { lastSuccessfulAppList } }
    override fun purgeRepository() { stateFlow.value = emptyList(); lastSuccessfulAppList = emptyList() }
}

class FakeScreenLockRepository : ScreenLockRepository, Purgeable {
    override val isLockingAvailableFlow = MutableStateFlow(true)
    private val lockRequest = MutableSharedFlow<Unit>()
    override val lockRequestFlow: Flow<Unit> = lockRequest
    override fun setServiceState(isAvailable: Boolean) { isLockingAvailableFlow.value = isAvailable }
    override suspend fun requestLock() { lockRequest.emit(Unit) }
    override fun purgeRepository() { isLockingAvailableFlow.value = true }
}

class FakeShortcutRepository : ShortcutRepository, Purgeable {
    override fun getShortcutsForPackage(packageName: String): List<ShortcutInfo> = emptyList()
    override fun purgeRepository() { }
}

class FakeGetOnboardingAppsUseCaseRepository : GetOnboardingAppsUseCaseRepository {
    val mutableOnboardingAppsFlow = MutableStateFlow<List<AppInfo>>(emptyList())
    override val onboardingAppsFlow: Flow<List<AppInfo>>
        get() = mutableOnboardingAppsFlow
    override fun purgeRepository() { mutableOnboardingAppsFlow.value = emptyList() }
}

open class FakeAppUpdateSignal : AppUpdateSignal(), Purgeable {
    var signalSentCount = 0
        private set
    fun reset() { purgeRepository() }
    override fun purgeRepository() { signalSentCount = 0 }
    override suspend fun sendUpdateSignal() { signalSentCount++; super.sendUpdateSignal() }
}