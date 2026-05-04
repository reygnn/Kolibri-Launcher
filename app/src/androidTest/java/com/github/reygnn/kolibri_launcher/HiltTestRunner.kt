package com.github.reygnn.kolibri_launcher

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Custom runner that swaps in HiltTestApplication so @HiltAndroidTest classes
 * get their own isolated SingletonComponent per test process. Required by the
 * testInstrumentationRunner declaration in app/build.gradle.kts.
 *
 * No production-side equivalent — this only ever runs under androidTest.
 */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?
    ): Application {
        return super.newApplication(cl, HiltTestApplication::class.java.name, context)
    }
}
