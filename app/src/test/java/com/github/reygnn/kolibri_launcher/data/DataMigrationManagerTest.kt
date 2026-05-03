package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertFailsWith

class DataMigrationManagerTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK
    private lateinit var context: Context

    // Version-tracking SharedPreferences (the Rule 5 exception — see DMM KDoc)
    @MockK
    private lateinit var sharedPreferences: SharedPreferences
    @MockK
    private lateinit var sharedPreferencesEditor: SharedPreferences.Editor

    // Legacy ACRA-consent SharedPreferences (read-once on V1→V2 migration)
    @MockK
    private lateinit var legacyAcraConsentPrefs: SharedPreferences

    private lateinit var fakeDataStore: FakeDataStore
    private lateinit var dataMigrationManager: DataMigrationManager

    private val VERSION_PREFS_NAME = "kolibri_data_version"
    private val KEY_DATA_VERSION = "data_version"
    private val TARGET_DATA_VERSION = 2

    private val LEGACY_ACRA_CONSENT_PREFS = "acra_consent"
    private val LEGACY_ACRA_KEY_CONSENT = "has_consent"
    private val LEGACY_ACRA_KEY_ASKED = "has_asked"

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        fakeDataStore = FakeDataStore()

        // Version-tracking prefs
        every { context.getSharedPreferences(eq(VERSION_PREFS_NAME), any()) } returns sharedPreferences
        every { sharedPreferences.edit() } returns sharedPreferencesEditor
        every { sharedPreferencesEditor.putInt(any(), any()) } returns sharedPreferencesEditor
        every { sharedPreferencesEditor.commit() } returns true

        // Legacy ACRA consent prefs (V1→V2 migration path)
        every { context.getSharedPreferences(eq(LEGACY_ACRA_CONSENT_PREFS), any()) } returns legacyAcraConsentPrefs
        every { legacyAcraConsentPrefs.getBoolean(eq(LEGACY_ACRA_KEY_CONSENT), any()) } returns false
        every { legacyAcraConsentPrefs.getBoolean(eq(LEGACY_ACRA_KEY_ASKED), any()) } returns false
        every { context.deleteSharedPreferences(eq(LEGACY_ACRA_CONSENT_PREFS)) } returns true

        dataMigrationManager = DataMigrationManager(context, fakeDataStore)
    }

    // ========== EXISTING TESTS ==========

    @Test
    fun `runMigrationIfNeeded - when first installation - sets version without clearing`() = runTest {
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) } returns 0
        fakeDataStore.setInitialData(preferencesOf(stringPreferencesKey("some_key") to "some_value"))

        dataMigrationManager.runMigrationIfNeeded()

        val data = fakeDataStore.data.first()
        Assert.assertFalse("DataStore should not be cleared on first installation", data.asMap().isEmpty())

        verify { sharedPreferencesEditor.putInt(eq(KEY_DATA_VERSION), eq(TARGET_DATA_VERSION)) }
        verify { sharedPreferencesEditor.commit() }
    }

    @Test
    fun `runMigrationIfNeeded - when version is current - does nothing`() = runTest {
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) } returns TARGET_DATA_VERSION
        fakeDataStore.setInitialData(preferencesOf(stringPreferencesKey("some_key") to "some_value"))

        dataMigrationManager.runMigrationIfNeeded()

        val data = fakeDataStore.data.first()
        Assert.assertFalse(data.asMap().isEmpty())

        verify(exactly = 0) { sharedPreferencesEditor.putInt(any(), any()) }
    }

    @Test
    fun `isFirstLaunch - returns true when version is old`() = runTest {
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) } returns 0
        Assert.assertTrue(dataMigrationManager.isFirstLaunch())
    }

    @Test
    fun `isFirstLaunch - returns false when version is current`() = runTest {
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) } returns TARGET_DATA_VERSION
        Assert.assertFalse(dataMigrationManager.isFirstLaunch())
    }

    // ========== CRASH-RESISTANCE TESTS ==========

    @Test
    fun `runMigrationIfNeeded - when DataStore migration write fails - still updates version`() = runTest {
        // V1→V2 migration writes to DataStore. If the write fails, the migration
        // step catches + logs, then runMigration still bumps the version so we
        // don't loop forever. User-visible effect: consent dialog re-shown next
        // launch, no privacy violation (default is no consent).
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) } returns 0
        fakeDataStore.makeEditFail()

        dataMigrationManager.runMigrationIfNeeded()

        verify { sharedPreferencesEditor.putInt(eq(KEY_DATA_VERSION), eq(TARGET_DATA_VERSION)) }
    }

    @Test
    fun `runMigrationIfNeeded - when SharedPreferences edit fails - does not crash`() = runTest {
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) } returns 0
        every { sharedPreferencesEditor.commit() } throws RuntimeException("Cannot write preferences")

        dataMigrationManager.runMigrationIfNeeded()
    }

    @Test
    fun `runMigrationIfNeeded - when SharedPreferences putInt fails - does not crash`() = runTest {
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) } returns 0
        every { sharedPreferencesEditor.putInt(any(), any()) } throws RuntimeException("Cannot put int")

        dataMigrationManager.runMigrationIfNeeded()
    }

    @Test
    fun `runMigrationIfNeeded - when CancellationException - propagates it`() = runTest {
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) } returns -1
        fakeDataStore.makeCancellable()

        assertFailsWith<CancellationException> {
            dataMigrationManager.runMigrationIfNeeded()
        }
    }

    @Test
    fun `isFirstLaunch - when SharedPreferences throws exception - returns true`() = runTest {
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) } throws RuntimeException("Cannot read preferences")

        Assert.assertTrue(dataMigrationManager.isFirstLaunch())
    }

    @Test
    fun `isFirstLaunch - when SharedPreferences is null - returns true`() = runTest {
        every { context.getSharedPreferences(eq(VERSION_PREFS_NAME), any()) } returns null
        val managerWithNullPrefs = DataMigrationManager(context, fakeDataStore)

        Assert.assertTrue(managerWithNullPrefs.isFirstLaunch())
    }

    @Test
    fun `runMigrationIfNeeded - called multiple times - only migrates once`() = runTest {
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) }
            .returnsMany(0, TARGET_DATA_VERSION)

        dataMigrationManager.runMigrationIfNeeded()
        dataMigrationManager.runMigrationIfNeeded()

        verify(exactly = 1) { sharedPreferencesEditor.putInt(eq(KEY_DATA_VERSION), eq(TARGET_DATA_VERSION)) }
    }

    @Test
    fun `runMigrationIfNeeded - with negative version number - treats as old version`() = runTest {
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) } returns -1

        dataMigrationManager.runMigrationIfNeeded()

        verify { sharedPreferencesEditor.putInt(eq(KEY_DATA_VERSION), eq(TARGET_DATA_VERSION)) }
    }

    @Test
    fun `runMigrationIfNeeded - with very high version number - does nothing`() = runTest {
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) } returns 999
        fakeDataStore.setInitialData(preferencesOf(stringPreferencesKey("key") to "value"))

        dataMigrationManager.runMigrationIfNeeded()

        val data = fakeDataStore.data.first()
        Assert.assertFalse(data.asMap().isEmpty())
        verify(exactly = 0) { sharedPreferencesEditor.putInt(any(), any()) }
    }

    @Test
    fun `isFirstLaunch - with version equals to target - returns false`() = runTest {
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) } returns TARGET_DATA_VERSION
        Assert.assertFalse(dataMigrationManager.isFirstLaunch())
    }

    @Test
    fun `isFirstLaunch - with version higher than target - returns false`() = runTest {
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) } returns TARGET_DATA_VERSION + 1
        Assert.assertFalse(dataMigrationManager.isFirstLaunch())
    }

    @Test
    fun `runMigrationIfNeeded - when DataStore has no data - still sets version`() = runTest {
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) } returns 0

        dataMigrationManager.runMigrationIfNeeded()

        verify { sharedPreferencesEditor.putInt(eq(KEY_DATA_VERSION), eq(TARGET_DATA_VERSION)) }
        verify { sharedPreferencesEditor.commit() }
    }

    @Test
    fun `runMigrationIfNeeded - when DataStore read fails - still updates version`() = runTest {
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) } returns 0
        fakeDataStore.makeReadFail()

        dataMigrationManager.runMigrationIfNeeded()

        verify { sharedPreferencesEditor.putInt(eq(KEY_DATA_VERSION), eq(TARGET_DATA_VERSION)) }
    }

    // ========== V1→V2 ACRA CONSENT MIGRATION TESTS ==========

    @Test
    fun `migration V1 to V2 - with default-false legacy values - writes them to DataStore`() = runTest {
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) } returns 1
        // Legacy values default to false/false from setup()

        dataMigrationManager.runMigrationIfNeeded()

        val data = fakeDataStore.data.first()
        Assert.assertEquals(false, data[CrashReportConsentStore.HAS_CONSENT_KEY])
        Assert.assertEquals(false, data[CrashReportConsentStore.HAS_ASKED_KEY])
        verify { sharedPreferencesEditor.putInt(eq(KEY_DATA_VERSION), eq(TARGET_DATA_VERSION)) }
    }

    @Test
    fun `migration V1 to V2 - preserves true consent value from legacy SP`() = runTest {
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) } returns 1
        every { legacyAcraConsentPrefs.getBoolean(eq(LEGACY_ACRA_KEY_CONSENT), any()) } returns true
        every { legacyAcraConsentPrefs.getBoolean(eq(LEGACY_ACRA_KEY_ASKED), any()) } returns true

        dataMigrationManager.runMigrationIfNeeded()

        val data = fakeDataStore.data.first()
        Assert.assertEquals(true, data[CrashReportConsentStore.HAS_CONSENT_KEY])
        Assert.assertEquals(true, data[CrashReportConsentStore.HAS_ASKED_KEY])
    }

    @Test
    fun `migration V1 to V2 - deletes legacy SP file after successful DataStore write`() = runTest {
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) } returns 1

        dataMigrationManager.runMigrationIfNeeded()

        verify { context.deleteSharedPreferences(eq(LEGACY_ACRA_CONSENT_PREFS)) }
    }

    @Test
    fun `migration V1 to V2 - when legacy SP read throws - bumps version and skips`() = runTest {
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) } returns 1
        every { legacyAcraConsentPrefs.getBoolean(any(), any()) } throws RuntimeException("legacy SP read failed")

        dataMigrationManager.runMigrationIfNeeded()

        // Migration step caught the exception; version still gets bumped so we
        // don't loop forever. DataStore stays at default (consent dialog will
        // re-prompt on next launch — annoying, not a privacy violation).
        verify { sharedPreferencesEditor.putInt(eq(KEY_DATA_VERSION), eq(TARGET_DATA_VERSION)) }
    }

    @Test
    fun `migration V1 to V2 - on first launch (V0) - also runs and writes default values`() = runTest {
        // V0 → V2 traverses both V1 (no-op) and V2 steps. Migration is idempotent
        // so even on first install it writes default false/false to DataStore.
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) } returns 0

        dataMigrationManager.runMigrationIfNeeded()

        val data = fakeDataStore.data.first()
        Assert.assertEquals(false, data[CrashReportConsentStore.HAS_CONSENT_KEY])
        Assert.assertEquals(false, data[CrashReportConsentStore.HAS_ASKED_KEY])
        verify { sharedPreferencesEditor.putInt(eq(KEY_DATA_VERSION), eq(TARGET_DATA_VERSION)) }
    }

    @Test
    fun `migration V1 to V2 - on already-V2 install - does not run again`() = runTest {
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) } returns TARGET_DATA_VERSION

        dataMigrationManager.runMigrationIfNeeded()

        verify(exactly = 0) { legacyAcraConsentPrefs.getBoolean(any(), any()) }
        verify(exactly = 0) { context.deleteSharedPreferences(any()) }
    }
}
