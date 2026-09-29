package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.model.FavoritesEditRead
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
        assertThat(repo.favoriteComponentsFlow.first()).isEqualTo(emptySet<String>())
    }

    // ---------- getFavoriteComponentsSnapshot (authoritative read for backup) ----------

    @Test
    fun `getFavoriteComponentsSnapshot returns the current favorites`() = runTest {
        val repo = createRepository()
        repo.addFavoriteComponent(compA)
        repo.addFavoriteComponent(compB)
        assertThat(repo.getFavoriteComponentsSnapshot()).isEqualTo(setOf(compA, compB))
    }

    @Test
    fun `getFavoriteComponentsSnapshot reflects the LATEST change, never a previous one`() = runTest {
        val repo = createRepository()
        repo.addFavoriteComponent(compA)
        repo.removeFavoriteComponent(compA)
        repo.addFavoriteComponent(compB)
        // Core guarantee behind the backup-stale-replay fix: the snapshot read
        // returns the newest persisted set, not the one it replaced.
        assertThat(repo.getFavoriteComponentsSnapshot()).isEqualTo(setOf(compB))
    }

    @Test
    fun `getFavoriteComponentsSnapshot on fresh repository is empty`() = runTest {
        val repo = createRepository()
        assertThat(repo.getFavoriteComponentsSnapshot()).isEqualTo(emptySet<String>())
    }

    // ---------- readFavoritesForEdit (distinguishable editor read, Belang C) ----------
    // Only the Loaded (success) shape is in the contract; the Unavailable (IOException)
    // branch is impl-only I/O and lives in FavoritesRepositoryImplTest.

    @Test
    fun `readFavoritesForEdit returns Loaded with the current favorites`() = runTest {
        val repo = createRepository()
        repo.addFavoriteComponent(compA)
        repo.addFavoriteComponent(compB)
        assertThat(repo.readFavoritesForEdit()).isEqualTo(FavoritesEditRead.Loaded(setOf(compA, compB)))
    }

    @Test
    fun `readFavoritesForEdit on fresh repository returns Loaded empty`() = runTest {
        val repo = createRepository()
        assertThat(repo.readFavoritesForEdit()).isEqualTo(FavoritesEditRead.Loaded(emptySet()))
    }

    @Test
    fun `readFavoritesForEdit reflects the LATEST change, never a previous one`() = runTest {
        val repo = createRepository()
        repo.addFavoriteComponent(compA)
        repo.removeFavoriteComponent(compA)
        repo.addFavoriteComponent(compB)
        assertThat(repo.readFavoritesForEdit()).isEqualTo(FavoritesEditRead.Loaded(setOf(compB)))
    }

    // ---------- addFavoriteComponent ----------

    @Test
    fun `addFavoriteComponent returns true for valid component`() = runTest {
        val repo = createRepository()
        assertThat(repo.addFavoriteComponent(compA)).isTrue()
    }

    @Test
    fun `addFavoriteComponent persists component to flow`() = runTest {
        val repo = createRepository()
        repo.addFavoriteComponent(compA)
        assertThat(compA in repo.favoriteComponentsFlow.first()).isTrue()
    }

    @Test
    fun `addFavoriteComponent returns false for empty string`() = runTest {
        val repo = createRepository()
        assertThat(repo.addFavoriteComponent("")).isFalse()
    }

    @Test
    fun `addFavoriteComponent returns false for whitespace-only string`() = runTest {
        val repo = createRepository()
        assertThat(repo.addFavoriteComponent("   ")).isFalse()
    }

    @Test
    fun `addFavoriteComponent returns false for a malformed component key`() = runTest {
        // A string that is not a package/class flatten (no separator, or an empty
        // side) must be rejected rather than silently persisted — otherwise it
        // survives as a stale favorite that the backup restore later drops for not
        // matching an installed component (TODO §15).
        val repo = createRepository()
        assertWithMessage("bare package, no class").that(repo.addFavoriteComponent("com.example.alpha")).isFalse()
        assertWithMessage("trailing slash, empty class").that(repo.addFavoriteComponent("com.example.alpha/")).isFalse()
        assertWithMessage("leading slash, empty package").that(repo.addFavoriteComponent("/.MainActivity")).isFalse()
    }

    @Test
    fun `addFavoriteComponent with a malformed key does not modify state`() = runTest {
        val repo = createRepository()
        repo.addFavoriteComponent(compA)
        repo.addFavoriteComponent("com.example.alpha")
        assertThat(repo.favoriteComponentsFlow.first()).isEqualTo(setOf(compA))
    }

    @Test
    fun `addFavoriteComponent with blank does not modify state`() = runTest {
        val repo = createRepository()
        repo.addFavoriteComponent(compA)
        repo.addFavoriteComponent("")
        repo.addFavoriteComponent("   ")
        assertThat(repo.favoriteComponentsFlow.first()).isEqualTo(setOf(compA))
    }

    @Test
    fun `addFavoriteComponent twice with same component is idempotent`() = runTest {
        val repo = createRepository()
        assertThat(repo.addFavoriteComponent(compA)).isTrue()
        assertThat(repo.addFavoriteComponent(compA)).isTrue()
        assertThat(repo.favoriteComponentsFlow.first()).isEqualTo(setOf(compA))
    }

    @Test
    fun `addFavoriteComponent preserves previously added components`() = runTest {
        val repo = createRepository()
        repo.addFavoriteComponent(compA)
        repo.addFavoriteComponent(compB)
        assertThat(repo.favoriteComponentsFlow.first()).isEqualTo(setOf(compA, compB))
    }

    // ---------- removeFavoriteComponent ----------

    @Test
    fun `removeFavoriteComponent returns true for existing component`() = runTest {
        val repo = createRepository()
        repo.addFavoriteComponent(compA)
        assertThat(repo.removeFavoriteComponent(compA)).isTrue()
    }

    @Test
    fun `removeFavoriteComponent actually removes the component from flow`() = runTest {
        val repo = createRepository()
        repo.addFavoriteComponent(compA)
        repo.addFavoriteComponent(compB)
        repo.removeFavoriteComponent(compA)
        assertThat(repo.favoriteComponentsFlow.first()).isEqualTo(setOf(compB))
    }

    @Test
    fun `removeFavoriteComponent returns false for blank`() = runTest {
        val repo = createRepository()
        assertThat(repo.removeFavoriteComponent("")).isFalse()
        assertThat(repo.removeFavoriteComponent("   ")).isFalse()
    }

    @Test
    fun `removeFavoriteComponent returns true for non-existing component`() = runTest {
        // Beide Implementierungen: Manager via expliziten Early-Return,
        // Fake via no-op-Set-Minus. Beide liefern `true` zurück — der "Zustand nach
        // dem Aufruf ist wie gewünscht" ist erfüllt, egal ob vorher schon so.
        val repo = createRepository()
        assertThat(repo.removeFavoriteComponent(compA)).isTrue()
        assertThat(repo.favoriteComponentsFlow.first()).isEqualTo(emptySet<String>())
    }

    // ---------- isFavoriteComponent ----------

    @Test
    fun `isFavoriteComponent returns true for added component`() = runTest {
        val repo = createRepository()
        repo.addFavoriteComponent(compA)
        assertThat(repo.isFavoriteComponent(compA)).isTrue()
    }

    @Test
    fun `isFavoriteComponent returns false for non-existing component`() = runTest {
        val repo = createRepository()
        assertThat(repo.isFavoriteComponent(compA)).isFalse()
    }

    @Test
    fun `isFavoriteComponent returns false for null`() = runTest {
        val repo = createRepository()
        assertThat(repo.isFavoriteComponent(null)).isFalse()
    }

    @Test
    fun `isFavoriteComponent returns false for empty string`() = runTest {
        val repo = createRepository()
        assertThat(repo.isFavoriteComponent("")).isFalse()
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
        assertThat(repo.favoriteComponentsFlow.first()).isEqualTo(setOf(compA, compB))
    }

    // ---------- toggleFavoriteComponent ----------

    @Test
    fun `toggleFavoriteComponent adds non-existing component and returns true`() = runTest {
        val repo = createRepository()
        assertThat(repo.toggleFavoriteComponent(compA)).isTrue()
        assertThat(compA in repo.favoriteComponentsFlow.first()).isTrue()
    }

    @Test
    fun `toggleFavoriteComponent removes existing component and returns false`() = runTest {
        val repo = createRepository()
        repo.addFavoriteComponent(compA)
        assertThat(repo.toggleFavoriteComponent(compA)).isFalse()
        assertThat(compA in repo.favoriteComponentsFlow.first()).isFalse()
    }

    @Test
    fun `toggleFavoriteComponent is symmetric on double call`() = runTest {
        val repo = createRepository()
        repo.toggleFavoriteComponent(compA) // add
        repo.toggleFavoriteComponent(compA) // remove
        assertThat(repo.favoriteComponentsFlow.first()).isEqualTo(emptySet<String>())
    }

    // ---------- saveFavoriteComponents ----------

    @Test
    fun `saveFavoriteComponents replaces the entire set`() = runTest {
        val repo = createRepository()
        repo.addFavoriteComponent(compA)
        repo.saveFavoriteComponents(listOf(compB, compC))
        assertThat(repo.favoriteComponentsFlow.first()).isEqualTo(setOf(compB, compC))
    }

    @Test
    fun `saveFavoriteComponents with empty list clears favorites`() = runTest {
        val repo = createRepository()
        repo.addFavoriteComponent(compA)
        repo.saveFavoriteComponents(emptyList())
        assertThat(repo.favoriteComponentsFlow.first()).isEqualTo(emptySet<String>())
    }

    @Test
    fun `saveFavoriteComponents deduplicates input`() = runTest {
        val repo = createRepository()
        repo.saveFavoriteComponents(listOf(compA, compA, compB))
        assertThat(repo.favoriteComponentsFlow.first()).isEqualTo(setOf(compA, compB))
    }

    // ---------- purgeRepository ----------

    @Test
    fun `purgeRepository empties the flow`() = runTest {
        val repo = createRepository()
        repo.addFavoriteComponent(compA)
        repo.addFavoriteComponent(compB)
        repo.purgeRepository()
        assertThat(repo.favoriteComponentsFlow.first()).isEqualTo(emptySet<String>())
    }

    @Test
    fun `purgeRepository is safe on empty repository`() = runTest {
        val repo = createRepository()
        repo.purgeRepository()
        assertThat(repo.favoriteComponentsFlow.first()).isEqualTo(emptySet<String>())
    }
}