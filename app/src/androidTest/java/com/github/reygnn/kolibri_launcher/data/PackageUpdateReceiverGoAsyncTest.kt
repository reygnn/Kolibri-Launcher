package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.core.AppUpdateSignal
import com.github.reygnn.kolibri_launcher.support.ShellCommand
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * Why instrumented: PackageUpdateReceiver wraps its work in goAsync() and
 * a 3-second withTimeout. The existing JVM PackageUpdateReceiverTest calls
 * handleReceive() directly — it does not exercise:
 *   - the actual goAsync() / pendingResult.finish() handshake on the real
 *     broadcast delivery thread (BroadcastReceiver runs on the main thread
 *     of the receiving process; under Robolectric that's the test thread
 *     itself with no real handshake)
 *   - Hilt EntryPoint resolution from a non-Hilt class in a real process
 *
 * COVERAGE LIMIT (read this before extending):
 * Because HiltAndroidTest swaps in HiltTestApplication, our production
 * KolibriLauncherApp.onCreate never runs in this test, so neither does
 * KolibriLauncherApp.registerPackageUpdateReceiver(). To still exercise
 * the receiver code, we instantiate the production PackageUpdateReceiver
 * class ourselves and register it on the context with our own IntentFilter
 * + lifecycle. Consequence: this test does NOT cover the registration
 * site itself — RECEIVER_NOT_EXPORTED choice, the try/catch wrapper,
 * the IntentFilter actions, all of that is invisible to this test. If
 * you change registerPackageUpdateReceiver() in KolibriLauncherApp, add
 * a JVM-side test for that change; this one will not catch a regression
 * there.
 *
 * Why a single positive test, not a positive + negative pair:
 * The "irrelevant action does not emit" assertion is structurally already
 * covered by the JVM PackageUpdateReceiverTest — it calls handleReceive()
 * directly with PACKAGE_FIRST_LAUNCH and verifies no signal. Reproducing
 * it here would need to spoof a non-protected broadcast into our own
 * registered receiver, and the only meaningful new dimension we'd add is
 * "the system actually delivered it", which is OS responsibility.
 */
@HiltAndroidTest
class PackageUpdateReceiverGoAsyncTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var appUpdateSignal: AppUpdateSignal
    @Inject @ApplicationContext lateinit var context: Context

    private val receiver = PackageUpdateReceiver()
    private var registered = false

    @Before
    fun setUp() {
        hiltRule.inject()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registered = true
    }

    @After
    fun tearDown() {
        // Don't unregister blindly — if @Before threw before the register
        // call (e.g. hiltRule.inject() failure), unregisterReceiver throws
        // IllegalArgumentException and that masks the original failure
        // in the test log.
        if (registered) {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
                // Already unregistered or never properly registered.
            }
        }
    }

    @Test
    fun realBroadcast_resolvesHiltEntryPointAndEmitsSignal_withinTimeoutBudget() = runBlocking {
        // Why turbine: AppUpdateSignal.events is a MutableSharedFlow with
        // no replay. A subscriber that arrives after the emission misses
        // it. Turbine's test{} block guarantees the collector is parked
        // on receive() before the lambda body executes, so the broadcast
        // we send inside the block can never out-race the subscription.
        // (See TESTING_CONVENTIONS „MUTABLESHAREDFLOW IN CONSTRUCTOR" and
        // TODO.md §16 for the production-side fix that would eliminate
        // this whole class of races — `replay = 1` on _events.)
        //
        // Budget: 5s — well above the receiver's 3s SIGNAL_TIMEOUT_MS,
        // below the 10s flake threshold. If we hit this, either Hilt
        // EntryPoint resolution is broken or goAsync() is leaking.
        appUpdateSignal.events.test(timeout = 5.seconds) {
            // We send via the instrumentation shell, not context.sendBroadcast,
            // because PACKAGE_ADDED is a protected broadcast: the AMS gates
            // it on the SENDER's permission, regardless of any setPackage()
            // restriction on the receiver side. The test app's uid does not
            // hold BROADCAST_PACKAGE_ADDED, but the shell does — and `-p`
            // scopes delivery to our test process so only our @Before-
            // registered receiver picks it up.
            ShellCommand.run(
                "am broadcast " +
                    "-a android.intent.action.PACKAGE_ADDED " +
                    "-d package:com.example.fake.added " +
                    "-p ${context.packageName}"
            )
            awaitItem() // throws on timeout, which is the failure mode we want.
            cancelAndIgnoreRemainingEvents()
        }
    }
}
