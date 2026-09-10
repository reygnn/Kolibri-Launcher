package com.github.reygnn.nyx_launcher.settings

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.reygnn.kolibri_launcher.crashreporting.consent.ConsentController
import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.nyx_launcher.BuildConfig
import com.github.reygnn.nyx_launcher.R
import com.github.reygnn.nyx_launcher.home.model.ImportResult
import com.github.reygnn.nyx_launcher.home.usecase.ExportLayoutUseCase
import com.github.reygnn.nyx_launcher.home.repository.PreferencesRepository
import com.github.reygnn.nyx_launcher.home.usecase.ImportLayoutUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Backup / restore of the home layout via the Storage Access Framework. The
 * use-cases own the logic (serialize / parse → save → reconcile); this screen
 * only bridges the picked [Uri] to a byte stream and back.
 */
@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {

    @Inject
    lateinit var exportLayout: ExportLayoutUseCase

    @Inject
    lateinit var importLayout: ImportLayoutUseCase

    @Inject
    lateinit var preferences: PreferencesRepository

    @Inject
    lateinit var consentController: ConsentController

    private val createDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let(::doExport)
        }

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(::doImport)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<Button>(R.id.export_button).setOnClickListener {
            createDocument.launch("nyx-layout.json")
        }
        findViewById<Button>(R.id.import_button).setOnClickListener {
            openDocument.launch(arrayOf("application/json", "*/*"))
        }

        val monochrome = findViewById<Switch>(R.id.monochrome_switch)
        monochrome.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch { preferences.setMonochromeIcons(checked) }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                preferences.monochromeIcons().collect { enabled ->
                    if (monochrome.isChecked != enabled) monochrome.isChecked = enabled
                }
            }
        }

        setupDevCommands()
    }

    /**
     * ACRA dev commands (mirrors Kolibri's), gated by BuildConfig.SHOW_DEV_COMMANDS
     * (always on in debug; release only with -PdevCommands / -PdailyDriver). Grant
     * consent enables ACRA live via [ConsentController.applyConsent]; the throw /
     * silent-error / warn buttons then verify delivery against the server.
     */
    private fun setupDevCommands() {
        val section = findViewById<LinearLayout>(R.id.dev_commands_section)
        if (!BuildConfig.SHOW_DEV_COMMANDS) return
        section.visibility = View.VISIBLE

        findViewById<Button>(R.id.dev_grant_consent).setOnClickListener {
            consentController.applyConsent(true)
            Toast.makeText(this, "ACRA consent granted + enabled", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.dev_throw_test).setOnClickListener {
            throw RuntimeException("ACRA throw test from Nyx settings (v${BuildConfig.VERSION_NAME})")
        }
        findViewById<Button>(R.id.dev_silent_error).setOnClickListener {
            TimberWrapper.silentError(
                RuntimeException("ACRA silent-error test from Nyx (v${BuildConfig.VERSION_NAME})"),
                "Nyx ACRA silent-error test",
            )
        }
        findViewById<Button>(R.id.dev_warn_test).setOnClickListener {
            Timber.w("Nyx ACRA warn test — untagged, must NOT reach the server")
        }
    }

    private fun doExport(uri: Uri) = lifecycleScope.launch {
        val raw = exportLayout()
        val ok = runCatching {
            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(uri)?.use { it.write(raw.toByteArray()) }
                    ?: error("no output stream")
            }
        }.isSuccess
        toast(if (ok) R.string.backup_export_done else R.string.backup_export_failed)
    }

    private fun doImport(uri: Uri) = lifecycleScope.launch {
        val raw = runCatching {
            withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }
        }.getOrNull()
        if (raw == null) {
            toast(R.string.backup_import_failed)
            return@launch
        }
        when (importLayout(raw)) {
            ImportResult.Success -> {
                toast(R.string.backup_import_done)
                finish() // back to home, which re-renders from the imported layout
            }
            ImportResult.InvalidData -> toast(R.string.backup_import_invalid)
        }
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
}
