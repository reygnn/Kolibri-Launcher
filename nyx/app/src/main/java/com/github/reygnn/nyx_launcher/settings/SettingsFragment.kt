package com.github.reygnn.nyx_launcher.settings

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.launcher.feature.crashreporting.consent.ConsentController
import com.github.reygnn.nyx_launcher.BuildConfig
import com.github.reygnn.nyx_launcher.R
import com.github.reygnn.nyx_launcher.home.model.ImportResult
import com.github.reygnn.nyx_launcher.home.repository.PreferencesRepository
import com.github.reygnn.nyx_launcher.home.usecase.ExportLayoutUseCase
import com.github.reygnn.nyx_launcher.home.usecase.ImportLayoutUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Settings as a Material 3 [PreferenceFragmentCompat] with categorised rows
 * (mirrors Kolibri's SettingsFragment). Preferences are NOT auto-persisted to
 * SharedPreferences (`isPersistent = false`) — the DataStore-backed
 * [PreferencesRepository] is the single source of truth: the switch drives it on
 * change and its flow drives the switch's checked state.
 */
@AndroidEntryPoint
class SettingsFragment : PreferenceFragmentCompat() {

    @Inject lateinit var exportLayout: ExportLayoutUseCase
    @Inject lateinit var importLayout: ImportLayoutUseCase
    @Inject lateinit var preferences: PreferencesRepository
    @Inject lateinit var consentController: ConsentController

    private var monochromeSwitch: SwitchPreferenceCompat? = null

    private val createDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let(::doExport)
        }

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(::doImport)
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.nyx_preferences, rootKey)

        monochromeSwitch = findPreference<SwitchPreferenceCompat>("monochrome_icons")?.apply {
            isPersistent = false // DataStore is the source of truth, not SharedPreferences
            setOnPreferenceChangeListener { _, newValue ->
                lifecycleScope.launch { preferences.setMonochromeIcons(newValue as Boolean) }
                true
            }
        }

        findPreference<Preference>("export_layout")?.setOnPreferenceClickListener {
            createDocument.launch("nyx-layout.json")
            true
        }
        findPreference<Preference>("import_layout")?.setOnPreferenceClickListener {
            openDocument.launch(arrayOf("application/json", "*/*"))
            true
        }

        setupDevCommands()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                preferences.monochromeIcons().collect { enabled ->
                    if (monochromeSwitch?.isChecked != enabled) monochromeSwitch?.isChecked = enabled
                }
            }
        }
    }

    /**
     * ACRA dev commands (mirrors Kolibri's), gated by BuildConfig.SHOW_DEV_COMMANDS
     * (always on in debug; release only with -PdevCommands / -PdailyDriver). The
     * whole category is hidden when the flag is off.
     */
    private fun setupDevCommands() {
        val category = findPreference<PreferenceCategory>("dev_commands")
        if (!BuildConfig.SHOW_DEV_COMMANDS) {
            category?.isVisible = false
            return
        }
        findPreference<Preference>("dev_grant_consent")?.setOnPreferenceClickListener {
            consentController.applyConsent(true)
            toast("ACRA consent granted + enabled")
            true
        }
        findPreference<Preference>("dev_throw_test")?.setOnPreferenceClickListener {
            throw RuntimeException("ACRA throw test from Nyx settings (v${BuildConfig.VERSION_NAME})")
        }
        findPreference<Preference>("dev_silent_error")?.setOnPreferenceClickListener {
            TimberWrapper.silentError(
                RuntimeException("ACRA silent-error test from Nyx (v${BuildConfig.VERSION_NAME})"),
                "Nyx ACRA silent-error test",
            )
            true
        }
        findPreference<Preference>("dev_warn_test")?.setOnPreferenceClickListener {
            Timber.w("Nyx ACRA warn test — untagged, must NOT reach the server")
            true
        }
    }

    private fun doExport(uri: Uri) = lifecycleScope.launch {
        val raw = exportLayout()
        val ok = runCatching {
            withContext(Dispatchers.IO) {
                requireContext().contentResolver.openOutputStream(uri)?.use { it.write(raw.toByteArray()) }
                    ?: error("no output stream")
            }
        }.isSuccess
        toast(getString(if (ok) R.string.backup_export_done else R.string.backup_export_failed))
    }

    private fun doImport(uri: Uri) = lifecycleScope.launch {
        val raw = runCatching {
            withContext(Dispatchers.IO) {
                requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }
        }.getOrNull()
        if (raw == null) {
            toast(getString(R.string.backup_import_failed))
            return@launch
        }
        when (importLayout(raw)) {
            ImportResult.Success -> {
                toast(getString(R.string.backup_import_done))
                requireActivity().finish() // back to home, which re-renders from the imported layout
            }
            ImportResult.InvalidData -> toast(getString(R.string.backup_import_invalid))
        }
    }

    private fun toast(text: String) = Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
}
