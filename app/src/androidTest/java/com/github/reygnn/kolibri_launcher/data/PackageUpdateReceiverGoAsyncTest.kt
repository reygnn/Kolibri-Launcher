package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.core.AppUpdateSignal
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * Why instrumented: PackageUpdateReceiver wraps its work in goAsync() and
 * a 3-second withTimeout. The existing JVM PackageUpdateReceiverTest calls
 * handleReceive() directly — it does not exercise:
 *   - the goAsync() branch in onReceive (production wraps the entire work
 *     in a try/finally around goAsync()/pendingResult.finish() — that
 *     code path is silent under Robolectric)
 *   - Hilt EntryPoint resolution from a non-Hilt class in a real process,
 *     including the Dispatchers.Main coroutine launched in
 *     processPackageUpdate
 *
 * COVERAGE LIMITS (read this before extending):
 *
 * 1. HiltAndroidTest swaps in HiltTestApplication, so our production
 *    KolibriLauncherApp.onCreate never runs in this test, so neither does
 *    KolibriLauncherApp.registerPackageUpdateReceiver(). RECEIVER_NOT_EXPORTED
 *    choice, the try/catch wrapper around the registration, the
 *    IntentFilter actions — none of that is covered here. If you change
 *    registerPackageUpdateReceiver() in KolibriLauncherApp, add a JVM-side
 *    test for that change; this one will not catch a regression there.
 *
 * 2. We invoke onReceive() directly instead of going through the system
 *    broadcast pipeline. Reasons we don't broadcast:
 *      - PACKAGE_ADDED is a protected broadcast: AMS gates it on the
 *        SENDER's permission, regardless of any setPackage() restriction
 *        on the receiver side. Our test app's UID does not hold the
 *        permission.
 *      - `am broadcast -p ourPkg` from the instrumentation shell is
 *        privileged enough to emit the protected action, but `-p` only
 *        scopes Manifest-declared receivers; our receiver is registered
 *        dynamically in production (KolibriLauncherApp.onCreate), and
 *        the dynamic-receiver match path in AMS does not honour `-p`.
 *    Direct onReceive() does the same job — it exercises goAsync() (which
 *    returns null outside the real pipeline; the production code already
 *    handles that null), the Hilt EntryPoint lookup, the
 *    Dispatchers.Main coroutine, and the AppUpdateSignal emission.
 *    What it does NOT exercise: the real broadcast delivery thread (in
 *    production, BroadcastReceiver.onReceive runs on the receiving
 *    process's main thread). That's an OS responsibility, not app code,
 *    so the lost coverage is acceptable.
 *
 * Why a single positive test, not a positive + negative pair:
 * The "irrelevant action does not emit" assertion is structurally already
 * covered by the JVM PackageUpdateReceiverTest — it calls handleReceive()
 * directly with PACKAGE_FIRST_LAUNCH and verifies no signal. Reproducing
 * it here would only add noise.
 */
@HiltAndroidTest
class PackageUpdateReceiverGoAsyncTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var appUpdateSignal: AppUpdateSignal
    @Inject @ApplicationContext lateinit var context: Context

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun directOnReceive_resolvesHiltEntryPointAndEmitsSignal_withinTimeoutBudget() = runBlocking {
        // Why turbine: AppUpdateSignal.events is a MutableSharedFlow with
        // no replay. A subscriber that arrives after the emission misses
        // it. Turbine's test{} block guarantees the collector is parked
        // on receive() before the lambda body executes, so the onReceive()
        // we trigger inside the block can never out-race the subscription.
        // (See TESTING_CONVENTIONS „MUTABLESHAREDFLOW IN CONSTRUCTOR" and
        // TODO.md §16 for the production-side fix that would eliminate
        // this whole class of races — `replay = 1` on _events.)
        //
        // Budget: 5s — well above the receiver's 3s SIGNAL_TIMEOUT_MS,
        // below the 10s flake threshold. If we hit this, either Hilt
        // EntryPoint resolution is broken or the Main-dispatched
        // coroutine in processPackageUpdate is leaking.
        appUpdateSignal.events.test(timeout = 5.seconds) {
            val receiver = PackageUpdateReceiver()
            val intent = Intent(Intent.ACTION_PACKAGE_ADDED).apply {
                data = "package:com.example.fake.added".toUri()
            }
            receiver.onReceive(context, intent)
            awaitItem() // throws on timeout, which is the failure mode we want.
            cancelAndIgnoreRemainingEvents()
        }
    }
}
