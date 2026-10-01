package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.repository.BackupRepository
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * ============================================================================
 * BACKUP REPOSITORY — CONTRACT TEST (BEWUSST DÜNN, FAKE-ONLY)
 * ============================================================================
 *
 * NO IMPL CONTRACT TEST (ADR) — canonical marker read by `./gradlew
 * checkConventions` (tools/check-contract-triple.sh). Exempts the impl half
 * only; the fake contract test stays required. The German rationale below
 * ("KEIN MANAGER-CONTRACT-TEST") is the actual argument — this line only makes
 * it greppable. BackupRepositoryImpl keeps its own impl-level coverage in
 * BackupRepositoryImplTest / …DoomsdayTest.
 *
 * Dieser Contract ist absichtlich klein. Hintergrund:
 *
 * Es gibt für [BackupRepository] genau eine Fake-Implementierung
 * (`FakeBackupRepository`), die fast komplett aus Test-Hooks besteht
 * (`shouldThrowOnExport`, `importResult`, `previewResult` etc. sind Test-
 * konfigurierbare Returns). Es gibt keine echte "Logik" im Fake, die mit
 * der Manager-Logik abdriften könnte — der Fake ist ein Stub, kein
 * Behavior-Double.
 *
 * KEIN MANAGER-CONTRACT-TEST:
 *   `BackupRepositoryImpl` (60KB Datei) macht echte JSON-Serialisierung,
 *   Datei-I/O über Android-`Uri`s, und braucht einen ganzen Stack an
 *   Dependencies (FavoritesRepositoryImpl, SettingsRepositoryImpl, WallpaperRepositoryImpl, …
 *   plus ContentResolver). Im Unit-Test-Setup ohne Robolectric +
 *   heavy mocking ist er nicht ehrlich instanziierbar — und mit
 *   Mocks würden wir Vertragstreue zwischen "Fake" und "Mock-Konstrukt"
 *   prüfen, nicht zwischen "Fake" und echtem Manager. Die Manager-
 *   Realität wird im umfangreichen `BackupRepositoryImpl*Test`-Suite separat
 *   abgedeckt (Doomsday, Security, Isolation, etc.).
 *
 * Was der Contract dennoch festschreibt — und damit Drift abfängt, falls
 * jemand später einen zweiten Fake schreibt oder den existierenden
 * vereinfacht:
 *
 *   - `exportToJson()` returnt einen Non-Null-String (auch wenn er nur
 *     ein leeres Stub-Object ist — das einzige API-Versprechen).
 *   - `previewBackup()` always returns a result: readable, or refused with the reason (2a-7b).
 *   - Methoden mit konfigurierbarem ImportResult-Output liefern den
 *     konfigurierten Wert zurück.
 *   - Default-Verhalten (kein Throw-Hook gesetzt) ist non-throwing.
 *
 * Little, but it keeps `exportToJson()` from silently becoming nullable and
 * `previewBackup()` from slipping back to a nullable result.
 *
 * @see FakeBackupRepositoryContractTest
 * ============================================================================
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class BackupRepositoryContract {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    protected abstract fun createRepository(): BackupRepository

    private val defaultOptions = ImportOptions()
    private val fakeUri = "file:///tmp/backup.json"

    @Test
    fun `exportToJson returns a non-null string`() = runTest {
        val repo = createRepository()
        assertThat(repo.exportToJson()).isNotNull()
    }

    @Test
    fun `exportToJson does not throw on default repository`() = runTest {
        val repo = createRepository()
        repo.exportToJson() // explizit ohne Assertion: das Nicht-Werfen ist die Property
    }

    @Test
    fun `importFromJson does not throw on default repository`() = runTest {
        val repo = createRepository()
        // Argument-Form bewusst nicht semantisch geprüft — der Fake interpretiert
        // den JSON-String nicht. Vertrag ist nur "wirft nicht ohne Throw-Hook".
        assertThat(repo.importFromJson("{}", defaultOptions)).isNotNull()
    }

    @Test
    fun `loadBackupFromFile does not throw on default repository`() = runTest {
        val repo = createRepository()
        assertThat(repo.loadBackupFromFile(fakeUri, defaultOptions)).isNotNull()
    }

    @Test
    fun `saveBackupToFile does not throw on default repository`() = runTest {
        val repo = createRepository()
        // Returnt Boolean — der konkrete Wert ist Implementation-Detail
        // (Fake hat exportSuccess als Hook). Vertrag ist nur "wirft nicht".
        repo.saveBackupToFile(fakeUri)
    }

    @Test
    fun `previewBackup does not throw on default repository`() = runTest {
        val repo = createRepository()
        // Returns a PreviewResult (readable or refused, never null since 2a-7b).
        assertThat(repo.previewBackup(fakeUri)).isNotNull()
    }
}
