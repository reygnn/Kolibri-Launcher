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
 *
 * ASSUMPTION TRAPS (the bring-up sessions hit each of these):
 *
 *  - `addFavoriteComponent("com.example.alpha")` does NOT validate the
 *    string format — synthetic strings persist silently and only fall
 *    out at the import-side filter. Tests that seed favorites through
 *    real round-trip code paths must use REAL `pkg/cls` components
 *    resolved at runtime via `queryIntentActivities(LAUNCHER)`.
 *    See TODO.md §15.
 *
 *  - `setPackage(ourPkg)` does NOT bypass protected-broadcast SENDER
 *    permission checks (PACKAGE_ADDED etc.). AMS gates on the sender's
 *    UID, regardless of `setPackage`. Even `am broadcast -p ourPkg`
 *    from the instrumentation shell only scopes Manifest-declared
 *    receivers — it does not deliver to a dynamically registered
 *    receiver. For receivers registered at runtime in production,
 *    invoke `receiver.onReceive(context, intent)` directly in tests.
 *    See PackageUpdateReceiverGoAsyncTest.
 *
 *  - `PackageManager.resolveActivity(ACTION_MAIN + CATEGORY_HOME)` is
 *    NOT semantically equivalent to `RoleManager.isRoleHeld(ROLE_HOME)`.
 *    With no explicit role-holder, resolveActivity falls back to
 *    best-match resolution and may return us; RoleManager honestly
 *    reports !isRoleHeld. Production never enters the no-holder limbo
 *    (the user always picks *some* launcher), so default-launcher
 *    consistency tests must simulate set→OTHER, not set→clear.
 *    See DefaultLauncherRoleConsistencyTest, TODO.md §17.
 *
 *  - `WhileSubscribed(timeout)`-StateFlow + `.first()` from a process
 *    with no prior subscriber returns the `initialValue` immediately
 *    (often emptyList) and unsubscribes again, before the upstream can
 *    even start producing. Production doesn't see this when the UI
 *    keeps the flow primed continuously, but cold paths (e.g. an
 *    import/restore that runs before any UI mounts a subscriber) hit
 *    the race and silently get the initialValue. Preferred fix is in
 *    the consumer itself: replace `.first()` with a predicate-bounded
 *    wait, e.g.
 *      withTimeoutOrNull(SOMETHING_MS) {
 *          repo.someFlow().first { it.isNotEmpty() }
 *      } ?: error("...")
 *    See `BackupDataAssembler.performImport` for the canonical fix
 *    pattern in this codebase, and `BackupDataAssemblerColdImportTest`
 *    for the unit-test shape that pins it.
 *
 *  - Espresso's idle-resource model waits on View / Animation /
 *    AsyncTask idle, NOT on Flow / StateFlow emission. A Recycler
 *    populated through a StateFlow can be empty when
 *    `RecyclerViewActions.actionOnItemAtPosition` fires →
 *    "No view holder at position: 0". Wrap with
 *    `support/awaitUntil { try { onView(...).check(matches(
 *    hasMinimumChildCount(N))); true } catch { false } }`.
 *    See OnboardingToHomeSmokeTest.
 *
 *  - The instrumentation runner runs in the target app's process, so
 *    self-targeted `pm clear` is a SIGKILL of the runner itself.
 *    Symptom: every test "Test instrumentation process crashed", empty
 *    stack. App-data cleanup goes through the orchestrator
 *    `clearPackageData=true` argument, not in-test rules.
 *
 * BUILD & RESULT-FILE LAYOUT (for future bring-up sessions):
 *
 *  - Headless emulator on a system with no X display:
 *      ~/Android/Sdk/emulator/emulator -avd <name> \
 *          -no-snapshot-save -no-boot-anim -no-window \
 *          -gpu swiftshader_indirect &
 *      adb wait-for-device
 *      until [[ -n $(adb shell getprop sys.boot_completed | tr -d '\r') ]];
 *          do sleep 2; done
 *    Without -no-window, the Qt xcb plugin needs a display and the
 *    emulator aborts with exit 134. Boot completes in ~30–60 s.
 *
 *  - Iteration cost: ~40 s for connectedDebugAndroidTest after a
 *    warm build, ~15 s of which is emulator handshake. Plan
 *    iterations sparingly.
 *
 *  - Test results live under
 *    `app/build/outputs/androidTest-results/connected/debug/<DeviceName>/`:
 *      * `test-result.textproto` — structured, every failure with
 *        full stack trace inline. First place to look.
 *      * `logcat-<TestClass>-<method>.txt` — full logcat for that one
 *        method; useful for "silent crash" symptoms.
 *      * `testlog/test-results.log` — gradle's own pass/fail list.
 *    HTML report at `app/build/reports/androidTests/connected/debug/index.html`
 *    is overview-only; the textproto has all the substance.
 */
private object InstrumentedTestingNotes
