package com.github.reygnn.nyx_launcher.data.icon

import com.github.reygnn.nyx_launcher.home.model.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.IconRef
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure JVM tests for deterministic keying + the evict-by-glob prefix (ICL-INV-5). */
class IconCacheKeyTest {

    private fun sys(pkg: String) = IconRef.System(ComponentKey(pkg, "$pkg.Main"))

    @Test fun same_input_yields_same_key() {
        val a = IconCacheKey.of(sys("com.foo"), 128, IconVariant.ADAPTIVE)
        val b = IconCacheKey.of(sys("com.foo"), 128, IconVariant.ADAPTIVE)
        assertThat(a).isEqualTo(b)
    }

    @Test fun different_size_yields_different_key() {
        val a = IconCacheKey.of(sys("com.foo"), 128, IconVariant.ADAPTIVE)
        val b = IconCacheKey.of(sys("com.foo"), 256, IconVariant.ADAPTIVE)
        assertThat(a).isNotEqualTo(b)
    }

    @Test fun different_variant_yields_different_key() {
        val a = IconCacheKey.of(sys("com.foo"), 128, IconVariant.ADAPTIVE)
        val b = IconCacheKey.of(sys("com.foo"), 128, IconVariant.THEMED)
        assertThat(a).isNotEqualTo(b)
    }

    @Test fun file_name_is_key_plus_webp() {
        val k = IconCacheKey.of(sys("com.foo"), 128, IconVariant.ADAPTIVE)
        assertThat(IconCacheKey.fileName(k)).isEqualTo("${k.raw}.webp")
    }

    @Test fun keys_of_a_package_share_its_prefix() {
        val prefix = IconCacheKey.packagePrefix("com.foo")
        val k1 = IconCacheKey.of(sys("com.foo"), 128, IconVariant.ADAPTIVE)
        val k2 = IconCacheKey.of(sys("com.foo"), 256, IconVariant.ADAPTIVE)
        assertThat(k1.raw).startsWith("$prefix-")
        assertThat(k2.raw).startsWith("$prefix-")
        // A different package does not share the prefix.
        assertThat(IconCacheKey.of(sys("com.bar"), 128, IconVariant.ADAPTIVE).raw)
            .doesNotContain("$prefix-")
    }
}
