package com.github.reygnn.kolibri_launcher

// Wichtige Imports: Context wird gemockt, Robolectric ist weg
import android.content.Context
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.mock // Import für Mockito
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Unit-Test für den BackupManager.
 *
 * HINWEIS: Dieser Test verwendet KEIN Robolectric.
 * 1. Er verwendet die 'MainDispatcherRule' für Coroutinen-Synchronisation.
 * 2. Er verwendet einen 'FakeAppDataSource', um "installierte" Apps zu simulieren.
 * 3. Er 'mockt' den Android-Context, da er für die reine Import/Export-Logik
 * nicht benötigt wird (nur für das spätere Schreiben/Lesen von Dateien).
 */
@ExperimentalCoroutinesApi
class BackupManagerTest {

    // Dieselbe Rule wie im BackupViewModelTest
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // Mocks und Fakes
    private lateinit var mockContext: Context
    private lateinit var fakeFavoritesRepo: FakeFavoritesRepository
    private lateinit var fakeFavoritesOrderRepo: FakeFavoritesOrderRepository
    private lateinit var fakeVisibilityRepo: FakeAppVisibilityRepository
    private lateinit var fakeNamesRepo: FakeAppNamesRepository
    private lateinit var fakeAppDataSource: FakeAppDataSource
    private lateinit var backupManager: BackupManager

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    @Before
    fun setUp() {
        // Erstelle Fakes und einen Mock-Context
        mockContext = mock(Context::class.java)
        fakeAppDataSource = FakeAppDataSource()
        fakeFavoritesRepo = FakeFavoritesRepository()
        fakeFavoritesOrderRepo = FakeFavoritesOrderRepository()
        fakeVisibilityRepo = FakeAppVisibilityRepository()
        fakeNamesRepo = FakeAppNamesRepository()

        // Initialisiere den BackupManager mit den Fakes
        backupManager = BackupManager(
            fakeFavoritesRepo,
            fakeFavoritesOrderRepo,
            fakeVisibilityRepo,
            fakeNamesRepo,
            fakeAppDataSource, // Übergib den neuen Fake
            mockContext        // Übergib den Mock-Context
        )
    }

    // Kein @After tearDown() nötig, wird von der MainDispatcherRule erledigt

    // ========== EXPORT TESTS ==========

    @Test
    fun `exportToJson - with empty data - creates valid backup`() = runTest {
        val jsonString = backupManager.exportToJson()

        val backup = json.decodeFromString<BackupData>(jsonString)

        assertThat(backup.version).isEqualTo("1.0.0")
        assertThat(backup.timestamp).isGreaterThan(0L)
        assertThat(backup.settings.favoriteComponents).isEmpty()
        assertThat(backup.settings.favoritesOrder).isEmpty()
        assertThat(backup.settings.hiddenComponents).isEmpty()
        assertThat(backup.settings.customAppNames).isEmpty()
    }

    @Test
    fun `exportToJson - with favorites - includes favorites in backup`() = runTest {
        fakeFavoritesRepo.favorites = setOf(
            "com.app1/.MainActivity",
            "com.app2/.MainActivity"
        )

        val jsonString = backupManager.exportToJson()
        val backup = json.decodeFromString<BackupData>(jsonString)

        assertThat(backup.settings.favoriteComponents).hasSize(2)
        assertThat(backup.settings.favoriteComponents).containsExactly(
            "com.app1/.MainActivity",
            "com.app2/.MainActivity"
        )
    }

    @Test
    fun `exportToJson - with order - includes order in backup`() = runTest {
        fakeFavoritesOrderRepo.order = listOf(
            "com.app2/.MainActivity",
            "com.app1/.MainActivity"
        )

        val jsonString = backupManager.exportToJson()
        val backup = json.decodeFromString<BackupData>(jsonString)

        assertThat(backup.settings.favoritesOrder).containsExactly(
            "com.app2/.MainActivity",
            "com.app1/.MainActivity"
        ).inOrder()
    }

