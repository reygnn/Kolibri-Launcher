package com.github.reygnn.nyx_launcher.data.home

import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerBackup
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

/** Pure JVM: the `nyx.backup` section data round-trips, forward-compat, defaults. */
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
            layout = sampleLayout(),
            prefs = NyxBackupPrefs(
                iconStyle = "GRAYSCALE",
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
                WallpaperLayerBackup(id = "l0", imageFileName = "a".repeat(64), scale = 1.5f, translateX = 10f, translateY = -5f, captureSampleSize = 2),
                WallpaperLayerBackup(id = "l1", imageFileName = "b".repeat(64)),
            ),
        )

        val restored = serializer.fromJson(serializer.toJson(backup))

        assertThat(restored).isEqualTo(backup)
    }

    @Test
    fun missing_optional_fields_decode_to_defaults() {
        // Only layout present; everything else absent.
        val restored = serializer.fromJson(Json.parseToJsonElement("""{ "layout": null }"""))

        assertThat(restored).isNotNull()
        assertThat(restored!!.prefs).isNull()
        assertThat(restored.drawerFolders).isNull()
        assertThat(restored.hiddenApps).isNull()
        assertThat(restored.wallpaperLayers).isEmpty()
    }

    @Test
    fun unknown_keys_are_ignored_forward_compat() {
        val json = """{ "futureField": 42, "prefs": { "iconStyle": "MONOCHROME", "brandNewToggle": true } }"""
        val restored = serializer.fromJson(Json.parseToJsonElement(json))

        assertThat(restored).isNotNull()
        assertThat(restored!!.prefs?.iconStyle).isEqualTo("MONOCHROME")
    }

    @Test
    fun section_data_of_the_wrong_shape_returns_null() {
        assertThat(serializer.fromJson(JsonPrimitive("{ not valid json"))).isNull()
        assertThat(serializer.fromJson(Json.parseToJsonElement("""{ "layout": 7 }"""))).isNull()
    }
}
