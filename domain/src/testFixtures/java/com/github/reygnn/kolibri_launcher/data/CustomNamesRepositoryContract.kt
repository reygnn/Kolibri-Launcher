package com.github.reygnn.kolibri_launcher.data

import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * ============================================================================
 * CUSTOM NAMES REPOSITORY — CONTRACT TEST
 * ============================================================================
 *
 * Siehe [FavoritesRepositoryContract] für Hintergrund und Konventionen.
 *
 * Sieben Methoden, das größte Contract-Interface im Projekt. Strukturell ist
 * die Klasse ein Map<packageName, customName>-Store mit zwei besonderen
 * Eigenschaften:
 *
 *   1. **Trim-Semantik beim Schreiben**: `setCustomNameForPackage(pkg, "  X  ")`
 *      muss als `"X"` persistiert werden. Sowohl Manager als auch Fake machen
 *      das via `customName.trim()`. Wer das beim Refactoring wegnimmt, soll
 *      es merken.
 *
 *   2. **Blank == Remove beim Single-Set**: `setCustomNameForPackage(pkg, "")`
 *      und `setCustomNameForPackage(pkg, "   ")` werden als "remove this entry"
 *      interpretiert. Das ist nicht offensichtlich aus dem Methodennamen, also
 *      vertraglich festgeschrieben.
 *
 *      Achtung: Im Batch-Pfad `setCustomNamesInBatch` ist die Semantik
 *      ANDERS — dort werden blank-Werte einfach **ignoriert**, nicht als
 *      Remove interpretiert. Beide Implementierungen verhalten sich so;
 *      ein eigener Test fixiert diese (subtile) Asymmetrie zwischen Single-
 *      und Batch-Pfad.
 *
 * BEKANNTE DIVERGENZ — wird auf dem Fake rot:
 *   `removeCustomNameForPackage(pkg)` für einen pkg ohne Custom-Name:
 *   - Manager: returnt **true**. Idempotent — der Zielzustand "kein Custom-Name
 *     für pkg" ist nach dem Aufruf erreicht, also Erfolg. Triggert ein
 *     Update.
 *   - Fake: returnt **false** (`map.remove() != null` ist false wenn Key
 *     nicht da war). Triggert kein Update.
 *
 *   Realer Schaden: User klickt zweimal hintereinander "Reset Name" (zweiter
 *   Klick ist remove auf nicht-existenten Eintrag). UI denkt der Reset wäre
 *   fehlgeschlagen (Fake-Verhalten), obwohl alles gut ist (Produktions-
 *   Verhalten). Saubere Lösung: Fake an Manager-Semantik angleichen
 *   (Idempotenz, immer true).
 *
 *   Konsistent zur gleichen Idempotenz-Regel bei
 *   `HiddenAppsRepository.showComponent` und
 *   `FavoritesRepository.removeFavoriteComponent`, die in ihren Contracts
 *   bereits so festgeschrieben sind.
 *
 * NICHT IM CONTRACT (Implementierungs-Details):
 *   - Konkretes Verhalten von `triggerCustomNameUpdate()` — Manager emittiert
 *     auf einen `MutableSharedFlow<Unit>`, Fake ruft einen Test-Hook auf.
 *     Vertrag ist nur: "wirft nicht, ist idempotent aufrufbar".
 *   - DataStore-IOException-Recovery (Manager-Detail).
 *   - Test-Hooks im Fake (`shouldFailOnSet`, `shouldFailOnBatch`,
 *     `batchSetCalled`, `onUpdateTrigger`) — sind kein Interface-Vertrag.
 *
 * @see FakeCustomNamesRepositoryContractTest
 * @see CustomNamesRepositoryImplContractTest
 * ============================================================================
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class CustomNamesRepositoryContract {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    protected abstract fun createRepository(): CustomNamesRepository

    private val pkgA = "com.example.a"
    private val pkgB = "com.example.b"
    private val pkgC = "com.example.c"

    // ---------- Fresh state ----------

    @Test
    fun `fresh repository has no custom names`() = runTest {
        val repo = createRepository()
        assertFalse(repo.hasCustomNameForPackage(pkgA))
    }

    @Test
    fun `fresh repository getAllCustomNames returns empty map`() = runTest {
        val repo = createRepository()
        assertEquals(emptyMap<String, String>(), repo.getAllCustomNames())
    }

    @Test
    fun `getDisplayNameForPackage with no custom name returns originalName`() = runTest {
        val repo = createRepository()
        assertEquals("Original", repo.getDisplayNameForPackage(pkgA, "Original"))
    }

    // ---------- setCustomNameForPackage ----------

    @Test
    fun `setCustomNameForPackage returns true on success`() = runTest {
        val repo = createRepository()
        assertTrue(repo.setCustomNameForPackage(pkgA, "Custom A"))
    }

    @Test
    fun `setCustomNameForPackage persists the name`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "Custom A")
        assertTrue(repo.hasCustomNameForPackage(pkgA))
        assertEquals("Custom A", repo.getDisplayNameForPackage(pkgA, "Original"))
    }

    @Test
    fun `setCustomNameForPackage trims whitespace from custom name`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "  Padded  ")
        assertEquals("Padded", repo.getDisplayNameForPackage(pkgA, "Original"))
    }

    @Test
    fun `setCustomNameForPackage overwrites previous custom name`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "First")
        repo.setCustomNameForPackage(pkgA, "Second")
        assertEquals("Second", repo.getDisplayNameForPackage(pkgA, "Original"))
    }

    @Test
    fun `setCustomNameForPackage on one package does not affect another`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "Name A")
        assertFalse(repo.hasCustomNameForPackage(pkgB))
        assertEquals("OriginalB", repo.getDisplayNameForPackage(pkgB, "OriginalB"))
    }

    /**
     * Vertraglich festgeschrieben: leerer custom name beim Single-Set wird als
     * "remove" interpretiert, nicht als "speichere leeren String". Asymmetrie
     * zum Batch-Pfad — siehe Klassen-KDoc.
     */
    @Test
    fun `setCustomNameForPackage with empty string removes the custom name`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "Custom")
        assertTrue(repo.hasCustomNameForPackage(pkgA))

        repo.setCustomNameForPackage(pkgA, "")

        assertFalse(repo.hasCustomNameForPackage(pkgA))
        assertEquals("Original", repo.getDisplayNameForPackage(pkgA, "Original"))
    }

    @Test
    fun `setCustomNameForPackage with whitespace-only string removes the custom name`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "Custom")
        assertTrue(repo.hasCustomNameForPackage(pkgA))

        repo.setCustomNameForPackage(pkgA, "   ")

        assertFalse(repo.hasCustomNameForPackage(pkgA))
    }

    // ---------- removeCustomNameForPackage ----------

    @Test
    fun `removeCustomNameForPackage removes existing custom name`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "Custom A")
        assertTrue(repo.removeCustomNameForPackage(pkgA))
        assertFalse(repo.hasCustomNameForPackage(pkgA))
    }

    /**
     * KNOWN CONTRACT DIVERGENCE — wird auf dem Fake rot. Siehe Klassen-KDoc
     * für die volle Begründung und den Fix-Vorschlag.
     */
    @Test
    fun `removeCustomNameForPackage on non-existent package is idempotent and returns true`() =
        runTest {
            val repo = createRepository()
            assertTrue(repo.removeCustomNameForPackage(pkgA))
            assertFalse(repo.hasCustomNameForPackage(pkgA))
        }

    @Test
    fun `removeCustomNameForPackage on one package does not affect another`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "Name A")
        repo.setCustomNameForPackage(pkgB, "Name B")
        repo.removeCustomNameForPackage(pkgA)
        assertFalse(repo.hasCustomNameForPackage(pkgA))
        assertTrue(repo.hasCustomNameForPackage(pkgB))
        assertEquals("Name B", repo.getDisplayNameForPackage(pkgB, "Original"))
    }

    // ---------- customNamesFlow ----------

    /**
     * REACTIVE_APPLIST_SPEC RAL-1: the reactive `packageName -> customName` view
     * emits the current mapping and re-emits on every change. Pins that both
     * fake and impl expose the SAME reactive contract, so a rename shows up as a
     * flow emission (the mechanism that lets consumers fold names in via
     * `combine` instead of re-enumerating).
     */
    @Test
    fun `customNamesFlow emits current mapping and updates on change`() = runTest {
        val repo = createRepository()
        repo.customNamesFlow.test {
            assertEquals(emptyMap<String, String>(), awaitItem())

            repo.setCustomNameForPackage(pkgA, "Name A")
            assertEquals(mapOf(pkgA to "Name A"), awaitItem())

            repo.setCustomNameForPackage(pkgB, "Name B")
            assertEquals(mapOf(pkgA to "Name A", pkgB to "Name B"), awaitItem())

            repo.removeCustomNameForPackage(pkgA)
            assertEquals(mapOf(pkgB to "Name B"), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------- getDisplayNameForPackage ----------

    @Test
    fun `getDisplayNameForPackage returns custom name when set`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "Custom A")
        assertEquals("Custom A", repo.getDisplayNameForPackage(pkgA, "Original"))
    }

    @Test
    fun `getDisplayNameForPackage falls back to originalName after remove`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "Custom A")
        repo.removeCustomNameForPackage(pkgA)
        assertEquals("Original", repo.getDisplayNameForPackage(pkgA, "Original"))
    }

    // ---------- hasCustomNameForPackage ----------

    @Test
    fun `hasCustomNameForPackage returns false initially`() = runTest {
        val repo = createRepository()
        assertFalse(repo.hasCustomNameForPackage(pkgA))
    }

    @Test
    fun `hasCustomNameForPackage returns true after set`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "Custom")
        assertTrue(repo.hasCustomNameForPackage(pkgA))
    }

    @Test
    fun `hasCustomNameForPackage returns false after remove`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "Custom")
        repo.removeCustomNameForPackage(pkgA)
        assertFalse(repo.hasCustomNameForPackage(pkgA))
    }

    // ---------- triggerCustomNameUpdate ----------

    /**
     * Konkretes Trigger-Verhalten ist Implementation-Detail (Manager emittiert
     * auf einen SharedFlow, Fake ruft einen Test-Hook). Vertrag ist nur: der
     * Aufruf wirft nicht und ist idempotent aufrufbar.
     */
    @Test
    fun `triggerCustomNameUpdate does not throw on fresh repository`() = runTest {
        val repo = createRepository()
        repo.triggerCustomNameUpdate()
    }

    @Test
    fun `triggerCustomNameUpdate can be called multiple times without throwing`() = runTest {
        val repo = createRepository()
        repo.triggerCustomNameUpdate()
        repo.triggerCustomNameUpdate()
        repo.triggerCustomNameUpdate()
    }

    // ---------- getAllCustomNames ----------

    @Test
    fun `getAllCustomNames returns all set names`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "Name A")
        repo.setCustomNameForPackage(pkgB, "Name B")
        repo.setCustomNameForPackage(pkgC, "Name C")

        val all = repo.getAllCustomNames()

        assertEquals(3, all.size)
        assertEquals("Name A", all[pkgA])
        assertEquals("Name B", all[pkgB])
        assertEquals("Name C", all[pkgC])
    }

    @Test
    fun `getAllCustomNames does not include removed names`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "Name A")
        repo.setCustomNameForPackage(pkgB, "Name B")
        repo.removeCustomNameForPackage(pkgA)

        val all = repo.getAllCustomNames()

        assertEquals(1, all.size)
        assertEquals("Name B", all[pkgB])
    }

    @Test
    fun `getAllCustomNames returns trimmed names`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "  Padded  ")
        assertEquals("Padded", repo.getAllCustomNames()[pkgA])
    }

    // ---------- setCustomNamesInBatch ----------

    @Test
    fun `setCustomNamesInBatch with empty map returns true`() = runTest {
        val repo = createRepository()
        assertTrue(repo.setCustomNamesInBatch(emptyMap()))
    }

    @Test
    fun `setCustomNamesInBatch with empty map does not modify state`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "Existing")
        repo.setCustomNamesInBatch(emptyMap())
        assertEquals("Existing", repo.getDisplayNameForPackage(pkgA, "Original"))
    }

    @Test
    fun `setCustomNamesInBatch sets all names from input`() = runTest {
        val repo = createRepository()
        repo.setCustomNamesInBatch(mapOf(pkgA to "A", pkgB to "B", pkgC to "C"))

        assertEquals("A", repo.getDisplayNameForPackage(pkgA, "OriginalA"))
        assertEquals("B", repo.getDisplayNameForPackage(pkgB, "OriginalB"))
        assertEquals("C", repo.getDisplayNameForPackage(pkgC, "OriginalC"))
    }

    @Test
    fun `setCustomNamesInBatch returns true on success`() = runTest {
        val repo = createRepository()
        assertTrue(repo.setCustomNamesInBatch(mapOf(pkgA to "A")))
    }

    @Test
    fun `setCustomNamesInBatch trims whitespace from names`() = runTest {
        val repo = createRepository()
        repo.setCustomNamesInBatch(mapOf(pkgA to "  Padded  "))
        assertEquals("Padded", repo.getDisplayNameForPackage(pkgA, "Original"))
    }

    @Test
    fun `setCustomNamesInBatch overwrites existing names`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "Old")
        repo.setCustomNamesInBatch(mapOf(pkgA to "New"))
        assertEquals("New", repo.getDisplayNameForPackage(pkgA, "Original"))
    }

    @Test
    fun `setCustomNamesInBatch preserves names not in the batch`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "Keep")
        repo.setCustomNamesInBatch(mapOf(pkgB to "New"))

        assertEquals("Keep", repo.getDisplayNameForPackage(pkgA, "Original"))
        assertEquals("New", repo.getDisplayNameForPackage(pkgB, "Original"))
    }

    /**
     * Wichtige Asymmetrie zum Single-Set-Pfad: leere Werte in einem Batch werden
     * **ignoriert**, nicht als Remove interpretiert. Wer einen Eintrag im Batch
     * löschen will, kann das mit dieser Methode NICHT — er muss separat
     * `removeCustomNameForPackage` rufen oder die Map ohne diesen Key
     * übergeben (was den Eintrag aber auch erhält, weil der Batch additiv ist).
     *
     * Das ist eine subtile Falle für Aufrufer, aber beide Implementierungen
     * machen es konsistent, also festschreiben.
     */
    @Test
    fun `setCustomNamesInBatch ignores blank entries instead of removing them`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "Keep me")

        repo.setCustomNamesInBatch(mapOf(pkgA to "", pkgB to "   ", pkgC to "Valid"))

        // pkgA bleibt erhalten — blank-Wert hat den existierenden Eintrag NICHT entfernt.
        assertEquals("Keep me", repo.getDisplayNameForPackage(pkgA, "OriginalA"))
        // pkgB wurde nie gesetzt, Original-Fallback.
        assertFalse(repo.hasCustomNameForPackage(pkgB))
        // pkgC wurde gesetzt.
        assertEquals("Valid", repo.getDisplayNameForPackage(pkgC, "OriginalC"))
    }

    // ---------- reconcileCustomNames ----------

    @Test
    fun `reconcileCustomNames keeps only names for installed packages`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "Name A")
        repo.setCustomNameForPackage(pkgB, "Name B")
        repo.setCustomNameForPackage(pkgC, "Name C")

        repo.reconcileCustomNames(listOf(pkgA, pkgC)) { false }

        assertTrue(repo.hasCustomNameForPackage(pkgA))
        assertFalse(repo.hasCustomNameForPackage(pkgB))
        assertTrue(repo.hasCustomNameForPackage(pkgC))
    }

    @Test
    fun `reconcileCustomNames with all packages installed changes nothing`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "Name A")
        repo.setCustomNameForPackage(pkgB, "Name B")

        repo.reconcileCustomNames(listOf(pkgA, pkgB, pkgC)) { false }

        assertEquals(mapOf(pkgA to "Name A", pkgB to "Name B"), repo.getAllCustomNames())
    }

    @Test
    fun `reconcileCustomNames with empty installed list clears all names`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "Name A")
        repo.setCustomNameForPackage(pkgB, "Name B")

        repo.reconcileCustomNames(emptyList()) { false }

        assertEquals(emptyMap<String, String>(), repo.getAllCustomNames())
    }

    @Test
    fun `reconcileCustomNames on empty repository stays empty`() = runTest {
        val repo = createRepository()
        repo.reconcileCustomNames(listOf(pkgA, pkgB)) { false }
        assertEquals(emptyMap<String, String>(), repo.getAllCustomNames())
    }

    @Test
    fun `reconcileCustomNames keeps a name the presence check reports present`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "Name A")
        // pkgA absent from the load but the presence predicate vetoes removal.
        repo.reconcileCustomNames(emptyList()) { it == pkgA }
        assertTrue(repo.hasCustomNameForPackage(pkgA))
    }

    // ---------- purgeRepository ----------

    @Test
    fun `purgeRepository clears all custom names`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "A")
        repo.setCustomNameForPackage(pkgB, "B")

        repo.purgeRepository()

        assertFalse(repo.hasCustomNameForPackage(pkgA))
        assertFalse(repo.hasCustomNameForPackage(pkgB))
        assertEquals(emptyMap<String, String>(), repo.getAllCustomNames())
    }

    @Test
    fun `purgeRepository on fresh repository is safe`() = runTest {
        val repo = createRepository()
        repo.purgeRepository()
        assertEquals(emptyMap<String, String>(), repo.getAllCustomNames())
    }

    // ---------- Round-trip property ----------

    /**
     * Komposit-Property: was via setCustomNameForPackage gesetzt wird, kommt
     * via getAllCustomNames im Set zurück. Stellt die Konsistenz zwischen den
     * Schreib- und Bulk-Lese-Pfaden sicher.
     */
    @Test
    fun `set and getAll round-trip preserves entries`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "Name A")
        repo.setCustomNameForPackage(pkgB, "Name B")

        val all = repo.getAllCustomNames()
        assertNotNull(all)
        assertEquals(setOf(pkgA, pkgB), all.keys)
        assertEquals("Name A", all[pkgA])
        assertEquals("Name B", all[pkgB])
    }
}