    @Test
    fun `exportToJson - with hidden apps - includes hidden in backup`() = runTest {
        fakeVisibilityRepo.hiddenApps = setOf(
            "com.app3/.MainActivity",
            "com.app4/.MainActivity"
        )

        val jsonString = backupManager.exportToJson()
        val backup = json.decodeFromString<BackupData>(jsonString)

        assertThat(backup.settings.hiddenComponents).hasSize(2)
        assertThat(backup.settings.hiddenComponents).containsExactly(
            "com.app3/.MainActivity",
            "com.app4/.MainActivity"
        )
    }

    @Test
    fun `exportToJson - with custom names - includes names in backup`() = runTest {
        fakeNamesRepo.setCustomNamesInBatch(mapOf(
            "com.app1" to "Custom App 1",
            "com.app2" to "Custom App 2"
        ))

        val jsonString = backupManager.exportToJson()
        val backup = json.decodeFromString<BackupData>(jsonString)

        assertThat(backup.settings.customAppNames).hasSize(2)
        assertThat(backup.settings.customAppNames).containsEntry("com.app1", "Custom App 1")
        assertThat(backup.settings.customAppNames).containsEntry("com.app2", "Custom App 2")
    }

    @Test
    fun `exportToJson - with all data - creates complete backup`() = runTest {
        fakeFavoritesRepo.favorites = setOf("com.app1/.MainActivity")
        fakeFavoritesOrderRepo.order = listOf("com.app1/.MainActivity")
        fakeVisibilityRepo.hiddenApps = setOf("com.app2/.MainActivity")
        fakeNamesRepo.setCustomNamesInBatch(mapOf("com.app1" to "My App"))

        val jsonString = backupManager.exportToJson()
        val backup = json.decodeFromString<BackupData>(jsonString)

        assertThat(backup.settings.favoriteComponents).hasSize(1)
        assertThat(backup.settings.favoritesOrder).hasSize(1)
        assertThat(backup.settings.hiddenComponents).hasSize(1)
        assertThat(backup.settings.customAppNames).hasSize(1)
    }

    @Test
    fun `exportToJson - sets timestamp explicitly`() = runTest {
        val beforeExport = System.currentTimeMillis()

        val jsonString = backupManager.exportToJson()
        val backup = json.decodeFromString<BackupData>(jsonString)

        val afterExport = System.currentTimeMillis()

        assertThat(backup.timestamp).isAtLeast(beforeExport)
        assertThat(backup.timestamp).isAtMost(afterExport)
        assertThat(backup.timestamp).isGreaterThan(0L)
    }

    // ========== IMPORT TESTS - SELECTIVE ==========

    @Test
    fun `importFromJson - with importNothing option - returns error`() = runTest {
        // "Installierte" Apps definieren (für diesen Test irrelevant, aber gute Praxis)
        fakeAppDataSource.installed = emptySet()

        val backup = createTestBackup()
        val jsonString = json.encodeToString(backup)

        val options = ImportOptions(
            importFavorites = false,
            importOrder = false,
            importHiddenApps = false,
            importCustomNames = false
        )

        val result = backupManager.importFromJson(jsonString, options)

        assertThat(result).isInstanceOf(ImportResult.Error::class.java)
    }

    @Test
    fun `importFromJson - only favorites - imports only favorites`() = runTest {
        // 1. Definiere, welche Apps "installiert" sind
        fakeAppDataSource.installed = setOf(
            "com.app1/.MainActivity",
            "com.app2/.MainActivity"
        )

        // 2. Erstelle Backup
        val backup = createTestBackup(
            favorites = setOf("com.app1/.MainActivity"),
            order = listOf("com.app1/.MainActivity"),
            hidden = setOf("com.app2/.MainActivity"),
            names = mapOf("com.app1" to "Name")
        )
        val jsonString = json.encodeToString(backup)

        val options = ImportOptions(
            importFavorites = true,
            importOrder = false,
            importHiddenApps = false,
            importCustomNames = false
        )

        // 3. Führe Import aus
        val result = backupManager.importFromJson(jsonString, options)

        // 4. Prüfe Ergebnis
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(fakeFavoritesRepo.favorites).hasSize(1) // <-- ERFOLG
        assertThat(fakeFavoritesOrderRepo.order).isEmpty() // Not imported
        assertThat(fakeVisibilityRepo.hiddenApps).isEmpty() // Not imported
        assertThat(fakeNamesRepo.getAllCustomNames()).isEmpty() // Not imported
    }

