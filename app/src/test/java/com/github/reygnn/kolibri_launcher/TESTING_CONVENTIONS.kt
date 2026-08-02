package com.github.reygnn.kolibri_launcher

/**
 * ============================================================================
 * COROUTINE TEST DISPATCHER — PROJECT CONVENTION
 * ============================================================================
 *
 * TL;DR: Use ONE dispatcher everywhere. Never mix dispatcher instances.
 *
 * THE PROBLEM WE HAD:
 * All delegate tests failed because two different dispatchers were used:
 * - MainDispatcherRule created an UnconfinedTestDispatcher
 * - @Before created a separate StandardTestDispatcher
 * - advanceUntilIdle() only advanced one of them
 * - Coroutines were launched but never executed
 * Result: 100% test failure, zero useful error messages.
 *
 * THE PATTERN THAT WORKS:
 *
 *   @get:Rule
 *   val mainDispatcherRule = MainDispatcherRule()   // sets Dispatchers.Main
 *
 *   private fun createDelegateScope() = DelegateScope(
 *       coroutineScope = CoroutineScope(mainDispatcherRule.testDispatcher + SupervisorJob()),
 *       mainDispatcher = mainDispatcherRule.testDispatcher,
 *       eventSender = { ... }
 *   )
 *
 *   @Test
 *   fun `my test`() = runTest {       // top-level runTest, NOT testScope.runTest
 *       val delegate = createDelegate()
 *       delegate.doSomething()
 *       advanceUntilIdle()
 *       // assert...
 *   }
 *
 * RULES:
 * 1. ONE dispatcher source: mainDispatcherRule.testDispatcher — everywhere.
 *    Never create a second StandardTestDispatcher() or TestScope().
 *
 * 2. ONE setMain: MainDispatcherRule does it. Never call Dispatchers.setMain()
 *    manually in @Before if the rule is present.
 *
 * 3. Top-level runTest: Use `= runTest { }`, not `= testScope.runTest { }`.
 *    The runTest block shares the scheduler with MainDispatcherRule's dispatcher.
 *
 * 4. SupervisorJob for scopes: Use CoroutineScope(dispatcher + SupervisorJob()),
 *    not testScope.backgroundScope. This ensures advanceUntilIdle() reaches
 *    all child coroutines.
 *
 * 5. Event collectors need UnconfinedTestDispatcher:
 *    `launch(UnconfinedTestDispatcher()) { vm.event.collect { ... } }`
 *    Otherwise the collector starts too late and misses events.
 *
 * 6. WhileSubscribed flows need active subscribers:
 *    `launch(UnconfinedTestDispatcher()) { vm.someState.collect {} }`
 *    Without a subscriber, the upstream never starts and values stay at default.
 *
 * ANTI-PATTERNS (will break tests silently):
 * ✗ private val testDispatcher = StandardTestDispatcher()  // second dispatcher!
 * ✗ private val testScope = TestScope(testDispatcher)      // separate scheduler!
 * ✗ @Before fun setUp() { Dispatchers.setMain(testDispatcher) }  // conflicts with rule!
 * ✗ testScope.runTest { }  // wrong scope, advanceUntilIdle won't work!
 *
 * EXCEPTIONS — when StandardTestDispatcher(testScheduler) IS needed:
 *
 * Both exceptions below construct a SECOND dispatcher but share the same
 * `testScheduler` from runTest. That keeps virtual time unified — it's not
 * "two schedulers", it's one scheduler with two dispatcher views.
 *
 * 1. DelegateScopeTest
 *    When testing DelegateScope itself (not a ViewModel/Delegate), use
 *    StandardTestDispatcher(testScheduler) from inside runTest { }.
 *    Reason: launchSafe catches exceptions, and UnconfinedTestDispatcher
 *    would let them escape to runTest first.
 *
 * 2. Init-time events on MutableSharedFlow (no replay)
 *    When a ViewModel's init block emits a one-shot event (e.g. ShowError
 *    from a failing flow during initialization), the test must SUBSCRIBE
 *    BEFORE the emission happens. With UnconfinedTestDispatcher, init's
 *    launch runs synchronously inside the constructor — the event fires
 *    before turbine's `event.test {}` block exists, and MutableSharedFlow
 *    without replay drops it. Result: turbine times out after 3s with
 *    "No value produced".
 *
 *    Fix: pass StandardTestDispatcher(testScheduler) as the ViewModel's
 *    `mainDispatcher` constructor parameter for that one test. The init
 *    launch is queued, the test subscribes via event.test {}, then
 *    advanceUntilIdle() runs the queued launch and the subscriber
 *    catches the event.
 *
 *      // ✅ pattern for init-time event tests
 *      every { useCase.flow } returns flow { throw IOException("boom") }
 *      val testDispatcher = StandardTestDispatcher(testScheduler)
 *      viewModel = MyViewModel(useCase, mainDispatcher = testDispatcher)
 *      viewModel.event.test {
 *          advanceUntilIdle()       // run queued init launch
 *          val event = awaitItem()  // subscriber catches emitted event
 *          assertTrue(event is ShowError)
 *      }
 *
 *    NOT applicable when:
 *      - the event is emitted in response to a USER ACTION (called after
 *        construction) — by then the subscriber is already attached.
 *      - the flow is a StateFlow or SharedFlow with replay >= 1 — the
 *        late subscriber gets the cached value.
 *
 * See: MainDispatcherRule.kt, AppManagementDelegateTest.kt (reference impl
 *      for delegate scope), OnboardingViewModelTest.kt (reference impl
 *      for init-time event)
 *
 *
 * SELF-ENFORCEMENT — WHY THE CONVENTION HOLDS
 * --------------------------------------------
 * Audit on 2026-05-01 (TODO §5) found zero gaps: every test that needs
 * MainDispatcherRule has it, every test that doesn't have it doesn't
 * need it. This is not luck — the convention enforces itself through
 * the architecture.
 *
 * The mechanism: every ViewModel takes its `mainDispatcher` as a
 * constructor parameter (typed `CoroutineDispatcher`, qualified with
 * `@MainDispatcher` from `di/DispatcherModule.kt`). When you write a
 * test for a new ViewModel, the constructor signature forces you to
 * pass a dispatcher — and the obvious right answer is
 * `mainDispatcherRule.testDispatcher`, which immediately implies
 * adding the rule. The compiler funnels you toward the convention.
 *
 * `BaseViewModel(mainDispatcher: CoroutineDispatcher)` makes this
 * non-optional for all subclasses. Repository tests that use
 * `shareIn`/`stateIn` follow a parallel pattern: they take an
 * `externalScope` constructor parameter so tests can pass `null` and
 * sidestep Main entirely (see FavoritesRepositoryImplShareInTest for
 * the case where the test does want Main and uses the rule).
 *
 * If a future ViewModel skips the constructor-injected dispatcher
 * (hard-coding `Dispatchers.Main` in the body, say), the
 * self-enforcement breaks for that VM and the convention drifts
 * silently — by the time anyone notices, the test is already flaky.
 * Reject that pattern in review. Every ViewModel must take its
 * dispatcher via constructor.
 * ============================================================================
 */

