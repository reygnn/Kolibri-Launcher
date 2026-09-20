package com.github.reygnn.nyx_launcher.data.icon

/** Opaque cache key: `"<pkgHash>-<contentHash>"` (see [IconCacheKey]). */
@JvmInline
value class CacheKey(val raw: String)

/**
 * Which rendering of an icon a key/file refers to, so the disk/memory cache never
 * mixes them: [ADAPTIVE] (original colour), [THEMED] (monochrome layer on the Nyx
 * night disc), [GRAYSCALE] (original desaturated). [PACK] (icon packs) is reserved
 * so the key space doesn't break when it lands (ICON_LOADER_SPEC §8-D4).
 */
enum class IconVariant { ADAPTIVE, THEMED, GRAYSCALE, PACK }
