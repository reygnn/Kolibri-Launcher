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
 * 4. App-data cleanup is handled by androidx.test.orchestrator via the
 *    `clearPackageData=true` instrumentation-runner argument (see
 *    app/build.gradle.kts > defaultConfig > testInstrumentationRunnerArguments).
 *    The orchestrator runs `pm clear` BETWEEN tests, AFTER the
 *    instrumentation has finished and BEFORE the next one starts. Do NOT
 *    add an in-test @get:Rule that calls `pm clear` on the target package
 *    from inside the test process — instrumentation runs in the target
 *    app's process, so a self-targeted `pm clear` SIGKILLs the runner.
 *    Symptom: every test fails with "Test instrumentation process crashed"
 *    and an empty stack trace. Same goes for manually deleting DataStore
 *    files — coroutines from the previous test will race the deletion.
 *
 * 5. Default-launcher state goes through DefaultHomeRoleHelper. Setting it
 *    via UI flow is fragile across OEM skins; the shell command is stable.
 *
 * 6. HiltAndroidTest swaps the production Application class for
 *    HiltTestApplication (see app/src/androidTest/AndroidManifest.xml,
 *    `tools:replace="android:name"`). This means KolibriLauncherApp.onCreate
 *    DOES NOT RUN in instrumented tests, and any side effects of that
 *    onCreate — most notably the dynamic registration of
 *    PackageUpdateReceiver — are NOT reproduced. Tests that need to
 *    exercise a dynamically registered Production receiver must register
 *    their own instance of the production receiver class against
 *    `context.registerReceiver(...)` in @Before, with a matching
 *    @After teardown that swallows IllegalArgumentException (so a failure
 *    in @Before before the registration call doesn't shadow the original
 *    error). See PackageUpdateReceiverGoAsyncTest for the canonical
 *    pattern. Trade-off: such tests cover the receiver class itself but
 *    NOT the registration site in KolibriLauncherApp — flag that limit
 *    in the test KDoc so future maintainers don't think the registration
 *    is covered.
 *
 * 7. Cache-lag polling pattern. Some Android subsystems (notably
 *    RoleManager's UID-side cache after a `cmd role` change) update with
 *    a small delay relative to the ground truth. Don't `Thread.sleep` to
 *    wait for them — use `support/awaitUntil(timeoutMs, describe) { ... }`
 *    which polls the condition until satisfied OR raises an AssertionError
 *    with the last-observed state. The returned elapsed-millis value can
 *    itself be asserted against a budget, turning the polling into a
 *    *measurement* — if a known cache lag exceeds its budget, that IS the
 *    failure and the production code (the consumer of the cached value,
 *    not the cache itself) needs the fix. See
 *    DefaultLauncherRoleConsistencyTest for the pattern.
 *
 * 8. Test budget: keep this source set small. Each test must target
 *    something that JVM + Robolectric structurally cannot reach (real
 *    ContentResolver descriptors, real RoleManager state, real
 *    LauncherApps permission gate, real broadcast goAsync, real RecyclerView
 *    measure pass). If a test could pass as Robolectric, write it there
 *    instead — feedback loop is 50× faster.
 *
 * ANTI-PATTERNS:
 * X  @get:Rule val mainDispatcherRule = MainDispatcherRule()      // deadlocks Main
 * X  runTest { ... }                                              // clashes with real scheduler
 * X  Thread.sleep(...) for synchronization                        // flaky; use Espresso or awaitUntil
 * X  Calling Dispatchers.setMain anywhere                         // ditto
 * X  Tests that depend on "previous test's data" — they MUST be independent
 *    (orchestrator clearPackageData=true handles cleanup; seed in @Before).
 * X  In-test @get:Rule that runs `pm clear <ourPkg>` — kills the runner.
 * X  scenario.recreate() AFTER the Activity has finish()'d itself (e.g.
 *    after triggering an Intent that finishes the source activity); the
 *    underlying Activity is destroyed and recreate() throws NPE. Move
 *    such state-survival assertions into a Robolectric ViewModel test.
 */
private object InstrumentedTestingNotes
