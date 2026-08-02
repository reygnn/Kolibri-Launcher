package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import kotlin.test.assertFailsWith

class HiddenAppsRepositoryImplTest {

    @get:Rule
    val timberRule = TimberRule()

    // FakeDataStore statt mockk<DataStore<Preferences>>:
    // DataStore.edit() ist eine Extension Function — MockK kann sie nicht stubben.
    private lateinit var fakeDataStore: FakeDataStore

    @MockK(relaxed = true)
    private lateinit var context: Context

    private lateinit var hiddenAppsManager: HiddenAppsRepositoryImpl

    private val hiddenComponentsKey = stringSetPreferencesKey("hidden_components_set")

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        fakeDataStore = FakeDataStore()
        hiddenAppsManager = HiddenAppsRepositoryImpl(fakeDataStore)
    }

    @Test
    fun `reconcileHiddenComponents - when DataStore edit fails - propagates (fail-closed)`() = runTest {
        fakeDataStore.setInitialData(preferencesOf(hiddenComponentsKey to setOf("com.app1/Component")))
        fakeDataStore.makeEditFail()
        // Orphan com.app1, predicate reports it absent -> edit attempted -> throws
        // (no swallow; the skip is enforced upstream by runCleanup, §6.6).
        assertFailsWith<IOException> {
            hiddenAppsManager.reconcileHiddenComponents(listOf("com.other/Component")) { false }
        }
    }

    // ========== EXISTING TESTS ==========

    @Test
    fun `isComponentHidden returns true for a hidden component`() = runTest {
        fakeDataStore.setInitialData(
            preferencesOf(hiddenComponentsKey to setOf("com.hidden.app/ComponentA"))
        )

        Assert.assertTrue(hiddenAppsManager.isComponentHidden("com.hidden.app/ComponentA"))
    }

    @Test
    fun `isComponentHidden returns false for a visible component`() = runTest {
        fakeDataStore.setInitialData(
            preferencesOf(hiddenComponentsKey to setOf("com.another.app/ComponentB"))
        )

        Assert.assertFalse(hiddenAppsManager.isComponentHidden("com.visible.app/ComponentC"))
    }

    @Test
    fun `hideComponent adds the component to the hidden set`() = runTest {
        fakeDataStore.setInitialData(
            preferencesOf(hiddenComponentsKey to setOf("com.already.hidden/ComponentD"))
        )

        val result = hiddenAppsManager.hideComponent("com.new.to.hide/ComponentE")

        Assert.assertTrue(result)
        Assert.assertTrue(fakeDataStore.updateDataCallCount > 0)
    }

    @Test
    fun `showComponent removes the component from the hidden set`() = runTest {
        fakeDataStore.setInitialData(
            preferencesOf(hiddenComponentsKey to setOf("com.app1/ComponentF", "com.to.show/ComponentG"))
        )

        val result = hiddenAppsManager.showComponent("com.to.show/ComponentG")

        Assert.assertTrue(result)
        Assert.assertTrue(fakeDataStore.updateDataCallCount > 0)
    }

    // ========== NEW CRASH-RESISTANCE TESTS ==========

    @Test
    fun `isComponentHidden - when DataStore fails with IOException - returns false`() = runTest {
        fakeDataStore.makeReadFail()

        Assert.assertFalse(hiddenAppsManager.isComponentHidden("com.test.app/Component"))
    }

    @Test
    fun `isComponentHidden - when DataStore fails with RuntimeException - returns false`() = runTest {
        // Lokales Mock nur für data-Property (kein edit → kein Extension-Function-Problem)
        val brokenStore = mockk<DataStore<Preferences>>()
        every { brokenStore.data } returns flow { throw RuntimeException("Corrupted data") }
        val manager = HiddenAppsRepositoryImpl(brokenStore)

        Assert.assertFalse(manager.isComponentHidden("com.test.app/Component"))
    }

    @Test
    fun `isComponentHidden - when CancellationException - propagates it`() = runTest {
        val brokenStore = mockk<DataStore<Preferences>>()
        every { brokenStore.data } returns flow { throw CancellationException("Flow cancelled") }
        val manager = HiddenAppsRepositoryImpl(brokenStore)

        assertFailsWith<CancellationException> {
            manager.isComponentHidden("com.test.app/Component")
        }
    }

    @Test
    fun `hideComponent - when DataStore edit fails with IOException - returns false`() = runTest {
        fakeDataStore.makeEditFail()

        val result = hiddenAppsManager.hideComponent("com.test.app/Component")

        Assert.assertFalse(result)
        Assert.assertTrue(fakeDataStore.updateDataCallCount > 0)
    }

    @Test
    fun `hideComponent - when DataStore edit fails with RuntimeException - returns false`() = runTest {
        fakeDataStore.makeEditFail()

        Assert.assertFalse(hiddenAppsManager.hideComponent("com.test.app/Component"))
    }

    @Test
    fun `hideComponent - when CancellationException - propagates it`() = runTest {
        fakeDataStore.makeCancellable()

        assertFailsWith<CancellationException> {
            hiddenAppsManager.hideComponent("com.test.app/Component")
        }
    }

    @Test
    fun `showComponent - when DataStore edit fails with IOException - returns false`() = runTest {
        fakeDataStore.setInitialData(
            preferencesOf(hiddenComponentsKey to setOf("com.test.app/Component"))
        )
        fakeDataStore.makeEditFail()

        Assert.assertFalse(hiddenAppsManager.showComponent("com.test.app/Component"))
    }

    @Test
    fun `showComponent - when CancellationException - propagates it`() = runTest {
        fakeDataStore.setInitialData(
            preferencesOf(hiddenComponentsKey to setOf("com.test.app/Component"))
        )
        fakeDataStore.makeCancellable()

        assertFailsWith<CancellationException> {
            hiddenAppsManager.showComponent("com.test.app/Component")
        }
    }

    @Test
    fun `hideComponent - with null componentName - returns false`() = runTest {
        Assert.assertFalse(hiddenAppsManager.hideComponent(null))
    }

    @Test
    fun `hideComponent - with blank componentName - returns false`() = runTest {
        Assert.assertFalse(hiddenAppsManager.hideComponent("   "))
    }

    @Test
    fun `hideComponent - with malformed componentName - still attempts to hide`() = runTest {
        Assert.assertTrue(hiddenAppsManager.hideComponent("invalid_format_no_slash"))
    }

    @Test
    fun `showComponent - with null componentName - returns false`() = runTest {
        Assert.assertFalse(hiddenAppsManager.showComponent(null))
    }

    @Test
    fun `showComponent - with blank componentName - returns false`() = runTest {
        Assert.assertFalse(hiddenAppsManager.showComponent(""))
    }

    @Test
    fun `isComponentHidden - with null componentName - returns false`() = runTest {
        Assert.assertFalse(hiddenAppsManager.isComponentHidden(null))
    }

    @Test
    fun `isComponentHidden - with blank componentName - returns false`() = runTest {
        Assert.assertFalse(hiddenAppsManager.isComponentHidden("  "))
    }

    // ========== IDEMPOTENCY ==========
    //
    // Note: the earlier zero-write fast path (assert updateDataCallCount == 0)
    // was intentionally dropped when hide/show became atomic — membership is
    // now re-checked on fresh data INSIDE the edit transaction to avoid a
    // lost-update race, so a no-op still enters edit but leaves the set intact.

    @Test
    fun `hideComponent - when component already hidden - returns true and keeps it hidden (idempotent)`() = runTest {
        fakeDataStore.setInitialData(
            preferencesOf(hiddenComponentsKey to setOf("com.test.app/Component"))
        )

        val result = hiddenAppsManager.hideComponent("com.test.app/Component")

        Assert.assertTrue(result)
        Assert.assertTrue(hiddenAppsManager.isComponentHidden("com.test.app/Component"))
    }

    @Test
    fun `showComponent - when component not hidden - returns true and stays visible (idempotent)`() = runTest {
        val result = hiddenAppsManager.showComponent("com.test.app/Component")

        Assert.assertTrue(result)
        Assert.assertFalse(hiddenAppsManager.isComponentHidden("com.test.app/Component"))
    }

    // ========== MISSING TESTS (Purge & Batch Update) ==========

    @Test
    fun `updateComponentVisibilities - updates DataStore correctly`() = runTest {
        fakeDataStore.setInitialData(
            preferencesOf(hiddenComponentsKey to setOf("com.keep.hidden/A", "com.to.show/B"))
        )

        hiddenAppsManager.updateComponentVisibilities(setOf("com.new.hide/C"), setOf("com.to.show/B"))

        Assert.assertTrue(fakeDataStore.updateDataCallCount > 0)
    }

    @Test
    fun `purgeRepository - clears hidden components`() = runTest {
        hiddenAppsManager.purgeRepository()

        Assert.assertTrue(fakeDataStore.updateDataCallCount > 0)
    }

    @Test
    fun `purgeRepository - handles exceptions gracefully`() = runTest {
        fakeDataStore.makeEditFail()

        // Should not crash
        hiddenAppsManager.purgeRepository()

        Assert.assertEquals(1, fakeDataStore.updateDataCallCount)
    }
}