/**
 * ============================================================================
 * MOCKK CONVENTIONS
 * ============================================================================
 *
 * IMPORTS
 * -------
 * Rule:    import com.github.reygnn.kolibri_launcher.rule.TimberRule       ✅
 *          import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule ✅
 * Not:     import com.github.reygnn.kolibri_launcher.rules.TimberRule       ❌ (falsches Package)
 *
 *
 * VARIABLE NAMING — NO `mock` PREFIX
 * ----------------------------------
 * Mock variables (both `@MockK lateinit var` and `val ... = mockk()`) are
 * named after what they represent, NOT prefixed with `mock`.
 *
 *   // ✅ correct
 *   @MockK private lateinit var context: Context
 *   @MockK private lateinit var settingsRepository: SettingsRepository
 *   private val wallpaperFileManager: WallpaperFileManager = mockk(relaxed = true)
 *
 *   // ❌ wrong (drift from older Mockito-style naming)
 *   @MockK private lateinit var mockContext: Context
 *   @MockK private lateinit var mockSettingsRepository: SettingsRepository
 *   private val mockWallpaperFileManager: WallpaperFileManager = mockk(relaxed = true)
 *
 * Reason: the `@MockK` annotation on the left, or the `= mockk(...)`
 * initializer on the right, already declares the variable as a mock.
 * The Type annotation says what it represents. Adding a `mock` prefix
 * is redundant boilerplate that worsens readability inside the test
 * body — `every { settingsRepository.x } returns y` reads better than
 * `every { mockSettingsRepository.x } returns y`.
 *
 * The codebase was unified to this convention in TODO §8 (sweep across
 * 55 variables in 29 test files). New tests must follow it.
 *
 *
 * MOCKITO → MOCKK MIGRATION
 * -------------------------
 * Mapping reference for porting old Mockito-based tests. Default to
 * mockk(relaxed = true) — it matches Mockito's lenient default and lets
 * the test focus on the calls you actually care about.
 *
 *   Mockito                                          MockK
 *   ─────────────────────────────────────────────    ─────────────────────────────────────────
 *   @RunWith(MockitoJUnitRunner::class)              (drop entirely)
 *   @Mock private lateinit var x: T                  private val x: T = mockk(relaxed = true)
 *   MockitoAnnotations.openMocks(this) in @Before    (drop entirely)
 *   lenient().`when`(x.f()).thenReturn(v)            every { x.f() } returns v
 *   `when`(x.f()).thenReturn(v)                      every { x.f() } returns v
 *   `when`(x.suspendF()).thenReturn(v)               coEvery { x.suspendF() } returns v
 *   `when`(x.f()).thenThrow(E())                     every { x.f() } throws E()
 *   `when`(x.f()).doAnswer { throw E() }             every { x.f() } throws E()
 *   `when`(x.f()).thenThrow(E()).thenReturn(v)       every { x.f() } throws E() andThen v
 *   verify(x).f(args)                                verify { x.f(args) }
 *   verify(x).suspendF(args)                         coVerify { x.suspendF(args) }
 *   verify(x, never()).f()                           verify(exactly = 0) { x.f() }
 *   verify(x, atLeastOnce()).f()                     verify(atLeast = 1) { x.f() }
 *   verify(x, times(2)).f()                          verify(exactly = 2) { x.f() }
 *   eq(value)                                        value     // no wrapper needed
 *   any() / any<T>()                                 any() / any<T>()    // io.mockk.any
 *   Mockito.mock(View::class.java)                   mockk<View>(relaxed = true)
 *
 * SUSPEND vs NON-SUSPEND — KNOW WHICH
 * Pick coEvery/coVerify for any function declared `suspend`. Pick
 * every/verify for properties (incl. `val of type Flow`) and regular
 * non-suspend functions. Getting this wrong gives confusing runtime
 * errors, not compile errors.
 *
 *   // Property (Flow exposed as val) → every
 *   every { repo.itemsFlow } returns flowOf(items)
 *
 *   // Non-suspend function returning Flow → every
 *   every { repo.getItems() } returns flowOf(items)
 *
 *   // Suspend function → coEvery
 *   coEvery { repo.saveItem(any()) } returns true
 *
 *
 * SUSPEND FUNCTIONS — coEvery + answers { }
 * -----------------------------------------
 * coAnswers does NOT exist in MockK. For suspend functions that need to
 * return a value based on their arguments, use coEvery with regular answers:
 *
 *   // ✅ correct
 *   coEvery { mock.suspendFun(any()) } returns fixedValue
 *   coEvery { mock.suspendFun(any()) } answers { secondArg() }    // arg-based return
 *   coEvery { mock.suspendFun(any()) } throws IOException("fail")
 *
 *   // ❌ coAnswers does not exist — compile error
 *   coEvery { mock.suspendFun(any()) } coAnswers { secondArg() }
 *
 * The answers { } lambda is always synchronous. It just extracts/transforms
 * the arguments and returns a value — it does not need to be a coroutine
 * even if the stubbed function is suspend.
 *
 *
 * RELAXED vs RELAXUNITFUN — SUSPEND FUNCTIONS BRAUCHEN relaxed = true
 * -------------------------------------------------------------------
 * MockK hat zwei verschiedene "relax"-Flags:
 *   - `relaxUnitFun = true` relaxiert NUR non-suspend Funktionen die
 *     `Unit` zurückgeben.
 *   - `relaxed = true` relaxiert ALLES, inklusive suspend-Funktionen.
 *
 * Für Fake-artige Mocks von Repositories mit suspend-Unit-Methoden
 * (`hideComponent`, `showComponent`, `triggerUpdate`, …) MUSS man
 * `relaxed = true` benutzen. `relaxUnitFun = true` sieht korrekt aus,
 * produziert aber zur LAUFZEIT einen `MockKException: no answer found`
 * beim ersten Aufruf der suspend-Methode.
 *
 *   // ❌ kompiliert, crashed zur Laufzeit
 *   val repo = mockk<HiddenAppsRepository>(relaxUnitFun = true)
 *   repo.hideComponent("...")   // MockKException: no answer found
 *
 *   // ✅ kompiliert, läuft
 *   val repo = mockk<HiddenAppsRepository>(relaxed = true)
 *   repo.hideComponent("...")
 *
 * Faustregel: im Zweifel `relaxed = true`. `relaxUnitFun = true` ist
 * eine Feinheit für Fälle wo man explizit will dass nicht-Unit-Fns
 * "loud" scheitern, aber suspend-Fns kriegt man damit nicht in den
 * Griff.
 *
 *
 * SHARED FLOW emit — emit(Unit) STATT emit(any()) STUBBEN
 * -------------------------------------------------------
 * MockK matcht `Unit` über `any()` nicht zuverlässig. Bei einem
 * Mock-Setup wie:
 *
 *   coEvery { mockTrigger.emit(any()) } just Runs
 *
 * auf einem `MutableSharedFlow<Unit>` inferiert MockK `any<Unit>()` und
 * schlägt beim tatsächlichen Aufruf mit
 *
 *     left matchers: [any<Unit>()]
 *
 * fehl — die Methode wird nicht gematcht, der Call schlägt durch als
 * "no answer found". Empirisch beobachtet in CustomNamesRepositoryImplTest und
 * InstalledAppsRepositoryImplTest.
 *
 *   // ❌ matcht nicht
 *   coEvery { mockTrigger.emit(any()) } just Runs
 *   coVerify { mockTrigger.emit(any()) }
 *
 *   // ✅ matcht
 *   coEvery { mockTrigger.emit(Unit) } just Runs
 *   coVerify { mockTrigger.emit(Unit) }
 *
 * Bei `MutableSharedFlow<Unit>` also IMMER `Unit` direkt angeben, nie
 * `any()`. Bei `MutableSharedFlow<T>` mit anderem T (z.B. Events,
 * Strings) ist `any()` wie gewohnt sicher.
 *
 *
 * DATASTORE — NEVER mock DataStore<Preferences> directly
 * -------------------------------------------------------
 * DataStore.edit() and DataStore.updateData() are extension/interface functions
 * that MockK cannot stub reliably. Use FakeDataStore instead.
 *
 *   // ❌ will NOT compile — edit() is an extension function
 *   val mockDataStore = mockk<DataStore<Preferences>>()
 *   coEvery { mockDataStore.edit(any()) } returns preferencesOf()
 *
 *   // ✅ use FakeDataStore — has makeEditFail(), makeCancellable(), makeReadFail()
 *   private lateinit var fakeDataStore: FakeDataStore
 *
 *   @Before fun setup() {
 *       fakeDataStore = FakeDataStore()
 *       manager = MyManager(fakeDataStore, ...)
 *   }
 *
 * Mapping from Mockito patterns to FakeDataStore:
 *
 *   whenever(store.data).thenReturn(flowOf(prefs))          → fakeDataStore.setInitialData(prefs)
 *   whenever(store.edit(any())).doReturn(preferencesOf())   → (no setup needed, works by default)
 *   whenever(store.edit(any())).doAnswer { throw IOException() } → fakeDataStore.makeEditFail()
 *   whenever(store.edit(any())).doAnswer { throw CancellationException() } → fakeDataStore.makeCancellable()
 *   whenever(store.data).thenReturn(flow { throw IOException() })  → fakeDataStore.makeReadFail()
 *   verify(store).edit(any())                               → Assert.assertTrue(fakeDataStore.updateDataCallCount > 0)
 *   verify(store, never()).edit(any())                      → Assert.assertEquals(0, fakeDataStore.updateDataCallCount)
 *
 * EXCEPTION: If you need data to throw RuntimeException or CancellationException
 * (not supported by FakeDataStore), mock only the data property — which IS a
 * regular interface member and CAN be stubbed:
 *
 *   val brokenStore = mockk<DataStore<Preferences>>()
 *   every { brokenStore.data } returns flow { throw CancellationException() }
 *   val manager = MyManager(brokenStore, ...)   // only for this one test
 *
 * ============================================================================
 */

