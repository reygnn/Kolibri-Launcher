package com.github.reygnn.kolibri_launcher.ui.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.github.reygnn.kolibri_launcher.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric smoke test for the AUDIT-8 #1 dropped-wallpaper warning
 * (Review 4813864 #4). BackupFragment renders the warning via
 * `resources.getQuantityString(R.plurals.backup_import_wallpaper_layers_dropped,
 * count, count)`. This pins the concrete risks the finding named: the plurals
 * resource exists, carries both quantity forms, formats the count, and — the
 * whole reason a `<plurals>` was used instead of a `%1$d` string — resolves to
 * SINGULAR for exactly one dropped layer (not the ugly "1 layers").
 *
 * Scope: covers the resource + format contract. It does NOT drive the
 * Fragment's trivial `if (count > 0)` guard — a full fragment-launch +
 * Snackbar-text read would be the first of its kind in this codebase and
 * cuts against the project's anti-flakiness stance (Rule 10) for near-zero
 * added value over this test.
 */
@RunWith(RobolectricTestRunner::class)
class BackupImportWarningPluralsTest {

    private val resources =
        ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `one dropped layer renders singular with the count`() {
        val text = resources.getQuantityString(
            R.plurals.backup_import_wallpaper_layers_dropped, 1, 1,
        )
        assertThat(text).contains("1")
        // English default locale: singular "layer", never the plural "layers".
        assertThat(text).doesNotContain("layers")
    }

    @Test
    fun `multiple dropped layers render plural with the count`() {
        val text = resources.getQuantityString(
            R.plurals.backup_import_wallpaper_layers_dropped, 3, 3,
        )
        assertThat(text).contains("3")
        assertThat(text).contains("wallpaper layers")
    }
}
