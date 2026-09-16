package com.github.reygnn.kolibri_launcher.rule

import com.github.reygnn.launcher.core.testing.MainDispatcherRuleBase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Kolibri's MainDispatcherRule — the shared [MainDispatcherRuleBase] plumbing with
 * Kolibri's eager [UnconfinedTestDispatcher]. The dispatcher flavour is a
 * deliberate per-module choice (see the base class); only this one line is
 * Kolibri-specific.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : MainDispatcherRuleBase(UnconfinedTestDispatcher())
