package com.github.reygnn.nyx_launcher.data.home

import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.DrawerFolder
import com.github.reygnn.nyx_launcher.home.model.DrawerFolderId
import com.github.reygnn.nyx_launcher.home.model.DrawerFolders
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** DRAWER_FOLDERS_SPEC §11: DTO roundtrip, undecodable ⇒ null, version field present. */
class DrawerFoldersSerializerTest {

    private val serializer = DrawerFoldersSerializer()
    private fun key(p: String) = ComponentKey(p, "$p.Main")

    private val sample = DrawerFolders(
        listOf(
            DrawerFolder(DrawerFolderId("f1"), "Work", listOf(key("a"), key("b"))),
            DrawerFolder(DrawerFolderId("f2"), "", listOf(key("c"), key("d"), key("e"))),
        ),
    )

    @Test
    fun roundtrip_preserves_folders_titles_and_member_order() {
        assertThat(serializer.deserialize(serializer.serialize(sample))).isEqualTo(sample)
    }

    @Test
    fun empty_roundtrips_to_EMPTY() {
        assertThat(serializer.deserialize(serializer.serialize(DrawerFolders.EMPTY)))
            .isEqualTo(DrawerFolders.EMPTY)
    }

    @Test
    fun undecodable_blob_deserializes_to_null() {
        assertThat(serializer.deserialize("{ not valid json")).isNull()
        assertThat(serializer.deserialize("")).isNull()
    }

    @Test
    fun serialized_form_carries_the_schema_version() {
        assertThat(serializer.serialize(sample)).contains("schemaVersion")
    }
}
