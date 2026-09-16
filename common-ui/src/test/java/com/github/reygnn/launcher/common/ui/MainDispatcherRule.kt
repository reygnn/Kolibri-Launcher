package com.github.reygnn.launcher.common.ui

import com.github.reygnn.launcher.core.testing.MainDispatcherRuleBase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher

/**
 * :common-ui's MainDispatcherRule — the shared [MainDispatcherRuleBase] plumbing
 * with the lazy [StandardTestDispatcher] (the family-documented default). The
 * dispatcher flavour is a deliberate per-module choice (see the base class); only
 * this one line is :common-ui-specific.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : MainDispatcherRuleBase(StandardTestDispatcher())