    @Test
    fun `importFromJson - only order - imports only order`() = runTest {
        // 1. Setze App-Status: "com.app1" ist installiert UND favorisiert
        fakeFavoritesRepo.favorites = setOf("com.app1/.MainActivity")
        fakeAppDataSource.installed = setOf("com.app1/.MainActivity")

        // 2. Erstelle Backup
        val backup = createTestBackup(
            favorites = setOf("com.app1/.MainActivity"),
            order = listOf("com.app1/.MainActivity")
        )
        val jsonString = json.encodeToString(backup)

        val options = ImportOptions(
            importFavorites = false,
            importOrder = true,
            importHiddenApps = false,
            importCustomNames = false
        )

        // 3. Führe Import aus
        val result = backupManager.importFromJson(jsonString, options)

        // 4. Prüfe Ergebnis
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(fakeFavoritesOrderRepo.order).hasSize(1) // <-- ERFOLG
    }

    @Test
    fun `importFromJson - only hidden apps - imports only hidden`() = runTest {
        // 1. "com.app1" ist installiert
        fakeAppDataSource.installed = setOf("com.app1/.MainActivity")

        // 2. Backup
        val backup = createTestBackup(
            hidden = setOf("com.app1/.MainActivity")
        )
        val jsonString = json.encodeToString(backup)
        val options = ImportOptions(
            importFavorites = false,
            importOrder = false,
            importHiddenApps = true,
            importCustomNames = false
        )

        // 3. Act
        val result = backupManager.importFromJson(jsonString, options)

        // 4. Assert
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(fakeVisibilityRepo.hiddenApps).hasSize(1) // <-- ERFOLG
    }

    @Test
    fun `importFromJson - only custom names - imports only names`() = runTest {
        // 1. "com.app1" ist installiert (als Paketname)
        fakeAppDataSource.installed = setOf("com.app1/.MainActivity")

        // 2. Backup
        val backup = createTestBackup(
            names = mapOf("com.app1" to "Custom")
        )
        val jsonString = json.encodeToString(backup)
        val options = ImportOptions(
            importFavorites = false,
            importOrder = false,
            importHiddenApps = false,
            importCustomNames = true
        )

        // 3. Act
        val result = backupManager.importFromJson(jsonString, options)

        // 4. Assert
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(fakeNamesRepo.getAllCustomNames()).hasSize(1) // <-- ERFOLG
        assertThat(fakeNamesRepo.batchSetCalled).isTrue()
    }

    @Test
    fun `importFromJson - all options - imports everything`() = runTest {
        // 1. Definiere "installierte" Apps
        fakeAppDataSource.installed = setOf(
            "com.app1/.MainActivity",
            "com.app2/.MainActivity"
        )

        // 2. Erstelle Backup
        val backup = createTestBackup(
            favorites = setOf("com.app1/.MainActivity"),
            order = listOf("com.app1/.MainActivity"),
            hidden = setOf("com.app2/.MainActivity"),
            names = mapOf("com.app1" to "Name")
        )
        val jsonString = json.encodeToString(backup)

        val options = ImportOptions(
            importFavorites = true,
            importOrder = true,
            importHiddenApps = true,
            importCustomNames = true
        )

        // 3. Act
        val result = backupManager.importFromJson(jsonString, options)

        // 4. Assert
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(fakeFavoritesRepo.favorites).hasSize(1)
        assertThat(fakeFavoritesOrderRepo.order).hasSize(1)
        assertThat(fakeVisibilityRepo.hiddenApps).hasSize(1)
        assertThat(fakeNamesRepo.getAllCustomNames()).hasSize(1)
    }

    // ========== VALIDATION TESTS ==========

    @Test
    fun `importFromJson - unsupported version - returns UnsupportedVersion`() = runTest {
        val backup = createTestBackup(version = "2.0.0")
        val jsonString = json.encodeToString(backup)

        val options = ImportOptions()
        val result = backupManager.importFromJson(jsonString, options)

        assertThat(result).isInstanceOf(ImportResult.UnsupportedVersion::class.java)
        assertThat((result as ImportResult.UnsupportedVersion).version).isEqualTo("2.0.0")
    }

