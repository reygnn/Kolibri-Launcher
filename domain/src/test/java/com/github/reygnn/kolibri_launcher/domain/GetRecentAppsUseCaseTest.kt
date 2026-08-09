package com.github.reygnn.kolibri_launcher.domain

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.usecase.GetRecentAppsUseCase
import com.github.reygnn.kolibri_launcher.fakes.FakeAppUsageRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeCustomNamesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeHiddenAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeInstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

        assertEquals(listOf("C", "B", "A"), names(useCase(8)))
    }

    @Test
    fun `lists each package once at its most recent position`() = runTest(mainDispatcherRule.testDispatcher) {
        installed.updateApps(listOf(appA, appB))
        usage.recordPackageLaunch("pkg.a")
        usage.recordPackageLaunch("pkg.b")
        usage.recordPackageLaunch("pkg.a") // A launched again → most recent

        assertEquals(listOf("A", "B"), names(useCase(8)))
    }

    @Test
    fun `caps the result at the limit`() = runTest(mainDispatcherRule.testDispatcher) {
        installed.updateApps(listOf(appA, appB, appC))
        usage.recordPackageLaunch("pkg.a")
        usage.recordPackageLaunch("pkg.b")
        usage.recordPackageLaunch("pkg.c")

        assertEquals(2, useCase(2).size)
    }

    @Test
    fun `excludes hidden apps`() = runTest(mainDispatcherRule.testDispatcher) {
        installed.updateApps(listOf(appA, appB, appC))
        hidden.hiddenAppsState.value = setOf(appB.componentName)
        usage.recordPackageLaunch("pkg.a")
        usage.recordPackageLaunch("pkg.b")
        usage.recordPackageLaunch("pkg.c")

        assertEquals(listOf("C", "A"), names(useCase(8)))
    }

    @Test
    fun `drops packages no longer installed`() = runTest(mainDispatcherRule.testDispatcher) {
        installed.updateApps(listOf(appA, appC)) // B recorded but not installed
        usage.recordPackageLaunch("pkg.a")
        usage.recordPackageLaunch("pkg.b")
        usage.recordPackageLaunch("pkg.c")

        assertEquals(listOf("C", "A"), names(useCase(8)))
    }

    @Test
    fun `empty when nothing was launched`() = runTest(mainDispatcherRule.testDispatcher) {
        installed.updateApps(listOf(appA, appB))
        assertTrue(useCase(8).isEmpty())
    }

    @Test
    fun `non-positive limit returns empty`() = runTest(mainDispatcherRule.testDispatcher) {
        installed.updateApps(listOf(appA))
        usage.recordPackageLaunch("pkg.a")
        assertTrue(useCase(0).isEmpty())
    }
}
