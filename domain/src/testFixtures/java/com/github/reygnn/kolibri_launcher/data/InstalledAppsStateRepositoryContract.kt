package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * ============================================================================
 * INSTALLED APPS STATE REPOSITORY — CONTRACT TEST
 * ============================================================================
 *
 * Siehe [FavoritesRepositoryContract] für Hintergrund und Konventionen.
 *
 * The [InstalledAppsStateRepository] interface looks trivial (a handful of
 * methods, one StateFlow), but it carries one important pattern that **must
 * be pinned down by contract**:
 *
 * **LAST KNOWN GOOD CACHING:**
 *   `getCurrentApps()` muss bei leerem aktuellen State auf die letzte
 *   nicht-leere App-Liste zurückfallen. Begründung im
 *   [InstalledAppsStateRepositoryImpl]-KDoc: "UI never shows empty state due to
 *   transient errors". Wenn ein Refactoring diesen Fallback entfernt, würde
 *   ein PackageManager-Hiccup direkt zu einer leeren App-Liste in der UI
 *   führen — die Kernfunktion des Launchers wäre temporär kaputt.
 *
 *   Beide Implementierungen halten dieses Pattern aktuell ein. Der Contract
 *   zementiert es, sodass es niemand versehentlich wegrefactoriert.
 *
 * NICHT IM CONTRACT — bewusste Drifts:
 *
 *   1. `purgeRepository()`:
 *      - Manager: explizit No-Op (System-Daten, kein User-State).
 *      - Fake: setzt State auf leer + leert Cache (Test-Isolation).
 *      Bewusste Drift, dokumentiert im Manager-Code. Kein Contract-Test.
 *
 *   2. `rawAppsFlow.value` als StateFlow ist beim Manager über `MutableStateFlow`
 *      privat backing, beim Fake direkt exponiert. Konkretes Backing ist
 *      Implementation-Detail.
 *
 * @see FakeInstalledAppsStateRepositoryContractTest
 * @see InstalledAppsStateRepositoryImplContractTest
 * ============================================================================
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class InstalledAppsStateRepositoryContract {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    protected abstract fun createRepository(): InstalledAppsStateRepository

    private fun appInfo(name: String, pkg: String) = AppInfo(
        originalName = name,
        displayName = name,
        packageName = pkg,
        className = "$pkg.MainActivity",
        isSystemApp = false,
        isFavorite = false
    )

    private val appA = appInfo("Alpha", "com.example.a")
    private val appB = appInfo("Beta", "com.example.b")
    private val appC = appInfo("Gamma", "com.example.c")

    // ---------- Initial State ----------

    @Test
    fun `fresh repository emits empty list on rawAppsFlow`() {
        val repo = createRepository()
        assertEquals(emptyList<AppInfo>(), repo.rawAppsFlow.value)
    }

    @Test
    fun `fresh repository returns empty from getCurrentApps`() {
        val repo = createRepository()
        assertEquals(emptyList<AppInfo>(), repo.getCurrentApps())
    }

    // ---------- updateApps + rawAppsFlow ----------

    @Test
    fun `updateApps reflects in rawAppsFlow value`() {
        val repo = createRepository()
        repo.updateApps(listOf(appA, appB))
        assertEquals(listOf(appA, appB), repo.rawAppsFlow.value)
    }

    @Test
    fun `updateApps reflects in getCurrentApps`() {
        val repo = createRepository()
        repo.updateApps(listOf(appA, appB))
        assertEquals(listOf(appA, appB), repo.getCurrentApps())
    }

    @Test
    fun `updateApps preserves input order`() {
        // Input bewusst nicht-alphabetisch; Repo darf nicht umsortieren.
        val repo = createRepository()
        val input = listOf(appC, appA, appB)
        repo.updateApps(input)
        assertEquals(input, repo.getCurrentApps())
    }

    @Test
    fun `updateApps overwrites previous state`() {
        val repo = createRepository()
        repo.updateApps(listOf(appA))
        repo.updateApps(listOf(appB, appC))
        assertEquals(listOf(appB, appC), repo.getCurrentApps())
    }

    // ---------- Last Known Good — die wichtige Vertrags-Property ----------

    /**
     * Kern-Vertrag: nach updateApps(empty) gibt rawAppsFlow.value leer zurück
     * (der Caller hat ja explizit auf "leer" gesetzt), aber getCurrentApps
     * fällt auf den letzten bekannten nicht-leeren Stand zurück.
     *
     * Beide Implementierungen halten das aktuell ein. Wer beim Refactoring
     * den Fallback entfernt, fängt ihn hier ab.
     */
    @Test
    fun `getCurrentApps falls back to last non-empty list when state is empty`() {
        val repo = createRepository()
        repo.updateApps(listOf(appA, appB))
        repo.updateApps(emptyList())

        // Direkter Flow-Read sieht den expliziten "leer"-Zustand.
        assertEquals(emptyList<AppInfo>(), repo.rawAppsFlow.value)

        // getCurrentApps fällt auf Last Known Good zurück.
        assertEquals(listOf(appA, appB), repo.getCurrentApps())
    }

    @Test
    fun `getCurrentApps fallback survives multiple empty updates`() {
        val repo = createRepository()
        repo.updateApps(listOf(appA))
        repo.updateApps(emptyList())
        repo.updateApps(emptyList())
        repo.updateApps(emptyList())
        assertEquals(listOf(appA), repo.getCurrentApps())
    }

    @Test
    fun `getCurrentApps fallback updates on each non-empty update`() {
        val repo = createRepository()
        repo.updateApps(listOf(appA))
        repo.updateApps(listOf(appB, appC))
        repo.updateApps(emptyList())
        // Letzter nicht-leerer Stand war [appB, appC], nicht [appA].
        assertEquals(listOf(appB, appC), repo.getCurrentApps())
    }

    @Test
    fun `getCurrentApps with no prior non-empty state returns empty`() {
        val repo = createRepository()
        repo.updateApps(emptyList())
        // Kein Last Known Good vorhanden → leere Liste.
        assertEquals(emptyList<AppInfo>(), repo.getCurrentApps())
    }

    // ---------- hasLoadedApps — explicit "list ever loaded" signal ----------

    @Test
    fun `fresh repository has not loaded apps`() {
        // Cold-start window: no successful load has happened yet.
        val repo = createRepository()
        assertEquals(false, repo.hasLoadedApps())
    }

    @Test
    fun `hasLoadedApps is true after a non-empty update`() {
        val repo = createRepository()
        repo.updateApps(listOf(appA, appB))
        assertEquals(true, repo.hasLoadedApps())
    }

    @Test
    fun `hasLoadedApps stays true after a later empty update`() {
        // A transient empty update must not flip the signal back to "not
        // loaded" — the last-known-good state still counts as loaded.
        val repo = createRepository()
        repo.updateApps(listOf(appA))
        repo.updateApps(emptyList())
        assertEquals(true, repo.hasLoadedApps())
    }

    @Test
    fun `hasLoadedApps stays false when only empty updates arrive`() {
        val repo = createRepository()
        repo.updateApps(emptyList())
        assertEquals(false, repo.hasLoadedApps())
    }
}
