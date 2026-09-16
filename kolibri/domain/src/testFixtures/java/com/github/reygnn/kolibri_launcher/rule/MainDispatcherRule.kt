package com.github.reygnn.kolibri_launcher.rule

import com.github.reygnn.launcher.core.testing.MainDispatcherRuleBase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher

/**
 * Kolibri's MainDispatcherRule — the shared [MainDispatcherRuleBase] plumbing with
 * the lazy [StandardTestDispatcher], the family-documented default (matching Nyx and
 * `:common-ui`). Lazy execution makes coroutine ordering explicit: launched work runs
 * only on `advanceUntilIdle()` / `runCurrent()`, so "launched but never awaited" bugs
 * surface in the test instead of being hidden by eager execution. The dispatcher
 * flavour is a deliberate per-module choice (see the base class); only this one line
 * is Kolibri-specific.
 *
 * History: Kolibri used the eager `UnconfinedTestDispatcher` until the suite was
 * converted to `StandardTestDispatcher`; the conversion added the missing
 * `advanceUntilIdle()` calls in the ViewModel tests that had implicitly relied on
 * eager execution.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : MainDispatcherRuleBase(StandardTestDispatcher())
