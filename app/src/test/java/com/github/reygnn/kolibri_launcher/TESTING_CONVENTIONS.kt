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
 *    `launch(UnconfinedTestDispatcher()) { vm.splitModeThreshold.collect {} }`
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
 *   Managers, die `shareIn(externalScope, WhileSubscribed(…), replay = 1)`
 *   über ihre Flows legen (FavoritesRepositoryImpl, FavoritesOrderRepositoryImpl,
 *   SwipeActionsRepositoryImpl), haben einen Write-then-Read-Bug unter
 *   UnconfinedTestDispatcher: der Write geht durch zu dataStore, der
 *   Upstream-Collector hat den Update aber noch nicht in den Replay-Buffer
 *   geschrieben, der Read liefert den alten gecachten Wert.
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
 *     - ScreenLockRepositoryContract: initial isLockingAvailable true vs false
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
 *   Einige Manager (z.B. CustomNamesRepositoryImpl, ScreenLockRepositoryImpl) benutzen
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
 *    Referenz: ScreenLockRepositoryContract.kt, ScreenLockRepositoryImplTest.kt
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