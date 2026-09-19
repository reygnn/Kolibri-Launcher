package com.github.reygnn.launcher.testing

import android.app.Activity
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage

/**
 * Runs [block] on the main thread and blocks until it completes. Thin,
 * product-neutral wrapper over Instrumentation.runOnMainSync so page objects
 * don't each reach into InstrumentationRegistry.
 */
public fun onMainSync(block: () -> Unit): Unit =
    InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

/**
 * The currently RESUMED activity of type [T], or null. Lifted verbatim from
 * the private helper that several instrumented tests already carry, so the
 * facade and those tests share one definition.
 */
public inline fun <reified T : Activity> currentResumed(): T? =
    ActivityLifecycleMonitorRegistry.getInstance()
        .getActivitiesInStage(Stage.RESUMED)
        .firstOrNull { it is T } as? T
