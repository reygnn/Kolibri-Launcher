package com.github.reygnn.kolibri_launcher.ui.settings

import android.os.Bundle
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.databinding.ActivitySettingsBinding
import com.github.reygnn.kolibri_launcher.ui.base.BaseActivity
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SettingsActivity : BaseActivity<UiEvent, SettingsViewModel>() {

    override val viewModel: SettingsViewModel by viewModels()

    // CRASH-SAFE: Nullable binding with cleanup in onDestroy.
    private var _binding: ActivitySettingsBinding? = null
    private val binding get() = _binding ?: throw IllegalStateException("Binding accessed after onDestroy")

    private var backPressedCallback: OnBackPressedCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
         super.onCreate(savedInstanceState)

        try {
            // Edge-to-edge, consistent with the sibling activities; the
            // explicit MaterialToolbar replaces the decor ActionBar so content
            // is never hidden behind the bar. fitsSystemWindows on the layout
            // root insets the toolbar below the status bar.
            WindowCompat.setDecorFitsSystemWindows(window, false)

            _binding = ActivitySettingsBinding.inflate(layoutInflater)
            setContentView(binding.root)

            setupUI()
            setupBackPressHandling()

            // CRASH-SAFE: Fragment nur laden wenn kein saved state
            if (savedInstanceState == null) {
                loadSettingsFragment()
            }

            // throw RuntimeException("ACRA Test Crash")

        } catch (e: Throwable) {
            // no suspension point — non-suspend onCreate body, cannot see
            // CancellationException.
            TimberWrapper.silentError(e, "Fatal error in onCreate")
            finish() // Graceful exit
        }
    }

    override fun onDestroy() {
        try {
            // CRASH-SAFE: Cleanup
            backPressedCallback?.remove()
            backPressedCallback = null
            _binding = null
        } catch (e: Throwable) {
            // no suspension point — non-suspend onDestroy teardown, cannot see
            // CancellationException.
            TimberWrapper.silentError(e, "Error in onDestroy")
        } finally {
            super.onDestroy()
        }
    }

    private fun setupUI() {
        setSupportActionBar(binding.settingsToolbar)
        // CRASH-SAFE: ActionBar kann null sein
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.settings_title)
        }
    }

    private fun setupBackPressHandling() {
        val callback = object : OnBackPressedCallback(true) {
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
        backPressedCallback = callback
        onBackPressedDispatcher.addCallback(this, callback)
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
                    R.id.settings_container,
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