    @Test
    fun `importFromJson - invalid JSON - returns InvalidFormat`() = runTest {
        val invalidJson = "{ invalid json }"

        val options = ImportOptions()
        val result = backupManager.importFromJson(invalidJson, options)

        assertThat(result).isInstanceOf(ImportResult.InvalidFormat::class.java)
    }

    @Test
    fun `importFromJson - exceeds package limit - returns LimitExceeded`() = runTest {
        // 1. Simuliere 10 installierte Apps
        val appNames = (1..10).map { "com.app$it/.MainActivity" }.toSet()
        fakeAppDataSource.installed = appNames

        // 2. Erstelle Backup mit 10 Favoriten
        val backup = createTestBackup(favorites = appNames)
        val jsonString = json.encodeToString(backup)

        val options = ImportOptions(importFavorites = true)

        // 3. Act
        val result = backupManager.importFromJson(jsonString, options)

        // 4. Assert
        assertThat(result).isInstanceOf(ImportResult.LimitExceeded::class.java)
        val limitExceeded = result as ImportResult.LimitExceeded
        assertThat(limitExceeded.packageCount).isEqualTo(10)
        assertThat(limitExceeded.limit).isEqualTo(AppConstants.MAX_FAVORITES_ON_HOME)
    }

    @Test
    fun `importFromJson - filters non-installed apps`() = runTest {
        // 1. Simuliere NUR "com.app1" ist installiert
        fakeAppDataSource.installed = setOf("com.app1/.MainActivity")

        // 2. Backup enthält "app1" und "nonexistent"
        val backup = createTestBackup(
            favorites = setOf(
                "com.app1/.MainActivity",  // Installed
                "com.nonexistent/.MainActivity"  // Not installed
            )
        )
        val jsonString = json.encodeToString(backup)
        val options = ImportOptions(importFavorites = true)

        // 3. Act
        val result = backupManager.importFromJson(jsonString, options)

        // 4. Assert
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        val success = result as ImportResult.Success
        assertThat(success.importedCount).isEqualTo(1) // nur app1
        assertThat(success.skippedCount).isEqualTo(1) // nonexistent
        assertThat(success.missingApps).contains("com.nonexistent/.MainActivity")
        assertThat(fakeFavoritesRepo.favorites).hasSize(1)
        assertThat(fakeFavoritesRepo.favorites).contains("com.app1/.MainActivity")
    }

    @Test
    fun `importFromJson - filters order by current favorites`() = runTest {
        // 1. Status: "app1" ist favorisiert, "app1" und "app2" sind installiert
        fakeFavoritesRepo.favorites = setOf("com.app1/.MainActivity")
        fakeAppDataSource.installed = setOf(
            "com.app1/.MainActivity",
            "com.app2/.MainActivity"
        )

        // 2. Backup
        val backup = createTestBackup(
            order = listOf(
                "com.app1/.MainActivity",
                "com.app2/.MainActivity"  // Nicht in Favoriten
            )
        )
        val jsonString = json.encodeToString(backup)
        val options = ImportOptions(
            importFavorites = false,
            importOrder = true,
            importHiddenApps = false,
            importCustomNames = false
        )

        // 3. Act
        val result = backupManager.importFromJson(jsonString, options)

        // 4. Assert
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(fakeFavoritesOrderRepo.order).containsExactly("com.app1/.MainActivity")
    }

    @Test
    fun `importFromJson - filters hidden apps by installed`() = runTest {
        // 1. Nur "app1" ist installiert
        fakeAppDataSource.installed = setOf("com.app1/.MainActivity")

        // 2. Backup
        val backup = createTestBackup(
            hidden = setOf(
                "com.app1/.MainActivity",  // Installed
                "com.nonexistent/.MainActivity"  // Not installed
            )
        )
        val jsonString = json.encodeToString(backup)
        val options = ImportOptions(importHiddenApps = true)

        // 3. Act
        val result = backupManager.importFromJson(jsonString, options)

        // 4. Assert
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(fakeVisibilityRepo.hiddenApps).hasSize(1)
        assertThat(fakeVisibilityRepo.hiddenApps).contains("com.app1/.MainActivity")
    }

