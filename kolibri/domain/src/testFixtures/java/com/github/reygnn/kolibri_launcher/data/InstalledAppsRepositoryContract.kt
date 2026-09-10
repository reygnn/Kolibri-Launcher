package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

/**
 * ============================================================================
 * INSTALLED APPS REPOSITORY — CONTRACT TEST (BEWUSST DÜNN)
 * ============================================================================
 *
 * NO IMPL CONTRACT TEST (ADR) — canonical marker read by `./gradlew
 * checkConventions` (tools/check-contract-triple.sh). Exempts the impl half
 * only; the fake contract test stays required. The German rationale below is
 * the actual argument — this line only makes it greppable. The impl is a
 * system-API wrapper (LauncherApps / PackageManager) and carries its own
 * impl-level coverage.
 *
 * Dieser Contract ist absichtlich klein. Hintergrund:
 *
 * Es gibt zwei Fakes für das [InstalledAppsRepository]-Interface:
 *
 *   1. `FakeInstalledAppsRepository` — universeller Fake, App-Liste wird
 *      extern gesetzt (z.B. `fake.installedApps = listOf(...)`).
 *      `triggerAppsUpdate()` zählt nur Aufrufe, mutiert nichts.
 *
 *   2. `ReactiveFakeInstalledAppsRepository` — spezialisierter Fake für
 *      `CustomNamesViewModelTest`. Hat eine hartkodierte App-Liste und
 *      `triggerAppsUpdate()` befüllt den Flow mit dieser Liste, gemappt durch
 *      einen injizierten `CustomNamesRepository`.
 *
 * Die `triggerAppsUpdate()`-Semantik unterscheidet sich also fundamental
 * zwischen den Fakes — und auch der echte [InstalledAppsRepositoryImpl] macht etwas
 * Drittes (löst eine `SharedFlow`-Emission aus, die einen `flatMapLatest`-
 * PackageManager-Reload triggert). Das heißt: das beobachtbare Verhalten von
 * `triggerAppsUpdate` ist **nicht Teil des Interface-Vertrags** — es ist
 * Implementierungs-Detail. Ein Contract-Test, der konkretes Verhalten
 * fordert, würde willkürlich eine der drei Welten privilegieren.
 *
 * Was der Contract dennoch festschreibt — und damit Drift abfängt:
 *
 *   - `getInstalledApps()` gibt einen Non-Null-Flow zurück.
 *   - Eine erste Emission ist beobachtbar, ohne dass `triggerAppsUpdate()`
 *     vorher aufgerufen werden musste (kein Deadlock, kein Hang).
 *   - `triggerAppsUpdate()` wirft nicht und ist beliebig oft idempotent
 *     aufrufbar.
 *   - `purgeRepository()` wirft nicht.
 *
 * Das ist wenig — aber es bewahrt davor, dass jemand später einen dritten
 * Fake schreibt, der `getInstalledApps()` versehentlich auf `null` setzt
 * oder `purgeRepository()` mit einer `RuntimeException` versieht. Beides
 * würde alle bestehenden Konsumenten brechen.
 *
 * KEIN MANAGER-CONTRACT-TEST:
 *   `InstalledAppsRepositoryImpl` liest aus dem Android-`PackageManager`. Um ihn
 *   im Unit-Test zu instanziieren, müsste der `PackageManager` voll gemockt
 *   werden — wir würden dann Vertragstreue zwischen "Fake" und
 *   "Mock-Konstrukt" prüfen, nicht zwischen "Fake" und echtem Manager. Die
 *   Manager-Realität gehört in instrumented Tests.
 *
 * @see FakeInstalledAppsRepositoryContractTest
 * @see ReactiveFakeInstalledAppsRepositoryContractTest
 * ============================================================================
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class InstalledAppsRepositoryContract {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    protected abstract fun createRepository(): InstalledAppsRepository

    @Test
    fun `getInstalledApps returns a non-null flow`() {
        val repo = createRepository()
        assertNotNull(repo.getInstalledApps())
    }

    @Test
    fun `getInstalledApps emits an initial value without triggerAppsUpdate`() = runTest {
        // Wenn diese Zeile hängt, ist das Repository defekt: Konsumenten dürfen
        // nicht erwarten müssen, dass sie selbst `triggerAppsUpdate` rufen,
        // bevor sie subscriben können.
        val repo = createRepository()
        val initial = repo.getInstalledApps().first()
        assertNotNull(initial)
    }

    @Test
    fun `triggerAppsUpdate does not throw on fresh repository`() = runTest {
        val repo = createRepository()
        repo.triggerAppsUpdate()
    }

    @Test
    fun `triggerAppsUpdate can be called multiple times without throwing`() = runTest {
        val repo = createRepository()
        repo.triggerAppsUpdate()
        repo.triggerAppsUpdate()
        repo.triggerAppsUpdate()
    }

    @Test
    fun `purgeRepository does not throw on fresh repository`() = runTest {
        val repo = createRepository()
        repo.purgeRepository()
    }

    @Test
    fun `purgeRepository can be called multiple times without throwing`() = runTest {
        val repo = createRepository()
        repo.purgeRepository()
        repo.purgeRepository()
    }

    @Test
    fun `purgeRepository followed by getInstalledApps still works`() = runTest {
        // Stellt sicher, dass purge das Repository nicht in einen kaputten
        // Zustand bringt, in dem nachfolgende Reads scheitern oder hängen.
        val repo = createRepository()
        repo.purgeRepository()
        val list = repo.getInstalledApps().first()
        assertNotNull(list)
    }

    @Test
    fun `getInstalledApps returns the same flow instance across calls`() {
        // Beide aktuellen Fakes (und der echte Manager) geben pro Repository-
        // Instanz EINEN stabilen Flow zurück, nicht jedes Mal einen neuen
        // Builder. Das ist wichtig, damit Konsumenten `getInstalledApps()`
        // bedenkenlos in `combine`/`flatMapLatest`-Pipelines hängen können,
        // ohne mit jedem Pipeline-Reset eine neue Subscription auf den
        // Upstream auszulösen.
        val repo = createRepository()
        val flow1 = repo.getInstalledApps()
        val flow2 = repo.getInstalledApps()
        assertEquals(flow1, flow2)
    }
}
