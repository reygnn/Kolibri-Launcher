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
    fun `isRetiredSettingsKey does NOT match live settings keys`() {
        // A sample of live settings-store keys — must never be classified retired.
        listOf(
            "wallpaper_uri", "wallpaper_scale", "wallpaper_layers_json",
            "favorites", "favorites_order", "hidden_apps", "custom_names",
            "text_color", "app_drawer_mode", "",
        ).forEach { assertFalse("must not retire live key '$it'", RetiredDataStoreKeys.isRetiredSettingsKey(it)) }
    }
}
