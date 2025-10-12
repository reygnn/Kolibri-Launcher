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

// Diese data class ist hier, weil sie in GetFavoriteAppsUseCase.kt definiert ist.
// Das macht unser Test-Modul eigenständig und komplett.
data class FavoriteAppsResult(val apps: List<AppInfo>, val isFallback: Boolean)

// Dieses Interface wurde aus deiner `InstalledAppsManager`-Implementierung abgeleitet,
// da die Datei fehlte. Dies ist die faktenbasierte Version.
interface InstalledAppsRepository {
    fun getInstalledApps(): Flow<List<AppInfo>>
}


/**
 * Ersetzt das produktive [RepositoryModule] in allen instrumentierten Tests.
 * Stellt "Fake"-Implementierungen für alle Repositories bereit, die vollständig
 * im Test kontrolliert und überprüft werden können.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [RepositoryModule::class]
)
object TestRepositoryModule {

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
    fun provideInstalledAppsRepository(): InstalledAppsRepository = FakeInstalledAppsRepository()

    @Provides
    @Singleton
    fun provideAppNamesRepository(): AppNamesRepository = FakeAppNamesRepository()

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

class FakeFavoritesRepository : FavoritesRepository, Purgeable {
    val favoritesState = MutableStateFlow(emptySet<String>())
    override val favoriteComponentsFlow: Flow<Set<String>> = favoritesState
    val favorites: Set<String> get() = favoritesState.value

    override suspend fun isFavoriteComponent(componentName: String?): Boolean =
        componentName != null && favoritesState.value.contains(componentName)

    override suspend fun cleanupFavoriteComponents(installedComponentNames: List<String>) {
        favoritesState.value = favoritesState.value.intersect(installedComponentNames.toSet())
    }

    override suspend fun toggleFavoriteComponent(componentName: String): Boolean {
        val isFavorite = favoritesState.value.contains(componentName)
        if (isFavorite) {
            removeFavoriteComponent(componentName)
        } else {
            addFavoriteComponent(componentName)
        }
        return !isFavorite
    }

    override suspend fun addFavoriteComponent(componentName: String): Boolean {
        favoritesState.value = favoritesState.value + componentName
        return true
    }

    override suspend fun removeFavoriteComponent(componentName: String): Boolean {
        favoritesState.value = favoritesState.value - componentName
        return true
    }

    override suspend fun saveFavoriteComponents(componentNames: List<String>) {
        favoritesState.value = componentNames.toSet()
    }

    override fun purgeRepository() {
        favoritesState.value = emptySet()
    }
}

class FakeAppVisibilityRepository : AppVisibilityRepository, Purgeable {
    val hiddenAppsState = MutableStateFlow(emptySet<String>())
    override val hiddenAppsFlow: Flow<Set<String>> = hiddenAppsState
    val hiddenApps: Set<String> get() = hiddenAppsState.value

    override suspend fun isComponentHidden(componentName: String?): Boolean =
        componentName != null && hiddenAppsState.value.contains(componentName)

    override suspend fun hideComponent(componentName: String?): Boolean {
        if (componentName != null) hiddenAppsState.value = hiddenAppsState.value + componentName
        return true
    }

    override suspend fun showComponent(componentName: String?): Boolean {
        if (componentName != null) hiddenAppsState.value = hiddenAppsState.value - componentName
        return true
    }

    override fun purgeRepository() {
        hiddenAppsState.value = emptySet()
    }
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

    override fun purgeRepository() {
        sortOrderFlow.value = SortOrder.ALPHABETICAL
        doubleTapToLockEnabledFlow.value = false
        readabilityModeFlow.value = "smart_contrast"
        onboardingCompletedFlow.value = false
    }
}

class FakeAppUsageRepository : AppUsageRepository, Purgeable {
    val launchedPackages = mutableListOf<String>()

    override suspend fun recordPackageLaunch(packageName: String?) { packageName?.let { launchedPackages.add(it) } }
    override suspend fun sortAppsByTimeWeightedUsage(apps: List<AppInfo>): List<AppInfo> = apps
    override suspend fun removeUsageDataForPackage(packageName: String?) { launchedPackages.removeAll { it == packageName } }
    override suspend fun hasUsageDataForPackage(packageName: String?): Boolean = launchedPackages.contains(packageName)

    override fun purgeRepository() {
        launchedPackages.clear()
    }
}

class FakeFavoritesOrderRepository : FavoritesOrderRepository, Purgeable {
    private val orderState = MutableStateFlow<List<String>>(emptyList())
    override val favoriteComponentsOrderFlow: Flow<List<String>> = orderState

    // Eigenschaften für den Test
    var savedOrder: List<String>? = null
        private set
    var saveOrderCallCount = 0
        private set

    // NEUE METHODE IMPLEMENTIEREN
    override suspend fun saveOrder(componentNames: List<String>): Boolean {
        savedOrder = componentNames
        saveOrderCallCount++
        orderState.value = componentNames
        return true
    }

    override suspend fun sortFavoriteComponents(favoriteApps: List<AppInfo>, order: List<String>): List<AppInfo> {
        if (order.isEmpty()) return favoriteApps.sortedBy { it.displayName }
        val appMap = favoriteApps.associateBy { it.componentName }
        return order.mapNotNull { appMap[it] } + (favoriteApps - appMap.keys.mapNotNull { appMap[it] }.toSet())
    }

    override fun purgeRepository() {
        orderState.value = emptyList()
        savedOrder = null
        saveOrderCallCount = 0
    }
}

class FakeInstalledAppsRepository : InstalledAppsRepository, Purgeable {
    val appsFlow = MutableStateFlow<List<AppInfo>>(emptyList())
    override fun getInstalledApps(): Flow<List<AppInfo>> = appsFlow

    override fun purgeRepository() {
        appsFlow.value = emptyList()
    }
}

class FakeGetDrawerAppsUseCaseRepository : GetDrawerAppsUseCaseRepository, Purgeable {
    override val drawerApps = MutableLiveData<List<AppInfo>>()

    override fun purgeRepository() {
        drawerApps.postValue(emptyList())
    }
}

class FakeGetFavoriteAppsUseCaseRepository : GetFavoriteAppsUseCaseRepository, Purgeable {
    override val favoriteApps = MutableStateFlow<UiState<FavoriteAppsResult>>(UiState.Loading)

    override fun purgeRepository() {
        favoriteApps.value = UiState.Loading
    }
}

class FakeInstalledAppsStateRepository : InstalledAppsStateRepository, Purgeable {
    private val stateFlow = MutableStateFlow<List<AppInfo>>(emptyList())
    private var lastSuccessfulAppList: List<AppInfo> = emptyList()

    override val rawAppsFlow: StateFlow<List<AppInfo>> = stateFlow

    override fun updateApps(newApps: List<AppInfo>) {
        if (newApps.isNotEmpty()) {
            lastSuccessfulAppList = newApps
        }
        stateFlow.value = newApps
    }

    override fun getCurrentApps(): List<AppInfo> {
        val currentApps = stateFlow.value
        return if (currentApps.isNotEmpty()) {
            currentApps
        } else {
            lastSuccessfulAppList
        }
    }

    override fun purgeRepository() {
        stateFlow.value = emptyList()
        lastSuccessfulAppList = emptyList()
    }
}

class FakeAppNamesRepository : AppNamesRepository, Purgeable {
    private val customNames = mutableMapOf<String, String>()

    override suspend fun getDisplayNameForPackage(packageName: String, originalName: String): String {
        return customNames[packageName] ?: originalName
    }

    override suspend fun setCustomNameForPackage(packageName: String, customName: String): Boolean {
        customNames[packageName] = customName
        return true
    }

    override suspend fun removeCustomNameForPackage(packageName: String): Boolean {
        customNames.remove(packageName)
        return true
    }

    override suspend fun hasCustomNameForPackage(packageName: String): Boolean {
        return customNames.containsKey(packageName)
    }

    override suspend fun triggerCustomNameUpdate() {
        // In den meisten Tests muss diese Methode nichts tun.
    }

    override fun purgeRepository() {
        customNames.clear()
    }
}

class FakeScreenLockRepository : ScreenLockRepository, Purgeable {
    override val isLockingAvailableFlow = MutableStateFlow(true)
    private val lockRequest = MutableSharedFlow<Unit>()
    override val lockRequestFlow: Flow<Unit> = lockRequest
    override fun setServiceState(isAvailable: Boolean) { isLockingAvailableFlow.value = isAvailable }
    override suspend fun requestLock() { lockRequest.emit(Unit) }

    override fun purgeRepository() {
        isLockingAvailableFlow.value = true
    }
}

class FakeShortcutRepository : ShortcutRepository, Purgeable {
    override fun getShortcutsForPackage(packageName: String): List<ShortcutInfo> = emptyList()
    override fun purgeRepository() { }
}

class FakeGetOnboardingAppsUseCaseRepository : GetOnboardingAppsUseCaseRepository {
    // Wir deklarieren einen MutableStateFlow, der von außen befüllt werden kann.
    // Dieser Flow wird dann für das Interface bereitgestellt.
    val mutableOnboardingAppsFlow = MutableStateFlow<List<AppInfo>>(emptyList())

    override val onboardingAppsFlow: Flow<List<AppInfo>>
        get() = mutableOnboardingAppsFlow // Gibt den steuerbaren Flow zurück

    override fun purgeRepository() {
        mutableOnboardingAppsFlow.value = emptyList()
    }
}

// --- DIE EINZIGE, FINALE UND KORREKTE VERSION VON FakeAppUpdateSignal ---
open class FakeAppUpdateSignal : AppUpdateSignal(), Purgeable {
    var signalSentCount = 0
        private set // Zähler ist von außen nur lesbar

    /** Setzt den Zähler für den nächsten Test zurück. */
    fun reset() {
        purgeRepository()
    }

    override fun purgeRepository() {
        signalSentCount = 0
    }

    override suspend fun sendUpdateSignal() {
        signalSentCount++
        super.sendUpdateSignal() // WICHTIG: Ruft die echte Logik auf, damit der Flow das Event sendet
    }
}