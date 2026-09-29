package com.github.reygnn.kolibri_launcher.data

import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
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
 *   - DataStore-IOException-Recovery (Manager-Detail).
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

    /** Builds a String from Unicode code points, keeping the source ASCII. */
    private fun cp(vararg codePoints: Int): String =
        buildString { codePoints.forEach { appendCodePoint(it) } }

    // ---------- Fresh state ----------

    @Test
    fun `fresh repository has no custom names`() = runTest {
        val repo = createRepository()
        assertThat(repo.hasCustomNameForPackage(pkgA)).isFalse()
    }

    @Test
    fun `fresh repository getAllCustomNames returns empty map`() = runTest {
        val repo = createRepository()
        assertThat(repo.getAllCustomNames()).isEqualTo(emptyMap<String, String>())
    }

    @Test
    fun `getDisplayNameForPackage with no custom name returns originalName`() = runTest {
        val repo = createRepository()
        assertThat(repo.getDisplayNameForPackage(pkgA, "Original")).isEqualTo("Original")
    }

    // ---------- setCustomNameForPackage ----------

    @Test
    fun `setCustomNameForPackage returns true on success`() = runTest {
        val repo = createRepository()
        assertThat(repo.setCustomNameForPackage(pkgA, "Custom A")).isTrue()
    }

    @Test
    fun `setCustomNameForPackage persists the name`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "Custom A")
        assertThat(repo.hasCustomNameForPackage(pkgA)).isTrue()
        assertThat(repo.getDisplayNameForPackage(pkgA, "Original")).isEqualTo("Custom A")
    }

    @Test
    fun `setCustomNameForPackage trims whitespace from custom name`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "  Padded  ")
        assertThat(repo.getDisplayNameForPackage(pkgA, "Original")).isEqualTo("Padded")
    }

    @Test
    fun `setCustomNameForPackage overwrites previous custom name`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "First")
        repo.setCustomNameForPackage(pkgA, "Second")
        assertThat(repo.getDisplayNameForPackage(pkgA, "Original")).isEqualTo("Second")
    }

    @Test
    fun `setCustomNameForPackage on one package does not affect another`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "Name A")
        assertThat(repo.hasCustomNameForPackage(pkgB)).isFalse()
        assertThat(repo.getDisplayNameForPackage(pkgB, "OriginalB")).isEqualTo("OriginalB")
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
        assertThat(repo.hasCustomNameForPackage(pkgA)).isTrue()

        repo.setCustomNameForPackage(pkgA, "")

        assertThat(repo.hasCustomNameForPackage(pkgA)).isFalse()
        assertThat(repo.getDisplayNameForPackage(pkgA, "Original")).isEqualTo("Original")
    }

    @Test
    fun `setCustomNameForPackage with whitespace-only string removes the custom name`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "Custom")
        assertThat(repo.hasCustomNameForPackage(pkgA)).isTrue()

        repo.setCustomNameForPackage(pkgA, "   ")

        assertThat(repo.hasCustomNameForPackage(pkgA)).isFalse()
    }

    /**
     * A combining-mark-only name (a lone U+0301, visually empty) counts as
     * effectively blank, so the single-set path removes the entry rather than
     * persisting an invisible label. `isBlank()` alone would miss this — a
     * combining mark is not whitespace — so both fake and impl guard with
     * `isEffectivelyBlank()`.
     */
    @Test
    fun `setCustomNameForPackage with a combining-mark-only string removes the custom name`() =
        runTest {
            val repo = createRepository()
            repo.setCustomNameForPackage(pkgA, "Custom")
            assertThat(repo.hasCustomNameForPackage(pkgA)).isTrue()

            repo.setCustomNameForPackage(pkgA, cp(0x0301))

            assertThat(repo.hasCustomNameForPackage(pkgA)).isFalse()
        }

    /**
     * The blank guard must not over-reach: an emoji-only name (U+1F41B) renders
     * fine and is a legitimate custom name, so it is persisted, not treated as a
     * remove.
     */
    @Test
    fun `setCustomNameForPackage keeps an emoji-only name`() = runTest {
        val repo = createRepository()
        val emoji = cp(0x1F41B)
        repo.setCustomNameForPackage(pkgA, emoji)
        assertThat(repo.hasCustomNameForPackage(pkgA)).isTrue()
        assertThat(repo.getDisplayNameForPackage(pkgA, "Original")).isEqualTo(emoji)
    }

    // ---------- removeCustomNameForPackage ----------

    @Test
    fun `removeCustomNameForPackage removes existing custom name`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "Custom A")
        assertThat(repo.removeCustomNameForPackage(pkgA)).isTrue()
        assertThat(repo.hasCustomNameForPackage(pkgA)).isFalse()
    }

    /**
     * KNOWN CONTRACT DIVERGENCE — wird auf dem Fake rot. Siehe Klassen-KDoc
     * für die volle Begründung und den Fix-Vorschlag.
     */
    @Test
    fun `removeCustomNameForPackage on non-existent package is idempotent and returns true`() =
        runTest {
            val repo = createRepository()
            assertThat(repo.removeCustomNameForPackage(pkgA)).isTrue()
            assertThat(repo.hasCustomNameForPackage(pkgA)).isFalse()
        }

    @Test
    fun `removeCustomNameForPackage on one package does not affect another`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "Name A")
        repo.setCustomNameForPackage(pkgB, "Name B")
        repo.removeCustomNameForPackage(pkgA)
        assertThat(repo.hasCustomNameForPackage(pkgA)).isFalse()
        assertThat(repo.hasCustomNameForPackage(pkgB)).isTrue()
        assertThat(repo.getDisplayNameForPackage(pkgB, "Original")).isEqualTo("Name B")
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
            assertThat(awaitItem()).isEqualTo(emptyMap<String, String>())

            repo.setCustomNameForPackage(pkgA, "Name A")
            assertThat(awaitItem()).isEqualTo(mapOf(pkgA to "Name A"))

            repo.setCustomNameForPackage(pkgB, "Name B")
            assertThat(awaitItem()).isEqualTo(mapOf(pkgA to "Name A", pkgB to "Name B"))

            repo.removeCustomNameForPackage(pkgA)
            assertThat(awaitItem()).isEqualTo(mapOf(pkgB to "Name B"))

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------- getDisplayNameForPackage ----------

    @Test
    fun `getDisplayNameForPackage returns custom name when set`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "Custom A")
        assertThat(repo.getDisplayNameForPackage(pkgA, "Original")).isEqualTo("Custom A")
    }

    @Test
    fun `getDisplayNameForPackage falls back to originalName after remove`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "Custom A")
        repo.removeCustomNameForPackage(pkgA)
        assertThat(repo.getDisplayNameForPackage(pkgA, "Original")).isEqualTo("Original")
    }

    // ---------- hasCustomNameForPackage ----------

    @Test
    fun `hasCustomNameForPackage returns false initially`() = runTest {
        val repo = createRepository()
        assertThat(repo.hasCustomNameForPackage(pkgA)).isFalse()
    }

    @Test
    fun `hasCustomNameForPackage returns true after set`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "Custom")
        assertThat(repo.hasCustomNameForPackage(pkgA)).isTrue()
    }

    @Test
    fun `hasCustomNameForPackage returns false after remove`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "Custom")
        repo.removeCustomNameForPackage(pkgA)
        assertThat(repo.hasCustomNameForPackage(pkgA)).isFalse()
    }

    // ---------- getAllCustomNames ----------

    @Test
    fun `getAllCustomNames returns all set names`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "Name A")
        repo.setCustomNameForPackage(pkgB, "Name B")
        repo.setCustomNameForPackage(pkgC, "Name C")

        val all = repo.getAllCustomNames()

        assertThat(all.size).isEqualTo(3)
        assertThat(all[pkgA]).isEqualTo("Name A")
        assertThat(all[pkgB]).isEqualTo("Name B")
        assertThat(all[pkgC]).isEqualTo("Name C")
    }

    @Test
    fun `getAllCustomNames does not include removed names`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "Name A")
        repo.setCustomNameForPackage(pkgB, "Name B")
        repo.removeCustomNameForPackage(pkgA)

        val all = repo.getAllCustomNames()

        assertThat(all.size).isEqualTo(1)
        assertThat(all[pkgB]).isEqualTo("Name B")
    }

    @Test
    fun `getAllCustomNames returns trimmed names`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "  Padded  ")
        assertThat(repo.getAllCustomNames()[pkgA]).isEqualTo("Padded")
    }

    // ---------- setCustomNamesInBatch ----------

    @Test
    fun `setCustomNamesInBatch with empty map returns true`() = runTest {
        val repo = createRepository()
        assertThat(repo.setCustomNamesInBatch(emptyMap())).isTrue()
    }

    @Test
    fun `setCustomNamesInBatch with empty map does not modify state`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "Existing")
        repo.setCustomNamesInBatch(emptyMap())
        assertThat(repo.getDisplayNameForPackage(pkgA, "Original")).isEqualTo("Existing")
    }

    @Test
    fun `setCustomNamesInBatch sets all names from input`() = runTest {
        val repo = createRepository()
        repo.setCustomNamesInBatch(mapOf(pkgA to "A", pkgB to "B", pkgC to "C"))

        assertThat(repo.getDisplayNameForPackage(pkgA, "OriginalA")).isEqualTo("A")
        assertThat(repo.getDisplayNameForPackage(pkgB, "OriginalB")).isEqualTo("B")
        assertThat(repo.getDisplayNameForPackage(pkgC, "OriginalC")).isEqualTo("C")
    }

    @Test
    fun `setCustomNamesInBatch returns true on success`() = runTest {
        val repo = createRepository()
        assertThat(repo.setCustomNamesInBatch(mapOf(pkgA to "A"))).isTrue()
    }

    @Test
    fun `setCustomNamesInBatch trims whitespace from names`() = runTest {
        val repo = createRepository()
        repo.setCustomNamesInBatch(mapOf(pkgA to "  Padded  "))
        assertThat(repo.getDisplayNameForPackage(pkgA, "Original")).isEqualTo("Padded")
    }

    @Test
    fun `setCustomNamesInBatch overwrites existing names`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "Old")
        repo.setCustomNamesInBatch(mapOf(pkgA to "New"))
        assertThat(repo.getDisplayNameForPackage(pkgA, "Original")).isEqualTo("New")
    }

    @Test
    fun `setCustomNamesInBatch preserves names not in the batch`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "Keep")
        repo.setCustomNamesInBatch(mapOf(pkgB to "New"))

        assertThat(repo.getDisplayNameForPackage(pkgA, "Original")).isEqualTo("Keep")
        assertThat(repo.getDisplayNameForPackage(pkgB, "Original")).isEqualTo("New")
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
        assertThat(repo.getDisplayNameForPackage(pkgA, "OriginalA")).isEqualTo("Keep me")
        // pkgB wurde nie gesetzt, Original-Fallback.
        assertThat(repo.hasCustomNameForPackage(pkgB)).isFalse()
        // pkgC wurde gesetzt.
        assertThat(repo.getDisplayNameForPackage(pkgC, "OriginalC")).isEqualTo("Valid")
    }

    /**
     * A combining-mark-only value (a lone U+0301, visually empty) is effectively
     * blank, so the batch path IGNORES it — same asymmetry as an empty/whitespace
     * value: the existing entry is preserved, not removed.
     */
    @Test
    fun `setCustomNamesInBatch ignores a combining-mark-only name`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "Keep me")

        repo.setCustomNamesInBatch(mapOf(pkgA to cp(0x0301), pkgB to "Valid"))

        assertThat(repo.getDisplayNameForPackage(pkgA, "OriginalA")).isEqualTo("Keep me")
        assertThat(repo.getDisplayNameForPackage(pkgB, "OriginalB")).isEqualTo("Valid")
    }

    // ---------- purgeRepository ----------

    @Test
    fun `purgeRepository clears all custom names`() = runTest {
        val repo = createRepository()
        repo.setCustomNameForPackage(pkgA, "A")
        repo.setCustomNameForPackage(pkgB, "B")

        repo.purgeRepository()

        assertThat(repo.hasCustomNameForPackage(pkgA)).isFalse()
        assertThat(repo.hasCustomNameForPackage(pkgB)).isFalse()
        assertThat(repo.getAllCustomNames()).isEqualTo(emptyMap<String, String>())
    }

    @Test
    fun `purgeRepository on fresh repository is safe`() = runTest {
        val repo = createRepository()
        repo.purgeRepository()
        assertThat(repo.getAllCustomNames()).isEqualTo(emptyMap<String, String>())
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
        assertThat(all).isNotNull()
        assertThat(all.keys).isEqualTo(setOf(pkgA, pkgB))
        assertThat(all[pkgA]).isEqualTo("Name A")
        assertThat(all[pkgB]).isEqualTo("Name B")
    }
}
