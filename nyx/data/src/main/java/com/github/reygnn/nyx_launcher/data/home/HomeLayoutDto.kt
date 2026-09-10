package com.github.reygnn.nyx_launcher.data.home

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Persistence DTOs for the home layout — the `@Serializable` mirror of the
 * domain graph. They live in `:data` on purpose so the `:domain` classes stay
 * annotation-free (CLAUDE.md rule 22, ICON_HOME_MODEL_SPEC §7-E1). The whole
 * graph is stored as ONE JSON blob under a versioned key.
 *
 * [HomeLayoutDto.schemaVersion] + the versioned key together carry migrations;
 * new optional fields must keep defaults so old blobs still decode.
 */
@Serializable
data class HomeLayoutDto(
    val schemaVersion: Int = 1,
    val columns: Int,
    val rows: Int,
    val pages: Int,
    val items: List<PlacedItemDto>,
    val dock: List<HomeItemDto>,
)

@Serializable
data class PlacedItemDto(
    val item: HomeItemDto,
    val page: Int,
    val x: Int,
    val y: Int,
    val spanW: Int = 1,
    val spanH: Int = 1,
)

@Serializable
sealed interface HomeItemDto {
    @Serializable
    @SerialName("app")
    data class AppDto(val id: String, val key: ComponentKeyDto) : HomeItemDto

    @Serializable
    @SerialName("folder")
    data class FolderDto(
        val id: String,
        val title: String,
        val members: List<ComponentKeyDto>,
    ) : HomeItemDto
}

@Serializable
data class ComponentKeyDto(
    val packageName: String,
    val className: String,
    val userSerial: Long = 0L,
)
