package com.github.reygnn.nyx_launcher.data.home

import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.repository.LayoutSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

/** JSON implementation of [LayoutSerializer], via the [HomeLayoutDto] mappers. */
class HomeLayoutSerializer @Inject constructor() : LayoutSerializer {

    override fun serialize(layout: HomeLayout): String = JSON.encodeToString(layout.toDto())

    override fun deserialize(raw: String): HomeLayout? =
        runCatching { JSON.decodeFromString<HomeLayoutDto>(raw).toDomain() }.getOrNull()

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
