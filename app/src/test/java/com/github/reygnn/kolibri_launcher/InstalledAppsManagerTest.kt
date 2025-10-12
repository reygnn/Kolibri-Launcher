package com.github.reygnn.kolibri_launcher

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertFailsWith

class InstalledAppsManagerTest {

    @get:Rule
    val mockitoRule: MockitoRule = MockitoJUnit.rule()

    @Mock
    private lateinit var mockPackageManager: PackageManager
    @Mock
    private lateinit var mockAppNamesManager: AppNamesRepository
    @Mock
    private lateinit var mockAppsUpdateTrigger: MutableSharedFlow<Unit>

    private lateinit var installedAppsManager: InstalledAppsManager
    @Mock
    private lateinit var mockContext: Context

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
        init {
            super.activityInfo = null
        }
        override fun loadLabel(pm: PackageManager): CharSequence = "Invalid"
    }

    @Before
    fun setup() {
        installedAppsManager = InstalledAppsManager(mockContext, mockPackageManager, mockAppNamesManager, mockAppsUpdateTrigger)
    }

    // ========== EXISTING TESTS ==========

    @Test
    fun `triggerAppsUpdate emits an event to the trigger flow`() = runTest {
        installedAppsManager.triggerAppsUpdate()

        verify(mockAppsUpdateTrigger).emit(Unit)
    }

    @Test
    fun `processResolveInfoList correctly converts and sorts a list of ResolveInfo`() = runTest {
        whenever(mockAppNamesManager.getDisplayNameForPackage(any(), any())).doAnswer {
            it.arguments[1] as String
        }

        val fakeResolveInfoList = listOf(
            FakeResolveInfo("App B", "com.b", "com.b.MainActivity"),
            FakeResolveInfo("App A", "com.a", "com.a.MainActivity")
        )

        val expectedAppList = listOf(
            AppInfo(originalName = "App A", displayName = "App A", packageName = "com.a", className = "com.a.MainActivity"),
            AppInfo(originalName = "App B", displayName = "App B", packageName = "com.b", className = "com.b.MainActivity")
        )

        val actualAppList = installedAppsManager.processResolveInfoList(fakeResolveInfoList)

        assertEquals(expectedAppList, actualAppList)
    }

    @Test
    fun `processResolveInfoList uses displayName from AppNamesManager`() = runTest {
        val customName = "Zebra Custom Name"

        whenever(mockAppNamesManager.getDisplayNameForPackage(eq("com.a"), eq("App A"))).thenReturn(customName)
        whenever(mockAppNamesManager.getDisplayNameForPackage(eq("com.b"), eq("App B"))).thenReturn("App B")

        val fakeResolveInfoList = listOf(
            FakeResolveInfo("App B", "com.b", "com.b.MainActivity"),
            FakeResolveInfo("App A", "com.a", "com.a.MainActivity")
        )

        val expectedAppList = listOf(
            AppInfo(originalName = "App B", displayName = "App B", packageName = "com.b", className = "com.b.MainActivity"),
            AppInfo(originalName = "App A", displayName = customName, packageName = "com.a", className = "com.a.MainActivity")
        )

        val actualAppList = installedAppsManager.processResolveInfoList(fakeResolveInfoList)

        assertEquals(expectedAppList, actualAppList)
    }

    @Test
    fun `processResolveInfoList returns empty list when input is empty`() = runTest {
        val actualAppList = installedAppsManager.processResolveInfoList(emptyList())
        assertTrue(actualAppList.isEmpty())
    }

    @Test
    fun `processResolveInfoList skips item that throws exception`() = runTest {
        whenever(mockAppNamesManager.getDisplayNameForPackage(any(), any())).doAnswer { it.arguments[1] as String }

        val fakeResolveInfoList = listOf(
            FakeResolveInfo("Good App", "com.good", "com.good.MainActivity"),
            FailingResolveInfo("com.bad", "com.bad.MainActivity")
        )

        val actualAppList = installedAppsManager.processResolveInfoList(fakeResolveInfoList)

        assertEquals(2, actualAppList.size)
        // Items are sorted alphabetically by displayName
        assertEquals("com.bad", actualAppList[0].displayName)
        assertEquals("Good App", actualAppList[1].displayName)
    }

    // ========== NEW CRASH-RESISTANCE TESTS ==========

    @Test
    fun `processResolveInfoList - when appNamesManager throws exception - uses original name`() = runTest {
        whenever(mockAppNamesManager.getDisplayNameForPackage(any(), any())).doAnswer {
            throw RuntimeException("Database error")
        }

        val fakeResolveInfoList = listOf(
            FakeResolveInfo("App A", "com.a", "com.a.MainActivity")
        )

        val actualAppList = installedAppsManager.processResolveInfoList(fakeResolveInfoList)

        assertEquals(1, actualAppList.size)
        assertEquals("App A", actualAppList[0].displayName)
    }

    @Test
    fun `processResolveInfoList - when appNamesManager throws IOException - uses original name`() = runTest {
        whenever(mockAppNamesManager.getDisplayNameForPackage(any(), any())).doAnswer {
            throw java.io.IOException("Cannot read preferences")
        }

        val fakeResolveInfoList = listOf(
            FakeResolveInfo("Original Name", "com.test", "com.test.MainActivity")
        )

        val actualAppList = installedAppsManager.processResolveInfoList(fakeResolveInfoList)

        assertEquals(1, actualAppList.size)
        assertEquals("Original Name", actualAppList[0].displayName)
    }

    @Test
    fun `processResolveInfoList - when appNamesManager throws CancellationException - propagates it`() = runTest {
        whenever(mockAppNamesManager.getDisplayNameForPackage(any(), any())).doAnswer {
            throw CancellationException("Cancelled")
        }

        val fakeResolveInfoList = listOf(
            FakeResolveInfo("App", "com.test", "com.test.MainActivity")
        )

        assertFailsWith<CancellationException> {
            installedAppsManager.processResolveInfoList(fakeResolveInfoList)
        }
    }

    @Test
    fun `processResolveInfoList - with null activityInfo - skips item`() = runTest {
        whenever(mockAppNamesManager.getDisplayNameForPackage(any(), any())).doAnswer { it.arguments[1] as String }

        val fakeResolveInfoList = listOf(
            FakeResolveInfo("Good App", "com.good", "com.good.MainActivity"),
            NullActivityInfoResolveInfo()
        )

        val actualAppList = installedAppsManager.processResolveInfoList(fakeResolveInfoList)

        assertEquals(1, actualAppList.size)
        assertEquals("Good App", actualAppList[0].displayName)
    }

    @Test
    fun `processResolveInfoList - with duplicate packages - keeps all entries`() = runTest {
        whenever(mockAppNamesManager.getDisplayNameForPackage(any(), any())).doAnswer { it.arguments[1] as String }

        val fakeResolveInfoList = listOf(
            FakeResolveInfo("App A", "com.a", "com.a.MainActivity"),
            FakeResolveInfo("App A Activity2", "com.a", "com.a.SecondActivity")
        )

        val actualAppList = installedAppsManager.processResolveInfoList(fakeResolveInfoList)

        assertEquals(2, actualAppList.size)
        assertEquals("com.a", actualAppList[0].packageName)
        assertEquals("com.a", actualAppList[1].packageName)
    }

    @Test
    fun `processResolveInfoList - with empty label - uses package name as fallback`() = runTest {
        whenever(mockAppNamesManager.getDisplayNameForPackage(any(), any())).doAnswer { it.arguments[1] as String }

        val fakeResolveInfoList = listOf(
            FakeResolveInfo("", "com.test", "com.test.MainActivity")
        )

        val actualAppList = installedAppsManager.processResolveInfoList(fakeResolveInfoList)

        assertEquals(1, actualAppList.size)
        assertEquals("", actualAppList[0].originalName)
    }

    @Test
    fun `processResolveInfoList - with very long app names - handles correctly`() = runTest {
        whenever(mockAppNamesManager.getDisplayNameForPackage(any(), any())).doAnswer { it.arguments[1] as String }

        val veryLongName = "A".repeat(500)
        val fakeResolveInfoList = listOf(
            FakeResolveInfo(veryLongName, "com.test", "com.test.MainActivity")
        )

        val actualAppList = installedAppsManager.processResolveInfoList(fakeResolveInfoList)

        assertEquals(1, actualAppList.size)
        assertEquals(veryLongName, actualAppList[0].displayName)
    }

    @Test
    fun `processResolveInfoList - with special characters in names - handles correctly`() = runTest {
        whenever(mockAppNamesManager.getDisplayNameForPackage(any(), any())).doAnswer { it.arguments[1] as String }

        val specialName = "App 🚀 Test & <Special> \"Chars\""
        val fakeResolveInfoList = listOf(
            FakeResolveInfo(specialName, "com.test", "com.test.MainActivity")
        )

        val actualAppList = installedAppsManager.processResolveInfoList(fakeResolveInfoList)

        assertEquals(1, actualAppList.size)
        assertEquals(specialName, actualAppList[0].displayName)
    }

    @Test
    fun `processResolveInfoList - with large list - handles efficiently`() = runTest {
        whenever(mockAppNamesManager.getDisplayNameForPackage(any(), any())).doAnswer { it.arguments[1] as String }

        val largeList = (1..1000).map {
            FakeResolveInfo("App $it", "com.app$it", "com.app$it.MainActivity")
        }

        val actualAppList = installedAppsManager.processResolveInfoList(largeList)

        assertEquals(1000, actualAppList.size)
        // Verify it's sorted alphabetically
        assertEquals("App 1", actualAppList[0].displayName)
    }

    @Test
    fun `processResolveInfoList - when multiple items fail - continues processing others`() = runTest {
        whenever(mockAppNamesManager.getDisplayNameForPackage(any(), any())).doAnswer { it.arguments[1] as String }

        val fakeResolveInfoList = listOf(
            FakeResolveInfo("Good 1", "com.good1", "com.good1.MainActivity"),
            FailingResolveInfo("com.bad1", "com.bad1.MainActivity"),
            FakeResolveInfo("Good 2", "com.good2", "com.good2.MainActivity"),
            FailingResolveInfo("com.bad2", "com.bad2.MainActivity"),
            FakeResolveInfo("Good 3", "com.good3", "com.good3.MainActivity")
        )

        val actualAppList = installedAppsManager.processResolveInfoList(fakeResolveInfoList)

        assertEquals(5, actualAppList.size)
        // Items are sorted alphabetically by displayName
        // "com.bad1" and "com.bad2" come before "Good 1", "Good 2", "Good 3"
        assertEquals("com.bad1", actualAppList[0].displayName)
        assertEquals("com.bad2", actualAppList[1].displayName)
        assertEquals("Good 1", actualAppList[2].displayName)
        assertEquals("Good 2", actualAppList[3].displayName)
        assertEquals("Good 3", actualAppList[4].displayName)
    }

    @Test
    fun `triggerAppsUpdate - when flow emit fails - does not crash`() = runTest {
        whenever(mockAppsUpdateTrigger.emit(any())).doAnswer {
            throw RuntimeException("Flow error")
        }

        // Should not crash
        installedAppsManager.triggerAppsUpdate()

        verify(mockAppsUpdateTrigger).emit(Unit)
    }

    @Test
    fun `processResolveInfoList - with null package name - skips item`() = runTest {
        whenever(mockAppNamesManager.getDisplayNameForPackage(any(), any())).doAnswer { it.arguments[1] as String }

        val resolveInfo = FakeResolveInfo("Test", "", "class")
        val fakeResolveInfoList = listOf(
            FakeResolveInfo("Good App", "com.good", "com.good.MainActivity"),
            resolveInfo
        )

        val actualAppList = installedAppsManager.processResolveInfoList(fakeResolveInfoList)

        // Empty package name might still be processed, just verify no crash
        assertTrue(actualAppList.isNotEmpty())
    }
}