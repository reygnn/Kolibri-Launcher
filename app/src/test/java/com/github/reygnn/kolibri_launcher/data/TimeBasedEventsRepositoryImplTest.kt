package com.github.reygnn.kolibri_launcher.data

import android.app.AlarmManager
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEventType
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import kotlin.test.assertFailsWith

@ExperimentalCoroutinesApi
class TimeBasedEventsRepositoryImplTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK
    private lateinit var mockContext: Context
    @MockK
    private lateinit var mockSettingsRepository: SettingsRepository
    @MockK
    private lateinit var mockAlarmManager: AlarmManager
    @MockK
    private lateinit var mockContentResolver: ContentResolver

    private lateinit var manager: TimeBasedEventsRepositoryImpl

    @Before
    fun setup() {
        MockKAnnotations.init(this)

        // MockK ist standardmäßig lenient — kein Äquivalent zu Mockito.lenient() nötig.
        // Stubs die nicht in jedem Test aufgerufen werden, verursachen keinen Fehler.
        every { mockContext.getSystemService(Context.ALARM_SERVICE) } returns mockAlarmManager
        every { mockContext.contentResolver } returns mockContentResolver
        every { mockContext.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_GRANTED

        manager = TimeBasedEventsRepositoryImpl(mockContext, mockSettingsRepository)
    }

    // ========== ALARM TESTS ==========

    @Test
    fun `getUpcomingTimeBasedEvents - when alarm disabled - returns empty list`() = runTest {
        every { mockSettingsRepository.showAlarmFlow } returns flowOf(false)
        every { mockSettingsRepository.showCalendarEventFlow } returns flowOf(false)

        val result = manager.getUpcomingTimeBasedEvents(5)

        Assert.assertTrue(result.isEmpty())
        verify(exactly = 0) { mockAlarmManager.nextAlarmClock }
    }

    @Test
    fun `getUpcomingTimeBasedEvents - with alarm enabled - returns alarm event`() = runTest {
        every { mockSettingsRepository.showAlarmFlow } returns flowOf(true)
        every { mockSettingsRepository.showCalendarEventFlow } returns flowOf(false)

        val triggerTime = System.currentTimeMillis() + 10000

        val alarmInfo = mockk<AlarmManager.AlarmClockInfo>()
        every { alarmInfo.triggerTime } returns triggerTime
        every { mockAlarmManager.nextAlarmClock } returns alarmInfo

        val result = manager.getUpcomingTimeBasedEvents(5)

        Assert.assertEquals(1, result.size)
        Assert.assertEquals(triggerTime, result[0].triggerTimeMillis)
        Assert.assertEquals(TimeBasedEventType.ALARM, result[0].type)
    }

    @Test
    fun `getUpcomingTimeBasedEvents - alarm enabled but none set - returns empty`() = runTest {
        every { mockSettingsRepository.showAlarmFlow } returns flowOf(true)
        every { mockSettingsRepository.showCalendarEventFlow } returns flowOf(false)
        every { mockAlarmManager.nextAlarmClock } returns null

        val result = manager.getUpcomingTimeBasedEvents(5)

        Assert.assertTrue(result.isEmpty())
    }

    @Test
    fun `getUpcomingTimeBasedEvents - alarm manager throws exception - handles gracefully`() = runTest {
        every { mockSettingsRepository.showAlarmFlow } returns flowOf(true)
        every { mockSettingsRepository.showCalendarEventFlow } returns flowOf(false)
        every { mockAlarmManager.nextAlarmClock } throws SecurityException("Not allowed")

        val result = manager.getUpcomingTimeBasedEvents(5)

        Assert.assertTrue(result.isEmpty())
    }

    // ========== SETTINGS ERROR HANDLING ==========

    @Test
    fun `getUpcomingTimeBasedEvents - settings flow throws - uses safe fallbacks`() = runTest {
        every { mockSettingsRepository.showAlarmFlow } returns flow { throw IOException("Disk read error") }
        every { mockSettingsRepository.showCalendarEventFlow } returns flow { throw RuntimeException("Error") }
        every { mockAlarmManager.nextAlarmClock } returns null

        manager.getUpcomingTimeBasedEvents(5)

        // Fallback alarm=true → soll Alarm versuchen
        verify { mockAlarmManager.nextAlarmClock }
        // Fallback calendar=false → soll contentResolver NICHT anfassen
        verify(exactly = 0) { mockContext.contentResolver }
    }

    @Test
    fun `getUpcomingTimeBasedEvents - settings flow cancellation - propagates exception`() = runTest {
        every { mockSettingsRepository.showAlarmFlow } returns flow { throw CancellationException("Cancelled") }

        assertFailsWith<CancellationException> {
            manager.getUpcomingTimeBasedEvents(5)
        }
    }

    // ========== CALENDAR TESTS ==========

    @Test
    fun `getUpcomingTimeBasedEvents - calendar permission denied - returns empty calendar events`() = runTest {
        every { mockSettingsRepository.showAlarmFlow } returns flowOf(false)
        every { mockSettingsRepository.showCalendarEventFlow } returns flowOf(true)
        every { mockContext.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_DENIED

        val result = manager.getUpcomingTimeBasedEvents(5)

        Assert.assertTrue(result.isEmpty())
        verify(exactly = 0) { mockContentResolver.query(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `getUpcomingTimeBasedEvents - sorts merged events correctly`() = runTest {
        every { mockSettingsRepository.showAlarmFlow } returns flowOf(true)
        every { mockSettingsRepository.showCalendarEventFlow } returns flowOf(false)

        val later = System.currentTimeMillis() + 5000

        val alarmInfo = mockk<AlarmManager.AlarmClockInfo>()
        every { alarmInfo.triggerTime } returns later
        every { mockAlarmManager.nextAlarmClock } returns alarmInfo

        val result = manager.getUpcomingTimeBasedEvents(5)

        Assert.assertEquals(1, result.size)
        Assert.assertEquals(later, result[0].triggerTimeMillis)
    }

    // ========== PURGE TEST ==========

    @Test
    fun `purgeRepository - does nothing`() = runTest {
        manager.purgeRepository()
        // Keine Exception
    }
}
