package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
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
 * HIDDEN APPS REPOSITORY — CONTRACT TEST
 * ============================================================================
 *
 * Siehe [FavoritesRepositoryContract] für Hintergrund und Konventionen.
 *
 * Strukturell sehr nah am Favorites-Contract: Set-basierte Persistenz mit
 * `add`/`remove`/`isMember`/Flow. Zusätzlich aber eine **atomare
 * Bulk-Operation** [HiddenAppsRepository.updateComponentVisibilities], die
 * beide Operationen in einem Schritt macht. Genau dort sind subtile
 * Divergenzen wahrscheinlich — speziell bei Overlap (eine Komponente
 * gleichzeitig in `toHide` UND `toShow`).
 *
 * SEMANTIK BEI OVERLAP — vertraglich festgeschrieben:
 *   Manager: `currentHidden.toMutableSet().addAll(toHide).removeAll(toShow)` →
 *            `show` gewinnt, weil zuletzt angewendet.
 *   Fake:    `(currentHidden + toHide) - toShow` → algebraisch identisch:
 *            `show` gewinnt.
 *   Beide implementieren also "show wins on conflict". Der entsprechende
 *   Contract-Test fixiert das, damit ein Refactoring auf einer Seite (z.B.
 *   "ich vertausche die Reihenfolge") sofort auf der anderen Seite auffällt.
 *
 * NICHT IM CONTRACT (Manager-spezifisch):
 *   - Logging-Verhalten und konkrete Log-Strings.
 *   - Manager-Early-Return bei `hide` einer bereits versteckten Komponente
 *     (Performance-Optimierung, kein vertragliches Verhalten — Fake macht
 *     einfach idempotente Set-Operation).
 *   - DataStore-IOException-Recovery.
 *
 * @see FakeHiddenAppsRepositoryContractTest
 * @see HiddenAppsRepositoryImplContractTest
 * ============================================================================
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class HiddenAppsRepositoryContract {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    protected abstract fun createRepository(): HiddenAppsRepository

    private val compA = "com.example.a/.MainActivity"
    private val compB = "com.example.b/.MainActivity"
    private val compC = "com.example.c/.MainActivity"

    // ---------- Initial State ----------

    @Test
    fun `fresh repository emits empty set`() = runTest {
        val repo = createRepository()
        assertThat(repo.hiddenAppsFlow.first()).isEqualTo(emptySet<String>())
    }

    @Test
    fun `fresh repository reports nothing as hidden`() = runTest {
        val repo = createRepository()
        assertThat(repo.isComponentHidden(compA)).isFalse()
    }

    // ---------- hideComponent ----------

    @Test
    fun `hideComponent returns true for valid component`() = runTest {
        val repo = createRepository()
        assertThat(repo.hideComponent(compA)).isTrue()
    }

    @Test
    fun `hideComponent persists component to flow`() = runTest {
        val repo = createRepository()
        repo.hideComponent(compA)
        assertThat(compA in repo.hiddenAppsFlow.first()).isTrue()
    }

    @Test
    fun `hideComponent twice with same component is idempotent`() = runTest {
        val repo = createRepository()
        assertThat(repo.hideComponent(compA)).isTrue()
        assertThat(repo.hideComponent(compA)).isTrue()
        assertThat(repo.hiddenAppsFlow.first()).isEqualTo(setOf(compA))
    }

    @Test
    fun `hideComponent returns false for null`() = runTest {
        val repo = createRepository()
        assertThat(repo.hideComponent(null)).isFalse()
    }

    @Test
    fun `hideComponent returns false for empty string`() = runTest {
        val repo = createRepository()
        assertThat(repo.hideComponent("")).isFalse()
    }

    @Test
    fun `hideComponent returns false for whitespace-only string`() = runTest {
        val repo = createRepository()
        assertThat(repo.hideComponent("   ")).isFalse()
    }

    @Test
    fun `hideComponent with blank does not modify state`() = runTest {
        val repo = createRepository()
        repo.hideComponent(compA)
        repo.hideComponent("")
        repo.hideComponent("   ")
        repo.hideComponent(null)
        assertThat(repo.hiddenAppsFlow.first()).isEqualTo(setOf(compA))
    }

    @Test
    fun `hideComponent preserves previously hidden components`() = runTest {
        val repo = createRepository()
        repo.hideComponent(compA)
        repo.hideComponent(compB)
        assertThat(repo.hiddenAppsFlow.first()).isEqualTo(setOf(compA, compB))
    }

    // ---------- showComponent ----------

    @Test
    fun `showComponent returns true for hidden component`() = runTest {
        val repo = createRepository()
        repo.hideComponent(compA)
        assertThat(repo.showComponent(compA)).isTrue()
    }

    @Test
    fun `showComponent removes component from flow`() = runTest {
        val repo = createRepository()
        repo.hideComponent(compA)
        repo.hideComponent(compB)
        repo.showComponent(compA)
        assertThat(repo.hiddenAppsFlow.first()).isEqualTo(setOf(compB))
    }

    @Test
    fun `showComponent returns true for already-visible component`() = runTest {
        // Beide Implementierungen: idempotent. Manager macht Early-Return mit
        // true, Fake führt no-op Set-Minus aus. Vertrag: keine Exception, true,
        // Zustand bleibt leer.
        val repo = createRepository()
        assertThat(repo.showComponent(compA)).isTrue()
        assertThat(repo.hiddenAppsFlow.first()).isEqualTo(emptySet<String>())
    }

    @Test
    fun `showComponent returns false for null and blank`() = runTest {
        val repo = createRepository()
        assertThat(repo.showComponent(null)).isFalse()
        assertThat(repo.showComponent("")).isFalse()
        assertThat(repo.showComponent("   ")).isFalse()
    }

    // ---------- isComponentHidden ----------

    @Test
    fun `isComponentHidden returns true for hidden component`() = runTest {
        val repo = createRepository()
        repo.hideComponent(compA)
        assertThat(repo.isComponentHidden(compA)).isTrue()
    }

    @Test
    fun `isComponentHidden returns false for non-hidden component`() = runTest {
        val repo = createRepository()
        repo.hideComponent(compA)
        assertThat(repo.isComponentHidden(compB)).isFalse()
    }

    @Test
    fun `isComponentHidden returns false for null`() = runTest {
        val repo = createRepository()
        assertThat(repo.isComponentHidden(null)).isFalse()
    }

    @Test
    fun `isComponentHidden returns false for blank`() = runTest {
        val repo = createRepository()
        assertThat(repo.isComponentHidden("")).isFalse()
        assertThat(repo.isComponentHidden("   ")).isFalse()
    }

    // ---------- updateComponentVisibilities (Bulk) ----------

    @Test
    fun `updateComponentVisibilities adds toHide entries`() = runTest {
        val repo = createRepository()
        repo.updateComponentVisibilities(
            componentsToHide = setOf(compA, compB),
            componentsToShow = emptySet()
        )
        assertThat(repo.hiddenAppsFlow.first()).isEqualTo(setOf(compA, compB))
    }

    @Test
    fun `updateComponentVisibilities removes toShow entries`() = runTest {
        val repo = createRepository()
        repo.hideComponent(compA)
        repo.hideComponent(compB)
        repo.updateComponentVisibilities(
            componentsToHide = emptySet(),
            componentsToShow = setOf(compA)
        )
        assertThat(repo.hiddenAppsFlow.first()).isEqualTo(setOf(compB))
    }

    @Test
    fun `updateComponentVisibilities applies both add and remove in one call`() = runTest {
        val repo = createRepository()
        repo.hideComponent(compA)
        repo.updateComponentVisibilities(
            componentsToHide = setOf(compB, compC),
            componentsToShow = setOf(compA)
        )
        assertThat(repo.hiddenAppsFlow.first()).isEqualTo(setOf(compB, compC))
    }

    /**
     * Wichtige Vertrags-Eigenschaft: bei Overlap zwischen `toHide` und `toShow`
     * gewinnt `show` — die Komponente ist NACH dem Aufruf NICHT versteckt.
     *
     * Begründung im Klassen-KDoc oben; der Test fixiert die Konvention, sodass
     * ein Refactoring auf einer Seite ("ich tausche addAll/removeAll-
     * Reihenfolge") sofort auf der anderen Seite auffällt.
     */
    @Test
    fun `updateComponentVisibilities with overlap - show wins`() = runTest {
        val repo = createRepository()
        repo.updateComponentVisibilities(
            componentsToHide = setOf(compA),
            componentsToShow = setOf(compA)
        )
        assertWithMessage("Bei Overlap zwischen toHide und toShow muss show gewinnen").that(repo.isComponentHidden(compA)).isFalse()
        assertThat(repo.hiddenAppsFlow.first()).isEqualTo(emptySet<String>())
    }

    @Test
    fun `updateComponentVisibilities with empty sets is a no-op`() = runTest {
        val repo = createRepository()
        repo.hideComponent(compA)
        repo.updateComponentVisibilities(
            componentsToHide = emptySet(),
            componentsToShow = emptySet()
        )
        assertThat(repo.hiddenAppsFlow.first()).isEqualTo(setOf(compA))
    }

    @Test
    fun `updateComponentVisibilities toShow on never-hidden component is harmless`() = runTest {
        val repo = createRepository()
        repo.updateComponentVisibilities(
            componentsToHide = emptySet(),
            componentsToShow = setOf(compA)
        )
        assertThat(repo.hiddenAppsFlow.first()).isEqualTo(emptySet<String>())
    }

    // ---------- purgeRepository ----------

    @Test
    fun `purgeRepository empties the flow`() = runTest {
        val repo = createRepository()
        repo.hideComponent(compA)
        repo.hideComponent(compB)
        repo.purgeRepository()
        assertThat(repo.hiddenAppsFlow.first()).isEqualTo(emptySet<String>())
    }

    @Test
    fun `purgeRepository on fresh repository is safe`() = runTest {
        val repo = createRepository()
        repo.purgeRepository()
        assertThat(repo.hiddenAppsFlow.first()).isEqualTo(emptySet<String>())
    }
}
