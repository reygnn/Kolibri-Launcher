package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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

    // ========== EXISTING TESTS ==========

    @Test
    fun `isComponentHidden returns true for a hidden component`() = runTest {
        fakeDataStore.setInitialData(
            preferencesOf(hiddenComponentsKey to setOf("com.hidden.app/ComponentA"))
        )

        assertThat(hiddenAppsManager.isComponentHidden("com.hidden.app/ComponentA")).isTrue()
    }

    @Test
    fun `isComponentHidden returns false for a visible component`() = runTest {
        fakeDataStore.setInitialData(
            preferencesOf(hiddenComponentsKey to setOf("com.another.app/ComponentB"))
        )

        assertThat(hiddenAppsManager.isComponentHidden("com.visible.app/ComponentC")).isFalse()
    }

    @Test
    fun `hideComponent adds the component to the hidden set`() = runTest {
        fakeDataStore.setInitialData(
            preferencesOf(hiddenComponentsKey to setOf("com.already.hidden/ComponentD"))
        )

        val result = hiddenAppsManager.hideComponent("com.new.to.hide/ComponentE")

        assertThat(result).isTrue()
        assertThat(fakeDataStore.updateDataCallCount > 0).isTrue()
    }

    @Test
    fun `showComponent removes the component from the hidden set`() = runTest {
        fakeDataStore.setInitialData(
            preferencesOf(hiddenComponentsKey to setOf("com.app1/ComponentF", "com.to.show/ComponentG"))
        )

        val result = hiddenAppsManager.showComponent("com.to.show/ComponentG")

        assertThat(result).isTrue()
        assertThat(fakeDataStore.updateDataCallCount > 0).isTrue()
    }

    // ========== NEW CRASH-RESISTANCE TESTS ==========

    @Test
    fun `isComponentHidden - when DataStore fails with IOException - returns false`() = runTest {
        fakeDataStore.makeReadFail()

        assertThat(hiddenAppsManager.isComponentHidden("com.test.app/Component")).isFalse()
    }

    @Test
    fun `isComponentHidden - when DataStore fails with RuntimeException - returns false`() = runTest {
        // Lokales Mock nur für data-Property (kein edit → kein Extension-Function-Problem)
        val brokenStore = mockk<DataStore<Preferences>>()
        every { brokenStore.data } returns flow { throw RuntimeException("Corrupted data") }
        val manager = HiddenAppsRepositoryImpl(brokenStore)

        assertThat(manager.isComponentHidden("com.test.app/Component")).isFalse()
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

        assertThat(result).isFalse()
        assertThat(fakeDataStore.updateDataCallCount > 0).isTrue()
    }

    @Test
    fun `hideComponent - when DataStore edit fails with RuntimeException - returns false`() = runTest {
        fakeDataStore.makeEditFail()

        assertThat(hiddenAppsManager.hideComponent("com.test.app/Component")).isFalse()
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

        assertThat(hiddenAppsManager.showComponent("com.test.app/Component")).isFalse()
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
        assertThat(hiddenAppsManager.hideComponent(null)).isFalse()
    }

    @Test
    fun `hideComponent - with blank componentName - returns false`() = runTest {
        assertThat(hiddenAppsManager.hideComponent("   ")).isFalse()
    }

    @Test
    fun `hideComponent - with malformed componentName - still attempts to hide`() = runTest {
        assertThat(hiddenAppsManager.hideComponent("invalid_format_no_slash")).isTrue()
    }

    @Test
    fun `showComponent - with null componentName - returns false`() = runTest {
        assertThat(hiddenAppsManager.showComponent(null)).isFalse()
    }

    @Test
    fun `showComponent - with blank componentName - returns false`() = runTest {
        assertThat(hiddenAppsManager.showComponent("")).isFalse()
    }

    @Test
    fun `isComponentHidden - with null componentName - returns false`() = runTest {
        assertThat(hiddenAppsManager.isComponentHidden(null)).isFalse()
    }

    @Test
    fun `isComponentHidden - with blank componentName - returns false`() = runTest {
        assertThat(hiddenAppsManager.isComponentHidden("  ")).isFalse()
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

        assertThat(result).isTrue()
        assertThat(hiddenAppsManager.isComponentHidden("com.test.app/Component")).isTrue()
    }

    @Test
    fun `showComponent - when component not hidden - returns true and stays visible (idempotent)`() = runTest {
        val result = hiddenAppsManager.showComponent("com.test.app/Component")

        assertThat(result).isTrue()
        assertThat(hiddenAppsManager.isComponentHidden("com.test.app/Component")).isFalse()
    }

    // ========== MISSING TESTS (Purge & Batch Update) ==========

    @Test
    fun `updateComponentVisibilities - updates DataStore correctly`() = runTest {
        fakeDataStore.setInitialData(
            preferencesOf(hiddenComponentsKey to setOf("com.keep.hidden/A", "com.to.show/B"))
        )

        hiddenAppsManager.updateComponentVisibilities(setOf("com.new.hide/C"), setOf("com.to.show/B"))

        assertThat(fakeDataStore.updateDataCallCount > 0).isTrue()
    }

    @Test
    fun `purgeRepository - removes the hidden components key`() = runTest {
        fakeDataStore.setInitialData(preferencesOf(hiddenComponentsKey to setOf("com.hidden.app/ComponentA")))

        hiddenAppsManager.purgeRepository()

        // Gone, not merely empty (2b-4c-1, ResetCompletenessContract) — an empty set left behind
        // was the old behaviour and must not come back.
        assertThat(fakeDataStore.data.first().contains(hiddenComponentsKey)).isFalse()
    }

    @Test
    fun `purgeRepository - handles exceptions gracefully`() = runTest {
        fakeDataStore.makeEditFail()

        // Should not crash
        hiddenAppsManager.purgeRepository()

        assertThat(fakeDataStore.updateDataCallCount).isEqualTo(1)
    }

    // ========== AUDIT-14 V2: distinctUntilChanged regression ==========

    @Test
    fun `hiddenAppsFlow - unrelated shared-store write does not re-emit identical set`() = runTest {
        // hiddenAppsFlow feeds three combines (drawer, favorites, recents) off the
        // shared settingsDataStore. distinctUntilChanged must stop an unrelated write
        // (per-launch usage tick, sort change, …) from re-running all three for an
        // identical hidden set.
        fakeDataStore.setInitialData(preferencesOf(hiddenComponentsKey to setOf("com.a/A")))

        hiddenAppsManager.hiddenAppsFlow.test {
            assertThat(awaitItem()).isEqualTo(setOf("com.a/A"))

            val usageKey = longPreferencesKey("usage_count_com.other/App")
            fakeDataStore.updateData { prefs ->
                prefs.toMutablePreferences().apply { set(usageKey, 1L) }
            }
            advanceUntilIdle()

            expectNoEvents()
        }
    }

    @Test
    fun `hiddenAppsFlow - still emits when the hidden set actually changes`() = runTest {
        fakeDataStore.setInitialData(preferencesOf(hiddenComponentsKey to setOf("com.a/A")))

        hiddenAppsManager.hiddenAppsFlow.test {
            assertThat(awaitItem()).isEqualTo(setOf("com.a/A"))

            fakeDataStore.updateData { prefs ->
                prefs.toMutablePreferences().apply {
                    set(hiddenComponentsKey, setOf("com.a/A", "com.b/B"))
                }
            }

            assertThat(awaitItem()).isEqualTo(setOf("com.a/A", "com.b/B"))
        }
    }
}