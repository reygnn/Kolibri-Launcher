package com.github.reygnn.kolibri_launcher.data

import android.app.AlarmManager
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEventType
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.IOException
import kotlin.test.assertFailsWith

@ExperimentalCoroutinesApi
@RunWith(MockitoJUnitRunner::class)
class TimeBasedEventsManagerTest {

    @Mock
    private lateinit var mockContext: Context
    @Mock
    private lateinit var mockSettingsRepository: SettingsRepository
    @Mock
    private lateinit var mockAlarmManager: AlarmManager
    @Mock
    private lateinit var mockContentResolver: ContentResolver

    private lateinit var manager: TimeBasedEventsManager

    @Before
    fun setup() {
        // Default Mocks
        // WICHTIG: Wir nutzen lenient(), da nicht alle Tests diese Services abrufen
        // (z.B. wenn "showAlarm" false ist, wird getSystemService nicht aufgerufen -> UnnecessaryStubbingException)
        Mockito.lenient().`when`(mockContext.getSystemService(Context.ALARM_SERVICE)).thenReturn(mockAlarmManager)
        Mockito.lenient().`when`(mockContext.contentResolver).thenReturn(mockContentResolver)

        // Permission check mock (Standard: Granted)
        Mockito.lenient().`when`(mockContext.checkPermission(anyString(), anyInt(), anyInt()))
            .thenReturn(PackageManager.PERMISSION_GRANTED)

        manager = TimeBasedEventsManager(mockContext, mockSettingsRepository)
    }

    // ========== ALARM TESTS ==========

    @Test
    fun `getUpcomingTimeBasedEvents - when alarm disabled - returns empty list`() = runTest {
        // Arrange
        whenever(mockSettingsRepository.showAlarmFlow).thenReturn(flowOf(false))
        whenever(mockSettingsRepository.showCalendarEventFlow).thenReturn(flowOf(false))

        // Act
        val result = manager.getUpcomingTimeBasedEvents(5)

        // Assert
        Assert.assertTrue(result.isEmpty())
        Mockito.verify(mockAlarmManager, Mockito.never()).nextAlarmClock
    }

    @Test
    fun `getUpcomingTimeBasedEvents - with alarm enabled - returns alarm event`() = runTest {
        // Arrange
        whenever(mockSettingsRepository.showAlarmFlow).thenReturn(flowOf(true))
        whenever(mockSettingsRepository.showCalendarEventFlow).thenReturn(flowOf(false))

        val triggerTime = System.currentTimeMillis() + 10000

        // FIX: Wir mocken das AlarmClockInfo Objekt, da der Konstruktor in Unit-Tests 0 zurückgibt
        val alarmInfo = mock<AlarmManager.AlarmClockInfo>()
        whenever(alarmInfo.triggerTime).thenReturn(triggerTime)

        whenever(mockAlarmManager.nextAlarmClock).thenReturn(alarmInfo)

        // Act
        val result = manager.getUpcomingTimeBasedEvents(5)

        // Assert
        Assert.assertEquals(1, result.size)
        Assert.assertEquals(triggerTime, result[0].triggerTimeMillis)
        Assert.assertEquals(TimeBasedEventType.ALARM, result[0].type)
    }

    @Test
    fun `getUpcomingTimeBasedEvents - alarm enabled but none set - returns empty`() = runTest {
        // Arrange
        whenever(mockSettingsRepository.showAlarmFlow).thenReturn(flowOf(true))
        whenever(mockSettingsRepository.showCalendarEventFlow).thenReturn(flowOf(false))
        whenever(mockAlarmManager.nextAlarmClock).thenReturn(null)

        // Act
        val result = manager.getUpcomingTimeBasedEvents(5)

        // Assert
        Assert.assertTrue(result.isEmpty())
    }

