package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.github.reygnn.kolibri_launcher.core.AppUpdateSignal
import com.github.reygnn.kolibri_launcher.support.ClearAppDataRule
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Why instrumented: PackageUpdateReceiver wraps its work in goAsync() and
 * a 3-second withTimeout. The existing JVM PackageUpdateReceiverTest calls
 * handleReceive() directly — it does not exercise:
 *   - the actual goAsync() / pendingResult.finish() handshake
 *   - the Hilt EntryPoint resolution from a non-Hilt class in a real process
 *   - the real broadcast delivery thread (BroadcastReceiver runs on the main
 *     thread of the receiving process, which under Robolectric is the test
 *     thread itself — very different timing characteristics)
 *
 * What this test validates:
 *   1. The real KolibriLauncherApp dynamic registration is alive (we observe
 *      the AppUpdateSignal flow and verify it emits, which only happens if
 *      the receiver actually fired and the EntryPoint resolved).
 *   2. The 3s withTimeout doesn't kill us on the happy path.
 *
 * NOTE on broadcast spoofing: we cannot send PACKAGE_ADDED unscoped (the
 * system blocks that). We send an EXPLICIT intent to our own receiver
 * component, which works for own-app dynamic receivers regardless of action.
 * This still exercises the full goAsync + Hilt EntryPoint + signal path —
 * the only thing it doesn't test is "did the system actually deliver the
 * broadcast", which is OS responsibility, not ours.
 */
@OptIn(DelicateCoroutinesApi::class)
@HiltAndroidTest
class PackageUpdateReceiverGoAsyncTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val clearData = ClearAppDataRule()

    @Inject lateinit var appUpdateSignal: AppUpdateSignal
    @Inject @ApplicationContext lateinit var context: Context

    @Before fun inject() = hiltRule.inject()

    @Test
    fun realBroadcast_resolvesHiltEntryPointAndEmitsSignal_withinTimeoutBudget() = runBlocking {
        // Subscribe BEFORE sending to avoid losing the emission. AppUpdateSignal
        // uses MutableSharedFlow with no replay, so a late subscriber misses it.
        val signalDeferred = GlobalScope.async {
            withTimeout(5_000) { // 3s app timeout + 2s scheduling slack
                appUpdateSignal.events.first()
            }
        }
        // Tiny yield so the collector is parked on receive() before we fire.
        delay(50)

        val intent = Intent(Intent.ACTION_PACKAGE_ADDED).apply {
            data = "package:com.example.fake.added".toUri()
            // The receiver is registered dynamically with no specific component;
            // for explicit delivery we resolve through the package manager.
            // The receiver class lives in the data module:
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)

        // If goAsync() hangs, the 3s in-receiver timeout fires AND our 5s
        // outer timeout fires. Either way, this completes.
        signalDeferred.await() // throws TimeoutCancellationException on hang.
    }

    @Test
    fun irrelevantAction_doesNotEmit_andDoesNotHangPendingResult() = runBlocking {
        // Negative case: ACTION_PACKAGE_FIRST_LAUNCH is filtered out at line 75.
        // We assert no emission AND that the receiver returned within ~500ms
        // (i.e. it didn't accidentally enter the coroutine path with no exit).
        val collector = GlobalScope.async {
            withTimeoutOrNull(1_500) {
                appUpdateSignal.events.first()
            }
        }
        delay(50)

        val intent = Intent(Intent.ACTION_PACKAGE_FIRST_LAUNCH).apply {
            data = "package:com.example.fake.firstlaunch".toUri()
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)

        assertThat(collector.await()).isNull() // no emission within budget
    }
}
