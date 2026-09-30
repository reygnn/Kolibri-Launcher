package com.github.reygnn.nyx_launcher.data.home

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject

/**
 * [NyxBackup] ↔ the JSON data of the `nyx.backup` section. Pure logic — no
 * Context/Uri/repos — so it's JVM round-trip-testable. Lean kotlinx only:
 * `ignoreUnknownKeys` keeps forward-compat, `encodeDefaults` writes a stable shape,
 * and a failed decode returns null (the caller maps that to an invalid backup).
 */
class NyxBackupSerializer @Inject constructor() {

    fun toJson(backup: NyxBackup): JsonElement = JSON.encodeToJsonElement(NyxBackup.serializer(), backup)

    fun fromJson(data: JsonElement): NyxBackup? = try {
        JSON.decodeFromJsonElement(NyxBackup.serializer(), data)
    } catch (e: IllegalArgumentException) { // SerializationException extends it
        null
    }

    private companion object {
        val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
