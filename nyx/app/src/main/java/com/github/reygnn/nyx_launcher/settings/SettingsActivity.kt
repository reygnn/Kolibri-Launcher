package com.github.reygnn.nyx_launcher.settings

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.reygnn.nyx_launcher.R
import com.github.reygnn.nyx_launcher.home.model.ImportResult
import com.github.reygnn.nyx_launcher.home.usecase.ExportLayoutUseCase
import com.github.reygnn.nyx_launcher.home.repository.PreferencesRepository
import com.github.reygnn.nyx_launcher.home.usecase.ImportLayoutUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
