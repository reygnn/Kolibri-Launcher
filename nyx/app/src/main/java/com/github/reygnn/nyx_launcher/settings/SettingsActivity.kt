package com.github.reygnn.nyx_launcher.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.github.reygnn.nyx_launcher.R
import dagger.hilt.android.AndroidEntryPoint

/**
 * Thin host for [SettingsFragment] (a PreferenceFragmentCompat). The ActionBar
 * (title + up) comes from Theme.Nyx.Settings; the fragment owns all the logic.
 * `@AndroidEntryPoint` is required so the `@AndroidEntryPoint` fragment can attach.
 */
@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
