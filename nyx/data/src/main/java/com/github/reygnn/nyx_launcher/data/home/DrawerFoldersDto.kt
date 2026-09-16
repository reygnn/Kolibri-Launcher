package com.github.reygnn.nyx_launcher.data.home

import kotlinx.serialization.Serializable

/**
 * Persistence DTOs for drawer folders — the `@Serializable` mirror of the domain
 * graph, kept in `:data` so the `:domain` types stay annotation-free (CLAUDE.md
 * rule 22, DRAWER_FOLDERS_SPEC §4). Stored as ONE JSON blob under a versioned key,
 * separate from the home-layout blob (D-5). Reuses [ComponentKeyDto] (same package).
 *
 * [DrawerFoldersDto.schemaVersion] + the versioned key carry migrations; new
 * optional fields must keep defaults so old blobs still decode.
 */
@Serializable
data class DrawerFoldersDto(
    val schemaVersion: Int = 1,
    val folders: List<DrawerFolderDto>,
)

@Serializable
data class DrawerFolderDto(
    val id: String,
    val title: String,
    val members: List<ComponentKeyDto>,
)