/**
 * ============================================================================
 * CONTRACT TESTS — FAKE vs REAL REPOSITORY
 * ============================================================================
 *
 * Wozu Contract Tests?
 *   Fakes driften über die Zeit von ihrer echten Implementierung ab — man ändert
 *   den Manager, vergisst den Fake, und plötzlich sehen Unit-Tests gegen den
 *   Fake glücklich aus, während Produktion still kaputt ist. Contract Tests
 *   fahren denselben Satz an Assertions gegen BEIDE Implementierungen. Wenn
 *   etwas driftet, wird ein Test rot.
 *
 * Naming-Konvention (siehe test/.../data/):
 *   XyzRepositoryContract.kt                — abstract, enthält alle @Test-Methoden
 *   FakeXyzRepositoryContractTest.kt        — konkrete Subklasse gegen den Fake
 *   XyzManagerContractTest.kt               — konkrete Subklasse gegen den Manager
 *
 * Das Muster:
 *
 *   abstract class XyzRepositoryContract {
 *       @get:Rule val mainDispatcherRule = MainDispatcherRule()
 *       @get:Rule val timberRule = TimberRule()
 *
 *       protected abstract fun createRepository(): XyzRepository
 *
 *       @Test fun `some invariant`() = runTest {
 *           val repo = createRepository()
 *           // assert against the interface contract…
 *       }
 *   }
 *
 *   class FakeXyzRepositoryContractTest : XyzRepositoryContract() {
 *       override fun createRepository() = FakeXyzRepository()
 *   }
 *
 *   class XyzManagerContractTest : XyzRepositoryContract() {
 *       override fun createRepository() = XyzManager(FakeDataStore(), mockk(relaxed = true))
 *   }
 *
 * JUNIT4 REGELN-VERERBUNG:
 *   JUnit4 findet geerbte @get:Rule Felder via Reflection. Die Rules gehören
 *   in die abstrakte Basisklasse, NICHT in jede Subklasse einzeln. Verifiziert
 *   mit allen bestehenden Contract-Tests — funktioniert zuverlässig.
 *
 * MANAGER-TEST: `externalScope = null` BEI shareIn-BASIERTEN MANAGERN
 *   Managers that layer `shareIn(externalScope, WhileSubscribed(…), replay = 1)`
 *   over their flows (FavoritesRepositoryImpl, FavoritesOrderRepositoryImpl)
 *   have a write-then-read bug under UnconfinedTestDispatcher: the write
 *   reaches dataStore, but the upstream collector has not yet written the
 *   update into the replay buffer, so the read returns the old cached value.
 *   (SwipeActionsRepositoryImpl used to belong here; it no longer hot-shares
 *   — all its reads are authoritative fresh reads — so it needs no such
 *   externalScope handling.)
 *
 *   Lösung: im Manager-Contract-Test `externalScope = null` übergeben —
 *   das überspringt die shareIn-Schicht, der Flow bleibt cold, und jeder
 *   `.first()` sieht den aktuellen dataStore-Wert direkt. Konkretes
 *   Beispiel in `FavoritesRepositoryImplContractTest.kt`.
 *
 *   Das shareIn-Verhalten SELBST gehört NICHT in den Contract (es ist
 *   Produktions-Infrastruktur, kein Interface-Vertrag). Separater Test:
 *   `FavoritesRepositoryImplShareInTest.kt` prüft Replay, Hot-Sharing und
 *   WhileSubscribed-Timeout mit virtueller Zeit.
 *
 *   Wichtig dort: `testScheduler.runCurrent()` statt `advanceUntilIdle()`,
 *   sonst läuft man versehentlich durch den WhileSubscribed-Timeout.
 *
 * MOCK-THEATER VERMEIDEN — WENN MANAGER NICHT EHRLICH INSTANZIIERBAR IST:
 *   Wenn der Manager System-APIs braucht (PackageManager, ContentResolver,
 *   AlarmManager, Calendar), ist er im Unit-Test nur mit heavy mocking
 *   instanziierbar. Ein Contract-Test gegen dieses Mock-Konstrukt beweist
 *   nichts: er prüft Vertragstreue zwischen Fake und Mock, nicht zwischen
 *   Fake und echtem Manager.
 *
 *   Konvention: In solchen Fällen KEINEN Manager-Contract-Test schreiben.
 *   Der Contract wird Fake-only (eine Subklasse), mit ehrlichem KDoc das
 *   die Limitation benennt. Beispiele:
 *     - BackupRepositoryContract (60KB Manager mit Uri/ContentResolver)
 *     - InstalledAppsRepositoryContract (PackageManager)
 *     - TimeBasedEventsRepositoryContract (kein Contract, nur ADR-Doku —
 *       siehe dortigen KDoc)
 *
 * BEKANNTE DIVERGENZEN DOKUMENTIEREN STATT VERSTECKEN:
 *   Wenn Fake und Manager bewusst unterschiedlich sind (z.B. Fake-Default
 *   ist `true` für Test-Convenience, Manager-Default ist `false`), gehört
 *   das in den Klassen-KDoc des Contracts. Unter "NICHT IM CONTRACT — bewusste
 *   Drifts". Das verhindert dass ein Nachfolger diese Drift aus Versehen
 *   als Contract-Bug interpretiert.
 *
 *   Beispiele (siehe jeweiliges KDoc):
 *     - InstalledAppsStateRepositoryContract: purgeRepository No-Op vs Reset
 *     - WallpaperRepositoryContract: scale ohne imageUri, non-file URI scheme
 *
 * DRIFT-FIX POLICY:
 *   Wenn ein Contract-Test einen echten Bug aufdeckt (Fake != Manager, keine
 *   bewusste Drift), ist die Faustregel: Fake an Manager-Semantik angleichen,
 *   NICHT Manager lockern. Grund: Produktions-Verhalten ist Wahrheit, Test-
 *   Verhalten muss dem folgen. Bereits so gefixt:
 *     - FakeFavoritesRepository.saveFavoriteComponents: Blank-Filter analog
 *       zum Manager
 *     - FakeFavoritesOrderRepository.sortFavoriteComponents: `order.distinct()`
 *       analog zur Find-and-Remove-Logik im Manager
 *     - FakeCustomNamesRepository.removeCustomNameForPackage: idempotent,
 *       immer true, immer trigger — analog zum Manager
 *
 * ANDROIDTEST-DOPPLUNG VERGESSEN NICHT:
 *   Einige Fakes haben Dublikate im androidTest-Sourceset (z.B.
 *   FakeSettingsRepository). Beim Fake-Fix IMMER BEIDE Orte syncen, sonst
 *   driftet man in die andere Richtung. Siehe Warnung in
 *   FakeSettingsRepository.kt.
 * ============================================================================
 */

