package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import android.content.Intent
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
    private lateinit var mockContext: Context

    @MockK
    private lateinit var mockIntent: Intent

    private lateinit var receiver: PackageUpdateReceiver

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        receiver = spyk(PackageUpdateReceiver())
    }

    // ========== onReceive SAFETY TESTS ==========

    @Test
    fun `onReceive - with null context - does not crash and returns early`() {
        receiver.onReceive(null, mockIntent)
        // Kein Crash = Test bestanden
    }

    @Test
    fun `onReceive - with null intent - does not crash and returns early`() {
        receiver.onReceive(mockContext, null)
        // Kein Crash = Test bestanden
    }

    // ========== handleReceive LOGIC TESTS ==========

    @Test
    fun `handleReceive - with null action - finishes immediately`() {
        every { mockIntent.action } returns null
        var finishCalled = false

        receiver.handleReceive(mockContext, mockIntent) { finishCalled = true }

        Assert.assertTrue("onFinish should be called for null action", finishCalled)
    }

    @Test
    fun `handleReceive - with irrelevant action - finishes immediately`() {
        every { mockIntent.action } returns Intent.ACTION_BOOT_COMPLETED
        var finishCalled = false

        receiver.handleReceive(mockContext, mockIntent) { finishCalled = true }

        Assert.assertTrue("onFinish should be called for irrelevant action", finishCalled)
    }

    @Test
    fun `handleReceive - with relevant action - launches coroutine and eventually finishes`() = runTest {
        every { mockIntent.action } returns Intent.ACTION_PACKAGE_ADDED

        val mockUri = mockk<Uri>()
        every { mockUri.schemeSpecificPart } returns "com.new.app"
        every { mockIntent.data } returns mockUri

        var finishCalled = false

        receiver.handleReceive(mockContext, mockIntent) { finishCalled = true }

        advanceUntilIdle()

        Assert.assertTrue("onFinish should be called after processing", finishCalled)
    }

    @Test
    fun `handleReceive - when exception occurs during setup - fails safe and calls onFinish`() {
        every { mockIntent.action } throws RuntimeException("Intent corrupted")
        var finishCalled = false

        receiver.handleReceive(mockContext, mockIntent) { finishCalled = true }

        Assert.assertTrue("onFinish should be called even on critical error", finishCalled)
    }

    @Test
    fun `handleReceive - with package removed action - processes correctly`() = runTest {
        every { mockIntent.action } returns Intent.ACTION_PACKAGE_REMOVED

        val mockUri = mockk<Uri>()
        every { mockUri.schemeSpecificPart } returns "com.old.app"
        every { mockIntent.data } returns mockUri

        var finishCalled = false

        receiver.handleReceive(mockContext, mockIntent) { finishCalled = true }

        advanceUntilIdle()

        Assert.assertTrue(finishCalled)
    }
}
