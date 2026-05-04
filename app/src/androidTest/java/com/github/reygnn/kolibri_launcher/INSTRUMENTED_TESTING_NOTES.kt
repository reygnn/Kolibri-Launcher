package com.github.reygnn.kolibri_launcher

/**
 * ============================================================================
 * INSTRUMENTED TESTING — PROJECT CONVENTION (companion to TESTING_CONVENTIONS.kt)
 * ============================================================================
 *
 * This source set (app/src/androidTest) intentionally diverges from the
 * unit-test conventions in TESTING_CONVENTIONS.kt on ONE point:
 *
 *   --> NO MainDispatcherRule HERE.
 *
 * WHY: Instrumented tests run on a real device/emulator with the real
 * AndroidX Main Looper. Calling Dispatchers.setMain(testDispatcher) in this
 * source set replaces the system Main with a virtual-time dispatcher — every
 * UI operation (View invalidation, RecyclerView measure/layout, animations,
 * Espresso's idle resources) then deadlocks waiting for time that never
 * advances. We saw this once already; do not bring the rule back.
 *
 * RULES FOR THIS SOURCE SET:
 *
 * 1. Synchronization is via Espresso, NOT advanceUntilIdle().
 *    Espresso.onIdle(), ActivityScenario state transitions, and IdlingResource
 *    are the source of truth. Virtual time has no meaning here.
 *
 * 2. For non-UI suspending code (e.g. waiting on a Flow), use:
 *      runBlocking { withTimeout(Xms) { flow.first() } }
 *    NOT runTest { } — runTest installs its own scheduler and clashes with
 *    the real Main when the SUT touches real coroutines.
 *
 * 3. Hilt: every test class is @HiltAndroidTest with
 *      @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
 *    Order 0 is mandatory — Hilt must initialise before any other rule
 *    that touches @Inject fields.
 *
 * 4. App-data cleanup goes through ClearAppDataRule (uses `pm clear`).
 *    Do NOT delete DataStore files manually — race conditions with running
 *    coroutines from the previous test will corrupt state.
 *
 * 5. Default-launcher state goes through DefaultHomeRoleHelper. Setting it
 *    via UI flow is fragile across OEM skins; the shell command is stable.
 *
 * 6. Test budget: keep this source set small. Five tests today, each
 *    targeting something that JVM + Robolectric structurally cannot reach
 *    (real ContentResolver descriptors, real RoleManager state, real
 *    LauncherApps permission gate, real broadcast goAsync, real RecyclerView
 *    measure pass). If a test could pass as Robolectric, write it there
 *    instead — feedback loop is 50× faster.
 *
 * ANTI-PATTERNS:
 * X  @get:Rule val mainDispatcherRule = MainDispatcherRule()      // deadlocks Main
 * X  runTest { ... }                                              // clashes with real scheduler
 * X  Thread.sleep(...) for synchronization                        // flaky; use Espresso
 * X  Calling Dispatchers.setMain anywhere                         // ditto
 * X  Tests that depend on "previous test's data" — they MUST be independent
 *    (use ClearAppDataRule or seed in @Before).
 */
private object InstrumentedTestingNotes
