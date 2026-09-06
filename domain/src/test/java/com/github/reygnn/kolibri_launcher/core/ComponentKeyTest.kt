package com.github.reygnn.kolibri_launcher.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        assertTrue(ComponentKey.isValid("com.example.alpha/com.example.alpha.MainActivity"))
        // Minimal shape: one non-empty char on each side of a single separator.
        assertTrue(ComponentKey.isValid("a/b"))
    }

    @Test
    fun `a bare package name with no class is rejected`() {
        // The garbage the guard exists to reject (KDoc / TODO 15): a package alone
        // is not a launchable component.
        assertFalse(ComponentKey.isValid("com.example.alpha"))
    }

    @Test
    fun `an empty string is rejected`() {
        assertFalse(ComponentKey.isValid(""))
    }

    @Test
    fun `an empty package (leading slash) is rejected`() {
        // Deliberately stricter than ComponentName.unflattenFromString, which
        // tolerates an empty package — an empty-package component is never a real app.
        assertFalse(ComponentKey.isValid("/com.example.Main"))
    }

    @Test
    fun `an empty class (trailing slash) is rejected`() {
        assertFalse(ComponentKey.isValid("com.example.alpha/"))
    }

    @Test
    fun `a lone separator is rejected`() {
        assertFalse(ComponentKey.isValid("/"))
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
        assertTrue(ComponentKey.isValid("pkg/b/c"))
    }
}