/**
 * ============================================================================
 * MUTABLESHAREDFLOW IN CONSTRUCTOR — BUFFER OVERFLOW TRAP
 * ============================================================================
 *
 * Das Problem:
 *   Einige Manager (z.B. CustomNamesRepositoryImpl) benutzen
 *   `MutableSharedFlow<Unit>` für Event-Streams. In Produktion wird dieser
 *   SharedFlow von einem Konsumenten (InstalledAppsRepositoryImpl, etc.)
 *   permanent collected — `emit` geht immer durch, weil der Buffer sofort
 *   geleert wird.
 *
 *   In Tests gibt es oft keinen Subscriber. Das default-Verhalten von
 *   MutableSharedFlow mit `extraBufferCapacity = 0` und keinen Subscribern
 *   ist: `emit` SUSPENDIERT, bis ein Subscriber kommt. Im Unit-Test kommt
 *   nie einer. Resultat: Der Test hängt, JUnit-Timeout nach 60s.
 *
 *   Auch mit `extraBufferCapacity = 1` (wie in Produktion via
 *   AppUpdateModule) ist das nur bis zur ERSTEN Emission sicher. Der
 *   zweite `emit` füllt den Buffer wieder und suspendiert.
 *
 * Zwei Fixes, je nach Kontext:
 *
 * 1) Manager-Tests mit Contract-artigen Aufrufen (repo.triggerX() mehrmals):
 *    SharedFlow im Test-Setup mit `BufferOverflow.DROP_OLDEST` konfigurieren.
 *    Trigger-Verhalten ist dann nicht beobachtbar, aber der Test hängt nicht.
 *
 *      val trigger = MutableSharedFlow<Unit>(
 *          replay = 0,
 *          extraBufferCapacity = 1,
 *          onBufferOverflow = BufferOverflow.DROP_OLDEST
 *      )
 *      val manager = CustomNamesRepositoryImpl(fakeDataStore, trigger, context)
 *
 *    Referenz: CustomNamesRepositoryImplContractTest.kt
 *
 * 2) Tests die das Event-Verhalten PRÜFEN wollen (nicht nur nicht-hängen):
 *    Turbine-Pattern — Collector vor Trigger aufsetzen.
 *
 *      repo.eventFlow.test {
 *          repo.doAction()
 *          assertEquals(Unit, awaitItem())
 *          cancelAndIgnoreRemainingEvents()
 *      }
 *
 *    Das funktioniert weil `test { }` synchron einen Collector attached,
 *    bevor `doAction` aufgerufen wird. Der Buffer-Overflow tritt nicht ein.
 *
 * ANTI-PATTERN:
 * ✗ val trigger = MutableSharedFlow<Unit>()   // hängt beim ersten emit!
 * ✗ val trigger = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
 *                                              // hängt beim zweiten emit!
 *
 * Wann WELCHER FIX:
 *   - Event-Emission ist nicht-observed, nur "darf nicht crashen"  → DROP_OLDEST
 *   - Event-Emission soll beobachtet werden (awaitItem/expectNoEvents) → Turbine
 * ============================================================================
 */

