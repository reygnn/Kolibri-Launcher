package com.github.reygnn.kolibri_launcher

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

@AndroidEntryPoint
class SettingsActivity : BaseActivity<SettingsViewModel>() {

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
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Fatal error in onCreate")
            finish() // Graceful exit
        }
    }

    override fun onDestroy() {
        try {
            // CRASH-SAFE: Cleanup
            backPressedCallback?.remove()
            backPressedCallback = null
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in onDestroy")
        } finally {
            super.onDestroy()
        }
    }

    private fun setupUI() {
        try {
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
                    try {
                        val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                        view.setPadding(
                            systemBars.left,
                            systemBars.top,
                            systemBars.right,
                            systemBars.bottom
                        )
                        insets
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error applying window insets")
                        insets
                    }
                }
            } ?: run {
                Timber.w("Content view not found")
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error setting up UI")
        }
    }

    private fun setupBackPressHandling() {
        try {
            backPressedCallback = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    try {
                        if (supportFragmentManager.backStackEntryCount > 0) {
                            supportFragmentManager.popBackStack()
                        } else {
                            finish()
                        }
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error handling back press")
                        finish() // Fallback
                    }
                }
            }
            onBackPressedDispatcher.addCallback(this, backPressedCallback!!)
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error setting up back press handling")
        }
    }

    private fun loadSettingsFragment() {
        try {
            // CRASH-SAFE: commitAllowingStateLoss statt commit
            supportFragmentManager.beginTransaction()
                .replace(
                    android.R.id.content,
                    SettingsFragment(),
                    AppConstants.FRAGMENT_SETTINGS
                )
                .commitAllowingStateLoss()
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error loading settings fragment")
            // Nicht finish() aufrufen - Activity kann trotzdem funktionieren
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return try {
            if (item.itemId == android.R.id.home) {
                onBackPressedDispatcher.onBackPressed()
                true
            } else {
                super.onOptionsItemSelected(item)
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in onOptionsItemSelected")
            false
        }
    }
}