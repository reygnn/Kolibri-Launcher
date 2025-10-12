package com.github.reygnn.kolibri_launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class PackageUpdateReceiverTest : BaseAndroidTest() {

    private lateinit var fakeAppUpdateSignal: FakeAppUpdateSignal

    @Before
    fun setup() {
        fakeAppUpdateSignal = appUpdateSignal as FakeAppUpdateSignal
        fakeAppUpdateSignal.reset()
    }

    // Wir verwenden `runTest`, weil wir keine UI haben, aber Coroutinen synchronisieren müssen.
    @Test
    fun onReceive_withPackageAddedAction_sendsUpdateSignal() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val receiver = PackageUpdateReceiver()
        val intent = Intent(Intent.ACTION_PACKAGE_ADDED).apply {
            data = Uri.fromParts("package", "com.example.newapp", null)
        }

        // Rufe die Test-Methode auf.
        // Hilt sorgt dafür, dass der EntryPoint im Inneren den FAKE signal liefert.
        receiver.handleReceive(context, intent) {}
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // Überprüfe den Zustand des Fakes, den Hilt uns injiziert hat.
        assertThat(fakeAppUpdateSignal.signalSentCount).isEqualTo(1)
    }

    @Test
    fun onReceive_withPackageRemovedAction_sendsUpdateSignal() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val receiver = PackageUpdateReceiver()
        val intent = Intent(Intent.ACTION_PACKAGE_REMOVED).apply {
            data = Uri.fromParts("package", "com.example.oldapp", null)
        }

        receiver.handleReceive(context, intent) {}
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        assertThat(fakeAppUpdateSignal.signalSentCount).isEqualTo(1)
    }

    @Test
    fun onReceive_withIrrelevantAction_doesNotSendSignal() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val receiver = PackageUpdateReceiver()
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)

        receiver.handleReceive(context, intent) {}
        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        assertThat(fakeAppUpdateSignal.signalSentCount).isEqualTo(0)
    }
}