/**
 * ============================================================================
 * ROBOLECTRIC APPLICATION CONFIG — TWO-LAYER STRATEGY
 * ============================================================================
 *
 * Reference: `app/src/test/resources/robolectric.properties`
 *
 * THE PROBLEM (also documented in TODO.md §6)
 * --------------------------------------------
 * AndroidManifest.xml declares `android:name=".KolibriLauncherApp"`.
 * Without an override, Robolectric loads that production Application for
 * every test, and its `onCreate()` installs ACRA's global
 * UncaughtExceptionHandler, plants Timber trees, registers a
 * BroadcastReceiver, creates a CoroutineScope, launches the
 * [com.github.reygnn.kolibri_launcher.crashreporting.ingestion.AnrReporter] coroutine,
 * and starts a [com.github.reygnn.kolibri_launcher.crashreporting.resilience.RecoveryWatchdog]
 * daemon thread. None of that is torn down between tests, so every
 * Robolectric test class would leak one of each. With ~10+ Robolectric
 * test classes and the test executor at -Xmx512m, the accumulated
 * handlers / threads / receivers pushed the heap over the edge —
 * locally and in CI.
 *
 * Historic note: pre-2026-05-06 the worst leaker in this list was
 * `ANRWatchDog(5000)`, a non-daemon thread that sampled the main thread
 * every 5 s for the JVM lifetime. The dependency is gone (replaced by
 * AnrReporter for reporting + RecoveryWatchdog for self-defense); the
 * two-layer strategy below was still load-bearing even without it
 * (ACRA + Timber trees alone cause cross-test bleed-through), so the
 * rules in this section did not change.
 *
 * Aside on RecoveryWatchdog specifically: it's a deliberate convention
 * break — raw [Thread], no coroutines. The class KDoc explains why
 * (the watchdog has to function when the kotlinx scheduler is the thing
 * that's hung). Don't "fix" it by routing through a dispatcher later.
 *
 *
 * THE TWO-LAYER STRATEGY
 * ----------------------
 * Robolectric resolves the test Application class in this priority order:
 *
 *   1. Per-test `@Config(application = ...)` — highest priority, overrides
 *      everything below.
 *   2. `app/src/test/resources/robolectric.properties` `application=...` —
 *      project-wide default for the test source set.
 *   3. AndroidManifest.xml `android:name` — fallback (would load
 *      KolibriLauncherApp; we never want this in tests).
 *
 * The project sets:
 *
 *   Layer 2 (default, in robolectric.properties):
 *     application=android.app.Application
 *
 *     A no-op Application that brings up no production singletons.
 *     Every Robolectric test that does NOT need Hilt automatically gets
 *     this default — no `@Config` needed at the test side.
 *
 *   Layer 1 (per-test override, in @HiltAndroidTest classes):
 *     @Config(application = HiltTestApplication::class)
 *
 *     A Hilt-managed minimal Application. Required for tests that
 *     resolve `@AndroidEntryPoint` / `@Inject` dependencies. Setting
 *     this override is what lets the @HiltAndroidTest scenario
 *     resolve the production Hilt graph at the test site.
 *
 *
 * RULES OF THUMB
 * --------------
 *  - Need Hilt? Set `@Config(application = HiltTestApplication::class)`
 *    and add `@HiltAndroidTest` plus `@get:Rule val hiltRule =
 *    HiltAndroidRule(this)`. See the next section.
 *  - Don't need Hilt? Don't set `application=`. The properties-default
 *    gives you a no-op Application and KolibriLauncherApp stays out.
 *  - NEVER let a test load `KolibriLauncherApp` — even one such test in
 *    the run installs ACRA's global handler, plants Timber trees, and
 *    creates a CoroutineScope that survives until the JVM ends. The
 *    properties-default exists to prevent that.
 *
 *
 * REGRESSION HAZARDS
 * ------------------
 * If `robolectric.properties` is ever deleted, the only safety net is
 * per-test `@Config(application = ...)` set on every Robolectric test
 * class. New tests will inevitably forget it and reintroduce the leak.
 * Treat the properties file as load-bearing infrastructure, not
 * cosmetic config.
 *
 * If a future review notices the apparent redundancy ("we have BOTH a
 * properties default AND per-test overrides — pick one"): the answer is
 * that they cover different cases. The properties default is a global
 * floor for tests that don't need Hilt; the per-test override is the
 * only way to express "this test wants the Hilt-managed Application".
 * Robolectric's @Config naturally takes priority — there is no conflict.
 * Removing either layer reintroduces a class of bug we already paid for.
 * ============================================================================
 */

