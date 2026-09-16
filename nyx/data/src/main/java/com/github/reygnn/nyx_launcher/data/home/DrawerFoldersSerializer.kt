package com.github.reygnn.nyx_launcher.data.home

import com.github.reygnn.nyx_launcher.home.model.DrawerFolders
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * JSON (de)serializer for drawer folders, via the [DrawerFoldersDto] mappers.
 * [deserialize] returns `null` on unparseable input; the repository maps that (and
 * a missing blob) to [DrawerFolders.EMPTY] (DRAWER_FOLDERS_SPEC §4). A concrete
 * class (not a shared interface): drawer folders are not part of backup/restore
 * yet, so there is no second consumer to abstract for.
 */
class DrawerFoldersSerializer @Inject constructor() {

    fun serialize(folders: DrawerFolders): String = JSON.encodeToString(folders.toDto())

    fun deserialize(raw: String): DrawerFolders? =
        runCatching { JSON.decodeFromString<DrawerFoldersDto>(raw).toDomain() }.getOrNull()

    private companion object {
        // encodeDefaults so the versioned blob always carries `schemaVersion`
        // explicitly (self-describing for future migrations), not only when it
        // differs from the default. ignoreUnknownKeys tolerates forward-added fields.
        val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