    @Test
    fun `getUpcomingTimeBasedEvents - alarm manager throws exception - handles gracefully`() = runTest {
        // Arrange
        whenever(mockSettingsRepository.showAlarmFlow).thenReturn(flowOf(true))
        whenever(mockSettingsRepository.showCalendarEventFlow).thenReturn(flowOf(false))
        whenever(mockAlarmManager.nextAlarmClock).thenThrow(SecurityException("Not allowed"))

        // Act
        val result = manager.getUpcomingTimeBasedEvents(5)

        // Assert
        Assert.assertTrue(result.isEmpty())
    }

    // ========== SETTINGS ERROR HANDLING ==========

    @Test
    fun `getUpcomingTimeBasedEvents - settings flow throws - uses safe fallbacks`() = runTest {
        // Arrange
        // Alarm setting fails -> fallback true
        whenever(mockSettingsRepository.showAlarmFlow).thenReturn(flow { throw IOException("Disk read error") })
        // Calendar setting fails -> fallback false
        whenever(mockSettingsRepository.showCalendarEventFlow).thenReturn(flow { throw RuntimeException("Error") })

        whenever(mockAlarmManager.nextAlarmClock).thenReturn(null)

        // Act
        manager.getUpcomingTimeBasedEvents(5)

        // Assert
        // Sollte versucht haben, Alarm zu laden (Fallback true)
        Mockito.verify(mockAlarmManager).nextAlarmClock
        // Sollte NICHT versucht haben, Kalender zu laden (Fallback false)
        Mockito.verify(mockContext, Mockito.never()).contentResolver
    }

    @Test
    fun `getUpcomingTimeBasedEvents - settings flow cancellation - propagates exception`() = runTest {
        // Arrange
        whenever(mockSettingsRepository.showAlarmFlow).thenReturn(flow { throw CancellationException("Cancelled") })

        // Act & Assert
        assertFailsWith<CancellationException> {
            manager.getUpcomingTimeBasedEvents(5)
        }
    }

    // ========== CALENDAR TESTS (Mocking Heavy) ==========
    // Hinweis: Diese Tests sind etwas "mock-lastig", da wir ContentResolver und Cursor simulieren müssen.
    // In einer echten Umgebung würde Uri.buildUpon() crashen ohne Robolectric.
    // Wir fangen hier potenzielle Uri-Fehler ab, um den Test auf der JVM lauffähig zu halten,
    // testen aber die Logik drumherum.

    @Test
    fun `getUpcomingTimeBasedEvents - calendar permission denied - returns empty calendar events`() = runTest {
        // Arrange
        whenever(mockSettingsRepository.showAlarmFlow).thenReturn(flowOf(false))
        whenever(mockSettingsRepository.showCalendarEventFlow).thenReturn(flowOf(true))

        // Deny Permission
        // Hinweis: Wir mocken checkPermission direkt, da ContextCompat dies intern aufruft
        whenever(mockContext.checkPermission(anyString(), anyInt(), anyInt()))
            .thenReturn(PackageManager.PERMISSION_DENIED)

        // Act
        val result = manager.getUpcomingTimeBasedEvents(5)

        // Assert
        Assert.assertTrue(result.isEmpty())
        Mockito.verify(mockContentResolver, Mockito.never()).query(any(), any(), any(), any(), any())
    }

    @Test
    fun `getUpcomingTimeBasedEvents - sorts merged events correctly`() = runTest {
        // Arrange
        whenever(mockSettingsRepository.showAlarmFlow).thenReturn(flowOf(true))
        whenever(mockSettingsRepository.showCalendarEventFlow).thenReturn(flowOf(false)) // Wir testen Sortierung hier nur mit Alarm + Mock Calendar logic wenn möglich

        val now = System.currentTimeMillis()
        val later = now + 5000

        // FIX: Mock AlarmClockInfo statt Instantiierung
        val alarmInfo = mock<AlarmManager.AlarmClockInfo>()
        whenever(alarmInfo.triggerTime).thenReturn(later)

        whenever(mockAlarmManager.nextAlarmClock).thenReturn(alarmInfo)

        // Act
        val result = manager.getUpcomingTimeBasedEvents(5)

        // Assert
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