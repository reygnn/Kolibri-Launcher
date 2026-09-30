package com.github.reygnn.nyx_launcher.data.home

import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.PlacedItem

// Domain → DTO ---------------------------------------------------------------

internal fun HomeLayout.toDto(): HomeLayoutDto = HomeLayoutDto(
    schemaVersion = 1,
    columns = grid.columns,
    rows = grid.rows,
    pages = pages,
    items = items.map { it.toDto() },
    dock = dock.map { it.toDto() },
)

internal fun PlacedItem.toDto(): PlacedItemDto = PlacedItemDto(
    item = item.toDto(),
    page = pos.page,
    x = pos.x,
    y = pos.y,
)

internal fun HomeItem.toDto(): HomeItemDto = when (this) {
    is HomeItem.App -> HomeItemDto.AppDto(id.raw, key.toDto())
    is HomeItem.Folder -> HomeItemDto.FolderDto(id.raw, title, members.map { it.toDto() })
}

internal fun ComponentKey.toDto(): ComponentKeyDto =
    ComponentKeyDto(packageName, className)

// DTO → domain ---------------------------------------------------------------

internal fun HomeLayoutDto.toDomain(): HomeLayout = HomeLayout(
    grid = GridSpec(columns, rows),
    pages = pages,
    // Invalid-keyed items are dropped on decode (§Audit-2 N15); the resulting off-grid gaps /
    // sub-two-member folders are then healed by the post-restore reconcile.
    items = items.mapNotNull { it.toDomain() },
    dock = dock.mapNotNull { it.toDomain() },
)

internal fun PlacedItemDto.toDomain(): PlacedItem? =
    item.toDomain()?.let { PlacedItem(item = it, pos = CellPos(page, x, y)) }

internal fun HomeItemDto.toDomain(): HomeItem? = when (this) {
    is HomeItemDto.AppDto -> key.toDomain()?.let { HomeItem.App(ItemId(id), it) }
    is HomeItemDto.FolderDto -> HomeItem.Folder(ItemId(id), title, members.mapNotNull { it.toDomain() })
}

/**
 * Decode a persisted/imported key, dropping it (`null`) when malformed — a crafted or
 * cross-device backup is not otherwise validated on the way in, so an empty package/class
 * would persist as a dead key (§Audit-2 N15). Uses the single shared validity authority.
 */
internal fun ComponentKeyDto.toDomain(): ComponentKey? =
    ComponentKey.of(packageName, className).takeIf { ComponentKey.isValid(it.flat) }
