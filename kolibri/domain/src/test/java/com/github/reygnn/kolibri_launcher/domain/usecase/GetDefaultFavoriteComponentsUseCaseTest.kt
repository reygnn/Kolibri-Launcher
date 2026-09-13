package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.DefaultAppsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GetDefaultFavoriteComponentsUseCaseTest {

    /** Simple in-test double — the repository is a system-API boundary (ADR: no fake). */
    private class FakeDefaultAppsRepository(private val packages: List<String>) : DefaultAppsRepository {
        override suspend fun getDefaultAppPackages(): List<String> = packages
    }

    private fun app(pkg: String, cls: String) = AppInfo("n", "n", pkg, cls)

    private fun useCase(defaultPackages: List<String>) =
        GetDefaultFavoriteComponentsUseCase(FakeDefaultAppsRepository(defaultPackages))

    @Test
    fun `maps default packages to component identities preserving role order`() = runTest {
        val phone = app("com.phone", "Dialer")
        val browser = app("com.browser", "Main")
        val available = listOf(browser, phone, app("com.other", "X"))

        val result = useCase(listOf("com.phone", "com.browser")).invoke(available)

        assertEquals(listOf(phone.componentName, browser.componentName), result)
    }

    @Test
    fun `skips a default package that is not installed`() = runTest {
        val phone = app("com.phone", "Dialer")
        val available = listOf(phone)

        val result = useCase(listOf("com.phone", "com.sms.not.installed")).invoke(available)

        assertEquals(listOf(phone.componentName), result)
    }

    @Test
    fun `first launcher entry per package wins`() = runTest {
        val firstEntry = app("com.multi", "AActivity")
        val secondEntry = app("com.multi", "BActivity")
        val available = listOf(firstEntry, secondEntry)

        val result = useCase(listOf("com.multi")).invoke(available)

        assertEquals(listOf(firstEntry.componentName), result)
    }

    @Test
    fun `de-duplicates when the same package is a default for two roles`() = runTest {
        // e.g. one app is both the default browser and camera.
        val combo = app("com.combo", "Main")
        val available = listOf(combo)

        val result = useCase(listOf("com.combo", "com.combo")).invoke(available)

        assertEquals(listOf(combo.componentName), result)
    }

    @Test
    fun `empty defaults yields empty result`() = runTest {
        val result = useCase(emptyList()).invoke(listOf(app("com.phone", "Dialer")))

        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun `empty available apps yields empty result`() = runTest {
        val result = useCase(listOf("com.phone")).invoke(emptyList())

        assertEquals(emptyList<String>(), result)
    }
}
