package com.github.reygnn.kolibri_launcher.data

import app.cash.turbine.test
import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.AppUsageRepository
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * ============================================================================
 * APP USAGE REPOSITORY — CONTRACT TEST (BEWUSST EINGESCHRÄNKT)
 * ============================================================================
 *
 * Siehe [FavoritesRepositoryContract] für Hintergrund und Konventionen.
 *
 * WICHTIG — was der Contract NICHT prüft, und warum:
 *
 *   [AppUsageRepository.sortAppsByTimeWeightedUsage] ist im Manager ein
 *   exponentieller-Decay-Algorithmus (`score = e^(-λ × Δt)`), der auf
 *   `System.currentTimeMillis()` basiert. Der Fake ignoriert die Sortier-
 *   Semantik komplett und gibt die Eingabeliste **unverändert** zurück.
 *   Das ist absichtlich so: der Fake ist ein Stub, kein Behavior-Double —
 *   Tests die Sortier-Reihenfolge brauchen, müssen den Manager direkt mit
 *   MockK kontrollieren.
 *
 *   Damit der Contract sinnvoll bleibt, prüft er für `sort`:
 *     - die Ausgabe enthält dieselben Apps wie die Eingabe (keine verloren,
 *       keine erfunden) — als **Multiset**, also Reihenfolge-unabhängig
 *     - leere Eingabe → leere Ausgabe
 *
 *   NICHT geprüft: konkrete Reihenfolge bei mehr als einer App, weil der Fake
 *   das nicht implementiert (und es im Manager zeitabhängig ist).
 *
 *   Wenn `AppUsageRepositoryImpl` jemals eine `Clock`-Abstraktion bekommt, kann
 *   dieser Contract um echte Sortier-Tests erweitert werden — der Fake
 *   müsste dann allerdings auch nachgerüstet werden.
 *
 * Was der Contract DOCH prüft — der Pfad, den beide Implementierungen wirklich
 * stützen und den auch der existierende `CheckAppUsageUseCaseTest` benutzt:
 *
 *   - `record + has + remove`-Roundtrip auf `packageName`-Ebene
 *   - blank/null-Handling (no-op auf record/remove, false auf has)
 *   - `purgeRepository` löscht alle Usage-Daten
 *
 * @see FakeAppUsageRepositoryContractTest
 * @see AppUsageRepositoryImplContractTest
 * ============================================================================
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class AppUsageRepositoryContract {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    protected abstract fun createRepository(): AppUsageRepository

    private val pkgA = "com.example.a"
    private val pkgB = "com.example.b"
    private val pkgC = "com.example.c"

    private fun appInfo(displayName: String, packageName: String) = AppInfo(
        originalName = displayName,
        displayName = displayName,
        packageName = packageName,
        className = "$packageName.MainActivity",
    )

    // ---------- Fresh state ----------

    @Test
    fun `fresh repository reports no usage data for any package`() = runTest {
        val repo = createRepository()
        assertThat(repo.hasUsageDataForPackage(pkgA)).isFalse()
    }

    // ---------- recordPackageLaunch + hasUsageDataForPackage ----------

    @Test
    fun `recordPackageLaunch makes hasUsageDataForPackage return true`() = runTest {
        val repo = createRepository()
        repo.recordPackageLaunch(pkgA)
        assertThat(repo.hasUsageDataForPackage(pkgA)).isTrue()
    }

    @Test
    fun `recordPackageLaunch on one package does not affect another`() = runTest {
        val repo = createRepository()
        repo.recordPackageLaunch(pkgA)
        assertThat(repo.hasUsageDataForPackage(pkgB)).isFalse()
    }

    @Test
    fun `recordPackageLaunch can be called multiple times for same package`() = runTest {
        val repo = createRepository()
        repo.recordPackageLaunch(pkgA)
        repo.recordPackageLaunch(pkgA)
        repo.recordPackageLaunch(pkgA)
        assertThat(repo.hasUsageDataForPackage(pkgA)).isTrue()
    }

    // ---------- blank/null handling ----------

    @Test
    fun `recordPackageLaunch with null is a no-op`() = runTest {
        val repo = createRepository()
        repo.recordPackageLaunch(null)
        assertThat(repo.hasUsageDataForPackage(pkgA)).isFalse()
    }

    @Test
    fun `recordPackageLaunch with blank is a no-op`() = runTest {
        val repo = createRepository()
        repo.recordPackageLaunch("")
        repo.recordPackageLaunch("   ")
        // Wir können nicht direkt nach "" / "   " fragen (Repos lehnen die ja
        // auch beim has-Aufruf ab). Indirekt: nichts anderes wurde recorded.
        assertThat(repo.hasUsageDataForPackage(pkgA)).isFalse()
    }

    @Test
    fun `hasUsageDataForPackage with null returns false`() = runTest {
        val repo = createRepository()
        repo.recordPackageLaunch(pkgA)
        assertThat(repo.hasUsageDataForPackage(null)).isFalse()
    }

    @Test
    fun `hasUsageDataForPackage with blank returns false`() = runTest {
        val repo = createRepository()
        repo.recordPackageLaunch(pkgA)
        assertThat(repo.hasUsageDataForPackage("")).isFalse()
        assertThat(repo.hasUsageDataForPackage("   ")).isFalse()
    }

    @Test
    fun `removeUsageDataForPackage with null is a no-op`() = runTest {
        val repo = createRepository()
        repo.recordPackageLaunch(pkgA)
        repo.removeUsageDataForPackage(null)
        assertThat(repo.hasUsageDataForPackage(pkgA)).isTrue()
    }

    @Test
    fun `removeUsageDataForPackage with blank is a no-op`() = runTest {
        val repo = createRepository()
        repo.recordPackageLaunch(pkgA)
        repo.removeUsageDataForPackage("")
        repo.removeUsageDataForPackage("   ")
        assertThat(repo.hasUsageDataForPackage(pkgA)).isTrue()
    }

    // ---------- removeUsageDataForPackage ----------

    @Test
    fun `removeUsageDataForPackage removes recorded data`() = runTest {
        val repo = createRepository()
        repo.recordPackageLaunch(pkgA)
        repo.removeUsageDataForPackage(pkgA)
        assertThat(repo.hasUsageDataForPackage(pkgA)).isFalse()
    }

    @Test
    fun `removeUsageDataForPackage on one package does not affect another`() = runTest {
        val repo = createRepository()
        repo.recordPackageLaunch(pkgA)
        repo.recordPackageLaunch(pkgB)
        repo.removeUsageDataForPackage(pkgA)
        assertThat(repo.hasUsageDataForPackage(pkgA)).isFalse()
        assertThat(repo.hasUsageDataForPackage(pkgB)).isTrue()
    }

    @Test
    fun `removeUsageDataForPackage on never-recorded package is safe`() = runTest {
        val repo = createRepository()
        repo.removeUsageDataForPackage(pkgA)
        assertThat(repo.hasUsageDataForPackage(pkgA)).isFalse()
    }

    // ---------- sortAppsByTimeWeightedUsage: nur Multiset-Properties ----------

    @Test
    fun `sortAppsByTimeWeightedUsage with empty input returns empty`() = runTest {
        val repo = createRepository()
        assertThat(repo.sortAppsByTimeWeightedUsage(emptyList(), emptyMap())).isEqualTo(emptyList<AppInfo>())
    }

    @Test
    fun `sortAppsByTimeWeightedUsage with single app returns same single app`() = runTest {
        val repo = createRepository()
        val input = listOf(appInfo("Alpha", pkgA))
        assertThat(repo.sortAppsByTimeWeightedUsage(input, emptyMap())).isEqualTo(input)
    }

    /**
     * Der wichtigste Property-Test für sort: die Ausgabe muss exakt dieselben
     * Apps enthalten wie die Eingabe — keine verloren, keine dazu erfunden.
     * Nur als **Multiset** (Reihenfolge-unabhängig), weil die konkrete
     * Reihenfolge implementierungsabhängig ist (siehe Klassen-KDoc).
     */
    @Test
    fun `sortAppsByTimeWeightedUsage preserves the set of apps`() = runTest {
        val repo = createRepository()
        val input = listOf(appInfo("Alpha", pkgA), appInfo("Beta", pkgB), appInfo("Gamma", pkgC))
        val output = repo.sortAppsByTimeWeightedUsage(input, emptyMap())
        assertThat(output.size).isEqualTo(input.size)
        assertThat(output.toSet()).isEqualTo(input.toSet())
    }

    @Test
    fun `sortAppsByTimeWeightedUsage works without any recorded usage`() = runTest {
        // Wichtig: keine Voraussetzung an "muss vorher record gerufen werden".
        // Beide Implementierungen müssen mit "no usage history" klarkommen.
        val repo = createRepository()
        val input = listOf(appInfo("Alpha", pkgA), appInfo("Beta", pkgB))
        val output = repo.sortAppsByTimeWeightedUsage(input, emptyMap())
        assertThat(output.toSet()).isEqualTo(input.toSet())
    }

    @Test
    fun `sortAppsByTimeWeightedUsage works after recording usage`() = runTest {
        val repo = createRepository()
        repo.recordPackageLaunch(pkgA)
        repo.recordPackageLaunch(pkgB)
        val input = listOf(appInfo("Alpha", pkgA), appInfo("Beta", pkgB), appInfo("Gamma", pkgC))
        val output = repo.sortAppsByTimeWeightedUsage(input, emptyMap())
        assertThat(output.toSet()).isEqualTo(input.toSet())
    }

    // ---------- getRecentlyLaunchedPackages ----------
    //
    // Strict recency ORDER is timing-dependent (System.currentTimeMillis on
    // the impl → ties within a fast test), same reason the sort order isn't
    // contract-tested. So here only the timing-independent properties both
    // implementations guarantee are checked; deterministic order is pinned in
    // GetRecentAppsUseCaseTest against the fake.

    @Test
    fun `getRecentlyLaunchedPackages returns the recorded packages`() = runTest {
        val repo = createRepository()
        repo.recordPackageLaunch(pkgA)
        repo.recordPackageLaunch(pkgB)
        assertThat(repo.getRecentlyLaunchedPackages(10).toSet()).isEqualTo(setOf(pkgA, pkgB))
    }

    @Test
    fun `getRecentlyLaunchedPackages lists each package once despite repeat launches`() = runTest {
        val repo = createRepository()
        repo.recordPackageLaunch(pkgA)
        repo.recordPackageLaunch(pkgA)
        repo.recordPackageLaunch(pkgB)
        val recent = repo.getRecentlyLaunchedPackages(10)
        assertThat(recent.size).isEqualTo(2)
        assertThat(recent.toSet()).isEqualTo(setOf(pkgA, pkgB))
    }

    @Test
    fun `getRecentlyLaunchedPackages caps at the limit`() = runTest {
        val repo = createRepository()
        repo.recordPackageLaunch(pkgA)
        repo.recordPackageLaunch(pkgB)
        repo.recordPackageLaunch(pkgC)
        assertThat(repo.getRecentlyLaunchedPackages(2).size).isEqualTo(2)
    }

    @Test
    fun `getRecentlyLaunchedPackages with non-positive limit returns empty`() = runTest {
        val repo = createRepository()
        repo.recordPackageLaunch(pkgA)
        assertThat(repo.getRecentlyLaunchedPackages(0).isEmpty()).isTrue()
        assertThat(repo.getRecentlyLaunchedPackages(-1).isEmpty()).isTrue()
    }

    @Test
    fun `getRecentlyLaunchedPackages on fresh repository is empty`() = runTest {
        val repo = createRepository()
        assertThat(repo.getRecentlyLaunchedPackages(10).isEmpty()).isTrue()
    }

    @Test
    fun `getRecentlyLaunchedPackages excludes a removed package`() = runTest {
        val repo = createRepository()
        repo.recordPackageLaunch(pkgA)
        repo.recordPackageLaunch(pkgB)
        repo.removeUsageDataForPackage(pkgA)
        assertThat(repo.getRecentlyLaunchedPackages(10).toSet()).isEqualTo(setOf(pkgB))
    }

    // ---------- usageSnapshotFlow ----------

    /**
     * REACTIVE_APPLIST_SPEC: the change-signal ticks once initially (so a
     * consumer runs the initial sort) and again after a usage change. Only ONE
     * change is asserted deliberately — a repeat launch may not alter the stored
     * set within the same millisecond on the impl (`System.currentTimeMillis`),
     * so asserting a second tick would be timing-dependent. Both fake and impl
     * must expose this reactive shape.
     */
    @Test
    fun `usageSnapshotFlow signals initially and after a usage change`() = runTest {
        val repo = createRepository()
        repo.usageSnapshotFlow.test {
            awaitItem() // initial signal
            repo.recordPackageLaunch(pkgA)
            awaitItem() // signal after the usage change
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------- purgeRepository ----------

    @Test
    fun `purgeRepository removes usage data for all packages`() = runTest {
        val repo = createRepository()
        repo.recordPackageLaunch(pkgA)
        repo.recordPackageLaunch(pkgB)
        repo.purgeRepository()
        assertThat(repo.hasUsageDataForPackage(pkgA)).isFalse()
        assertThat(repo.hasUsageDataForPackage(pkgB)).isFalse()
    }

    @Test
    fun `purgeRepository on fresh repository is safe`() = runTest {
        val repo = createRepository()
        repo.purgeRepository()
        assertThat(repo.hasUsageDataForPackage(pkgA)).isFalse()
    }
}
