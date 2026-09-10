package com.github.reygnn.nyx_launcher.data.icon

/** Opaque cache key: `"<pkgHash>-<contentHash>"` (see [IconCacheKey]). */
@JvmInline
value class CacheKey(val raw: String)

/**
 * Which rendering of an icon a key/file refers to. v1 only ever uses [ADAPTIVE];
 * [THEMED] (monochrome + Material-You tint) and [PACK] (icon packs) are reserved
 * so the key space doesn't break when those land (ICON_LOADER_SPEC §8-D4).
 */
enum class IconVariant { ADAPTIVE, THEMED, PACK }
