package com.github.reygnn.kolibri_launcher.data

import android.app.AlarmManager
import android.app.PendingIntent
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
    private lateinit var context: Context
    @MockK
    private lateinit var settingsRepository: SettingsRepository
    @MockK
    private lateinit var alarmManager: AlarmManager
    @MockK
    private lateinit var contentResolver: ContentResolver

    private lateinit var manager: TimeBasedEventsRepositoryImpl

    @Before
    fun setup() {
        MockKAnnotations.init(this)

        // MockK ist standardmäßig lenient — kein Äquivalent zu Mockito.lenient() nötig.
        // Stubs die nicht in jedem Test aufgerufen werden, verursachen keinen Fehler.
        every { context.getSystemService(Context.ALARM_SERVICE) } returns alarmManager
        every { context.contentResolver } returns contentResolver
        every { context.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_GRANTED

        manager = TimeBasedEventsRepositoryImpl(context, settingsRepository)
    }

    // ========== ALARM TESTS ==========

    @Test
    fun `getUpcomingTimeBasedEvents - when alarm disabled - returns empty list`() = runTest {
        every { settingsRepository.showAlarmFlow } returns flowOf(false)
        every { settingsRepository.showCalendarEventFlow } returns flowOf(false)

        val result = manager.getUpcomingTimeBasedEvents(5)

        Assert.assertTrue(result.isEmpty())
        verify(exactly = 0) { alarmManager.nextAlarmClock }
    }

    @Test
    fun `getUpcomingTimeBasedEvents - with alarm enabled - returns alarm event`() = runTest {
        every { settingsRepository.showAlarmFlow } returns flowOf(true)
        every { settingsRepository.showCalendarEventFlow } returns flowOf(false)

        val triggerTime = System.currentTimeMillis() + 10000

        val alarmInfo = mockk<AlarmManager.AlarmClockInfo>()
        every { alarmInfo.triggerTime } returns triggerTime
        every { alarmInfo.showIntent } returns null
        every { alarmManager.nextAlarmClock } returns alarmInfo

        val result = manager.getUpcomingTimeBasedEvents(5)

        Assert.assertEquals(1, result.size)
        Assert.assertEquals(triggerTime, result[0].triggerTimeMillis)
        Assert.assertEquals(TimeBasedEventType.ALARM, result[0].type)
    }

    @Test
    fun `getUpcomingTimeBasedEvents - alarm from non-alarm OEM package (Samsung Calendar) - filtered out`() = runTest {
        // Samsung Calendar registers its midnight rollover via setAlarmClock(),
        // so getNextAlarmClock() returns a phantom "alarm" whose showIntent is
        // owned by com.samsung.android.calendar. It must not surface as an event.
        every { settingsRepository.showAlarmFlow } returns flowOf(true)
        every { settingsRepository.showCalendarEventFlow } returns flowOf(false)

        val showIntent = mockk<PendingIntent>()
        every { showIntent.creatorPackage } returns "com.samsung.android.calendar"

        val alarmInfo = mockk<AlarmManager.AlarmClockInfo>()
        every { alarmInfo.triggerTime } returns System.currentTimeMillis() + 10000
        every { alarmInfo.showIntent } returns showIntent
        every { alarmManager.nextAlarmClock } returns alarmInfo

        val result = manager.getUpcomingTimeBasedEvents(5)

        Assert.assertTrue(result.isEmpty())
    }

    @Test
    fun `getUpcomingTimeBasedEvents - alarm from real clock app - kept`() = runTest {
        // A genuine alarm-clock app's PendingIntent is not on the blocklist and
        // must still show.
        every { settingsRepository.showAlarmFlow } returns flowOf(true)
        every { settingsRepository.showCalendarEventFlow } returns flowOf(false)

        val triggerTime = System.currentTimeMillis() + 10000
        val showIntent = mockk<PendingIntent>()
        every { showIntent.creatorPackage } returns "com.google.android.deskclock"

        val alarmInfo = mockk<AlarmManager.AlarmClockInfo>()
        every { alarmInfo.triggerTime } returns triggerTime
        every { alarmInfo.showIntent } returns showIntent
        every { alarmManager.nextAlarmClock } returns alarmInfo

        val result = manager.getUpcomingTimeBasedEvents(5)

        Assert.assertEquals(1, result.size)
        Assert.assertEquals(triggerTime, result[0].triggerTimeMillis)
        Assert.assertEquals(TimeBasedEventType.ALARM, result[0].type)
    }

    @Test
    fun `getUpcomingTimeBasedEvents - alarm with null showIntent creatorPackage - kept (fail-open)`() = runTest {
        // An unidentifiable source (null showIntent or null creatorPackage) is
        // treated as a real alarm so a genuine alarm is never hidden.
        every { settingsRepository.showAlarmFlow } returns flowOf(true)
        every { settingsRepository.showCalendarEventFlow } returns flowOf(false)

        val triggerTime = System.currentTimeMillis() + 10000
        val showIntent = mockk<PendingIntent>()
        every { showIntent.creatorPackage } returns null

        val alarmInfo = mockk<AlarmManager.AlarmClockInfo>()
        every { alarmInfo.triggerTime } returns triggerTime
        every { alarmInfo.showIntent } returns showIntent
        every { alarmManager.nextAlarmClock } returns alarmInfo

        val result = manager.getUpcomingTimeBasedEvents(5)

        Assert.assertEquals(1, result.size)
        Assert.assertEquals(triggerTime, result[0].triggerTimeMillis)
    }

    @Test
    fun `getUpcomingTimeBasedEvents - alarm beyond 12h look-ahead window - filtered out`() = runTest {
        // getNextAlarmClock() returns the next alarm regardless of distance; an
        // alarm more than 12h away is not "upcoming" for the preview and must be
        // suppressed until it enters the window.
        every { settingsRepository.showAlarmFlow } returns flowOf(true)
        every { settingsRepository.showCalendarEventFlow } returns flowOf(false)

        val alarmInfo = mockk<AlarmManager.AlarmClockInfo>()
        every { alarmInfo.triggerTime } returns System.currentTimeMillis() + 13 * 60 * 60 * 1000L
        every { alarmInfo.showIntent } returns null
        every { alarmManager.nextAlarmClock } returns alarmInfo

        val result = manager.getUpcomingTimeBasedEvents(5)

        Assert.assertTrue(result.isEmpty())
    }

    @Test
    fun `getUpcomingTimeBasedEvents - alarm just inside 12h look-ahead window - kept`() = runTest {
        every { settingsRepository.showAlarmFlow } returns flowOf(true)
        every { settingsRepository.showCalendarEventFlow } returns flowOf(false)

        val triggerTime = System.currentTimeMillis() + 11 * 60 * 60 * 1000L
        val alarmInfo = mockk<AlarmManager.AlarmClockInfo>()
        every { alarmInfo.triggerTime } returns triggerTime
        every { alarmInfo.showIntent } returns null
        every { alarmManager.nextAlarmClock } returns alarmInfo

        val result = manager.getUpcomingTimeBasedEvents(5)

        Assert.assertEquals(1, result.size)
        Assert.assertEquals(triggerTime, result[0].triggerTimeMillis)
    }

    @Test
    fun `getUpcomingTimeBasedEvents - alarm enabled but none set - returns empty`() = runTest {
        every { settingsRepository.showAlarmFlow } returns flowOf(true)
        every { settingsRepository.showCalendarEventFlow } returns flowOf(false)
        every { alarmManager.nextAlarmClock } returns null

        val result = manager.getUpcomingTimeBasedEvents(5)

        Assert.assertTrue(result.isEmpty())
    }

    @Test
    fun `getUpcomingTimeBasedEvents - alarm manager throws exception - handles gracefully`() = runTest {
        every { settingsRepository.showAlarmFlow } returns flowOf(true)
        every { settingsRepository.showCalendarEventFlow } returns flowOf(false)
        every { alarmManager.nextAlarmClock } throws SecurityException("Not allowed")

        val result = manager.getUpcomingTimeBasedEvents(5)

        Assert.assertTrue(result.isEmpty())
    }

    // ========== SETTINGS ERROR HANDLING ==========

    @Test
    fun `getUpcomingTimeBasedEvents - settings flow throws - uses safe fallbacks`() = runTest {
        every { settingsRepository.showAlarmFlow } returns flow { throw IOException("Disk read error") }
        every { settingsRepository.showCalendarEventFlow } returns flow { throw RuntimeException("Error") }
        every { alarmManager.nextAlarmClock } returns null

        manager.getUpcomingTimeBasedEvents(5)

        // Fallback alarm=true → soll Alarm versuchen
        verify { alarmManager.nextAlarmClock }
        // Fallback calendar=false → soll contentResolver NICHT anfassen
        verify(exactly = 0) { context.contentResolver }
    }

    @Test
    fun `getUpcomingTimeBasedEvents - settings flow cancellation - propagates exception`() = runTest {
        every { settingsRepository.showAlarmFlow } returns flow { throw CancellationException("Cancelled") }

        assertFailsWith<CancellationException> {
            manager.getUpcomingTimeBasedEvents(5)
        }
    }

    // ========== CALENDAR TESTS ==========

    @Test
    fun `getUpcomingTimeBasedEvents - calendar permission denied - returns empty calendar events`() = runTest {
        every { settingsRepository.showAlarmFlow } returns flowOf(false)
        every { settingsRepository.showCalendarEventFlow } returns flowOf(true)
        every { context.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_DENIED

        val result = manager.getUpcomingTimeBasedEvents(5)

        Assert.assertTrue(result.isEmpty())
        verify(exactly = 0) { contentResolver.query(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `getUpcomingTimeBasedEvents - sorts merged events correctly`() = runTest {
        every { settingsRepository.showAlarmFlow } returns flowOf(true)
        every { settingsRepository.showCalendarEventFlow } returns flowOf(false)

        val later = System.currentTimeMillis() + 5000

        val alarmInfo = mockk<AlarmManager.AlarmClockInfo>()
        every { alarmInfo.triggerTime } returns later
        every { alarmInfo.showIntent } returns null
        every { alarmManager.nextAlarmClock } returns alarmInfo

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
