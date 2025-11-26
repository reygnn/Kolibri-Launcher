package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.github.reygnn.kolibri_launcher.core.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExperimentalCoroutinesApi
class PackageUpdateReceiverTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockIntent: Intent

    private lateinit var receiver: PackageUpdateReceiver

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        receiver = spy(PackageUpdateReceiver())
    }

    // ========== onReceive SAFETY TESTS ==========

    @Test
    fun `onReceive - with null context - does not crash and returns early`() {
        // Act
        receiver.onReceive(null, mockIntent)

        // Assert
        // Wir können nur prüfen, dass kein Crash passierte.
        // Da handleReceive internal ist, könnten wir verify(receiver, never()).handleReceive... nutzen,
        // aber handleReceive ist nicht open/spyable ohne weiteres in Kotlin (Standard ist final).
        // Der Durchlauf ohne Exception ist der Test.
    }

    @Test
    fun `onReceive - with null intent - does not crash and returns early`() {
        // Act
        receiver.onReceive(mockContext, null)

        // Assert - Kein Crash
    }

    // ========== handleReceive LOGIC TESTS ==========

    @Test
    fun `handleReceive - with null action - finishes immediately`() {
        // Arrange
        whenever(mockIntent.action).thenReturn(null)
        var finishCalled = false

        // Act
        receiver.handleReceive(mockContext, mockIntent) {
            finishCalled = true
        }

        // Assert
        Assert.assertTrue("onFinish should be called for null action", finishCalled)
    }

    @Test
    fun `handleReceive - with irrelevant action - finishes immediately`() {
        // Arrange
        whenever(mockIntent.action).thenReturn(Intent.ACTION_BOOT_COMPLETED)
        var finishCalled = false

        // Act
        receiver.handleReceive(mockContext, mockIntent) {
            finishCalled = true
        }

        // Assert
        Assert.assertTrue("onFinish should be called for irrelevant action", finishCalled)
    }

    @Test
    fun `handleReceive - with relevant action - launches coroutine and eventually finishes`() =
        runTest {
            // Arrange
            whenever(mockIntent.action).thenReturn(Intent.ACTION_PACKAGE_ADDED)

            // Mock Uri data
            val mockUri = mock<Uri>()
            whenever(mockUri.schemeSpecificPart).thenReturn("com.new.app")
            whenever(mockIntent.data).thenReturn(mockUri)

            var finishCalled = false

            // Act
            receiver.handleReceive(mockContext, mockIntent) {
                finishCalled = true
            }

            // Die Coroutine läuft auf Dispatchers.Main (durch MainDispatcherRule kontrolliert)
            // Wir müssen warten, bis sie ausgeführt wird.
            advanceUntilIdle()

            // Assert
            Assert.assertTrue("onFinish should be called after processing", finishCalled)

            // Hinweis: Der eigentliche 'EntryPointAccessors' Aufruf im try-catch Block wird fehlschlagen
            // (da wir EntryPointAccessors nicht statisch mocken), aber der Test prüft,
            // ob der Receiver diesen Fehler sauber abfängt und TROTZDEM onFinish aufruft.
            // Das ist genau das gewünschte "Crash-Safety"-Verhalten.
        }

    @Test
    fun `handleReceive - when exception occurs during setup - fails safe and calls onFinish`() {
        // Arrange
        // Simuliere Fehler beim Zugriff auf Action (z.B. RuntimeException)
        whenever(mockIntent.action).thenThrow(RuntimeException("Intent corrupted"))
        var finishCalled = false

        // Act
        receiver.handleReceive(mockContext, mockIntent) {
            finishCalled = true
        }

        // Assert
        Assert.assertTrue("onFinish should be called even on critical error", finishCalled)
    }

    @Test
    fun `handleReceive - with package removed action - processes correctly`() = runTest {
        // Arrange
        whenever(mockIntent.action).thenReturn(Intent.ACTION_PACKAGE_REMOVED)
        val mockUri = mock<Uri>()
        whenever(mockUri.schemeSpecificPart).thenReturn("com.old.app")
        whenever(mockIntent.data).thenReturn(mockUri)

        var finishCalled = false

        // Act
        receiver.handleReceive(mockContext, mockIntent) {
            finishCalled = true
        }

        advanceUntilIdle()

        // Assert
        Assert.assertTrue(finishCalled)
    }
}