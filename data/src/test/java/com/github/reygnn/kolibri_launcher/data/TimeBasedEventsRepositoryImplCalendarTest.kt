package com.github.reygnn.kolibri_launcher.data

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEvent
import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEventType
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Robolectric tests for the calendar window logic of
 * [TimeBasedEventsRepositoryImpl.getUpcomingTimeBasedEvents].
 *
 * These live apart from the pure-JVM [TimeBasedEventsRepositoryImplTest]
 * because the calendar query builds a `content://` [android.net.Uri] via
 * [android.content.ContentUris] — not implemented in the plain JVM android.jar,
 * so Robolectric supplies the real Uri/ContentUris/CalendarContract classes
 * (same reason [WallpaperRepositoryImplTest] runs under Robolectric). The
 * [ContentResolver] and its [Cursor] are still MockK doubles; only the row
 * FILTER (today + tomorrow window, all-day inclusion, past-timed drop) is
 * under test, so the mocked cursor returns fixed rows regardless of the query
 * range and the production per-row filter does the work.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class TimeBasedEventsRepositoryImplCalendarTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK
    private lateinit var context: Context
    @MockK
    private lateinit var settingsRepository: SettingsRepository
    @MockK
    private lateinit var contentResolver: ContentResolver

    private lateinit var repository: TimeBasedEventsRepositoryImpl

    private val zone: ZoneId = ZoneId.systemDefault()
    private val today: LocalDate = LocalDate.now(zone)
    private val tomorrow: LocalDate = today.plusDays(1)
    private val now: Long = System.currentTimeMillis()

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        every { context.contentResolver } returns contentResolver
        // ContextCompat.checkSelfPermission routes through one of these two,
        // depending on the androidx.core version — stub both to be safe.
        every { context.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_GRANTED
        every { context.checkSelfPermission(any()) } returns PackageManager.PERMISSION_GRANTED
        // Alarm path is disabled in every test here; no ALARM_SERVICE stub needed.

        repository = TimeBasedEventsRepositoryImpl(context, settingsRepository)
    }

    private data class Row(val title: String, val begin: Long, val end: Long, val allDay: Boolean)

    /** An all-day instance BEGIN is stored as UTC midnight of the event's date. */
    private fun allDayBegin(date: LocalDate): Long =
        date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun cursorOf(rows: List<Row>): Cursor {
        val cursor = mockk<Cursor>(relaxed = true)
        var pos = -1
        every { cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE) } returns 0
        every { cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN) } returns 1
        every { cursor.getColumnIndexOrThrow(CalendarContract.Instances.END) } returns 2
        every { cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY) } returns 3
        every { cursor.moveToNext() } answers { pos++; pos < rows.size }
        every { cursor.getString(0) } answers { rows[pos].title }
        every { cursor.getLong(1) } answers { rows[pos].begin }
        every { cursor.getLong(2) } answers { rows[pos].end }
        every { cursor.getInt(3) } answers { if (rows[pos].allDay) 1 else 0 }
        return cursor
    }

    private suspend fun query(rows: List<Row>): List<TimeBasedEvent> {
        every { settingsRepository.showAlarmFlow } returns flowOf(false)
        every { settingsRepository.showCalendarEventFlow } returns flowOf(true)
        every {
            contentResolver.query(any(), any(), any(), any(), any())
        } returns cursorOf(rows)
        return repository.getUpcomingTimeBasedEvents(10)
    }

    @Test
    fun `timed event still upcoming today is kept`() = runTest {
        val begin = now + 2 * 60 * 60 * 1000L
        val result = query(listOf(Row("Standup", begin, begin + 1_800_000, allDay = false)))

        assertEquals(1, result.size)
        assertEquals("Standup", result[0].title)
        assertFalse(result[0].isAllDay)
        assertEquals(TimeBasedEventType.CALENDAR, result[0].type)
    }

    @Test
    fun `timed event that already ended today is dropped`() = runTest {
        // begin 3h ago, ended 2.5h ago → end < now.
        val begin = now - 3 * 60 * 60 * 1000L
        val result = query(listOf(Row("PastMeeting", begin, begin + 1_800_000, allDay = false)))

        assertTrue("past timed event must be dropped, was: $result", result.isEmpty())
    }

    @Test
    fun `timed event in progress is kept until its end time`() = runTest {
        // Started 35 min ago, ends in 25 min: begin < now but end > now. A late
        // joiner must still see it (the reason the filter is end > now, not
        // begin >= now).
        val begin = now - 35 * 60 * 1000L
        val end = now + 25 * 60 * 1000L
        val result = query(listOf(Row("Standup", begin, end, allDay = false)))

        assertEquals(1, result.size)
        assertEquals("Standup", result[0].title)
        assertEquals(begin, result[0].triggerTimeMillis)
    }

    @Test
    fun `timed event ending exactly now is dropped`() = runTest {
        // Boundary: end == now is NOT still running (end > now is strict).
        val end = now
        val result = query(listOf(Row("JustEnded", now - 60 * 60 * 1000L, end, allDay = false)))

        assertTrue("event ending exactly now must be dropped, was: $result", result.isEmpty())
    }

    @Test
    fun `timed event tomorrow is kept`() = runTest {
        val begin = now + 25 * 60 * 60 * 1000L
        val result = query(listOf(Row("TomorrowCall", begin, begin + 1_800_000, allDay = false)))

        assertEquals(1, result.size)
        assertEquals("TomorrowCall", result[0].title)
    }

    @Test
    fun `all-day event today is kept and flagged`() = runTest {
        val begin = allDayBegin(today)
        val result = query(listOf(Row("Holiday", begin, begin + 86_400_000, allDay = true)))

        assertEquals(1, result.size)
        assertEquals("Holiday", result[0].title)
        assertTrue("expected all-day flag", result[0].isAllDay)
    }

    @Test
    fun `all-day event tomorrow is kept and flagged`() = runTest {
        val begin = allDayBegin(tomorrow)
        val result = query(listOf(Row("Birthday", begin, begin + 86_400_000, allDay = true)))

        assertEquals(1, result.size)
        assertEquals("Birthday", result[0].title)
        assertTrue(result[0].isAllDay)
    }

    @Test
    fun `all-day event yesterday is dropped`() = runTest {
        val begin = allDayBegin(today.minusDays(1))
        val result = query(listOf(Row("PastAllDay", begin, begin + 86_400_000, allDay = true)))

        assertTrue("yesterday's all-day event must be dropped, was: $result", result.isEmpty())
    }

    @Test
    fun `all-day event day after tomorrow is dropped`() = runTest {
        val begin = allDayBegin(today.plusDays(2))
        val result = query(listOf(Row("FutureAllDay", begin, begin + 86_400_000, allDay = true)))

        assertTrue("out-of-window all-day event must be dropped, was: $result", result.isEmpty())
    }

    @Test
    fun `mixed rows keep only in-window events`() = runTest {
        val rows = listOf(
            Row("PastTimed", now - 3 * 60 * 60 * 1000L, now - 1_800_000, allDay = false),
            Row("AllDayToday", allDayBegin(today), allDayBegin(today) + 86_400_000, allDay = true),
            Row("UpcomingTimed", now + 2 * 60 * 60 * 1000L, now + 3 * 60 * 60 * 1000L, allDay = false),
            Row("AllDayYesterday", allDayBegin(today.minusDays(1)), allDayBegin(today), allDay = true),
            Row("AllDayTomorrow", allDayBegin(tomorrow), allDayBegin(tomorrow) + 86_400_000, allDay = true)
        )

        val titles = query(rows).map { it.title }.toSet()

        assertEquals(setOf("AllDayToday", "UpcomingTimed", "AllDayTomorrow"), titles)
    }
}
