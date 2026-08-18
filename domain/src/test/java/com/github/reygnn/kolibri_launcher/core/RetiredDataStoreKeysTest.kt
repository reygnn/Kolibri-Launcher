package com.github.reygnn.kolibri_launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [RetiredDataStoreKeys]. The set-canary tests fail on ANY change to the retired set, forcing
 * a reviewer to re-confirm the per-entry safety rationale (each entry must be written by NO current
 * settings-store repo) — the only place a mistake could delete live user data.
 */
class RetiredDataStoreKeysTest {

    @Test
    fun `retired exact keys are pinned - changes must be deliberate and re-justified`() {
        assertEquals(setOf("wallpaper_flattened_path"), RetiredDataStoreKeys.settingsExactKeys)
    }

    @Test
    fun `retired prefixes are pinned - changes must be deliberate and re-justified`() {
        assertEquals(setOf("usage_"), RetiredDataStoreKeys.settingsPrefixes)
    }

    @Test
    fun `isRetiredSettingsKey matches retired exact keys and prefixes`() {
        assertTrue(RetiredDataStoreKeys.isRetiredSettingsKey("wallpaper_flattened_path"))
        assertTrue(RetiredDataStoreKeys.isRetiredSettingsKey("usage_com.example.app"))
        assertTrue(RetiredDataStoreKeys.isRetiredSettingsKey("usage_")) // bare prefix
    }

    @Test
    fun `usage_ prefix retires EVERY usage_-prefixed key - settings store must never hold a live usage_ key`() {
        // The usage_ prefix is the single broadest deletion rule: it retires ANY key starting with
        // "usage_", not only per-app "usage_<package>" entries. This is safe ONLY because no live
        // feature writes a usage_-prefixed key to the SETTINGS store (both usage producers inject
        // @UsageDataStore). The whitelist-of-dead's "a live key must be explicitly listed first"
        // guarantee therefore does NOT hold for this prefix — it over-matches by construction. If a
        // future feature ever needs a live "usage_*" key in the settings store, it must be renamed
        // (or this match narrowed to the exact per-app shape) FIRST, or cleanup will delete it. This
        // canary pins that blast radius so the constraint is visible to the next reviewer.
        assertTrue(RetiredDataStoreKeys.isRetiredSettingsKey("usage_com.foo"))
        assertTrue(RetiredDataStoreKeys.isRetiredSettingsKey("usage_stats_enabled"))
        assertTrue(RetiredDataStoreKeys.isRetiredSettingsKey("usage_anything_at_all"))
    }

    @Test
    fun `isRetiredSettingsKey does NOT match live settings keys`() {
        // A sample of live settings-store keys — must never be classified retired.
        listOf(
            "wallpaper_uri", "wallpaper_scale", "wallpaper_layers_json",
            "favorites", "favorites_order", "hidden_apps", "custom_names",
            "text_color", "app_drawer_mode", "",
        ).forEach { assertFalse("must not retire live key '$it'", RetiredDataStoreKeys.isRetiredSettingsKey(it)) }
    }
}
