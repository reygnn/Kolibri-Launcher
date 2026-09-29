package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.kolibri_launcher.domain.usecase.GetRecentAppsUseCase
import com.github.reygnn.kolibri_launcher.fakes.FakeAppUsageRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeCustomNamesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeHiddenAppsRepository
import com.github.reygnn.launcher.core.installedapps.FakeInstalledAppsStateRepository
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GetRecentAppsUseCaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    private lateinit var usage: FakeAppUsageRepository
    private lateinit var installed: FakeInstalledAppsStateRepository
    private lateinit var hidden: FakeHiddenAppsRepository
    private lateinit var customNames: FakeCustomNamesRepository
    private lateinit var useCase: GetRecentAppsUseCase

    private val appA = AppInfo("A", "A", "pkg.a", "cls.a")
    private val appB = AppInfo("B", "B", "pkg.b", "cls.b")
    private val appC = AppInfo("C", "C", "pkg.c", "cls.c")

    @Before
    fun setup() {
        usage = FakeAppUsageRepository()
        installed = FakeInstalledAppsStateRepository()
        hidden = FakeHiddenAppsRepository()
        customNames = FakeCustomNamesRepository()
        useCase = GetRecentAppsUseCase(usage, installed, hidden, customNames, mainDispatcherRule.testDispatcher)
    }

    private fun names(apps: List<AppInfo>) = apps.map { it.displayName }

    @Test
    fun `returns installed apps newest-launch first`() = runTest(mainDispatcherRule.testDispatcher) {
        installed.updateApps(listOf(appA, appB, appC))
        usage.recordPackageLaunch("pkg.a")
        usage.recordPackageLaunch("pkg.b")
        usage.recordPackageLaunch("pkg.c")

        assertThat(names(useCase(8))).isEqualTo(listOf("C", "B", "A"))
    }

    @Test
    fun `lists each package once at its most recent position`() = runTest(mainDispatcherRule.testDispatcher) {
        installed.updateApps(listOf(appA, appB))
        usage.recordPackageLaunch("pkg.a")
        usage.recordPackageLaunch("pkg.b")
        usage.recordPackageLaunch("pkg.a") // A launched again → most recent

        assertThat(names(useCase(8))).isEqualTo(listOf("A", "B"))
    }

    @Test
    fun `caps the result at the limit`() = runTest(mainDispatcherRule.testDispatcher) {
        installed.updateApps(listOf(appA, appB, appC))
        usage.recordPackageLaunch("pkg.a")
        usage.recordPackageLaunch("pkg.b")
        usage.recordPackageLaunch("pkg.c")

        assertThat(useCase(2).size).isEqualTo(2)
    }

    @Test
    fun `excludes hidden apps`() = runTest(mainDispatcherRule.testDispatcher) {
        installed.updateApps(listOf(appA, appB, appC))
        hidden.hiddenAppsState.value = setOf(appB.componentName)
        usage.recordPackageLaunch("pkg.a")
        usage.recordPackageLaunch("pkg.b")
        usage.recordPackageLaunch("pkg.c")

        assertThat(names(useCase(8))).isEqualTo(listOf("C", "A"))
    }

    @Test
    fun `drops packages no longer installed`() = runTest(mainDispatcherRule.testDispatcher) {
        installed.updateApps(listOf(appA, appC)) // B recorded but not installed
        usage.recordPackageLaunch("pkg.a")
        usage.recordPackageLaunch("pkg.b")
        usage.recordPackageLaunch("pkg.c")

        assertThat(names(useCase(8))).isEqualTo(listOf("C", "A"))
    }

    @Test
    fun `empty when nothing was launched`() = runTest(mainDispatcherRule.testDispatcher) {
        installed.updateApps(listOf(appA, appB))
        assertThat(useCase(8).isEmpty()).isTrue()
    }

    @Test
    fun `non-positive limit returns empty`() = runTest(mainDispatcherRule.testDispatcher) {
        installed.updateApps(listOf(appA))
        usage.recordPackageLaunch("pkg.a")
        assertThat(useCase(0).isEmpty()).isTrue()
    }
}
