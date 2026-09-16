package com.github.reygnn.nyx_launcher.data.home

import com.github.reygnn.nyx_launcher.home.model.DrawerFolder
import com.github.reygnn.nyx_launcher.home.model.DrawerFolderId
import com.github.reygnn.nyx_launcher.home.model.DrawerFolders

// Domain → DTO ---------------------------------------------------------------
// (ComponentKey.toDto lives in HomeLayoutMappers, same package — reused.)

internal fun DrawerFolders.toDto(): DrawerFoldersDto = DrawerFoldersDto(
    schemaVersion = 1,
    folders = folders.map { it.toDto() },
)

internal fun DrawerFolder.toDto(): DrawerFolderDto = DrawerFolderDto(
    id = id.raw,
    title = title,
    members = members.map { it.toDto() },
)

// DTO → domain ---------------------------------------------------------------

internal fun DrawerFoldersDto.toDomain(): DrawerFolders = DrawerFolders(
    folders = folders.map { it.toDomain() },
)

internal fun DrawerFolderDto.toDomain(): DrawerFolder = DrawerFolder(
    id = DrawerFolderId(id),
    title = title,
    members = members.map { it.toDomain() },
)
