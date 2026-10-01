package com.github.reygnn.launcher.core

import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Cross-launcher factory reset (SPEC_NYX_REWRITE A2, 2b-4c): after a launcher's factory reset
 * every DataStore it covers is empty except for keys marked `purge-exempt`, and the wallpaper
 * directory holds no file.
 *
 * The subclass runs the launcher's REAL reset over REAL file-backed DataStores and the real
 * wallpaper directory, so "empty" means the file, not a mock. 2b-4c rule (30.09.): the Nyx
 * subclass first runs against the reset as it is (one `clear()`), and the switch to per-store
 * `Purgeable` purges must keep this contract green — proof that the new reset deletes exactly
 * as much as the old one.
 *
 * Standard project contract shape (`NoAutoPruneContract`): `MainDispatcherRule` +
 * `runTest(testDispatcher)`; the subclass wires the SAME dispatcher into its stores.
 */
abstract class ResetCompletenessContract {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /**
     * Fresh stores filled through the launcher's own repositories with a non-default value in
     * every part a reset covers, at least one wallpaper file included.
     */
    protected abstract suspend fun seedEverything()

    /** The launcher's factory reset, as its UI runs it; true when it reports full success. */
    protected abstract suspend fun factoryReset(): Boolean

    /** DataStore name → names of the keys currently in that store's file. */
    protected abstract suspend fun storedKeys(): Map<String, Set<String>>

    /** File names in the launcher's wallpaper directory. */
    protected abstract fun wallpaperFiles(): Set<String>

    /** Keys a factory reset keeps on purpose (`purge-exempt`), per store. */
    protected open val purgeExempt: Map<String, Set<String>> = emptyMap()

    /**
     * Keys [seedEverything] must have written, per store — the launcher's inventory of what a
     * reset has to remove. Empty to check only that every store got something.
     */
    protected open val inventory: Map<String, Set<String>> = emptyMap()

    @Test
    fun `a factory reset leaves every store empty but for purge-exempt keys and no wallpaper file`() =
        runTest(mainDispatcherRule.testDispatcher) {
            seedEverything()
            val seeded = storedKeys()
            for ((store, keys) in seeded) {
                assertWithMessage("seeding must reach store '$store'").that(keys).isNotEmpty()
            }
            for ((store, keys) in inventory) {
                assertWithMessage("seeding must write the inventory of store '$store'")
                    .that(seeded[store].orEmpty()).containsAtLeastElementsIn(keys)
            }
            assertWithMessage("seeding must leave a wallpaper file").that(wallpaperFiles()).isNotEmpty()

            assertWithMessage("the reset must report full success").that(factoryReset()).isTrue()

            for ((store, keys) in storedKeys()) {
                assertWithMessage("store '$store' after the reset: only purge-exempt keys may remain")
                    .that(keys - purgeExempt[store].orEmpty()).isEmpty()
            }
            assertWithMessage("the reset must leave no wallpaper file").that(wallpaperFiles()).isEmpty()
        }
}
