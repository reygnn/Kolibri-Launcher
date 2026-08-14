package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Fail-capable guards for [toOnboardingPicker] (RAL-4 map-only Step 1).
 *
 * The list is fed **unsorted**, so a dropped `.sortedByDisplayName()` flips the
 * asserted order and the test goes red — independently of the (currently sorting)
 * upstream `applyCustomNames`, which the real use case runs through and which would
 * otherwise mask a missing consumer sort. Also pins the blank-entry filter.
 */
class ToOnboardingPickerTest {

    @Test
    fun `drops blank entries and sorts by display name`() {
        val input = listOf(
            AppInfo("Zebra", "Zebra", "com.zebra", "com.zebra.Main"),
            AppInfo("Blank", "", "com.blank", "com.blank.Main"),       // blank display name → dropped
            AppInfo("Apple", "Apple", "com.apple", "com.apple.Main"),
            AppInfo("NoPkg", "NoPkg", "", "com.nopkg.Main"),           // blank package → dropped
        )

        val result = input.toOnboardingPicker()

        assertThat(result.map { it.displayName })
            .containsExactly("Apple", "Zebra")
            .inOrder()
    }

    @Test
    fun `empty input yields empty`() {
        assertThat(emptyList<AppInfo>().toOnboardingPicker()).isEmpty()
    }
}
