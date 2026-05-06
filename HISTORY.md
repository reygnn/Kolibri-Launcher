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

*Architecture rules live in [CLAUDE.md](CLAUDE.md), current
work-in-progress in [TODO.md](TODO.md), known unfixable issues in
[KNOWN_ISSUES.md](KNOWN_ISSUES.md), and fictional reviews in
[REVIEWS.md](REVIEWS.md). The why-Claude collaboration story is in
[WHY_CLAUDE.md](WHY_CLAUDE.md).*
