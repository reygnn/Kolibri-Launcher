package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import timber.log.Timber

/**
 * Regression guard for the ACRA false-positive that fired twice in production:
 * a malformed / non-backup file the user picks for preview/import is an
 * EXPECTED external-input failure. [BackupSerializer.parseBackupData] returns
 * null (the contract) and must NOT emit an INTENT-tagged log.
 *
 * Since §23 (report by intent), AcraTree forwards only entries carrying an
 * intent tag (SILENT_ERROR / ACRA_REPORT), NOT by log level. So the reject path
 * may log locally at WARN (that no longer reaches ACRA) — what it must never do
 * is carry an intent tag, which would turn every wrong-file pick back into a
 * crash report.
 *
 * Robolectric: parseBackupData runs org.json (type validation + strict merge),
 * an Android-provided class.
 */
@RunWith(RobolectricTestRunner::class)
class BackupSerializerMalformedInputTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun checkEnvironment() {
            assumeTrue("Skipping Robolectric tests in GitHub CI", System.getenv("CI") == null)
        }
    }

    private val serializer = BackupSerializer()

    private data class LogEntry(val priority: Int, val tag: String?)
    private val recorded = mutableListOf<LogEntry>()
    private val recordingTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            recorded.add(LogEntry(priority, tag))
        }
    }

    @Before
    fun plantTree() {
        Timber.plant(recordingTree)
    }

    @After
    fun uprootTree() {
        Timber.uproot(recordingTree)
    }

    @Test
    fun `backup missing settings returns null without an intent-tagged log`() {
        // Reproduces both reported crashes: kotlinx fails ("Field 'settings' is
        // required … missing") → strict fallback fails ("Missing required field:
        // settings") → null. Neither may carry an intent tag (that would report).
        val result = serializer.parseBackupData("""{"version":"1.0.0"}""")

        assertThat(result).isNull()
        // Report by intent (§23): only SILENT_ERROR / ACRA_REPORT-tagged entries
        // reach ACRA. A malformed-file reject must never carry an intent tag...
        val intentTags = setOf(TimberWrapper.SILENT_LOG_TAG, TimberWrapper.ACRA_REPORT_TAG)
        assertThat(recorded.filter { it.tag in intentTags }).isEmpty()
        // ...and must STILL log locally at WARN (untagged) for diagnostics — so
        // the assertion above can't pass vacuously by the path going silent.
        // WARN = 5 (android.util.Log). The tag is null (untagged local WARN).
        assertThat(recorded.any { it.priority >= 5 && it.tag !in intentTags }).isTrue()
    }
}
