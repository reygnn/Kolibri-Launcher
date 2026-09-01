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
import java.time.Instant
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
 *
 * REGRESSION NOTE: classification is by the provider's LOCAL Julian day
 * (START_DAY/END_DAY), never by re-reading BEGIN in a fixed zone. To prove the
 * fix, every all-day row is fed a deliberately WRONG BEGIN ([wrongUtcBegin]) —
 * the value seen on devices whose provider does not return all-day BEGIN at UTC
 * midnight. Under the old UTC-based date read this landed on the previous day
 * and dropped today's all-day event; the code must now ignore BEGIN for the day.
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

    // Must match TimeBasedEventsRepositoryImpl.JULIAN_DAY_EPOCH.
    private val julianEpoch = 2_440_588L

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

    private data class Row(
        val title: String,
        val begin: Long,
        val end: Long,
        val allDay: Boolean,
        val startDay: Int,
        val endDay: Int
    )

    /** CalendarContract Julian day number for a local calendar day. */
    private fun julian(date: LocalDate): Int = (date.toEpochDay() + julianEpoch).toInt()

    /** Local calendar day (as a Julian day) of a timed instant. */
    private fun localDay(millis: Long): Int =
        julian(Instant.ofEpochMilli(millis).atZone(zone).toLocalDate())

    /**
     * A deliberately WRONG all-day BEGIN: UTC midnight of the day BEFORE [date].
     * Reading this in UTC (the old bug) yields yesterday; the production code must
     * instead classify by the supplied START_DAY and ignore this value.
     */
    private fun wrongUtcBegin(date: LocalDate): Long =
        date.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun timedRow(title: String, begin: Long, end: Long) = Row(
        title = title, begin = begin, end = end, allDay = false,
        startDay = localDay(begin), endDay = localDay(end)
    )

    private fun allDayRow(title: String, date: LocalDate) = Row(
        title = title,
        begin = wrongUtcBegin(date),
        end = wrongUtcBegin(date) + 86_400_000,
        allDay = true,
        startDay = julian(date),
        endDay = julian(date)
    )

    /** The LOCAL-midnight trigger the repository must produce for an all-day day. */
    private fun localMidnight(date: LocalDate): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun cursorOf(rows: List<Row>): Cursor {
        val cursor = mockk<Cursor>(relaxed = true)
        var pos = -1
        every { cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE) } returns 0
        every { cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN) } returns 1
        every { cursor.getColumnIndexOrThrow(CalendarContract.Instances.END) } returns 2
        every { cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY) } returns 3
        every { cursor.getColumnIndexOrThrow(CalendarContract.Instances.START_DAY) } returns 4
        every { cursor.getColumnIndexOrThrow(CalendarContract.Instances.END_DAY) } returns 5
        every { cursor.moveToNext() } answers { pos++; pos < rows.size }
        every { cursor.getString(0) } answers { rows[pos].title }
        every { cursor.getLong(1) } answers { rows[pos].begin }
        every { cursor.getLong(2) } answers { rows[pos].end }
        every { cursor.getInt(3) } answers { if (rows[pos].allDay) 1 else 0 }
        every { cursor.getInt(4) } answers { rows[pos].startDay }
        every { cursor.getInt(5) } answers { rows[pos].endDay }
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
        val result = query(listOf(timedRow("Standup", begin, begin + 1_800_000)))

        assertEquals(1, result.size)
        assertEquals("Standup", result[0].title)
        assertFalse(result[0].isAllDay)
        assertEquals(TimeBasedEventType.CALENDAR, result[0].type)
    }

    @Test
    fun `timed event that already ended today is dropped`() = runTest {
        // begin 3h ago, ended 2.5h ago -> end < now.
        val begin = now - 3 * 60 * 60 * 1000L
        val result = query(listOf(timedRow("PastMeeting", begin, begin + 1_800_000)))

        assertTrue("past timed event must be dropped, was: $result", result.isEmpty())
    }

    @Test
    fun `timed event in progress is kept until its end time`() = runTest {
        // Started 35 min ago, ends in 25 min: begin < now but end > now. A late
        // joiner must still see it (the reason the filter is end > now, not
        // begin >= now).
        val begin = now - 35 * 60 * 1000L
        val end = now + 25 * 60 * 1000L
        val result = query(listOf(timedRow("Standup", begin, end)))

        assertEquals(1, result.size)
        assertEquals("Standup", result[0].title)
        assertEquals(begin, result[0].triggerTimeMillis)
    }

    @Test
    fun `timed event ending exactly now is dropped`() = runTest {
        // Boundary: end == now is NOT still running (end > now is strict).
        val end = now
        val result = query(listOf(timedRow("JustEnded", now - 60 * 60 * 1000L, end)))

        assertTrue("event ending exactly now must be dropped, was: $result", result.isEmpty())
    }

    @Test
    fun `timed event tomorrow is kept`() = runTest {
        val begin = now + 25 * 60 * 60 * 1000L
        val result = query(listOf(timedRow("TomorrowCall", begin, begin + 1_800_000)))

        assertEquals(1, result.size)
        assertEquals("TomorrowCall", result[0].title)
    }

    @Test
    fun `all-day event today is kept, flagged and normalised to local midnight`() = runTest {
        // begin is a WRONG UTC value (yesterday); START_DAY says today. The old
        // UTC read dropped this; the fix keeps it and normalises the trigger.
        val result = query(listOf(allDayRow("Holiday", today)))

        assertEquals(1, result.size)
        assertEquals("Holiday", result[0].title)
        assertTrue("expected all-day flag", result[0].isAllDay)
        assertEquals(
            "all-day trigger must be local midnight of today",
            localMidnight(today), result[0].triggerTimeMillis
        )
    }

    @Test
    fun `all-day event tomorrow is kept, flagged and normalised to local midnight`() = runTest {
        val result = query(listOf(allDayRow("Birthday", tomorrow)))

        assertEquals(1, result.size)
        assertEquals("Birthday", result[0].title)
        assertTrue(result[0].isAllDay)
        assertEquals(localMidnight(tomorrow), result[0].triggerTimeMillis)
    }

    @Test
    fun `yearly birthday - today's expanded instance is kept and shown at local midnight`() = runTest {
        // A birthday is an all-day event with a yearly recurrence. The Instances
        // provider expands the RRULE, so we receive ONE instance for the current
        // year on the birthday's month/day; this models that instance landing on
        // today. The recurrence itself is the provider's job (see CONTENT_BY_DAY_URI)
        // — this asserts only our per-row handling of the resulting instance.
        val originalEvent = today.minusYears(30) // event created decades ago
        // Deliberately adversarial BEGIN: the ORIGINAL year, not this year's
        // instance. A real provider would put this year's date in BEGIN; feeding the
        // original year proves classification uses START_DAY only and never BEGIN.
        val begin = originalEvent.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val row = Row(
            title = "Alex's Birthday",
            begin = begin,
            end = begin + 86_400_000,
            allDay = true,
            startDay = julian(today), // this year's expanded instance is today
            endDay = julian(today)
        )

        val result = query(listOf(row))

        assertEquals(1, result.size)
        assertEquals("Alex's Birthday", result[0].title)
        assertTrue("a birthday is an all-day event", result[0].isAllDay)
        assertEquals(
            "the expanded instance must show today, normalised to local midnight",
            localMidnight(today), result[0].triggerTimeMillis
        )
    }

    @Test
    fun `all-day event yesterday is dropped`() = runTest {
        val result = query(listOf(allDayRow("PastAllDay", today.minusDays(1))))

        assertTrue("yesterday's all-day event must be dropped, was: $result", result.isEmpty())
    }

    @Test
    fun `all-day event day after tomorrow is dropped`() = runTest {
        val result = query(listOf(allDayRow("FutureAllDay", today.plusDays(2))))

        assertTrue("out-of-window all-day event must be dropped, was: $result", result.isEmpty())
    }

    @Test
    fun `multi-day all-day span that began before today is anchored to today`() = runTest {
        // A 3-day span [yesterday, tomorrow]. It intersects the window, so it is
        // kept and anchored to today's local midnight (not its earlier start).
        val row = Row(
            title = "Vacation",
            begin = wrongUtcBegin(today.minusDays(1)),
            end = wrongUtcBegin(today.minusDays(1)) + 3 * 86_400_000L,
            allDay = true,
            startDay = julian(today.minusDays(1)),
            endDay = julian(tomorrow)
        )
        val result = query(listOf(row))

        assertEquals(1, result.size)
        assertTrue(result[0].isAllDay)
        assertEquals(localMidnight(today), result[0].triggerTimeMillis)
    }

    @Test
    fun `mixed rows keep only in-window events`() = runTest {
        val rows = listOf(
            timedRow("PastTimed", now - 3 * 60 * 60 * 1000L, now - 1_800_000),
            allDayRow("AllDayToday", today),
            timedRow("UpcomingTimed", now + 2 * 60 * 60 * 1000L, now + 3 * 60 * 60 * 1000L),
            allDayRow("AllDayYesterday", today.minusDays(1)),
            allDayRow("AllDayTomorrow", tomorrow)
        )

        val titles = query(rows).map { it.title }.toSet()

        assertEquals(setOf("AllDayToday", "UpcomingTimed", "AllDayTomorrow"), titles)
    }
}
