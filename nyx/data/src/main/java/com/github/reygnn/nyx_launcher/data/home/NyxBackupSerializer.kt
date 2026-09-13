package com.github.reygnn.nyx_launcher.data.home

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * [NyxBackup] ↔ JSON (the `backup.json` manifest inside the ZIP). Pure logic — no
 * Context/Uri/repos — so it's JVM round-trip-testable. Lean kotlinx only:
 * `ignoreUnknownKeys` keeps forward-compat, `encodeDefaults` writes a stable shape,
 * and a failed decode returns null (the caller maps that to InvalidData).
 */
class NyxBackupSerializer @Inject constructor() {

    fun serialize(backup: NyxBackup): String = JSON.encodeToString(backup)

    fun deserialize(raw: String): NyxBackup? =
        runCatching { JSON.decodeFromString<NyxBackup>(raw) }.getOrNull()

    private companion object {
        val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }
    }
}
