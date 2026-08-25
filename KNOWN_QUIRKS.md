# Known OEM / Framework Quirks (worked around)

This document tracks **OEM- or framework-specific behaviours** that Kolibri
**actively works around in app code**. They are not our bugs, but they surface
as user-visible wrongness on some devices, and the workaround needs a home for
its rationale so future-us doesn't "clean it up" without understanding why it
exists.

This is the third sibling in a family of "deliberately-not-obvious" docs, each
a different axis:

- **`KNOWN_ISSUES.md`** — StrictMode violations (framework/OEM/library, or
  intentional app-side). We *tolerate or mitigate* these.
- **`ACCEPTED_LIMITATIONS.md`** — intentional UX/behavioural limitations that
  are consequences of an architectural decision. We *do not fix* these.
- **`KNOWN_QUIRKS.md`** (this file) — OEM/framework behaviours we *do work
  around* with real code. Each entry pins the quirk + why the workaround is
  shaped the way it is.

The distinction from `ACCEPTED_LIMITATIONS.md` is the verb: a limitation is
priced-in and left alone; a quirk here has a live workaround whose logic must
not be reverted by someone who only sees "odd-looking filter code".

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
(`NON_ALARM_CLOCK_PACKAGES`, currently just `com.samsung.android.calendar`), the
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

### Extending

Add further confirmed non-alarm sources to `NON_ALARM_CLOCK_PACKAGES` in
`TimeBasedEventsRepositoryImpl`. Confirm a candidate the same way this one was
found: `adb shell dumpsys alarm | grep -A8 "Next alarm clock"` on the affected
device, and read the `showIntent` package on the `Alarm clock:` entry whose
`triggerTime` matches the reported phantom.
