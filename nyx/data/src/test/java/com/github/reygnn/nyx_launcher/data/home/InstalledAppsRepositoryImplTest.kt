package com.github.reygnn.nyx_launcher.data.home

import android.content.ComponentName
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.AppLoadResult
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the fail-closed policy of [InstalledAppsRepositoryImpl] (RHL-INV-1,
 * AUDIT-1 A1-01/A1-11): a thrown OR empty enumeration is an [AppLoadResult.Error],
 * never `Loaded(emptyList())` — otherwise reconcile would prune the whole home.
 * Robolectric only for `Process.myUserHandle()` / `ComponentName`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class InstalledAppsRepositoryImplTest {

    private val launcherApps = mockk<LauncherApps>()
    private val dispatcher = StandardTestDispatcher()
    private val repo = InstalledAppsRepositoryImpl(launcherApps, dispatcher)

    @Test
    fun empty_enumeration_is_error_not_empty_loaded() = runTest(dispatcher) {
        every { launcherApps.getActivityList(any(), any()) } returns emptyList()
        assertThat(repo.loadInstalledApps())
            .isEqualTo(AppLoadResult.Error(AppLoadResult.Reason.ENUMERATION_EMPTY))
    }

    @Test
    fun thrown_enumeration_is_error() = runTest(dispatcher) {
        every { launcherApps.getActivityList(any(), any()) } throws RuntimeException("boom")
        assertThat(repo.loadInstalledApps())
            .isEqualTo(AppLoadResult.Error(AppLoadResult.Reason.ENUMERATION_FAILED))
    }

    @Test
    fun non_empty_enumeration_maps_to_loaded() = runTest(dispatcher) {
        val info = mockk<LauncherActivityInfo>()
        every { info.componentName } returns ComponentName("com.example", "com.example.Main")
        every { info.label } returns "Example"
        every { launcherApps.getActivityList(any(), any()) } returns listOf(info)

        val result = repo.loadInstalledApps()
        assertThat(result).isInstanceOf(AppLoadResult.Loaded::class.java)
        val apps = (result as AppLoadResult.Loaded).apps
        assertThat(apps).hasSize(1)
        assertThat(apps[0].key).isEqualTo(ComponentKey("com.example", "com.example.Main"))
        assertThat(apps[0].label).isEqualTo("Example")
    }
}
