package com.github.reygnn.nyx_launcher.testing

import com.github.reygnn.launcher.core.testing.MainDispatcherRuleBase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher

/**
 * Nyx's MainDispatcherRule — the shared [MainDispatcherRuleBase] plumbing with
 * Nyx's lazy [StandardTestDispatcher] (the family-documented default). Tests read
 * it as `mainDispatcherRule.dispatcher`. The dispatcher flavour is a deliberate
 * per-module choice (see the base class); only this one line is Nyx-specific.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : MainDispatcherRuleBase(StandardTestDispatcher())
