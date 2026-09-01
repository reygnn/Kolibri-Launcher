# Known OEM / Framework Quirks (worked around)

This document tracks **OEM- or framework-specific behaviours** that Kolibri
**actively works around in app code**. They are not our bugs, but they surface
as user-visible wrongness on some devices, and the workaround needs a home for
its rationale so future-us doesn't "clean it up" without understanding why it
exists.

This is one of a family of "deliberately-not-obvious" docs, each a different
axis:

- **`KNOWN_ISSUES.md`** — StrictMode violations (framework/OEM/library, or
  intentional app-side). We *tolerate or mitigate* these.
- **`ACCEPTED_LIMITATIONS.md`** — intentional UX/behavioural limitations that
  are consequences of an architectural decision. We *do not fix* these.
- **`KNOWN_QUIRKS.md`** (this file) — OEM/framework behaviours we *do work
  around* with real code. Each entry pins the quirk + why the workaround is
  shaped the way it is.
- **`KNOWN_OS_GLITCHES.md`** — rare platform glitches with *no workaround* and
  no code change: transient OS/framework races we neither caused nor can
  address, recorded only for recognition.

The distinction from `ACCEPTED_LIMITATIONS.md` is the verb: a limitation is
priced-in and left alone; a quirk here has a live workaround whose logic must
not be reverted by someone who only sees "odd-looking filter code". The
distinction from `KNOWN_OS_GLITCHES.md` is also the verb: a quirk has code, a
glitch has none.

---

## 1. Samsung Calendar phantom "alarm" at 00:00 via `setAlarmClock()`

- **Status:** 🟢 Worked around (`TimeBasedEventsRepositoryImpl`)
- **Context:** Home-screen alarm event indicator + the double-tap upcoming-events
  dialog
- **Affected Devices:** Samsung (any device with Samsung Calendar,
  `com.samsung.android.calendar`, installed — i.e. effectively all of them)
- **Not affected:** Pixel / AOSP devices without Samsung Calendar (the original
  report: the A17 showed a phantom alarm, the Pixel 9a did not)

### The quirk

`AlarmManager.setAlarmClock()` is the API reserved for **user-visible** alarms:
an entry set through it is what `AlarmManager.getNextAlarmClock()` returns, and
on stock Android it lights the status-bar alarm icon.

Samsung Calendar misuses it. It registers its daily **midnight date-change
rollover** — `com.samsung.android.calendar.ACTION_MIDNIGHT_DATE_CHANGED_FOR_NOTIFICATION`
— via `setAlarmClock()`, scheduled at the next local midnight. So on a Samsung
device `getNextAlarmClock()` returns a non-null `AlarmClockInfo` with
`triggerTime` = tomorrow 00:00:00, **even when the user has set no alarm at all**.

Samsung's own SystemUI recognises this and suppresses the status-bar icon for
its own calendar. A third-party launcher reading the public
`getNextAlarmClock()` has no such privileged knowledge — it only sees a
legitimate-looking future alarm.

### Evidence (`dumpsys alarm` on an SM-A176B, A17)

```
Next alarm clock information:
  user:0 pendingSend:false time:1787695200000 = 2026-08-26 00:00:00.000

RTC_WAKEUP #26: Alarm{... com.samsung.android.calendar}
  tag=*walarm*:com.samsung.android.calendar.ACTION_MIDNIGHT_DATE_CHANGED_FOR_NOTIFICATION
  Alarm clock:
    triggerTime=2026-08-26 00:00:00.000
    showIntent=PendingIntent{... com.samsung.android.calendar ...}
```

Note the `triggerTime` is a genuine **future** midnight (tomorrow), **not** a
stale/epoch-zero value. A "reject past/zero trigger time" filter would therefore
*not* catch this — the only discriminator is the source package.

### The workaround

`TimeBasedEventsRepositoryImpl.getNextAlarm()` reads the `AlarmClockInfo`'s
`showIntent` and inspects its `PendingIntent.getCreatorPackage()`. If the creator
is on a small blocklist of known non-alarm sources
(`NON_ALARM_CLOCK_PACKAGES` — `com.samsung.android.calendar` and
`com.android.providers.calendar`, see the second source below), the
alarm is dropped and no indicator is shown.

