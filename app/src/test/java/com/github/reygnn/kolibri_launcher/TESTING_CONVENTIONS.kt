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
 * EXCEPTION — DelegateScopeTest:
 * When testing DelegateScope itself (not a ViewModel/Delegate), use
 * StandardTestDispatcher(testScheduler) from inside runTest { } to share
 * the scheduler. This is needed because launchSafe catches exceptions,
 * and UnconfinedTestDispatcher would let them escape to runTest first.
 *
 * See: MainDispatcherRule.kt, AppManagementDelegateTest.kt (reference impl)
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