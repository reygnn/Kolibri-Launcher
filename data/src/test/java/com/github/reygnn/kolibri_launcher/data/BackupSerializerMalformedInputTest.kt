package com.github.reygnn.kolibri_launcher.data

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
 * null (the contract) and must NOT log at WARN+ — AcraTree forwards WARN+ to
 * ACRA, so a WARN turned every wrong-file pick into a crash report (two per
 * pick: one from the kotlinx catch, one from the strict-parse catch).
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

    private val recordedPriorities = mutableListOf<Int>()
    private val recordingTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            recordedPriorities.add(priority)
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
    fun `backup missing settings returns null without WARN-or-higher logs`() {
        // Reproduces both reported crashes: kotlinx fails ("Field 'settings' is
        // required … missing") → strict fallback fails ("Missing required field:
        // settings") → null. Neither may log at report level.
        val result = serializer.parseBackupData("""{"version":"1.0.0"}""")

        assertThat(result).isNull()
        // Timber priorities mirror android.util.Log: WARN = 5, ERROR = 6,
        // ASSERT = 7. Anything >= WARN(5) is delivered to ACRA by AcraTree.
        assertThat(recordedPriorities.filter { it >= 5 }).isEmpty()
    }
}