    @Test
    fun `importFromJson - filters custom names by installed packages`() = runTest {
        // 1. Nur "com.app1" ist installiert
        fakeAppDataSource.installed = setOf("com.app1/.MainActivity")

        // 2. Backup
        val backup = createTestBackup(
            names = mapOf(
                "com.app1" to "Name 1",  // Installed
                "com.nonexistent" to "Name 2"  // Not installed
            )
        )
        val jsonString = json.encodeToString(backup)
        val options = ImportOptions(importCustomNames = true)

        // 3. Act
        val result = backupManager.importFromJson(jsonString, options)

        // 4. Assert
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        val importedNames = fakeNamesRepo.getAllCustomNames()
        assertThat(importedNames).hasSize(1)
        assertThat(importedNames).containsKey("com.app1")
    }

    // ========== PREVIEW TESTS ==========

    @Test
    fun `previewBackup - returns correct counts`() = runTest {
        // Preview-Tests testen nicht die Import-Logik, daher kein AppDataSource nötig
        val backup = createTestBackup(
            favorites = setOf("com.app1/.MainActivity", "com.app2/.MainActivity"),
            order = listOf("com.app1/.MainActivity"),
            hidden = setOf("com.app3/.MainActivity"),
            names = mapOf("com.app1" to "Name")
        )
        val jsonString = json.encodeToString(backup)

        // Teste die JSON-Deserialisierung direkt
        val parsedBackup = json.decodeFromString<BackupData>(jsonString)

        assertThat(parsedBackup.settings.favoriteComponents.size).isEqualTo(2)
        assertThat(parsedBackup.settings.favoritesOrder.size).isEqualTo(1)
        assertThat(parsedBackup.settings.hiddenComponents.size).isEqualTo(1)
        assertThat(parsedBackup.settings.customAppNames.size).isEqualTo(1)
    }

    // ========== EDGE CASES ==========

    @Test
    fun `importFromJson - empty backup - succeeds`() = runTest {
        fakeAppDataSource.installed = emptySet()
        val backup = createTestBackup()
        val jsonString = json.encodeToString(backup)

        val options = ImportOptions()
        val result = backupManager.importFromJson(jsonString, options)

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        val success = result as ImportResult.Success
        assertThat(success.importedCount).isEqualTo(0)
        assertThat(success.skippedCount).isEqualTo(0)
    }

    @Test
    fun `importFromJson - custom names with empty map - succeeds without trigger`() = runTest {
        fakeAppDataSource.installed = emptySet()
        val backup = createTestBackup(names = emptyMap())
        val jsonString = json.encodeToString(backup)

        val options = ImportOptions(importCustomNames = true)
        val result = backupManager.importFromJson(jsonString, options)

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(fakeNamesRepo.batchSetCalled).isFalse()
    }

    @Test
    fun `importFromJson - multiple packages from same app - within limit`() = runTest {
        // 1. Alle 3 Komponenten sind installiert
        fakeAppDataSource.installed = setOf(
            "com.app1/.MainActivity",
            "com.app1/.SecondActivity",
            "com.app1/.ThirdActivity"
        )

        // 2. Backup
        val backup = createTestBackup(
            favorites = setOf(
                "com.app1/.MainActivity",
                "com.app1/.SecondActivity",
                "com.app1/.ThirdActivity"
            )
        )
        val jsonString = json.encodeToString(backup)
        val options = ImportOptions(importFavorites = true)

        // 3. Act
        val result = backupManager.importFromJson(jsonString, options)

        // 4. Assert
        // Der Limit-Check (uniquePackages.size) sollte 1 sein, also < 8
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(fakeFavoritesRepo.favorites).hasSize(3) // Alle 3 werden importiert
    }

    // ========== HELPER METHODS ==========