/**
 * ============================================================================
 * ROBOLECTRIC + HILT — ACTIVITY / FRAGMENT TESTS
 * ============================================================================
 *
 * Reference: [com.github.reygnn.kolibri_launcher.ui.onboarding
 *             .OnboardingActivityRobolectricTest]
 *
 * The project's default test target is JVM (per Rule 10 in the top-level
 * CLAUDE.md). For most logic that is the right answer. But when the
 * thing under test only reaches its bug under an actual Activity /
 * Fragment lifecycle — onCreate inflation, ViewBinding, lifecycle-scoped
 * coroutine collection, fragment teardown races — Robolectric + Hilt
 * lets us cover it on the JVM, without an emulator.
 *
 *
 * WHEN TO REACH FOR THIS PATTERN
 * ------------------------------
 * - You're about to remove try/catch wrappers from a Fragment / Activity
 *   (the §2 sweep), and you want a smoke-test backstop that proves
 *   onCreate still completes.
 * - You're touching Activity/Fragment code that Pure-Logic extraction
 *   (Rule 10) can't move out — view inflation, binding lifecycle,
 *   ViewModel collection wiring.
 *
 * Don't reach for it when JVM tests on a ViewModel / use case / pure
 * helper would cover the same risk. Robolectric tests are ~10x slower
 * (Robolectric framework boot dominates the first test in a file —
 * ~9s vs. <1s for JVM). Run them sparingly.
 *
 *
 * THE PATTERN
 * -----------
 *
 *   @RunWith(RobolectricTestRunner::class)
 *   @HiltAndroidTest
 *   @Config(application = HiltTestApplication::class)
 *   class MyActivityRobolectricTest {
 *
 *       @get:Rule
 *       val hiltRule = HiltAndroidRule(this)
 *
 *       @Test
 *       fun `activity launches without crashing`() {
 *           ActivityScenario.launch(MyActivity::class.java).use { scenario ->
 *               scenario.onActivity { activity ->
 *                   assertNotNull(activity)
 *                   // assert specific UI bits if you care, e.g.
 *                   // assertNotNull(activity.findViewById(R.id.search_field))
 *               }
 *           }
 *       }
 *   }
 *
 * For Fragments, swap `ActivityScenario` for `FragmentScenario`
 * (`androidx.fragment:fragment-testing-manifest` may be needed depending
 * on the fragment's host requirements). For activities that take an
 * intent (e.g. OnboardingActivity reads `EXTRA_LAUNCH_MODE`), pass it
 * via `ActivityScenario.launch(Intent(context, MyActivity::class.java)
 * .apply { putExtra(...) })`.
 *
 *
 * ABOUT @HiltAndroidTest + HiltTestApplication
 * ---------------------------------------------
 * - `HiltTestApplication` (from `dagger.hilt.android.testing`) is a
 *   minimal Hilt-managed Application. It is *not* the production
 *   `KolibriLauncherApp` — so production-only init code (ACRA, package
 *   receiver registration, AnrReporter `ApplicationExitInfo` query) does
 *   NOT run in tests. That's intentional and what we want.
 * - All production Hilt modules are in effect. The Activity's @Inject
 *   dependencies wire from the real graph; the real Repositories,
 *   UseCases etc. all initialise on Robolectric's in-memory filesystem.
 *   In practice this just works for the smoke-test pattern above —
 *   surprisingly little fights it.
 * - HiltAndroidRule must be `@get:Rule` (not just `@Rule`) for Kotlin.
 *   No `.inject()` call needed for Activity/Fragment tests; Hilt wires
 *   the @AndroidEntryPoint as soon as the scenario launches.
 * - For Robolectric tests that do NOT need Hilt (e.g. plain Repository
 *   / WallpaperFileManager / Wallpaper-Uri tests), do NOT set
 *   `@Config(application = ...)`. The project-wide default in
 *   `app/src/test/resources/robolectric.properties` resolves to
 *   `android.app.Application`, which is correct for those tests. Only
 *   `@HiltAndroidTest` classes need the override. See the previous
 *   section ("ROBOLECTRIC APPLICATION CONFIG") for the rationale.
 *
 *
 * WHEN A REAL DEPENDENCY IS THE WRONG CHOICE
 * -------------------------------------------
 * If a production module is too heavy / slow / nondeterministic for the
 * test (e.g. it network-touches, or runs a real package scan), replace
 * it with a fake module via @TestInstallIn:
 *
 *   @Module
 *   @TestInstallIn(
 *       components = [SingletonComponent::class],
 *       replaces   = [ProductionModule::class]
 *   )
 *   object FakeProductionModule {
 *       @Provides @Singleton fun provideXyzRepository(): XyzRepository =
 *           FakeXyzRepository()
 *   }
 *
 * Place test modules under `app/src/test/.../testmodule/` or alongside
 * the test that uses them. Don't add @TestInstallIn modules
 * preemptively — only when a real dependency demonstrably blocks a test.
 *
 *
 * DEPENDENCIES (already in `app/build.gradle.kts`)
 * -------------------------------------------------
 *   testImplementation org.robolectric:robolectric            // existing
 *   testImplementation com.google.dagger:hilt-android-testing // existing
 *   testImplementation androidx.test:core-ktx                 // for ActivityScenario
 *   testImplementation androidx.test.ext:junit-ktx            // AndroidJUnit base
 *   kaptTest          com.google.dagger:hilt-compiler         // existing
 *
 * For Fragment tests, the existing approach is to host the fragment in
 * the project's `HiltTestActivity` (under `src/debug/`) via
 * `ActivityScenario.launch(HiltTestActivity::class.java)` plus a manual
 * fragment transaction — see [com.github.reygnn.kolibri_launcher.ui
 * .appdrawer.AppDrawerFragmentRobolectricTest]. That route is required
 * when the fragment uses `activityViewModels()` to resolve a
 * Hilt-scoped ViewModel, because `androidx.fragment:fragment-testing`'s
 * default `EmptyFragmentActivity` is not `@AndroidEntryPoint`.
 * Consequently `androidx.fragment:fragment-testing` is intentionally
 * NOT a `testImplementation` — it would only pull in
 * `fragment-testing-manifest`'s `EmptyActivity` registration without
 * being usefully reachable from any test.
 * ============================================================================
 */

