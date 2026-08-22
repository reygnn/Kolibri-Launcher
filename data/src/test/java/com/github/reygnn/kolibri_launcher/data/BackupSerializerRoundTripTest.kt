package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.model.BackupData
import com.github.reygnn.kolibri_launcher.domain.model.LauncherSettings
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperLayerBackup
import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Full-field sweep round-trip for [BackupSerializer]. Where
 * `BackupRepositoryImplNamingConventionTest`'s round-trip asserts a handful of
 * fields, this asserts EVERY field of [LauncherSettings] via data-class
 * equality: build a fully-populated settings object (every scalar at a
 * distinctive non-default value, every nullable non-null), encode it the way
 * the app does, parse it back through the real parse+strict-merge path, and
 * require `parsed.settings == original`.
 *
 * This is the comprehensive guard AUDIT-8 §3 flagged as missing: it fails the
 * instant ANY field stops round-tripping — the AUDIT-3 #3 class of bug
 * (camelCase written, a key silently reset on read) at full field coverage,
 * not just the ~5 fields an ad-hoc assertion happens to check.
 *
 * Robolectric: [BackupSerializer.parseBackupData] runs `mergeWithStrictValues`
 * over org.json on every decode, and org.json is an Android-provided class.
 */
@RunWith(RobolectricTestRunner::class)
class BackupSerializerRoundTripTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun checkEnvironment() {
            assumeTrue("Skipping Robolectric tests in GitHub CI", System.getenv("CI") == null)
        }

        /**
         * Every field set to a non-default, distinctive value so a silent reset
         * to the type default is unmistakeable. Enum-backed fields use the
         * `name` strings the app persists (`FavoritesAlignment.END`,
         * `SortOrder.ALPHABETICAL`, `WallpaperSurfaceMode.LIGHT`).
         */
        private val FULLY_POPULATED = LauncherSettings(
            favoriteComponents = setOf("com.a/A", "com.b/B"),
            favoritesOrder = listOf("com.b/B", "com.a/A"),
            hiddenComponents = setOf("com.c/C"),
            customAppNames = mapOf("com.a" to "Alpha", "com.b" to "Beta"),
            swipeLeftApp = "com.a/A",
            swipeRightApp = "com.b/B",
            textColor = -111,
            chipBackgroundColor = -222,
            textShadowEnabled = true,
            layoutScale = 1.33f,
            wallpaperScrimAlpha = 0.42f,
            verticalPaddingScale = 0.77f,
            isFontBold = true,
            contentTopMarginScale = 1.11f,
            favoritesAlignment = "END",
            wallpaperSurfaceMode = "LIGHT",
            wallpaperUri = "file:///wp/single.img",
            wallpaperScale = 2.5f,
            wallpaperTranslateX = 9.0f,
            wallpaperTranslateY = -3.0f,
            wallpaperImageFileName = "wallpapers/single.img",
            wallpaperLayers = listOf(
                WallpaperLayerBackup(
                    id = "layer_0",
                    imageUri = "file:///wp/l0.img",
                    imageFileName = "wallpapers/layer_0.img",
                    scale = 1.4f,
                    translateX = 5f,
                    translateY = -6f,
                ),
                WallpaperLayerBackup(
                    id = "layer_1",
                    imageUri = "file:///wp/l1.img",
                    imageFileName = "wallpapers/layer_1.img",
                    scale = 2.0f,
                    translateX = -7f,
                    translateY = 8f,
                ),
            ),
            showCalendarEvent = true,
            showAlarm = true,
            autoShowKeyboard = true,
            autoLaunchApp = true,
            sortOrder = "ALPHABETICAL",
            doubleTapClipboardEnabled = true,
            rotationLocked = true,
        )
    }

    private val serializer = BackupSerializer()

    @Test
    fun `encode then parse - every settings field survives`() {
        val original = BackupData(settings = FULLY_POPULATED)

        val json = serializer.encodeToJsonString(original)
        val parsed = serializer.parseBackupData(json)

        assertThat(parsed).isNotNull()
        // Data-class equality over the whole settings object: any single field
        // that fails to round-trip (silent reset, dropped alias, list drift)
        // trips this assertion.
        assertThat(parsed!!.settings).isEqualTo(FULLY_POPULATED)
    }

    @Test
    fun `encode uses camelCase keys, not snake_case`() {
        // Guards the write side directly: the strict-merge read path is only
        // safe because the app WRITES camelCase (@JsonNames is read-only).
        val json = serializer.encodeToJsonString(BackupData(settings = FULLY_POPULATED))

        assertThat(json).contains("\"swipeLeftApp\":")
        assertThat(json).contains("\"chipBackgroundColor\":")
        assertThat(json).doesNotContain("\"swipe_left_app\":")
        assertThat(json).doesNotContain("\"chip_bg_color\":")
    }
}
