package com.github.reygnn.nyx_launcher.data.home

import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import com.github.reygnn.nyx_launcher.home.model.Span

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
    spanW = span.w,
    spanH = span.h,
)

internal fun HomeItem.toDto(): HomeItemDto = when (this) {
    is HomeItem.App -> HomeItemDto.AppDto(id.raw, key.toDto())
    is HomeItem.Folder -> HomeItemDto.FolderDto(id.raw, title, members.map { it.toDto() })
}

internal fun ComponentKey.toDto(): ComponentKeyDto =
    ComponentKeyDto(packageName, className, userSerial)

// DTO → domain ---------------------------------------------------------------

internal fun HomeLayoutDto.toDomain(): HomeLayout = HomeLayout(
    grid = GridSpec(columns, rows),
    pages = pages,
    items = items.map { it.toDomain() },
    dock = dock.map { it.toDomain() },
)

internal fun PlacedItemDto.toDomain(): PlacedItem = PlacedItem(
    item = item.toDomain(),
    pos = CellPos(page, x, y),
    span = Span(spanW, spanH),
)

internal fun HomeItemDto.toDomain(): HomeItem = when (this) {
    is HomeItemDto.AppDto -> HomeItem.App(ItemId(id), key.toDomain())
    is HomeItemDto.FolderDto -> HomeItem.Folder(ItemId(id), title, members.map { it.toDomain() })
}

internal fun ComponentKeyDto.toDomain(): ComponentKey =
    ComponentKey(packageName, className, userSerial)
