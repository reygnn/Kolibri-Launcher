package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import android.content.Intent
import com.github.reygnn.kolibri_launcher.core.PackageEvent
import android.net.Uri
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@ExperimentalCoroutinesApi
class PackageUpdateReceiverTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    @get:Rule
    val timberRule = TimberRule()

    @MockK(relaxed = true)
    private lateinit var context: Context

    @MockK
    private lateinit var intent: Intent

    private lateinit var receiver: PackageUpdateReceiver

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        receiver = spyk(PackageUpdateReceiver())
    }

    // ========== onReceive SAFETY TESTS ==========

    @Test
    fun `onReceive - with null context - does not crash and returns early`() {
        receiver.onReceive(null, intent)
        // Kein Crash = Test bestanden
    }

    @Test
    fun `onReceive - with null intent - does not crash and returns early`() {
        receiver.onReceive(context, null)
        // Kein Crash = Test bestanden
    }

    // ========== handleReceive LOGIC TESTS ==========

    @Test
    fun `handleReceive - with null action - finishes immediately`() {
        every { intent.action } returns null
        var finishCalled = false

        receiver.handleReceive(context, intent) { finishCalled = true }

        Assert.assertTrue("onFinish should be called for null action", finishCalled)
    }

    @Test
    fun `handleReceive - with irrelevant action - finishes immediately`() {
        every { intent.action } returns Intent.ACTION_BOOT_COMPLETED
        var finishCalled = false

        receiver.handleReceive(context, intent) { finishCalled = true }

        Assert.assertTrue("onFinish should be called for irrelevant action", finishCalled)
    }

    @Test
    fun `handleReceive - with relevant action - launches coroutine and eventually finishes`() = runTest {
        every { intent.action } returns Intent.ACTION_PACKAGE_ADDED

        val mockUri = mockk<Uri>()
        every { mockUri.schemeSpecificPart } returns "com.new.app"
        every { intent.data } returns mockUri

        var finishCalled = false

        receiver.handleReceive(context, intent) { finishCalled = true }

        advanceUntilIdle()

        Assert.assertTrue("onFinish should be called after processing", finishCalled)
    }

    @Test
    fun `handleReceive - when exception occurs during setup - fails safe and calls onFinish`() {
        every { intent.action } throws RuntimeException("Intent corrupted")
        var finishCalled = false

        receiver.handleReceive(context, intent) { finishCalled = true }

        Assert.assertTrue("onFinish should be called even on critical error", finishCalled)
    }

    @Test
    fun `handleReceive - with package removed action - processes correctly`() = runTest {
        every { intent.action } returns Intent.ACTION_PACKAGE_REMOVED
        // A genuine uninstall, not a replace — so it takes the processing path.
        every { intent.getBooleanExtra(Intent.EXTRA_REPLACING, false) } returns false

        val mockUri = mockk<Uri>()
        every { mockUri.schemeSpecificPart } returns "com.old.app"
        every { intent.data } returns mockUri

        var finishCalled = false

        receiver.handleReceive(context, intent) { finishCalled = true }

        advanceUntilIdle()

        Assert.assertTrue(finishCalled)
    }

    @Test
    fun `handleReceive - PACKAGE_REMOVED during a replace (update) is skipped, not processed`() {
        every { intent.action } returns Intent.ACTION_PACKAGE_REMOVED
        // EXTRA_REPLACING=true marks the removal half of an in-place update.
        every { intent.getBooleanExtra(Intent.EXTRA_REPLACING, false) } returns true

        val mockUri = mockk<Uri>()
        every { mockUri.schemeSpecificPart } returns "com.updating.app"
        every { intent.data } returns mockUri

        var finishCalled = false

        // Not a runTest: the processing path launches a coroutine on the (un-advanced)
        // Main test dispatcher, so if this removal were processed, onFinish would fire
        // only from inside that coroutine and stay false here. A synchronous finish
        // therefore proves the replace-removal took the early-return skip path and
        // fired no reconcile signal — the paired PACKAGE_ADDED handles the refresh.
        receiver.handleReceive(context, intent) { finishCalled = true }

        Assert.assertTrue(
            "replace-removal should finish immediately without launching processing",
            finishCalled
        )
    }

    // ========== Intent -> PackageEvent mapping (L1) ==========

    @Test
    fun `mapToPackageEvent - PACKAGE_ADDED maps to Added`() {
        Assert.assertEquals(
            PackageEvent.Added("com.example"),
            receiver.mapToPackageEvent(Intent.ACTION_PACKAGE_ADDED, "com.example"),
        )
    }

    @Test
    fun `mapToPackageEvent - PACKAGE_REMOVED maps to Removed`() {
        Assert.assertEquals(
            PackageEvent.Removed("com.example"),
            receiver.mapToPackageEvent(Intent.ACTION_PACKAGE_REMOVED, "com.example"),
        )
    }
}
