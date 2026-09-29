package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.main.CustomizationDialogModel
import com.github.reygnn.kolibri_launcher.ui.main.CustomizationOption
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/**
 * Pure JVM tests for [CustomizationDialogModel.Companion.build]. No MockK,
 * no Robolectric — same shape as [AppLaunchActionTest], [RenameDecisionTest],
 * [WallpaperSaveActionTest].
 *
 * The Activity's string resolution and click-handler dispatch are not
 * exercised here; this test pins what *should* be in the dialog and in
 * what order, given the two state inputs.
 */
class CustomizationDialogModelTest {

    @get:Rule
    val timberRule = TimberRule()

    // ------------------------------------------------------------------
    // Hidden — wallpaper edit mode suppresses the dialog
    // ------------------------------------------------------------------

    @Test
    fun `build returns Hidden when isWallpaperEditMode is true and no wallpaper`() {
        val result = CustomizationDialogModel.build(
            hasWallpaper = false,
            isWallpaperEditMode = true,
        )
        assertThat(result).isEqualTo(CustomizationDialogModel.Hidden)
    }

    @Test
    fun `build returns Hidden when isWallpaperEditMode is true and wallpaper present`() {
        // hasWallpaper is irrelevant once edit mode is active.
        val result = CustomizationDialogModel.build(
            hasWallpaper = true,
            isWallpaperEditMode = true,
        )
        assertThat(result).isEqualTo(CustomizationDialogModel.Hidden)
    }

    // ------------------------------------------------------------------
    // Visible — option content and ordering
    // ------------------------------------------------------------------

    @Test
    fun `build without wallpaper returns Visible with no edit or remove entries`() {
        val result = CustomizationDialogModel.build(
            hasWallpaper = false,
            isWallpaperEditMode = false,
        )
        val expected = CustomizationDialogModel.Visible(
            options = listOf(
                CustomizationOption.ChooseWallpaper,
                CustomizationOption.CustomizeColors,
                CustomizationOption.CustomizeLayout,
                CustomizationOption.MoreSettings,
            ),
        )
        assertThat(result).isEqualTo(expected)
    }

    @Test
    fun `build with wallpaper returns Visible with edit and remove inserted after choose`() {
        val result = CustomizationDialogModel.build(
            hasWallpaper = true,
            isWallpaperEditMode = false,
        )
        val expected = CustomizationDialogModel.Visible(
            options = listOf(
                CustomizationOption.ChooseWallpaper,
                CustomizationOption.EditWallpaper,
                CustomizationOption.RemoveWallpaper,
                CustomizationOption.CustomizeColors,
                CustomizationOption.CustomizeLayout,
                CustomizationOption.MoreSettings,
            ),
        )
        assertThat(result).isEqualTo(expected)
    }

    // ------------------------------------------------------------------
    // Invariants
    // ------------------------------------------------------------------

    @Test
    fun `Visible options always start with ChooseWallpaper regardless of hasWallpaper`() {
        val withWallpaper = CustomizationDialogModel.build(
            hasWallpaper = true,
            isWallpaperEditMode = false,
        ) as CustomizationDialogModel.Visible
        val withoutWallpaper = CustomizationDialogModel.build(
            hasWallpaper = false,
            isWallpaperEditMode = false,
        ) as CustomizationDialogModel.Visible

        assertThat(withWallpaper.options.first()).isEqualTo(CustomizationOption.ChooseWallpaper)
        assertThat(withoutWallpaper.options.first()).isEqualTo(CustomizationOption.ChooseWallpaper)
    }

    @Test
    fun `Visible options always end with the four non-wallpaper entries in fixed order`() {
        val withWallpaper = CustomizationDialogModel.build(
            hasWallpaper = true,
            isWallpaperEditMode = false,
        ) as CustomizationDialogModel.Visible
        val withoutWallpaper = CustomizationDialogModel.build(
            hasWallpaper = false,
            isWallpaperEditMode = false,
        ) as CustomizationDialogModel.Visible

        // ChooseWallpaper is always first; everything from CustomizeColors
        // onwards forms the trailing fixed block.
        val tail = listOf(
            CustomizationOption.CustomizeColors,
            CustomizationOption.CustomizeLayout,
            CustomizationOption.MoreSettings,
        )
        assertThat(withWallpaper.options.takeLast(3)).isEqualTo(tail)
        assertThat(withoutWallpaper.options.takeLast(3)).isEqualTo(tail)
    }

    @Test
    fun `Visible never includes EditWallpaper or RemoveWallpaper when hasWallpaper is false`() {
        val result = CustomizationDialogModel.build(
            hasWallpaper = false,
            isWallpaperEditMode = false,
        ) as CustomizationDialogModel.Visible
        val forbidden = setOf(
            CustomizationOption.EditWallpaper,
            CustomizationOption.RemoveWallpaper,
        )
        val intersect = result.options.toSet().intersect(forbidden)
        assertThat(intersect).isEqualTo(emptySet<CustomizationOption>())
    }
}
