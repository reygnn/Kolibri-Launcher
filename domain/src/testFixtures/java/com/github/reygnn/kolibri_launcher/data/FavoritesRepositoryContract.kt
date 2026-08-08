package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.model.FavoritesEditRead
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * ============================================================================
 * FAVORITES REPOSITORY — CONTRACT TEST
 * ============================================================================
 *
 * Dieses ist ein *Contract-Test*: Er definiert das Verhalten, das JEDE Implementierung
 * von [FavoritesRepository] einhalten muss — egal ob es sich um die Produktions-
 * implementierung [FavoritesRepositoryImpl] oder um den Unit-Test-Fake
 * `FakeFavoritesRepository` handelt.
 *
 * ZWECK:
 *   Fakes driften über die Zeit von der echten Implementierung ab. Dieser
 *   Contract-Test fährt denselben Satz an Assertions gegen beide Implementierungen.
 *   Wenn der Fake sich anders verhält als der Manager, wird ein Test rot.
 *
 * NEUE IMPLEMENTIERUNG ANBINDEN:
 *   1. Neue Subklasse anlegen (z.B. `MyFavoritesRepositoryContractTest`).
 *   2. `createRepository()` überschreiben und eine frische Instanz
 *      zurückgeben.
 *   3. Fertig. Alle Contract-Tests laufen automatisch mit.
 *
 * REGELN FÜR SUBKLASSEN:
 *   - Keine eigenen `@Rule`s deklarieren (mainDispatcherRule + timberRule kommen
 *     über Vererbung). JUnit findet geerbte Rules via Reflection.
 *   - Kein eigenes `@Before` für Dispatcher-Setup — MainDispatcherRule erledigt das.
 *   - Kein eigener `TestScope` / `StandardTestDispatcher`
 *     (siehe TESTING_CONVENTIONS.kt).
 *
 * NICHT IM CONTRACT:
 *   `FavoritesRepositoryImpl` erzwingt `AppConstants.MAX_FAVORITES_ON_HOME`
 *   als Package-Limit. Der Fake tut das nicht. Das ist (a) Business-Regel des
 *   konkreten Managers und (b) 500 Adds pro Test wären unverhältnismäßig —
 *   daher getestet in `FavoritesRepositoryImplTest` direkt, nicht hier.
 *
 * @see FakeFavoritesRepositoryContractTest
 * @see FavoritesRepositoryImplContractTest
 * ============================================================================
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class FavoritesRepositoryContract {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    /**
     * Baut eine frische Repository-Instanz für genau diesen Test.
     *
     * Called from within the `runTest { … }` block. No implementation needs an
     * external coroutine scope: `FakeFavoritesRepository` works purely on a
     * `MutableStateFlow`, and since the DATASTORE_READ_SPEC Belang A teardown
     * `FavoritesRepositoryImpl` exposes a plain COLD flow (no `shareIn` layer),
     * so a `.first()` after a write is already a fresh read of `dataStore.data`
     * — there is no replay cache to return a stale value and nothing to route
     * around. (Previously the impl took `externalScope = null` to skip the
     * `shareIn` layer; that constructor is gone.)
     */
    protected abstract fun createRepository(): FavoritesRepository

    // Konstanten: kein Overlap zwischen Component A und B, aber C teilt Package mit A.
    // Das macht cleanup- und save-Tests aussagekräftig.
    private val compA = "com.example.a/.MainActivity"
    private val compB = "com.example.b/.MainActivity"
    private val compC = "com.example.a/.SecondActivity"

    // ---------- Initial State ----------

    @Test
    fun `fresh repository emits empty set`() = runTest {
        val repo = createRepository()
        assertEquals(emptySet<String>(), repo.favoriteComponentsFlow.first())
    }

    // ---------- getFavoriteComponentsSnapshot (authoritative read for backup) ----------

    @Test
    fun `getFavoriteComponentsSnapshot returns the current favorites`() = runTest {
        val repo = createRepository()
        repo.addFavoriteComponent(compA)
        repo.addFavoriteComponent(compB)
        assertEquals(setOf(compA, compB), repo.getFavoriteComponentsSnapshot())
    }

    @Test
    fun `getFavoriteComponentsSnapshot reflects the LATEST change, never a previous one`() = runTest {
        val repo = createRepository()
        repo.addFavoriteComponent(compA)
        repo.removeFavoriteComponent(compA)
        repo.addFavoriteComponent(compB)
        // Core guarantee behind the backup-stale-replay fix: the snapshot read
        // returns the newest persisted set, not the one it replaced.
        assertEquals(setOf(compB), repo.getFavoriteComponentsSnapshot())
    }

    @Test
    fun `getFavoriteComponentsSnapshot on fresh repository is empty`() = runTest {
        val repo = createRepository()
        assertEquals(emptySet<String>(), repo.getFavoriteComponentsSnapshot())
    }

    // ---------- readFavoritesForEdit (distinguishable editor read, Belang C) ----------
    // Only the Loaded (success) shape is in the contract; the Unavailable (IOException)
    // branch is impl-only I/O and lives in FavoritesRepositoryImplTest.

    @Test
    fun `readFavoritesForEdit returns Loaded with the current favorites`() = runTest {
        val repo = createRepository()
        repo.addFavoriteComponent(compA)
        repo.addFavoriteComponent(compB)
        assertEquals(FavoritesEditRead.Loaded(setOf(compA, compB)), repo.readFavoritesForEdit())
    }

    @Test
    fun `readFavoritesForEdit on fresh repository returns Loaded empty`() = runTest {
        val repo = createRepository()
        assertEquals(FavoritesEditRead.Loaded(emptySet()), repo.readFavoritesForEdit())
    }

    @Test
    fun `readFavoritesForEdit reflects the LATEST change, never a previous one`() = runTest {
        val repo = createRepository()
        repo.addFavoriteComponent(compA)
        repo.removeFavoriteComponent(compA)
        repo.addFavoriteComponent(compB)
        assertEquals(FavoritesEditRead.Loaded(setOf(compB)), repo.readFavoritesForEdit())
    }

    // ---------- addFavoriteComponent ----------

    @Test
    fun `addFavoriteComponent returns true for valid component`() = runTest {
        val repo = createRepository()
        assertTrue(repo.addFavoriteComponent(compA))
    }

    @Test
    fun `addFavoriteComponent persists component to flow`() = runTest {
        val repo = createRepository()
        repo.addFavoriteComponent(compA)
        assertTrue(compA in repo.favoriteComponentsFlow.first())
    }

    @Test
    fun `addFavoriteComponent returns false for empty string`() = runTest {
        val repo = createRepository()
        assertFalse(repo.addFavoriteComponent(""))
    }

    @Test
    fun `addFavoriteComponent returns false for whitespace-only string`() = runTest {
        val repo = createRepository()
        assertFalse(repo.addFavoriteComponent("   "))
    }

    @Test
    fun `addFavoriteComponent with blank does not modify state`() = runTest {
        val repo = createRepository()
        repo.addFavoriteComponent(compA)
        repo.addFavoriteComponent("")
        repo.addFavoriteComponent("   ")
        assertEquals(setOf(compA), repo.favoriteComponentsFlow.first())
    }

    @Test
    fun `addFavoriteComponent twice with same component is idempotent`() = runTest {
        val repo = createRepository()
        assertTrue(repo.addFavoriteComponent(compA))
        assertTrue(repo.addFavoriteComponent(compA))
        assertEquals(setOf(compA), repo.favoriteComponentsFlow.first())
    }

    @Test
    fun `addFavoriteComponent preserves previously added components`() = runTest {
        val repo = createRepository()
        repo.addFavoriteComponent(compA)
        repo.addFavoriteComponent(compB)
        assertEquals(setOf(compA, compB), repo.favoriteComponentsFlow.first())
    }

    // ---------- removeFavoriteComponent ----------

    @Test
    fun `removeFavoriteComponent returns true for existing component`() = runTest {
        val repo = createRepository()
        repo.addFavoriteComponent(compA)
        assertTrue(repo.removeFavoriteComponent(compA))
    }

    @Test
    fun `removeFavoriteComponent actually removes the component from flow`() = runTest {
        val repo = createRepository()
        repo.addFavoriteComponent(compA)
        repo.addFavoriteComponent(compB)
        repo.removeFavoriteComponent(compA)
        assertEquals(setOf(compB), repo.favoriteComponentsFlow.first())
    }

    @Test
    fun `removeFavoriteComponent returns false for blank`() = runTest {
        val repo = createRepository()
        assertFalse(repo.removeFavoriteComponent(""))
        assertFalse(repo.removeFavoriteComponent("   "))
    }

    @Test
    fun `removeFavoriteComponent returns true for non-existing component`() = runTest {
        // Beide Implementierungen: Manager via expliziten Early-Return,
        // Fake via no-op-Set-Minus. Beide liefern `true` zurück — der "Zustand nach
        // dem Aufruf ist wie gewünscht" ist erfüllt, egal ob vorher schon so.
        val repo = createRepository()
        assertTrue(repo.removeFavoriteComponent(compA))
        assertEquals(emptySet<String>(), repo.favoriteComponentsFlow.first())
    }

    // ---------- isFavoriteComponent ----------

    @Test
    fun `isFavoriteComponent returns true for added component`() = runTest {
        val repo = createRepository()
        repo.addFavoriteComponent(compA)
        assertTrue(repo.isFavoriteComponent(compA))
    }

    @Test
    fun `isFavoriteComponent returns false for non-existing component`() = runTest {
        val repo = createRepository()
        assertFalse(repo.isFavoriteComponent(compA))
    }

    @Test
    fun `isFavoriteComponent returns false for null`() = runTest {
        val repo = createRepository()
        assertFalse(repo.isFavoriteComponent(null))
    }

    @Test
    fun `isFavoriteComponent returns false for empty string`() = runTest {
        val repo = createRepository()
        assertFalse(repo.isFavoriteComponent(""))
    }

    /**
     * `saveFavoriteComponents` ist die einzige Tür, durch die beliebige Strings
     * (Backup-Import, UI-Eingabe, Onboarding) in den persistierten Set kommen.
     * Blanks haben dort nie etwas zu suchen — `addFavoriteComponent` filtert
     * sie bereits, und `isFavoriteComponent(blank)` gibt false zurück. Damit
     * die drei Schreib-/Lese-Operationen konsistent bleiben, muss auch `save`
     * Blanks aus der Eingabe herausfiltern.
     */
    @Test
    fun `saveFavoriteComponents filters out blank entries`() = runTest {
        val repo = createRepository()
        repo.saveFavoriteComponents(listOf(compA, "", "   ", compB))
        assertEquals(setOf(compA, compB), repo.favoriteComponentsFlow.first())
    }

    // ---------- toggleFavoriteComponent ----------

    @Test
    fun `toggleFavoriteComponent adds non-existing component and returns true`() = runTest {
        val repo = createRepository()
        assertTrue(repo.toggleFavoriteComponent(compA))
        assertTrue(compA in repo.favoriteComponentsFlow.first())
    }

    @Test
    fun `toggleFavoriteComponent removes existing component and returns false`() = runTest {
        val repo = createRepository()
        repo.addFavoriteComponent(compA)
        assertFalse(repo.toggleFavoriteComponent(compA))
        assertFalse(compA in repo.favoriteComponentsFlow.first())
    }

    @Test
    fun `toggleFavoriteComponent is symmetric on double call`() = runTest {
        val repo = createRepository()
        repo.toggleFavoriteComponent(compA) // add
        repo.toggleFavoriteComponent(compA) // remove
        assertEquals(emptySet<String>(), repo.favoriteComponentsFlow.first())
    }

    // ---------- saveFavoriteComponents ----------

    @Test
    fun `saveFavoriteComponents replaces the entire set`() = runTest {
        val repo = createRepository()
        repo.addFavoriteComponent(compA)
        repo.saveFavoriteComponents(listOf(compB, compC))
        assertEquals(setOf(compB, compC), repo.favoriteComponentsFlow.first())
    }

    @Test
    fun `saveFavoriteComponents with empty list clears favorites`() = runTest {
        val repo = createRepository()
        repo.addFavoriteComponent(compA)
        repo.saveFavoriteComponents(emptyList())
        assertEquals(emptySet<String>(), repo.favoriteComponentsFlow.first())
    }

    @Test
    fun `saveFavoriteComponents deduplicates input`() = runTest {
        val repo = createRepository()
        repo.saveFavoriteComponents(listOf(compA, compA, compB))
        assertEquals(setOf(compA, compB), repo.favoriteComponentsFlow.first())
    }

    // ---------- reconcileFavoriteComponents ----------

    @Test
    fun `reconcileFavoriteComponents keeps only components in installed list`() = runTest {
        val repo = createRepository()
        repo.saveFavoriteComponents(listOf(compA, compB, compC))
        repo.reconcileFavoriteComponents(listOf(compA, compC)) { false }
        assertEquals(setOf(compA, compC), repo.favoriteComponentsFlow.first())
    }

    @Test
    fun `reconcileFavoriteComponents with all favorites installed changes nothing`() = runTest {
        val repo = createRepository()
        repo.saveFavoriteComponents(listOf(compA, compB))
        repo.reconcileFavoriteComponents(listOf(compA, compB, compC)) { false }
        assertEquals(setOf(compA, compB), repo.favoriteComponentsFlow.first())
    }

    @Test
    fun `reconcileFavoriteComponents with empty installed list clears favorites`() = runTest {
        val repo = createRepository()
        repo.saveFavoriteComponents(listOf(compA, compB))
        repo.reconcileFavoriteComponents(emptyList()) { false }
        assertEquals(emptySet<String>(), repo.favoriteComponentsFlow.first())
    }

    @Test
    fun `reconcileFavoriteComponents on empty repository stays empty`() = runTest {
        val repo = createRepository()
        repo.reconcileFavoriteComponents(listOf(compA, compB)) { false }
        assertEquals(emptySet<String>(), repo.favoriteComponentsFlow.first())
    }

    @Test
    fun `reconcileFavoriteComponents keeps an orphan the presence check reports present`() = runTest {
        val repo = createRepository()
        repo.saveFavoriteComponents(listOf(compA, compB))
        // compB is absent from the load but the presence predicate vetoes its removal.
        repo.reconcileFavoriteComponents(listOf(compA)) { it == compB }
        assertEquals(setOf(compA, compB), repo.favoriteComponentsFlow.first())
    }

    // ---------- purgeRepository ----------

    @Test
    fun `purgeRepository empties the flow`() = runTest {
        val repo = createRepository()
        repo.addFavoriteComponent(compA)
        repo.addFavoriteComponent(compB)
        repo.purgeRepository()
        assertEquals(emptySet<String>(), repo.favoriteComponentsFlow.first())
    }

    @Test
    fun `purgeRepository is safe on empty repository`() = runTest {
        val repo = createRepository()
        repo.purgeRepository()
        assertEquals(emptySet<String>(), repo.favoriteComponentsFlow.first())
    }
}