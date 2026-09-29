package com.github.reygnn.launcher.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Boundary pins for [ComponentKey.isValid], the single format rule shared by the
 * repository fakes (`:domain` fixtures) and the production impls (`:data`) — it
 * guards favorites writes ([com.github.reygnn.kolibri_launcher.data.FavoritesRepositoryImpl])
 * and, transitively, what a backup import is allowed to restore. It had no direct
 * test: the fakes used it, but no test pinned the exact accept/reject edges the
 * KDoc promises. These lock them so a future tweak to the one-line rule can't
 * silently widen or narrow it.
 */
class ComponentKeyTest {

    @Test
    fun `a well-formed flattened component is valid`() {
        assertThat(ComponentKey.isValid("com.example.alpha/com.example.alpha.MainActivity")).isTrue()
        // Minimal shape: one non-empty char on each side of a single separator.
        assertThat(ComponentKey.isValid("a/b")).isTrue()
    }

    @Test
    fun `a bare package name with no class is rejected`() {
        // The garbage the guard exists to reject (KDoc / TODO 15): a package alone
        // is not a launchable component.
        assertThat(ComponentKey.isValid("com.example.alpha")).isFalse()
    }

    @Test
    fun `an empty string is rejected`() {
        assertThat(ComponentKey.isValid("")).isFalse()
    }

    @Test
    fun `an empty package (leading slash) is rejected`() {
        // Deliberately stricter than ComponentName.unflattenFromString, which
        // tolerates an empty package — an empty-package component is never a real app.
        assertThat(ComponentKey.isValid("/com.example.Main")).isFalse()
    }

    @Test
    fun `an empty class (trailing slash) is rejected`() {
        assertThat(ComponentKey.isValid("com.example.alpha/")).isFalse()
    }

    @Test
    fun `a lone separator is rejected`() {
        assertThat(ComponentKey.isValid("/")).isFalse()
    }

    /**
     * A string with more than one '/' is ACCEPTED: only the first separator is
     * inspected (`indexOf`), so everything after it is treated as the "class".
     * This mirrors `ComponentName.unflattenFromString`, which also splits on the
     * first '/' (it would hand back class = "b/c"). Pinned as the current contract
     * — a real component's class cannot contain '/', so tightening this later is a
     * conscious choice, not an accident.
     */
    @Test
    fun `multi-slash is accepted, matching unflatten's first-separator rule`() {
        assertThat(ComponentKey.isValid("pkg/b/c")).isTrue()
    }

    // --- flat: the persistence/backup projection (must equal the historical string) ---

    @Test
    fun `flat is the package and class joined by a single separator`() {
        val key = ComponentKey("com.example.alpha", "com.example.alpha.MainActivity")
        assertThat(key.flat).isEqualTo("com.example.alpha/com.example.alpha.MainActivity")
    }

    // --- parse: the inverse of flat (former scattered substringBefore decomposition) ---

    @Test
    fun `parse splits a well-formed flat string into package and class`() {
        val key = ComponentKey.parse("com.example.alpha/com.example.alpha.MainActivity")
        assertThat(key).isEqualTo(ComponentKey("com.example.alpha", "com.example.alpha.MainActivity"))
    }

    @Test
    fun `parse round-trips flat`() {
        val key = ComponentKey("com.example.alpha", "com.example.alpha.MainActivity")
        assertThat(ComponentKey.parse(key.flat)).isEqualTo(key)
    }

    @Test
    fun `parse rejects a bare package name`() {
        // The former TODO 15 hole: substringBefore('/') on "com.example" returned
        // the whole string; parse returns null so a malformed key can't masquerade.
        assertThat(ComponentKey.parse("com.example")).isNull()
    }

    @Test
    fun `parse rejects empty, leading-slash and trailing-slash forms`() {
        assertThat(ComponentKey.parse("")).isNull()
        assertThat(ComponentKey.parse("/com.example.Main")).isNull()
        assertThat(ComponentKey.parse("com.example.alpha/")).isNull()
    }

    @Test
    fun `parse keeps everything after the first separator as the class`() {
        // Mirrors isValid's first-separator rule (a real class cannot contain '/').
        assertThat(ComponentKey.parse("pkg/b/c")).isEqualTo(ComponentKey("pkg", "b/c"))
    }
}
