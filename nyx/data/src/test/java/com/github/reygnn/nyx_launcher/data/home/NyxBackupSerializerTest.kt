package com.github.reygnn.nyx_launcher.data.home

import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerBackup
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure JVM: NyxBackup serialize → deserialize round-trips, forward-compat, defaults. */
class NyxBackupSerializerTest {

    private val serializer = NyxBackupSerializer()

    private fun sampleLayout() = HomeLayoutDto(
        columns = 4, rows = 6, pages = 2,
        items = listOf(
            PlacedItemDto(
                item = HomeItemDto.AppDto("a1", ComponentKeyDto("com.x", "com.x.Main")),
                page = 0, x = 1, y = 2,
            ),
        ),
        dock = listOf(HomeItemDto.AppDto("d1", ComponentKeyDto("com.y", "com.y.Main"))),
    )

    @Test
    fun round_trips_a_full_backup() {
        val backup = NyxBackup(
            schemaVersion = 1,
            timestamp = 123L,
            appVersion = "0.1.2-dev",
            layout = sampleLayout(),
            prefs = NyxBackupPrefs(
                monochromeIcons = true,
                notificationDots = true,
                showAlarm = false,
                showCalendarEvent = true,
                scrimAlpha = 0.25f,
                backdrop = "BLACK",
                surfaceMode = "AUTO",
                fabXFraction = 0.9f,
                fabYFraction = 0.75f,
            ),
            wallpaperLayers = listOf(
                WallpaperLayerBackup(id = "l0", imageFileName = "wallpapers/layer_0.img", scale = 1.5f, translateX = 10f, translateY = -5f, captureSampleSize = 2),
                WallpaperLayerBackup(id = "l1", imageFileName = "wallpapers/layer_1.img"),
            ),
        )

        val restored = serializer.deserialize(serializer.serialize(backup))

        assertThat(restored).isEqualTo(backup)
    }

    @Test
    fun missing_optional_fields_decode_to_defaults() {
        // Only layout present; prefs + wallpaperLayers absent.
        val json = """{ "schemaVersion": 1, "layout": null }"""
        val restored = serializer.deserialize(json)

        assertThat(restored).isNotNull()
        assertThat(restored!!.prefs).isNull()
        assertThat(restored.wallpaperLayers).isEmpty()
        assertThat(restored.timestamp).isEqualTo(0L)
    }

    @Test
    fun unknown_keys_are_ignored_forward_compat() {
        val json = """{ "schemaVersion": 2, "futureField": 42, "prefs": { "monochromeIcons": true, "brandNewToggle": true } }"""
        val restored = serializer.deserialize(json)

        assertThat(restored).isNotNull()
        assertThat(restored!!.schemaVersion).isEqualTo(2)
        assertThat(restored.prefs?.monochromeIcons).isTrue()
    }

    @Test
    fun malformed_json_returns_null() {
        assertThat(serializer.deserialize("{ not valid json")).isNull()
    }
}
