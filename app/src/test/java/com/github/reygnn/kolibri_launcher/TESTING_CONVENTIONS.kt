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