/**
 * ============================================================================
 * TIME-BASED ASSERTIONS — pinning delays, retries, timeouts, throttles
 * ============================================================================
 *
 * Reference: `ObserveInstalledAppsUseCaseTest.retry counter resets between
 *             invocations on IOException backoff` (commit f8a578b),
 *             `FavoritesRepositoryImplShareInTest` (the older idiom).
 *
 *
 * THE GAP THAT BIRTHED THIS CONVENTION
 * ------------------------------------
 * `ObserveInstalledAppsUseCase` had a class-field retry counter that only
 * reset on success — after a fully failed first invocation it stayed at
 * MAX_APP_LOAD_RETRIES, so the second invocation's first retry computed
 * `delay = base * (max + 1)` instead of `base * 1`. The linear backoff
 * silently scaled across invocations, producing 4–6× longer waits than
 * the design intended.
 *
 * The bug was production code from file inception. Seven existing tests
 * exercised the use case's flow logic. None of them caught the bug,
 * because all seven tested *what* the flow emitted, not *when*. Backoff
 * is a temporal property; it is invisible to result-only assertions.
 *
 *
 * THE RULE
 * --------
 * Any production code that uses `delay(…)`, `withTimeout(…)`,
 * `withTimeoutOrNull(…)`, `retry(…)` (with a delay-emitting predicate),
 * `WhileSubscribed(timeout)` in `shareIn` / `stateIn`, or any other
 * temporal primitive needs at least one test that asserts on virtual
 * time, not just on the final emitted value.
 *
 * The test should:
 *
 *   1. Run inside `runTest { … }` so virtual time is in scope.
 *   2. Either snapshot `testScheduler.currentTime` before and after the
 *      behavior and assert on the difference, OR call
 *      `advanceTimeBy(specific)` to set up a precise timing scenario
 *      and assert the boundary behavior.
 *   3. Carry a comment that names what the test pins — e.g. "linear
 *      backoff per retry" or "share-in upstream stays alive for the
 *      WhileSubscribed timeout window". This makes the test robust to
 *      future refactors: the next reader knows whether to update the
 *      assertion or restore the timing.
 *
 *
 * WHICH IDIOM, WHEN
 * -----------------
 *
 *   `testScheduler.currentTime` snapshot
 *   ────────────────────────────────────
 *   Use when the assertion is *relative* — comparing two durations
 *   for equality, monotonicity, etc. Reference:
 *
 *     val firstStart = testScheduler.currentTime
 *     useCase().test { awaitItem(); cancelAndIgnoreRemainingEvents() }
 *     val firstDuration = testScheduler.currentTime - firstStart
 *
 *     val secondStart = testScheduler.currentTime
 *     useCase().test { awaitItem(); cancelAndIgnoreRemainingEvents() }
 *     val secondDuration = testScheduler.currentTime - secondStart
 *
 *     assertEquals(firstDuration, secondDuration)
 *
 *   Source: `ObserveInstalledAppsUseCaseTest.retry counter resets…`.
 *   Pins symmetric timing without locking down the exact constants.
 *
 *
 *   `advanceTimeBy(specific)` + boundary asserts
 *   ────────────────────────────────────────────
 *   Use when the assertion is *absolute* — proving behavior at a
 *   timeout's edge (just before vs. just after). Reference:
 *
 *     advanceTimeBy(AppConstants.FLOW_SHARING_TIMEOUT_MS - 1000)
 *     // upstream still alive — the cached value is still there
 *     advanceTimeBy(2000)
 *     // upstream has now timed out — next read goes to dataStore
 *
 *   Source: `FavoritesRepositoryImplShareInTest`. Pins the exact
 *   timeout boundary so a later edit that changes the constant gets
 *   noticed.
 *
 *
 * BACKSTOP — what `advanceUntilIdle()` proves and what it doesn't
 * ---------------------------------------------------------------
 * `advanceUntilIdle()` runs every queued task to completion regardless
 * of the time involved. Tests that use only `advanceUntilIdle()` prove
 * "the operation finishes and produces the expected result", which is
 * good and necessary — but is silent on whether a `delay(N)` actually
 * advanced N or 0 (or 10×N). The retry-counter bug passed every
 * `advanceUntilIdle()`-based test in the use case's suite.
 *
 * If the production code uses a temporal primitive *and* the
 * correctness of that primitive is observable to the user (visible
 * UI lag, throttle behavior, retry pacing, lock duration), the suite
 * needs at least one assertion in virtual time on that site. If the
 * primitive is internal plumbing whose duration the user can't
 * observe (`SharingStarted.Lazily`, etc.), `advanceUntilIdle` is
 * sufficient.
 *
 *
 * SITES THAT EXIST IN THIS REPO
 * -----------------------------
 * Surveyed 2026-05-03. Each site uses a temporal primitive in
 * production code; the column shows whether a virtual-time assertion
 * exists today.
 *
 *   ObserveInstalledAppsUseCase  — retry/backoff       ✓ pinned
 *   GestureDelegate              — lock-block-duration ✓ pinned
 *   FavoritesRepositoryImpl      — share-in timeout    ✓ pinned (ShareInTest)
 *   FavoritesOrderRepositoryImpl — share-in timeout    ✗ relies on contract
 *   InstalledAppsRepositoryImpl  — share-in timeout    ✗ relies on contract
 *   LayoutDelegate               — share-in timeout    ✗ relies on contract
 *   AppManagementDelegate        — initial-load delay  ✗ low-risk (100ms)
 *   PackageUpdateReceiver        — withTimeout 3s      ✗ system path
 *   AppDrawerFragment            — search debounce     ✗ Fragment-side
 *   HomeFragment                 — scroll-debounce     ✗ Fragment-side
 *   BackupFragment               — preview timeout     ✗ Fragment-side
 *
 * "✗ relies on contract" means the share-in semantic is uniformly
 * tested in `FavoritesRepositoryImplShareInTest` for one impl, and
 * the others use the same `shareIn` builder with the same constant —
 * a regression in any of them would show up in the contract test
 * surface even without an isolated check. If the constant ever
 * diverges across the impls, each of those needs its own
 * ShareInTest mirror.
 *
 * "✗ Fragment-side" means the test would need Robolectric or a
 * dedicated controller-extraction (per Rule 10) before a JVM test
 * can pin the timing. Those bring their own cost — see the
 * Robolectric+Hilt section above. Promote them only when a
 * regression actually lands.
 * ============================================================================
 */