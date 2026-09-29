package com.github.reygnn.launcher.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins [LazySlotMembership.isMissing] — the single rule nyx home tiles, kolibri
 * favorites and kolibri swipe slots all delegate to, so none of them can drift on the
 * empty-view guard.
 *
 * The load-bearing case is [an empty installed view flags nothing]: it is the clause
 * whose loss greys every tile during cold start and, on a mutating path, risks dropping
 * a still-installed reference. Removing the `installed.isNotEmpty()` guard in
 * [LazySlotMembership.isMissing] turns exactly that test red.
 *
 * Pure JVM: no coroutines / dispatchers, so per the project convention this correctly
 * carries no MainDispatcherRule (the rule is only for dispatcher-touching tests).
 */
class LazySlotMembershipTest {

    @Test
    fun `an absent key is missing when the installed view is non-empty`() {
        assertThat(LazySlotMembership.isMissing("com.gone/.Main", setOf("com.here/.Main"))).isTrue()
    }

    @Test
    fun `a present key is never missing`() {
        assertThat(LazySlotMembership.isMissing("com.here/.Main", setOf("com.here/.Main", "com.other/.Main"))).isFalse()
    }

    @Test
    fun `an empty installed view flags nothing (not-loaded, not all-gone)`() {
        // The cold-start / transient-failure guard: an empty set means "not loaded yet",
        // so NOTHING is missing — never "everything is gone".
        assertThat(LazySlotMembership.isMissing("com.gone/.Main", emptySet())).isFalse()
    }

    @Test
    fun `the rule is generic over the key type`() {
        // nyx uses a structured key type, not a string; a minimal stand-in pins genericity.
        data class Key(val v: Int)
        assertThat(LazySlotMembership.isMissing(Key(9), setOf(Key(1), Key(2)))).isTrue()
        assertThat(LazySlotMembership.isMissing(Key(1), setOf(Key(1), Key(2)))).isFalse()
        assertThat(LazySlotMembership.isMissing(Key(9), emptySet())).isFalse()
    }

    // ---- isPackageMissing (package-grain variant, same empty-view guard) ----

    @Test
    fun `a package with no installed component is missing when the view is non-empty`() {
        assertThat(LazySlotMembership.isPackageMissing("com.gone", setOf(ComponentKey("com.here", "com.here.Main")))).isTrue()
    }

    @Test
    fun `a package is present if any installed component belongs to it`() {
        // Multi-activity package: matching by packageName, so any component counts.
        assertThat(LazySlotMembership.isPackageMissing(
                "com.here",
                setOf(ComponentKey("com.here", "com.here.Alt"), ComponentKey("com.other", "com.other.Main")),
            )).isFalse()
    }

    @Test
    fun `an empty installed view flags no package (not-loaded)`() {
        assertThat(LazySlotMembership.isPackageMissing("com.gone", emptySet())).isFalse()
    }
}
