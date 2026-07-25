# HISTORY

A short narrative of how Kolibri Launcher came to be what it is today.
Not a changelog — for that, the git log is authoritative. This file
answers the *why*: which decisions were set on day one, which were
reactions to specific incidents, and which features still don't exist
because of them.

---

## Day 1 — A static layout from Claude 3.7

The project began around August 2025 with a single prompt: write me the
layout of a minimalist launcher. The model at the time was Claude 3.7,
and what came back was exactly what was asked for — one MainActivity,
one HomeFragment, a single screen showing the clock, the date, the
battery indicator, and a handful of fake apps. Fully static. No
functionality.

That layout has been preserved literally unchanged for the nine months
since. The visual surface a user sees today is the same one Claude 3.7
generated. The entire codebase grew *around* that frozen surface — not
to expand it, but to defend and extend what it does.

This puts Kolibri Launcher in a different mode of minimalism than the
usual one. "Reduction minimalism" starts with too much and cuts back.
This launcher's minimalism is "minimum-from-day-one minimalism" — the
visible form was set on day one and has been religiously preserved
since. The engineering exists to keep that surface working under every
conceivable condition, not to make it richer.

---

## Feature evolution

Once the static layout was in place, functionality was added in a
deliberate order. Four features came first — the ones that constitute
what the launcher actually does:

1. **App drawer** — the searchable list of installed apps.
2. **Favorite apps** — the small set surfaced on the home screen.
3. **Hidden apps** — curating what the app drawer shows.
4. **Custom names** — letting the user rename apps for their own
   organization.

These four are the operations a launcher actually has to perform. The
repository / use-case architecture grew specifically *for* them; the
abstractions around them are the load-bearing ones.

Everything else came later, after the launcher was stable: the
onboarding flow (which did not exist originally — it was added much
later), the multi-layer wallpaper system, swipe actions, time-based
events, backup and restore, color customization, layout customization,
usage export, the app context menu, the recovery watchdog, the
ApplicationExitInfo-based ANR reporter. These features are used and
maintained, but they are quality-of-life polish — not the launcher's
identity.

---

## Testing infrastructure — the deletion event

Unit tests and ACRA arrived very early in the project. Instrumented
tests came afterward and accumulated to roughly 500.

They were severely flaky. Calling them flaky was the polite term.
Maintaining them outweighed the value they returned. Eventually the
decision was made to do something more honest: extract every piece of
unit-testable logic out of the Fragments and into ViewModels, use
cases, helpers, and `ui/main/delegate/` siblings; harden the extracted
logic with JVM tests until it was 101% stable; then delete the entire
`androidTest/` source set.

