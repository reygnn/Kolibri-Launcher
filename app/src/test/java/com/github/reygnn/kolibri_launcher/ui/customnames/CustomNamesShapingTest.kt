package com.github.reygnn.kolibri_launcher.ui.customnames

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Fail-capable guards for [buildCustomNamesViews] (RAL-4 map-only Step 1).
 *
 * The master list is fed **unsorted** and in a custom-name order that differs from
 * enumeration order, so a dropped `.sortedByDisplayName()` on EITHER view flips the
 * asserted order — the test would go red. This is the guard the old
 * `CustomNamesViewModelTest` could not be: that one runs through the real
 * (currently sorting) `applyCustomNames`, which masks a missing consumer sort.
 * Feeding the pure function directly bypasses that masking.
 */
class CustomNamesShapingTest {

    // Input order is deliberately NOT alphabetical by display name, and the two
    // renamed apps (Mango→"Zeta Rename", Orange→"Alpha Rename") sort to positions
    // different from both their input slot and their original name.
    private val unsortedMaster = listOf(
        AppInfo("Zebra", "Zebra", "com.zebra", "com.zebra.Main"),
        AppInfo("Mango", "Zeta Rename", "com.mango", "com.mango.Main"),
        AppInfo("Apple", "Apple", "com.apple", "com.apple.Main"),
        AppInfo("Orange", "Alpha Rename", "com.orange", "com.orange.Main"),
    )

    @Test
    fun `displayedApps are sorted by display name for a blank query`() {
        val views = buildCustomNamesViews(unsortedMaster, query = "")

        assertThat(views.displayedApps.map { it.displayName })
            .containsExactly("Alpha Rename", "Apple", "Zebra", "Zeta Rename")
            .inOrder()
    }

    @Test
    fun `appsWithCustomNames are sorted by display name (not master or input order)`() {
        val views = buildCustomNamesViews(unsortedMaster, query = "")

        // Only the two renamed apps; sorted by display name, so "Alpha Rename"
        // (Orange) precedes "Zeta Rename" (Mango) despite the reverse input order.
        assertThat(views.appsWithCustomNames.map { it.displayName })
            .containsExactly("Alpha Rename", "Zeta Rename")
            .inOrder()
    }

    @Test
    fun `search filters then sorts the display list`() {
        val views = buildCustomNamesViews(unsortedMaster, query = "rename")

        // Both renamed apps match on display name; result is filtered AND sorted.
        assertThat(views.displayedApps.map { it.displayName })
            .containsExactly("Alpha Rename", "Zeta Rename")
            .inOrder()
    }
}
