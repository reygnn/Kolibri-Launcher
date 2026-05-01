package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class InstalledAppsRepositoryImplTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK
    private lateinit var mockPackageManager: PackageManager
    @MockK
    private lateinit var mockCustomNamesRepository: CustomNamesRepository
    @MockK
    private lateinit var mockAppsUpdateTrigger: MutableSharedFlow<Unit>
    @MockK(relaxed = true)
    private lateinit var mockContext: Context

    private lateinit var installedAppsRepositoryImpl: InstalledAppsRepositoryImpl

    private class FakeResolveInfo(
        private val label: CharSequence,
        packageName: String,
        className: String
    ) : ResolveInfo() {
        init {
            super.activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                this.name = className
            }
        }
        override fun loadLabel(pm: PackageManager): CharSequence = label
    }

    private class FailingResolveInfo(packageName: String, className: String) : ResolveInfo() {
        init {
            super.activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                this.name = className
            }
        }
        override fun loadLabel(pm: PackageManager): CharSequence {
            throw RuntimeException("Test-Fehler beim Laden des Labels")
        }
    }

    private class NullActivityInfoResolveInfo : ResolveInfo() {
        init { super.activityInfo = null }
        override fun loadLabel(pm: PackageManager): CharSequence = "Invalid"
    }

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        installedAppsRepositoryImpl = InstalledAppsRepositoryImpl(
            mockContext,
            mockPackageManager,
            mockCustomNamesRepository,
            mockAppsUpdateTrigger
        )
    }

    // ========== EXISTING TESTS ==========

    @Test
    fun `triggerAppsUpdate emits an event to the trigger flow`() = runTest {
        coEvery { mockAppsUpdateTrigger.emit(Unit) } returns Unit

        installedAppsRepositoryImpl.triggerAppsUpdate()

        coVerify { mockAppsUpdateTrigger.emit(Unit) }
    }

    @Test
    fun `processResolveInfoList correctly converts and sorts a list of ResolveInfo`() = runTest {
        // getDisplayNameForPackage gibt den originalName (2. Argument) zurück
        coEvery { mockCustomNamesRepository.getDisplayNameForPackage(any(), any()) } answers { secondArg() }

        val fakeResolveInfoList = listOf(
            FakeResolveInfo("App B", "com.b", "com.b.MainActivity"),
            FakeResolveInfo("App A", "com.a", "com.a.MainActivity")
        )

        val expectedAppList = listOf(
            AppInfo(originalName = "App A", displayName = "App A", packageName = "com.a", className = "com.a.MainActivity"),
            AppInfo(originalName = "App B", displayName = "App B", packageName = "com.b", className = "com.b.MainActivity")
        )

        val actualAppList = installedAppsRepositoryImpl.processResolveInfoList(fakeResolveInfoList)

        Assert.assertEquals(expectedAppList.size, actualAppList.size)
        Assert.assertEquals(expectedAppList[0].displayName, actualAppList[0].displayName)
        Assert.assertEquals(expectedAppList[1].displayName, actualAppList[1].displayName)
    }

    @Test
    fun `processResolveInfoList - with null activityInfo - skips item`() = runTest {
        coEvery { mockCustomNamesRepository.getDisplayNameForPackage(any(), any()) } answers { secondArg() }

        val fakeResolveInfoList = listOf(
            FakeResolveInfo("Good App", "com.good", "com.good.MainActivity"),
            NullActivityInfoResolveInfo()
        )

        val actualAppList = installedAppsRepositoryImpl.processResolveInfoList(fakeResolveInfoList)

        Assert.assertEquals(1, actualAppList.size)
        Assert.assertEquals("Good App", actualAppList[0].displayName)
    }

    @Test
    fun `processResolveInfoList - with duplicate packages - keeps all entries`() = runTest {
        coEvery { mockCustomNamesRepository.getDisplayNameForPackage(any(), any()) } answers { secondArg() }

        val fakeResolveInfoList = listOf(
            FakeResolveInfo("App A", "com.a", "com.a.MainActivity"),
            FakeResolveInfo("App A Activity2", "com.a", "com.a.SecondActivity")
        )

        val actualAppList = installedAppsRepositoryImpl.processResolveInfoList(fakeResolveInfoList)

        Assert.assertEquals(2, actualAppList.size)
        Assert.assertEquals("com.a", actualAppList[0].packageName)
        Assert.assertEquals("com.a", actualAppList[1].packageName)
    }

    @Test
    fun `processResolveInfoList - with empty label - uses package name as fallback`() = runTest {
        coEvery { mockCustomNamesRepository.getDisplayNameForPackage(any(), any()) } answers { secondArg() }

        val fakeResolveInfoList = listOf(FakeResolveInfo("", "com.test", "com.test.MainActivity"))

        val actualAppList = installedAppsRepositoryImpl.processResolveInfoList(fakeResolveInfoList)

        Assert.assertEquals(1, actualAppList.size)
        Assert.assertEquals("com.test", actualAppList[0].originalName)
        Assert.assertEquals("com.test", actualAppList[0].displayName)
    }

    @Test
    fun `processResolveInfoList - with very long app names - handles correctly`() = runTest {
        coEvery { mockCustomNamesRepository.getDisplayNameForPackage(any(), any()) } answers { secondArg() }

        val veryLongName = "A".repeat(500)
        val actualAppList = installedAppsRepositoryImpl.processResolveInfoList(
            listOf(FakeResolveInfo(veryLongName, "com.test", "com.test.MainActivity"))
        )

        Assert.assertEquals(1, actualAppList.size)
        Assert.assertEquals(veryLongName, actualAppList[0].displayName)
    }

    @Test
    fun `processResolveInfoList - with special characters in names - handles correctly`() = runTest {
        coEvery { mockCustomNamesRepository.getDisplayNameForPackage(any(), any()) } answers { secondArg() }

        val specialName = "App 🚀 Test & <Special> \"Chars\""
        val actualAppList = installedAppsRepositoryImpl.processResolveInfoList(
            listOf(FakeResolveInfo(specialName, "com.test", "com.test.MainActivity"))
        )

        Assert.assertEquals(1, actualAppList.size)
        Assert.assertEquals(specialName, actualAppList[0].displayName)
    }

    @Test
    fun `processResolveInfoList - with large list - handles efficiently`() = runTest {
        coEvery { mockCustomNamesRepository.getDisplayNameForPackage(any(), any()) } answers { secondArg() }

        val largeList = (1..1000).map {
            FakeResolveInfo("App $it", "com.app$it", "com.app$it.MainActivity")
        }

        val actualAppList = installedAppsRepositoryImpl.processResolveInfoList(largeList)

        Assert.assertEquals(1000, actualAppList.size)
        Assert.assertEquals("App 1", actualAppList[0].displayName)
    }

    @Test
    fun `processResolveInfoList - when multiple items fail - continues processing others`() = runTest {
        coEvery { mockCustomNamesRepository.getDisplayNameForPackage(any(), any()) } answers { secondArg() }

        val fakeResolveInfoList = listOf(
            FakeResolveInfo("Good 1", "com.good1", "com.good1.MainActivity"),
            FailingResolveInfo("com.bad1", "com.bad1.MainActivity"),
            FakeResolveInfo("Good 2", "com.good2", "com.good2.MainActivity"),
            FailingResolveInfo("com.bad2", "com.bad2.MainActivity"),
            FakeResolveInfo("Good 3", "com.good3", "com.good3.MainActivity")
        )

        val actualAppList = installedAppsRepositoryImpl.processResolveInfoList(fakeResolveInfoList)

        Assert.assertEquals(5, actualAppList.size)
        Assert.assertEquals("com.bad1", actualAppList[0].displayName)
        Assert.assertEquals("com.bad2", actualAppList[1].displayName)
        Assert.assertEquals("Good 1", actualAppList[2].displayName)
        Assert.assertEquals("Good 2", actualAppList[3].displayName)
        Assert.assertEquals("Good 3", actualAppList[4].displayName)
    }

    @Test
    fun `triggerAppsUpdate - when flow emit fails - does not crash`() = runTest {
        coEvery { mockAppsUpdateTrigger.emit(Unit) } throws RuntimeException("Flow error")

        installedAppsRepositoryImpl.triggerAppsUpdate()

        coVerify { mockAppsUpdateTrigger.emit(Unit) }
    }

    @Test
    fun `processResolveInfoList - with null package name - skips item`() = runTest {
        coEvery { mockCustomNamesRepository.getDisplayNameForPackage(any(), any()) } answers { secondArg() }

        val fakeResolveInfoList = listOf(
            FakeResolveInfo("Good App", "com.good", "com.good.MainActivity"),
            FakeResolveInfo("Test", "", "class")
        )

        val actualAppList = installedAppsRepositoryImpl.processResolveInfoList(fakeResolveInfoList)

        Assert.assertTrue(actualAppList.isNotEmpty())
    }

    // ========== MISSING PURGE TEST ==========

    @Test
    fun `purgeRepository - does nothing and does not crash`() = runTest {
        installedAppsRepositoryImpl.purgeRepository()

        coVerify(exactly = 0) { mockAppsUpdateTrigger.emit(Unit) }
    }
}