This is the actual origin of CLAUDE.md Rule 10 ("testable logic lives
outside Android-runtime classes"). The rule is not a stylistic
preference — it is the residue of a deletion event of roughly 500
tests' worth of pain. The same lineage explains the four-category
catch-frame at `HomeFragment.kt:157–208` documented under Rule 11:
after the logic extraction, the only catches that remain inside the
Fragments are the four genuinely necessary categories — expected
error, teardown race, programmer error, and lying-state path.

For roughly six months after the deletion event, `androidTest/` was
empty. In late April 2026 the most important instrumented test paths
were reintroduced using a newer Espresso version. The rebuild paid off
immediately — it surfaced two cold-path bugs and an
`androidx.test:core` force-pin that had been invisible for weeks. The
stability discipline this time is stricter than before:
process-killing teardown (`pm clear`) was rejected outright in favor
of the orchestrator's `clearPackageData` API.

---

## Crash paranoia and the origin of `silentDeath`

The user's foundational principle for the launcher is straightforward:
it must not crash. That single requirement drives the multi-layer
safety stack — `KolibriLauncherApp`'s catch-Throwable layers (Rule 7),
opt-in ACRA with rate-limited reports, the RecoveryWatchdog daemon
thread, the AnrReporter post-mortem via `ApplicationExitInfo`, the
CrashReportLimiter, and the four-category catch-frame discipline of
Rule 11. All of it is downstream of "no crashes, ever."

The paranoia, however, produced its own failure mode.

At one point Fragment XML inflation was wrapped in a broad
`catch(Throwable)` block. The app survived the inflate failure — and
the user ended up sitting in front of a blank screen. The catch had
stopped the crash and produced *lying state* in its place.

The right fix was neither "remove the catch" (which would re-introduce
crashes) nor "leave it as it was" (which would leave the lying state).
It was to introduce a third primitive: `silentDeath`. The function
logs FATAL and calls `exitProcess(1)` in *every* build, even DEBUG, so
that the exit cuts through any outer catch-Throwable chain. This is
why CLAUDE.md Rule 9 lists `silentDeath` as a separate primitive from
`silentError`, rather than as an escalation level of it. A
`silentError`-throw-in-DEBUG would not have helped the inflate path,
because in release builds the throw does not happen and the user is
back in front of black.

The examples currently listed in Rule 9 — half-migrated DataStore,
Activity without ViewModel, HOME-Activity restart loop — are
descendants of the same pattern. The Fragment-inflate scenario is the
canonical original.

---

## The working method — three sessions and cold-eye reviews

For most of the project's nine months, the user has worked with three
Claude sessions in parallel, each playing a distinct role:

- **Dev** — implements changes.
- **Revisor (reviewer)** — reviews each change against the rules and
  conventions.
- **Auditor** — assesses the result against an external-reviewer
  standard, scoring it against the codebase's own rubric.

The same proposal has to clear three independent perspectives before
it lands. Visible artifacts of this methodology in the repository:
[AUDIT.md](AUDIT.md) is direct Auditor-session output (an external
Senior Android Engineer perspective with a BLOCKER/MAJOR/MINOR/NIT
scale, refreshed 2026-05-04). The "Auditor Snapshot" section in
[TODO.md](TODO.md) and the documented score formula come from the
same lineage. The catch-frame annotation discipline visible in
`MainActivity.kt` ("Catch kept", "Rethrow per canonical", etc.) and
the Rule-11 annotation linter at `tools/check-rule11-annotation.awk`
carry the Revisor session's handwriting, institutionalized in code.

This is also the explanation for how a solo project reaches
committee-quality engineering. Discipline that looks excessive when
read in isolation is the residue of multiple competing perspectives
reaching equilibrium over many iterations.

A periodic complement to the three-session approach: the user took
the full source code, gave it to a *completely fresh* Claude session
with no prior context, and asked it to find bugs and logic holes. The
findings were folded back into the next iteration. Repeated many
times.

This works because an existing session — even an Auditor session
loaded with all 13 rules — accumulates unconscious rationalizations
over time ("I accepted this earlier, so it must be fine"). A fresh
session reads code with the question "does this make sense?" rather
than "is this consistent with what I have already signed off on?"
That difference catches the holes that accumulated sessions have
grown blind to. Visible traces:
`UsageExportRepositoryImplXenomorphSpec.kt` (an adversarial-input
spec deliberately named after Ash's review of the Alien xenomorph),
and the Doomsday / Strict / Malformed / Security / Adversarial test
suites against `BackupRepositoryImpl` — together about 6,000 test
lines for roughly 650 implementation lines.

The architectural arc — ViewModels → use cases → repositories → unit
tests → Hilt → MockK → three-module split (`:app → :data → :domain`)
— is the standard Clean-Architecture progression for Android. Each
step here was triangulated through the three roles and
pressure-tested by fresh sessions, not adopted from a book. The
three-module split was the endpoint, not a starting design — only
reachable once the layers had been cleanly separated through prior
iteration.

---

## 2026-07 — Double-tap-to-lock removed

Double-tap-to-lock was dropped in July 2026. The feature let two taps
on the home screen lock the device via `AccessibilityService` +
`GLOBAL_ACTION_LOCK_SCREEN` — the only path a non-privileged launcher
has on Android 14+. Because that route slides the keyguard window in
*before* the display dims, a device with a different lockscreen than
homescreen wallpaper saw a brief pop-in of the lockscreen wallpaper on
the way to black. Months of mitigation (a paint-synced black overlay,
three rejected dismissal timings, a `OneShotPreDrawListener` frame
sync) reduced it from "every tap" to "occasional" but could never
close it: the keyguard is a system window the app cannot mask, and the
clean fix (`PowerManager#goToSleep`) needs platform/`DEVICE_POWER`
privileges that would break Play Integrity and banking apps. The
residual pop-in lived as `ACCEPTED_LIMITATIONS.md` §1 until the
feature itself was removed — for a perfectionist maintainer the
occasional wrong-wallpaper flash was worse than not having the gesture
at all, and the hardware power button already delivers exactly the
flash-free "black → off" the gesture could not. Removing double-tap-to-lock
left the `AccessibilityService` driving only one thing —
swipe-down-to-open-notifications — through a pile of now-misnamed
`ScreenLock*` plumbing.

---

## 2026-07 — The AccessibilityService removed entirely

A follow-up review asked the obvious next question: what still hangs on
the service? Exactly one feature — swipe-down-on-home to open the
notification shade — reached through `RequestNotificationsUseCase`,
`ScreenLockRepository`, and `performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)`.
Weighed against the cost — a scary, hard-to-grant accessibility
permission, OEM accessibility StrictMode noise, and a whole subsystem of
`ScreenLock*`/`isLockingAvailable` names that no longer had anything to
do with locking — the feature did not earn its keep. It was removed too,
and with it the last reason for the launcher to be an
`AccessibilityService` at all: the service class, its config, the
`ScreenLockRepository` interface, `RequestNotificationsUseCase`, the
swipe-down setting, the "enable accessibility" dialog and Settings entry,
and the backup "gesture settings" category all went. Deleting is cleaner
than renaming here — Android prunes the now-orphaned accessibility grant
as a no-op, so no user is left on a half-broken feature. The launcher no
longer requests any accessibility capability. (A stale
`settingsActivity="com.github.reygnn.bblauncher.SettingsActivity"` —
copy-pasted years ago from a sibling project — was found and fixed on the
way out, then deleted with the rest.)

---

## Coda — Ash's review as the unconscious target

The fictional Ash review at the top of [REVIEWS.md](REVIEWS.md) was
added almost on day one. It was not a programmatic spec the project
worked toward; it was an early articulation of a craft intuition the
user kept reaching for at each decision point. Conscious planning, as
the user describes it, has been mostly after-the-fact
rationalization. The actual driver was a feel for what felt right,
captured in writing before he knew that *was* what he was after.

That makes the entire arc — the multi-layer crash safety, the sixteen
repositories with their contract tests, the recovery watchdog, the
post-mortem ANR reporter, the convention linter, the
abandoned-and-revived instrumented test set — legible as one thing:
stages of metamorphosis pulled forward by an undeclared aesthetic,
not feature scope creep.

> "I admire its purity. A launcher unclouded by widgets, legacy code,
> or delusions of necessity. A structural perfection matched only by
> its efficiency."
> — Ash, Android Science Officer, [REVIEWS.md](REVIEWS.md)

---

*Architecture rules live in [CLAUDE.md](CLAUDE.md), current
work-in-progress in [TODO.md](TODO.md), known unfixable issues in
[KNOWN_ISSUES.md](KNOWN_ISSUES.md), and fictional reviews in
[REVIEWS.md](REVIEWS.md). The why-Claude collaboration story is in
[WHY_CLAUDE.md](WHY_CLAUDE.md).*
