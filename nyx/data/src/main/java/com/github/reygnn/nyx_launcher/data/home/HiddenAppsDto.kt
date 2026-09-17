package com.github.reygnn.nyx_launcher.data.home

import com.github.reygnn.launcher.core.ComponentKey
import kotlinx.serialization.Serializable

/**
 * Persistence DTO for the hidden-apps set — the `@Serializable` mirror kept in `:data` so
 * the `:domain` types stay annotation-free (CLAUDE.md rule 22). Stored as ONE JSON blob
 * under a versioned key, separate from the layout/folders blobs. Reuses [ComponentKeyDto].
 *
 * [schemaVersion] + the versioned key carry migrations; new optional fields must keep
 * defaults so old blobs still decode.
 */
@Serializable
data class HiddenAppsDto(
    val schemaVersion: Int = 1,
    val apps: List<ComponentKeyDto>,
)

// Domain <-> DTO (ComponentKey.toDto / ComponentKeyDto.toDomain live in HomeLayoutMappers,
// same package — reused). A set persists as a list; decode de-duplicates back to a set.
internal fun Set<ComponentKey>.toHiddenDto(): HiddenAppsDto =
    HiddenAppsDto(schemaVersion = 1, apps = map { it.toDto() })

internal fun HiddenAppsDto.toDomain(): Set<ComponentKey> =
    apps.map { it.toDomain() }.toSet()
