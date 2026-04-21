package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * ============================================================================
 * FAVORITES ORDER REPOSITORY — CONTRACT TEST
 * ============================================================================
 *
 * Siehe [FavoritesRepositoryContract] für Hintergrund und Konventionen.
 *
 * Das [FavoritesOrderRepository]-Interface hat zwei sehr unterschiedliche
 * Verantwortlichkeiten in derselben Klasse:
 *
 *   1. **Persistenz der Reihenfolge** — `saveOrder` + `favoriteComponentsOrderFlow`.
 *      Standard-Roundtrip wie bei den anderen Repositories.
 *
 *   2. **Sortier-Algorithmus** — `sortFavoriteComponents`. Reine Funktion,
 *      braucht keinen State, aber das Verhalten ist nicht trivial: zwei-Phasen-
 *      Sortierung (geordneter Teil zuerst, Rest alphabetisch). Genau hier sind
 *      Fake-vs-Real-Divergenzen wahrscheinlich, weil "alphabetisch" und
 *      "Reihenfolge gemäß order-Liste" unterschiedlich implementiert werden
 *      können — und tatsächlich gibt es genau hier eine bekannte Divergenz.
 *
 * BEKANNTE DIVERGENZ (wird auf dem Fake rot):
 *   `sortFavoriteComponents` mit duplikaten Einträgen in der `order`-Liste.
 *   - Manager iteriert `order` und macht `find` + `remove` auf einer
 *     mutablen Liste → eine App taucht im Output höchstens einmal auf.
 *   - Fake macht `order.mapNotNull { appMap[it] }` → bei duplikaten in
 *     `order` taucht dieselbe App mehrfach im Output auf.
 *
 *   Das ist semantisch wichtig: in der UI würde sonst dieselbe Favoriten-App
 *   doppelt erscheinen, sobald die JSON-Order durch irgendeine Migration
 *   oder einen Bug-Pfad einen Duplikat-Eintrag bekommt. Der Manager-Code
 *   verteidigt sich dagegen, der Fake nicht. Fix-Vorschlag im
 *   Antwort-Kommentar.
 *
 * NICHT IM CONTRACT (Manager-spezifisch):
 *   - `MAX_ORDER_LIST_SIZE`-Truncation in `saveOrder` (Manager: Cap; Fake: nimmt
 *     alles). Business-Regel des konkreten Managers — gehört in
 *     `FavoritesOrderManagerTest`.
 *   - JSON-Parse-Fehler → empty list (kein JSON im Fake).
 *   - `saveOrder` returnt false bei Schreibfehlern (Fake returnt immer true).
 *   - `removeComponentFromOrder` ist **nicht** Teil des Interfaces, sondern eine
 *     `open suspend fun` direkt auf dem Manager → kein Contract-Test hier.
 *
 * @see FakeFavoritesOrderRepositoryContractTest
 * @see FavoritesOrderManagerContractTest
 * ============================================================================
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class FavoritesOrderRepositoryContract {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    protected abstract fun createRepository(): FavoritesOrderRepository

    // Test-Daten: alphabetisch B, C, A — damit die alphabetische Fallback-
    // Sortierung von der Insertion-Reihenfolge unterscheidbar ist.
    private fun appInfo(name: String, pkg: String, klass: String) = AppInfo(
        originalName = name,
        displayName = name,
        packageName = pkg,
        className = klass,
        isSystemApp = false,
        isFavorite = true
    )

    private val appA = appInfo("Alpha", "com.example.a", ".MainActivity")
    private val appB = appInfo("Beta", "com.example.b", ".MainActivity")
    private val appC = appInfo("Gamma", "com.example.c", ".MainActivity")

    // ---------- Initial State ----------

    @Test
    fun `fresh repository emits empty list`() = runTest {
        val repo = createRepository()
        assertEquals(emptyList<String>(), repo.favoriteComponentsOrderFlow.first())
    }

    // ---------- saveOrder Roundtrip ----------

    @Test
    fun `saveOrder reflects in flow`() = runTest {
        val repo = createRepository()
        val order = listOf(appA.componentName, appB.componentName)
        repo.saveOrder(order)
        assertEquals(order, repo.favoriteComponentsOrderFlow.first())
    }

    @Test
    fun `saveOrder returns true on success`() = runTest {
        val repo = createRepository()
        assertTrue(repo.saveOrder(listOf(appA.componentName)))
    }

    @Test
    fun `saveOrder preserves order of input`() = runTest {
        val repo = createRepository()
        // Bewusst nicht-alphabetisch, damit "irgendeine Sortierung" als Bug auffällt.
        val order = listOf(appC.componentName, appA.componentName, appB.componentName)
        repo.saveOrder(order)
        assertEquals(order, repo.favoriteComponentsOrderFlow.first())
    }

    @Test
    fun `saveOrder overwrites previous order completely`() = runTest {
        val repo = createRepository()
        repo.saveOrder(listOf(appA.componentName, appB.componentName))
        repo.saveOrder(listOf(appC.componentName))
        assertEquals(listOf(appC.componentName), repo.favoriteComponentsOrderFlow.first())
    }

    @Test
    fun `saveOrder with empty list clears the order`() = runTest {
        val repo = createRepository()
        repo.saveOrder(listOf(appA.componentName))
        repo.saveOrder(emptyList())
        assertEquals(emptyList<String>(), repo.favoriteComponentsOrderFlow.first())
    }

    // ---------- sortFavoriteComponents: trivial cases ----------

    @Test
    fun `sortFavoriteComponents with empty input returns empty`() = runTest {
        val repo = createRepository()
        val result = repo.sortFavoriteComponents(
            favoriteApps = emptyList(),
            order = listOf(appA.componentName)
        )
        assertEquals(emptyList<AppInfo>(), result)
    }

    @Test
    fun `sortFavoriteComponents with empty order falls back to alphabetical`() = runTest {
        val repo = createRepository()
        // Input bewusst NICHT alphabetisch — wenn der Sort fehlt, fällt der Test auf.
        val result = repo.sortFavoriteComponents(
            favoriteApps = listOf(appC, appA, appB),
            order = emptyList()
        )
        assertEquals(listOf(appA, appB, appC), result)
    }

    // ---------- sortFavoriteComponents: Standard-Cases ----------

    @Test
    fun `sortFavoriteComponents with full order returns apps in order`() = runTest {
        val repo = createRepository()
        val order = listOf(appC.componentName, appA.componentName, appB.componentName)
        val result = repo.sortFavoriteComponents(
            favoriteApps = listOf(appA, appB, appC),
            order = order
        )
        assertEquals(listOf(appC, appA, appB), result)
    }

    @Test
    fun `sortFavoriteComponents puts unordered apps after ordered ones alphabetically`() = runTest {
        val repo = createRepository()
        // Nur appC steht in der Order — appA und appB sollten alphabetisch
        // (Alpha vor Beta) ans Ende.
        val result = repo.sortFavoriteComponents(
            favoriteApps = listOf(appC, appB, appA),
            order = listOf(appC.componentName)
        )
        assertEquals(listOf(appC, appA, appB), result)
    }

    @Test
    fun `sortFavoriteComponents ignores order entries not in input`() = runTest {
        val repo = createRepository()
        // "ghost.app" ist in der Order, aber nicht in den favoriteApps.
        // Der Eintrag muss ignoriert werden — der Output enthält nur echte Apps.
        val result = repo.sortFavoriteComponents(
            favoriteApps = listOf(appA, appB),
            order = listOf("com.ghost.app/.MainActivity", appB.componentName, appA.componentName)
        )
        assertEquals(listOf(appB, appA), result)
    }

    // ---------- sortFavoriteComponents: Divergenz-Tests ----------

    /**
     * KNOWN CONTRACT DIVERGENCE — wird auf dem Fake rot.
     *
     * Wenn `order` eine App doppelt enthält, darf das im Output **nicht** zu
     * einer doppelten App führen. Der Output muss jede App aus `favoriteApps`
     * höchstens einmal enthalten.
     *
     * - Manager: Schutz via `find` + `remove` auf mutabler Liste — nach dem
     *   ersten Match ist die App raus, der zweite Order-Eintrag findet nichts.
     * - Fake: `order.mapNotNull { appMap[it] }` produziert bei duplikaten den
     *   App-Verweis mehrfach.
     *
     * Realer Schaden: Eine Favorites-Liste mit doppeltem Eintrag würde im Home-
     * Screen die App doppelt anzeigen, sobald die persistierte JSON-Order durch
     * Migration/Bug einen Duplikat-Eintrag enthält.
     *
     * Fix für Fake: vor `mapNotNull` die `order` deduplizieren
     * (`order.distinct().mapNotNull { appMap[it] }`).
     */
    @Test
    fun `sortFavoriteComponents with duplicate order entries does not duplicate apps`() = runTest {
        val repo = createRepository()
        val result = repo.sortFavoriteComponents(
            favoriteApps = listOf(appA, appB),
            order = listOf(appA.componentName, appA.componentName, appB.componentName)
        )
        assertEquals(listOf(appA, appB), result)
    }

    /**
     * Eine App, die NICHT in `order` ist, darf nicht verloren gehen — sie
     * gehört in den alphabetischen Rest. Beweis, dass der "remaining"-Teil
     * der zwei-Phasen-Sortierung wirklich alle Apps abdeckt, nicht nur die
     * mit Match.
     */
    @Test
    fun `sortFavoriteComponents preserves all apps even when none match order`() = runTest {
        val repo = createRepository()
        val result = repo.sortFavoriteComponents(
            favoriteApps = listOf(appC, appA, appB),
            order = listOf("com.unknown.x/.X", "com.unknown.y/.Y")
        )
        // Alle drei Apps müssen drin sein — alphabetisch sortiert, weil keine
        // mit der Order matched.
        assertEquals(listOf(appA, appB, appC), result)
    }

    // ---------- purgeRepository ----------

    @Test
    fun `purgeRepository clears the order`() = runTest {
        val repo = createRepository()
        repo.saveOrder(listOf(appA.componentName, appB.componentName))
        repo.purgeRepository()
        assertEquals(emptyList<String>(), repo.favoriteComponentsOrderFlow.first())
    }

    @Test
    fun `purgeRepository on fresh repository is safe`() = runTest {
        val repo = createRepository()
        repo.purgeRepository()
        assertEquals(emptyList<String>(), repo.favoriteComponentsOrderFlow.first())
    }
}
