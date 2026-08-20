package com.github.reygnn.kolibri_launcher.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure-JVM unit tests for [WallpaperLayerBackup.toLayerState] — the domain-side
 * mapping applied to every layer of a restored multi-layer wallpaper.
 *
 * This pins the AUDIT-3 #10 fix at the level where the bug actually lived: a
 * legacy JSON backup can carry layers with `id = null`, and the earlier
 * implementation minted `"layer_${currentTimeMillis()}_restored"` — so N
 * null-id layers restored inside the same millisecond collapsed to identical
 * IDs, violating the process-wide-unique invariant of
 * [WallpaperLayerState.newId]. The ZIP-level round-trip test
 * (`BackupRoundTripMultiLayerWallpaperTest`) uses real, non-null IDs and does
 * NOT exercise this path; this test does.
 */
class WallpaperLayerBackupTest {

    @Test
    fun `toLayerState - many null-id layers produce distinct ids`() {
        // Simulate a legacy multi-layer backup: every layer's id is null.
        // All mapped in one tight loop (well within a single millisecond),
        // which is exactly what the bare-timestamp implementation collided on.
        val backups = List(64) { WallpaperLayerBackup(id = null, imageUri = "file:///w/$it.img") }

        val ids = backups.map { it.toLayerState().id }

        assertThat(ids).hasSize(64)
        assertThat(ids.toSet()).hasSize(64) // no collisions
        assertThat(ids.none { it.isBlank() }).isTrue()
    }

    @Test
    fun `toLayerState - explicit id is preserved`() {
        val restored = WallpaperLayerBackup(id = "layer_persisted_7", imageUri = "file:///a.img")
            .toLayerState()

        assertThat(restored.id).isEqualTo("layer_persisted_7")
    }

    @Test
    fun `toLayerState - transform round-trips`() {
        val restored = WallpaperLayerBackup(
            id = "l",
            imageUri = "file:///a.img",
            scale = 2.3f,
            translateX = 11f,
            translateY = -4f,
        ).toLayerState()

        assertThat(restored.scale).isEqualTo(2.3f)
        assertThat(restored.translateX).isEqualTo(11f)
        assertThat(restored.translateY).isEqualTo(-4f)
    }

    @Test
    fun `toLayerState - empty imageUri collapses to null`() {
        // The mapping guards imageUri with takeIf { isNotEmpty() }, so a
        // hand-edited backup carrying "" must not resurface as an empty URI
        // (which downstream code treats differently from null).
        val restored = WallpaperLayerBackup(
            id = "l",
            imageUri = "",
        ).toLayerState()

        assertThat(restored.imageUri).isNull()
    }
}
