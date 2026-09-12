package com.github.reygnn.nyx_launcher.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.github.reygnn.nyx_launcher.R
import com.google.android.material.appbar.MaterialToolbar
import dagger.hilt.android.AndroidEntryPoint

/**
 * Thin host for [SettingsFragment] (a PreferenceFragmentCompat). Uses an explicit
 * [MaterialToolbar] rather than the decor ActionBar so the content is never hidden
 * behind the bar under Android 15+ edge-to-edge. `@AndroidEntryPoint` is required
 * so the `@AndroidEntryPoint` fragment can attach.
 */
@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<MaterialToolbar>(R.id.settings_toolbar)
            .setNavigationOnClickListener { finish() }
    }
}
