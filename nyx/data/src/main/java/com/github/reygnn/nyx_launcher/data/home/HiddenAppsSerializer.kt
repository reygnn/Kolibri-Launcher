package com.github.reygnn.nyx_launcher.data.home

import com.github.reygnn.launcher.core.ComponentKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * JSON (de)serializer for the hidden-apps set, via the [HiddenAppsDto] mappers.
 * [deserialize] returns `null` on unparseable input; the repository maps that (and a
 * missing blob) to the empty set. Mirrors [DrawerFoldersSerializer].
 */
class HiddenAppsSerializer @Inject constructor() {

    fun serialize(hidden: Set<ComponentKey>): String = JSON.encodeToString(hidden.toHiddenDto())

    fun deserialize(raw: String): Set<ComponentKey>? =
        runCatching { JSON.decodeFromString<HiddenAppsDto>(raw).toDomain() }.getOrNull()

    private companion object {
        val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
