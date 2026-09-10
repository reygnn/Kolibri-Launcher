package com.github.reygnn.nyx_launcher.data.home

import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure JVM: serialize → deserialize round-trips a mixed layout exactly. */
class HomeLayoutSerializerTest {

    private val serializer = HomeLayoutSerializer()
    private fun ck(p: String) = ComponentKey(p, "$p.Main")

    @Test
    fun round_trips_apps_folders_and_dock() {
        val layout = HomeLayout(
            grid = GridSpec(columns = 4, rows = 6),
            pages = 2,
            items = listOf(
                PlacedItem(HomeItem.App(ItemId("a"), ck("pa")), CellPos(0, 0, 0)),
                PlacedItem(HomeItem.Folder(ItemId("f"), "Games", listOf(ck("pb"), ck("pc"))), CellPos(1, 1, 2)),
            ),
            dock = listOf(HomeItem.App(ItemId("d"), ck("pd"))),
        )

        val restored = serializer.deserialize(serializer.serialize(layout))

        assertThat(restored).isEqualTo(layout)
    }

    @Test
    fun garbage_deserializes_to_null() {
        assertThat(serializer.deserialize("not json at all")).isNull()
    }
}