    private fun createTestBackup(
        version: String = "1.0.0",
        timestamp: Long = System.currentTimeMillis(),
        favorites: Set<String> = emptySet(),
        order: List<String> = emptyList(),
        hidden: Set<String> = emptySet(),
        names: Map<String, String> = emptyMap()
    ): BackupData {
        return BackupData(
            version = version,
            timestamp = timestamp,
            appVersion = "1.0.0",
            settings = LauncherSettings(
                favoriteComponents = favorites,
                favoritesOrder = order,
                hiddenComponents = hidden,
                customAppNames = names
            )
        )
    }
}

// ========== FAKE REPOSITORIES (Unverändert) ==========

class FakeFavoritesRepository : FavoritesRepository {
    // 1. Mache den Flow zur "Single Source of Truth"
    private val flow = MutableStateFlow(setOf<String>())

    // 2. Leite die 'favorites'-Variable vom Flow ab
    var favorites: Set<String>
        get() = flow.value
        set(value) {
            flow.value = value
        }

    override val favoriteComponentsFlow = flow

    // Der Rest der Methoden funktioniert jetzt automatisch korrekt
    override suspend fun isFavoriteComponent(componentName: String?) = componentName in favorites
    override suspend fun cleanupFavoriteComponents(installedComponentNames: List<String>) {}
    override suspend fun toggleFavoriteComponent(componentName: String) = true
    override suspend fun addFavoriteComponent(componentName: String) = true
    override suspend fun removeFavoriteComponent(componentName: String) = true
    override suspend fun saveFavoriteComponents(componentNames: List<String>) {
        favorites = componentNames.toSet() // Ruft jetzt den Setter auf
    }
    override fun purgeRepository() {
        favorites = emptySet() // Ruft jetzt den Setter auf
    }
}

class FakeFavoritesOrderRepository : FavoritesOrderRepository {
    // 1. Mache den Flow zur "Single Source of Truth"
    private val flow = MutableStateFlow(listOf<String>())

    // 2. Leite die 'order'-Variable vom Flow ab
    var order: List<String>
        get() = flow.value
        set(value) {
            flow.value = value
        }

    override val favoriteComponentsOrderFlow = flow

    // Der Rest der Methoden
    override suspend fun sortFavoriteComponents(favoriteApps: List<AppInfo>, order: List<String>) = favoriteApps
    override suspend fun saveOrder(orderedComponentNames: List<String>): Boolean {
        order = orderedComponentNames // Ruft jetzt den Setter auf
        return true
    }
    override fun purgeRepository() {
        order = emptyList() // Ruft jetzt den Setter auf
    }
}

// In BackupManagerTest.kt

class FakeAppVisibilityRepository : AppVisibilityRepository {
    // 1. Mache den Flow zur "Single Source of Truth"
    private val flow = MutableStateFlow(setOf<String>())

    // 2. Leite die 'hiddenApps'-Variable vom Flow ab
    var hiddenApps: Set<String>
        get() = flow.value
        set(value) {
            flow.value = value
        }

    override val hiddenAppsFlow = flow

    // Der Rest der Methoden
    override suspend fun isComponentHidden(componentName: String?) = componentName in hiddenApps
    override suspend fun hideComponent(componentName: String?) = true
    override suspend fun showComponent(componentName: String?) = true
    override suspend fun updateComponentVisibilities(
        componentsToHide: Set<String>,
        componentsToShow: Set<String>
    ) {
        hiddenApps = (hiddenApps + componentsToHide) - componentsToShow // Ruft Setter auf
    }
    override fun purgeRepository() {
        hiddenApps = emptySet() // Ruft Setter auf
    }
}

// (FakeAppNamesRepository ist in seiner eigenen Datei, wird hier nicht benötigt)

/**
 * Fake-Implementierung der AppDataSource für Tests.
 * Ermöglicht die Kontrolle darüber, welche Apps als "installiert" gelten.
 */
class FakeAppDataSource : AppDataSource {
    var installed = setOf<String>()

    override suspend fun getInstalledComponents(): Set<String> {
        return installed
    }
}

// ========== ANDERE FAKES (falls benötigt) ==========

class FakeContentResolver(private val content: ByteArray) {
    fun openInputStream(uri: Uri) = ByteArrayInputStream(content)
    fun openOutputStream(uri: Uri) = ByteArrayOutputStream()
}