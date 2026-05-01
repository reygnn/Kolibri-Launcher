package com.github.reygnn.kolibri_launcher.ui.settings

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.ui.base.BaseActivity
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

@AndroidEntryPoint
class SettingsActivity : BaseActivity<UiEvent, SettingsViewModel>() {

    override val viewModel: SettingsViewModel by viewModels()

    private var backPressedCallback: OnBackPressedCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
         super.onCreate(savedInstanceState)

        try {
            setContentView(R.layout.activity_settings)

            setupUI()
            setupBackPressHandling()

            // CRASH-SAFE: Fragment nur laden wenn kein saved state
            if (savedInstanceState == null) {
                loadSettingsFragment()
            }

            // throw RuntimeException("ACRA Test Crash")

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Fatal error in onCreate")
            finish() // Graceful exit
        }
    }

    internal fun initialize() {
        // Aktuell leer, aber vorhanden für Konsistenz und zukünftige Lade-Logik.
    }

    override fun onDestroy() {
        try {
            // CRASH-SAFE: Cleanup
            backPressedCallback?.remove()
            backPressedCallback = null
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onDestroy")
        } finally {
            super.onDestroy()
        }
    }

    private fun setupUI() {
        // CRASH-SAFE: ActionBar kann null sein
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.settings_title)
        } ?: run {
            Timber.w("ActionBar is null, skipping toolbar setup")
        }

        // CRASH-SAFE: Safe findViewById mit null check
        findViewById<View>(android.R.id.content)?.let { contentView ->
            ViewCompat.setOnApplyWindowInsetsListener(contentView) { view, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(
                    systemBars.left,
                    systemBars.top,
                    systemBars.right,
                    systemBars.bottom
                )
                insets
            }
        } ?: run {
            Timber.w("Content view not found")
        }
    }

    private fun setupBackPressHandling() {
        backPressedCallback = object : OnBackPressedCallback(true) {
            // Inner catch kept: FragmentManager.popBackStack can throw
            // IllegalStateException after onSaveInstanceState; finish()
            // gives the user an exit if back-press handling itself
            // breaks down.
            override fun handleOnBackPressed() {
                try {
                    if (supportFragmentManager.backStackEntryCount > 0) {
                        supportFragmentManager.popBackStack()
                    } else {
                        finish()
                    }
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error handling back press")
                    finish() // Fallback
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backPressedCallback!!)
    }

    private fun loadSettingsFragment() {
        // EXPECTED: FragmentTransaction.commitAllowingStateLoss can still
        // throw IllegalStateException in edge cases (Activity destroyed
        // between onCreate and the commit). Activity remains usable
        // without the SettingsFragment shown — partially-broken is
        // better than crash.
        try {
            supportFragmentManager.beginTransaction()
                .replace(
                    android.R.id.content,
                    SettingsFragment(),
                    AppConstants.FRAGMENT_SETTINGS
                )
                .commitAllowingStateLoss()
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error loading settings fragment")
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

    /**
     * Implements the abstract method from BaseActivity.
     * This screen's ViewModel only uses generic UiEvents (like ShowToast), which are already
     * handled in the BaseActivity. Therefore, this method can remain empty.
     */
    override fun handleSpecificEvent(event: UiEvent) {
        // No app-specific events are sent from AppNamesViewModel, so this is intentionally empty.
    }
}