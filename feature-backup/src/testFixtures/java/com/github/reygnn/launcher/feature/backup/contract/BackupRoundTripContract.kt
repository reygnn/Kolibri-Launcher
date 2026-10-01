package com.github.reygnn.launcher.feature.backup.contract

import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Cross-launcher backup round trip (SPEC_NYX_REWRITE A2, 2b-2c). One set of assertions, run
 * once per launcher through its REAL export and import over the shared engine:
 *
 *  - export, then import onto empty stores, restores the exported state (the restore onto a
 *    new device — the case a backup exists for);
 *  - an import with a single option leaves every other part as it was, and a part whose
 *    import replaces the current value (agreed semantics only) takes the backup's value;
 *  - the most valuable stores are written last (U4);
 *  - a backup without wallpaper leaves the current wallpaper standing (E2).
 *
 * Not here, deliberately: importing a part over an existing state where the launcher MERGES
 * (Kolibri's custom names, an empty swipe slot) — open question O3; once decided, a case
 * for it joins this contract or not. [freshStores] is a harness operation (new, empty
 * stores), never the product's factory reset, so the contract does not depend on it.
 *
 * Parts are named by the subclass; [snapshot] returns one comparable value per part. Seeds
 * use normalized component keys only, and the wallpaper part compares layer id, transform
 * and image bytes, never the URI (the import rebinds every image to a new file).
 *
 * Standard project contract shape (`NoAutoPruneContract`): `MainDispatcherRule` +
 * `runTest(testDispatcher)`; the subclass wires the SAME dispatcher into its stores.
 *
 * @param O the launcher's import options type.
 */
abstract class BackupRoundTripContract<O : Any> {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** Two states that differ in every part; both differ from empty stores in every part. */
    enum class Variant { A, B }

    /**
     * Replace every store with fresh ones holding [variant]'s values — every part set, at
     * least one wallpaper layer with an image, component keys already normalized.
     */
    protected abstract suspend fun seed(variant: Variant)

    /** Replace every store with fresh, empty ones (harness operation, not the product's reset). */
    protected abstract suspend fun freshStores()

    /** The current state: part name → comparable value. */
    protected abstract suspend fun snapshot(): Map<String, Any?>

    /** A backup of the current state through the launcher's real export; without wallpaper on request. */
    protected abstract suspend fun export(withWallpaper: Boolean = true): ByteArray

    /** Imports [bytes] through the launcher's real import; true when it reports success. */
    protected abstract suspend fun import(bytes: ByteArray, options: O): Boolean

    protected abstract val allOptions: O

    /** Each option on its own → the parts it covers. Together they cover every part. */
    protected abstract val singleOptions: Map<O, Set<String>>

    /** Parts whose import replaces the current value (agreed semantics; no O3 part). */
    protected abstract val replacingParts: Set<String>

    /** The parts written by the last [import], in order (one entry per store write). */
    protected abstract val writeLog: List<String>

    /** U4: the stores the launcher protects by writing them last. */
    protected abstract val mostValuableParts: Set<String>

    /** The part holding the wallpaper. */
    protected abstract val wallpaperPart: String

    @Test
    fun `export then import onto empty stores restores the exported state`() =
        runTest(mainDispatcherRule.testDispatcher) {
            seed(Variant.A)
            val exported = snapshot()
            val bytes = export()
            freshStores()
            val empty = snapshot()
            for (part in exported.keys) {
                assertWithMessage("seed(A) must give part '$part' a value that empty stores do not have")
                    .that(empty[part]).isNotEqualTo(exported[part])
            }

            assertWithMessage("import onto empty stores must succeed").that(import(bytes, allOptions)).isTrue()

            assertThat(snapshot()).isEqualTo(exported)
        }

    @Test
    fun `an import with one option leaves every other part untouched`() =
        runTest(mainDispatcherRule.testDispatcher) {
            seed(Variant.A)
            val a = snapshot()
            assertWithMessage("the single options must cover every part")
                .that(singleOptions.values.flatten().toSet()).containsExactlyElementsIn(a.keys)

            for ((option, covered) in singleOptions) {
                seed(Variant.A)
                val bytes = export()
                seed(Variant.B)
                val before = snapshot()
                for (part in a.keys) {
                    assertWithMessage("seed(A) and seed(B) must differ in part '$part'").that(before[part]).isNotEqualTo(a[part])
                }

                assertWithMessage("import with only $option must succeed").that(import(bytes, option)).isTrue()

                val after = snapshot()
                for (part in a.keys - covered) {
                    assertWithMessage("part '$part' is not covered by $option and must stay untouched")
                        .that(after[part]).isEqualTo(before[part])
                }
                for (part in covered intersect replacingParts) {
                    assertWithMessage("part '$part' is replaced by $option and must take the backup's value")
                        .that(after[part]).isEqualTo(a[part])
                }
            }
        }

    @Test
    fun `the most valuable stores are written last`() =
        runTest(mainDispatcherRule.testDispatcher) {
            seed(Variant.A)
            val bytes = export()
            seed(Variant.B)

            assertThat(import(bytes, allOptions)).isTrue()

            val log = writeLog
            assertWithMessage("every most valuable store is written").that(log).containsAtLeastElementsIn(mostValuableParts)
            val lastOther = log.indexOfLast { it !in mostValuableParts }
            val firstValuable = log.indexOfFirst { it in mostValuableParts }
            assertWithMessage("U4: no other store may be written after the most valuable ones (writes: $log)")
                .that(lastOther).isLessThan(firstValuable)
        }

    @Test
    fun `a backup without wallpaper leaves the current wallpaper standing`() =
        runTest(mainDispatcherRule.testDispatcher) {
            seed(Variant.A)
            val bytes = export(withWallpaper = false)
            seed(Variant.B)
            val before = snapshot()[wallpaperPart]

            assertThat(import(bytes, allOptions)).isTrue()

            assertWithMessage("E2: a backup without layers must not touch the wallpaper")
                .that(snapshot()[wallpaperPart]).isEqualTo(before)
        }
}
