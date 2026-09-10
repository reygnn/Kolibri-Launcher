# Kolibri Launcher — v1.0.0-rc1

First release candidate for **1.0**. This is the release that hardens the
launcher to a 1.0 milestone: new visible features on the frozen minimalist
surface, plus a large robustness and cold-start-performance pass.

> `versionName = 1.0.0-rc1`, `versionCode = 233`. Tag: `v1.0.0-rc1-stable`
> (kept on the historical `-stable` suffix). Range: `v0.99.188-stable..HEAD`
> (148 commits). Published as a GitHub **pre-release**.

---

## Highlights

### Home screen
- **Battery charge indicators.** A bolt shows next to the battery percentage
  while charging; a shield shows while the charge is held (battery-protection
  active). The two are mutually exclusive.
- **Time-based events next to the clock.** Alarm and calendar indicators are
  now crisp vector icons (no more emoji/glyphs). Double-tapping the clock opens
  a dialog listing today's and tomorrow's alarms and calendar events —
  including all-day events and events still running until their end time.
  Replaces the old clipboard double-tap.
  - Samsung's phantom midnight "alarm" (00:00) is filtered out.
  - Calendar event reminders are no longer misreported as alarms.
- **Readability.** Clock, date and favorites now use a thin text outline
  instead of a drop-shadow, so they stay legible over any wallpaper.

### Wallpaper
- **Manual scrim.** A dim slider in the "Colors & Shadow" dialog lets you
  darken the wallpaper behind the text, with haptic feedback and a live preview
  that fades the dialog while sliding.
- **Backdrop setting.** Choose the system wallpaper or solid black behind the
  multi-layer collage.
- **Reset offer** when you swap or remove a wallpaper.

### Onboarding
- **First-run extras.** Set Kolibri as the default launcher and restore a
  backup directly from onboarding. Material 3 button styling and toast
  feedback throughout.

---

## Under the hood

- **Cold-start performance.** New Baseline Profile + macrobenchmark gates;
  favorites now paint provisionally on the first frame; the wallpaper color
  read moved off the main-thread first-frame path. **TTFD improved ~19 %**
  (751 → 606 ms on the A17 reference device).
- **Crash reporting is now report-by-intent (§23).** Whether an entry reaches
  ACRA is decided by an explicit intent tag, not by log level — which removes a
  class of false-positive reports (coroutine cancellations, malformed backup
  imports).
- **Robustness.** Malformed favorite component keys are rejected instead of
  silently persisted; a wallpaper is preserved instead of wiped when a backup's
  wallpaper can't be restored; usage-import now does a bounded read.
- **Public-safe release build.** The ACRA dev test-triggers are compiled out of
  the public GitHub AAB; the `-PdailyDriver` master flag re-enables all
  personal-only toggles for local builds.

---

## Before tagging / uploading

- Regenerate the baseline profile on a connected device
  (`./gradlew :app:generateBaselineProfile`) **before** `bundleRelease` — it is
  gitignored and not auto-generated, so a fresh build otherwise ships degraded
  ART rules (see the Versioning section in `CLAUDE.md`).
- The public GitHub AAB is a plain `./gradlew bundleRelease` (dev commands
  compiled out). Do **not** pass `-PdailyDriver` for the public artifact.
- Tag: `v1.0.0-rc1-stable` (historical `-stable` suffix).
