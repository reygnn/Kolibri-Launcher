package com.github.reygnn.launcher.feature.backup.container

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * `manifest.json` of the container (E5a). Blobs are referenced from sections by hash
 * only; the table here is the single place that says which blobs exist.
 */
@Serializable
data class ContainerManifest(
    /** "MAJOR.MINOR". A newer major is rejected; a newer minor only adds fields. */
    val formatVersion: String = ContainerFormat.CURRENT_VERSION,
    val producer: Producer,
    /** Version of the producing app's schema (the sections' shape). */
    val schemaVersion: Int,
    val blobs: List<Blob> = emptyList(),
    /** Section id → versioned payload; an unknown section is skipped and reported. */
    val sections: Map<String, Section> = emptyMap(),
) {
    /** Who wrote the backup — lets an app refuse another app's backup with a clear message. */
    @Serializable
    data class Producer(val appId: String, val appVersion: String, val createdAtEpochMillis: Long)

    @Serializable
    data class Blob(val sha256: String, val size: Long, val mediaType: String)

    @Serializable
    data class Section(val version: Int, val data: JsonElement)
}

/** JSON codec for [ContainerManifest]; the container layer itself stays JSON-free. */
object ContainerManifestCodec {

    private val json = Json {
        ignoreUnknownKeys = true // a newer minor may add fields
        encodeDefaults = true
    }

    /** Only the version — readable even when a newer major changed everything else. */
    @Serializable
    private data class VersionProbe(val formatVersion: String)

    fun encode(manifest: ContainerManifest): ByteArray =
        json.encodeToString(ContainerManifest.serializer(), manifest).toByteArray(Charsets.UTF_8)

    /** The full manifest, or null when it is not a readable 1.x manifest. */
    fun decode(bytes: ByteArray): ContainerManifest? = try {
        json.decodeFromString(ContainerManifest.serializer(), bytes.toString(Charsets.UTF_8))
    } catch (e: IllegalArgumentException) { // SerializationException extends it
        null
    }

    /**
     * The header the container layer needs. Reads the version first, so a newer major
     * comes back as such (→ "unsupported version") instead of as "unreadable".
     */
    fun header(bytes: ByteArray): ManifestHeader? {
        val version = try {
            json.decodeFromString(VersionProbe.serializer(), bytes.toString(Charsets.UTF_8)).formatVersion
        } catch (e: IllegalArgumentException) {
            return null
        }
        val (major, minor) = parseVersion(version) ?: return null
        if (major != ContainerFormat.SUPPORTED_MAJOR) return ManifestHeader(major, minor, emptyList())
        val manifest = decode(bytes) ?: return null
        return ManifestHeader(major, minor, manifest.blobs.map { BlobEntry(it.sha256, it.size, it.mediaType) })
    }

    internal fun parseVersion(value: String): Pair<Int, Int>? {
        val parts = value.split('.')
        if (parts.size != 2) return null
        val major = parts[0].toIntOrNull() ?: return null
        val minor = parts[1].toIntOrNull() ?: return null
        return major to minor
    }
}
