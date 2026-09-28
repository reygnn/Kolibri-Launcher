package com.github.reygnn.nyx_launcher.data.home

import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import com.github.reygnn.nyx_launcher.home.model.Span
import com.github.reygnn.nyx_launcher.home.testing.RandomHomeLayouts
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

/**
 * Pure JVM: the domain↔DTO mappers must round-trip losslessly. The property run
 * drives the shared [RandomHomeLayouts] generator (the same one the domain
 * invariant property test uses) so folders-with-members, dock apps/folders,
 * varied grids and multi-page layouts all get exercised; the example cases pin
 * the readable corners.
 */
class HomeLayoutMappersTest {

    private fun ck(p: String) = ComponentKey(p, "$p.Main")

    // ---------------------------------------------------------------- property
    @Test
    fun toDto_toDomain_round_trips_every_generated_layout() {
        repeat(2000) { run ->
            val rnd = Random(run.toLong() * 1_000_003L + 7)
            val sc = RandomHomeLayouts.scenario(rnd)
            val layout = RandomHomeLayouts.seed(rnd, sc)

            val restored = layout.toDto().toDomain()

            assertThat(restored).isEqualTo(layout)
        }
    }

    @Test
    fun toDto_always_stamps_current_schema_version() {
        repeat(500) { run ->
            val rnd = Random(run.toLong() * 7919L + 1)
            val layout = RandomHomeLayouts.seed(rnd, RandomHomeLayouts.scenario(rnd))

            assertThat(layout.toDto().schemaVersion).isEqualTo(1)
        }
    }

    // ---------------------------------------------------------------- examples
    @Test
    fun empty_layout_round_trips() {
        val layout = HomeLayout(
            grid = GridSpec(columns = 4, rows = 6),
            pages = 1,
            items = emptyList(),
            dock = emptyList(),
        )

        assertThat(layout.toDto().toDomain()).isEqualTo(layout)
    }

    @Test
    fun mixed_grid_and_dock_folder_round_trips() {
        val layout = HomeLayout(
            grid = GridSpec(columns = 5, rows = 6),
            pages = 2,
            items = listOf(
                PlacedItem(HomeItem.App(ItemId("a"), ck("pa")), CellPos(0, 0, 0)),
                PlacedItem(HomeItem.Folder(ItemId("f"), "Games", listOf(ck("pb"), ck("pc"))), CellPos(1, 1, 2)),
            ),
            dock = listOf(
                HomeItem.App(ItemId("d"), ck("pd")),
                HomeItem.Folder(ItemId("df"), "Tools", listOf(ck("pe"), ck("pf"))),
            ),
        )

        assertThat(layout.toDto().toDomain()).isEqualTo(layout)
    }

    /**
     * The generator only ever emits default [Span] (1×1), so a swapped or dropped
     * span field would round-trip invisibly there. Pin asymmetric spans explicitly
     * so `span.w↔spanW` / `span.h↔spanH` can't silently regress.
     */
    @Test
    fun non_default_spans_round_trip() {
        val layout = HomeLayout(
            grid = GridSpec(columns = 5, rows = 6),
            pages = 1,
            items = listOf(
                PlacedItem(HomeItem.App(ItemId("wide"), ck("pw")), CellPos(0, 0, 0), Span(w = 2, h = 1)),
                PlacedItem(HomeItem.App(ItemId("tall"), ck("pt")), CellPos(0, 3, 0), Span(w = 1, h = 3)),
            ),
            dock = emptyList(),
        )

        assertThat(layout.toDto().toDomain()).isEqualTo(layout)
    }

    // ------------------------------------------------- N15: drop invalid keys on decode
    @Test
    fun a_component_key_dto_decodes_only_when_both_parts_are_present() {
        assertThat(ComponentKeyDto("com.a", "com.a.Main").toDomain()).isEqualTo(ComponentKey("com.a", "com.a.Main"))
        assertThat(ComponentKeyDto("", "com.a.Main").toDomain()).isNull() // empty package
        assertThat(ComponentKeyDto("com.a", "").toDomain()).isNull() // empty class
    }

    @Test
    fun decode_drops_a_grid_item_with_an_invalid_key() {
        // A crafted / cross-device blob with an empty-package key — not validated on the way in
        // until now (§Audit-2 N15). The invalid item is dropped; the valid one survives.
        val dto = HomeLayoutDto(
            columns = 4, rows = 6, pages = 1,
            items = listOf(
                PlacedItemDto(HomeItemDto.AppDto("ok", ComponentKeyDto("com.a", "com.a.Main")), page = 0, x = 0, y = 0),
                PlacedItemDto(HomeItemDto.AppDto("bad", ComponentKeyDto("", "com.b.Main")), page = 0, x = 1, y = 0),
            ),
            dock = emptyList(),
        )

        assertThat(dto.toDomain().items.map { it.item.id.raw }).containsExactly("ok")
    }

    @Test
    fun decode_filters_invalid_folder_members() {
        val dto = HomeLayoutDto(
            columns = 4, rows = 6, pages = 1,
            items = listOf(
                PlacedItemDto(
                    HomeItemDto.FolderDto(
                        "f", "Games",
                        listOf(
                            ComponentKeyDto("com.a", "com.a.Main"),
                            ComponentKeyDto("com.b", ""), // empty class → dropped
                            ComponentKeyDto("com.c", "com.c.Main"),
                        ),
                    ),
                    page = 0, x = 0, y = 0,
                ),
            ),
            dock = emptyList(),
        )

        val folder = dto.toDomain().items.single().item as HomeItem.Folder
        assertThat(folder.members.map { it.packageName }).containsExactly("com.a", "com.c").inOrder()
    }

    @Test
    fun hidden_apps_decode_drops_invalid_keys() {
        val dto = HiddenAppsDto(
            schemaVersion = 1,
            apps = listOf(ComponentKeyDto("com.a", "com.a.Main"), ComponentKeyDto("", "com.b.Main")),
        )
        assertThat(dto.toDomain()).containsExactly(ComponentKey("com.a", "com.a.Main"))
    }
}