**Blocklist, fail-open, by design.** An unrecognised or `null` creator package is
treated as a *real* alarm and shown. A genuine user alarm is never hidden — a
phantom is a nuisance, a missed alarm is not. The trade-off is that a new OEM
offender surfaces as a phantom until its package is added to the blocklist; the
inverse (an allowlist of "known alarm apps") risked hiding a real alarm from an
unusual clock app and was rejected.

The `PendingIntent` creator package is the only signal that generalises across
OEMs — the `showIntent`'s *action* is opaque to a foreign receiver, and the
trigger time is a legitimate future value.

### Second source: calendar event reminders (`com.android.providers.calendar`)

The same blocklist mechanism catches a distinct, non-Samsung offender found on
the A17 while testing the calendar preview: the **calendar provider** schedules
each event's REMINDER via `setAlarmClock()`.

```
Next alarm clock information:
  user:0 time:1788090600000 = 2026-08-30 13:50:00.000

RTC_WAKEUP #46: Alarm{... com.android.providers.calendar}
  tag=*walarm*:android.intent.action.EVENT_REMINDER
    triggerTime=2026-08-30 13:50:00.000
    showIntent=PendingIntent{... com.android.providers.calendar ...}
--
    triggerTime=2026-08-30 17:00:00.000
    showIntent=PendingIntent{... com.sec.android.app.clockpackage ...}
```

Because `getNextAlarmClock()` returns only the **single chronologically-next**
entry, the 13:50 event reminder is returned instead of the user's real 17:00
Samsung Clock alarm — so the launcher displayed the alarm as **13:50** (and, for
another event, **09:50**). Blocklisting `com.android.providers.calendar` drops
the reminder; the event it belongs to still shows via the separate calendar path,
so nothing is lost.

**Trade-off specific to this source.** Unlike the Samsung midnight phantom (which
is only "next" briefly around 00:00), an event reminder can be the next alarm
clock for hours. While it is, `getNextAlarmClock()` never exposes the real alarm
behind it, so the alarm indicator shows **nothing** until the reminder time
passes — then the real alarm surfaces. This is the fail-open contract in action:
better to show nothing than the wrong time. There is no public API to enumerate
past the single next entry.

### Extending

Add further confirmed non-alarm sources to `NON_ALARM_CLOCK_PACKAGES` in
`TimeBasedEventsRepositoryImpl`. Confirm a candidate the same way these were
found: `adb shell dumpsys alarm | grep -A8 "Next alarm clock"` on the affected
device, and read the `showIntent` package on the `Alarm clock:` entry whose
`triggerTime` matches the reported phantom.

## 2. All-day calendar `BEGIN` is not reliably UTC midnight

### The quirk

`CalendarContract` documents an all-day event's `DTSTART`/`Instances.BEGIN` as
UTC midnight of the event's date. In practice this does not hold on every
device / sync-adapter combination: on some (observed on a Pixel 9a) the all-day
`Instances.BEGIN` comes back at, or near, *local* midnight instead.

### The symptom

Deriving the event's calendar day by reading `BEGIN` in UTC then breaks in a
UTC+ zone: local midnight read in UTC lands on the *previous* day. A today
all-day event is misclassified as "yesterday" and dropped, while timed events
(filtered by `end > now`, no zone assumption) show normally. Reported as:
"an 11:00–12:00 event shows, but today's all-day event does not."

### The workaround

Never derive the day from `BEGIN` + a hard-coded zone. Query
`Instances.CONTENT_BY_DAY_URI` with Julian-day bounds and classify each row by
the provider's `START_DAY` / `END_DAY` — the instance's LOCAL calendar day as a
Julian day number, computed by the provider in the device timezone. All-day
triggers are then normalised to local midnight of their in-window day so the
timestamp is self-consistent for sorting and for the dialog's day-grouping.
See `getCalendarEvents` in `TimeBasedEventsRepositoryImpl` and
`TimeEventFormatter.buildEventRows`.

### Testing

`TimeBasedEventsRepositoryImplCalendarTest` feeds every all-day row a
deliberately wrong `BEGIN` (UTC midnight of the day before) while `START_DAY`
carries the correct local day, so the test fails if the code ever reads `BEGIN`
for the day again. The old test helper (`allDayBegin` = `atStartOfDay(UTC)`,
read back in UTC) was circular and could not catch this.
