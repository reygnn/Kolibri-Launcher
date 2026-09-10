# Known OS Glitches (no workaround)

This document tracks **rare OS / framework glitches** Kolibri has been observed
to surface that have **no app-side workaround** and require **no code change**.
They are transient, typically non-reproducible races in the platform
(SurfaceFlinger compositing, window / task visibility, transition animations).
The point of writing them down is *recognition*: so a future observer who hits
one again does not chase a phantom Kolibri bug, and so we have a record if a
"seen once" glitch ever turns into a reproducible pattern worth escalating.

This is the fourth sibling in the family of "deliberately-not-obvious" docs,
each a different axis:

- **`KNOWN_ISSUES.md`** — StrictMode violations. We *tolerate or mitigate* these.
- **`ACCEPTED_LIMITATIONS.md`** — intentional UX / behavioural limitations that
  are consequences of an architectural decision. We *do not fix* these.
- **`KNOWN_QUIRKS.md`** — OEM / framework behaviours we *do work around* with
  real code.
- **`KNOWN_OS_GLITCHES.md`** (this file) — platform glitches with *nothing to
  do*: no workaround exists or is worth building, and the app cannot even detect
  the state to react to it.

The distinction from `KNOWN_QUIRKS.md` is the verb: a quirk has live workaround
code that must not be reverted; a glitch here has **no code at all** — only an
observation. The distinction from `ACCEPTED_LIMITATIONS.md` is intent: a
limitation is a priced-in consequence of *our* design; a glitch is a *platform*
malfunction we neither caused nor can address.

**Bar for an entry.** Not every odd frame belongs here. An entry earns its place
when (a) the cause is assessed to be the platform, not Kolibri, (b) there is no
reasonable app-side fix, and (c) it is worth recognizing later. A reproducible
issue does not belong here — it belongs in a bug fix, or, if OEM-specific and
worked around, in `KNOWN_QUIRKS.md`.

---

## 1. Home task stays composited over the Overview / Recents screen

- **Status:** 🔵 Observed once, no action (platform race)
- **Context:** Swiping from the Kolibri home screen into the system
  Overview / Recents screen
- **Affected:** Pixel 9a, Android 17 (seen once; never before or since,
  including across the entire multi-layer-wallpaper era)
- **Reproducible:** No — multiple reconstruction attempts failed

### Symptom

The complete Kolibri home view tree — clock, favorite labels, and the full
4-layer wallpaper collage — was composited *on top of* the system
Overview / Recents screen for several seconds. The system recents cards were
visible only peeking at the left and right edges; the "Screenshot" / "Select"
recents actions were present at the bottom. The overlay was stable long enough
to be noticed, screenshotted, and to survive a small thumb swipe — so it was a
live, still-redrawing surface, not a frozen transition frame.

### Assessed cause

A framework / SurfaceFlinger **task-visibility (z-order) race**: the HOME task
was not occluded or stopped behind the Overview surface, so the whole home
window stayed drawn above it. This is a platform state — an app cannot place its
own task above the system Overview, and the WindowManager, not app code, decides
task visibility.

Kolibri's setup *demasks* the glitch rather than causing it: the home window is
transparent + `FLAG_SHOW_WALLPAPER` (`MainActivity.setupWindow`) and the
wallpaper collage is drawn into the app's own views (`ZoomableImageView` /
`WallpaperViewBinder`), not pushed to the system wallpaper. So when the window
mis-composited, the fully designed home surface bled through — where a launcher
relying only on the system wallpaper and system-drawn chrome would have shown a
plainer, less alarming rectangle of the same framework fault.

### Why no workaround

- **No usable event.** Nothing signals "I am being wrongly composited over
  Overview." The Overview swipe is driven by SystemUI; the launcher gets only the
  ordinary lifecycle (`onPause`, possibly `onStop`), which fire on *every* leave
  and cannot distinguish this case. During the glitch the task stayed visible, so
  `onStop` may not even have fired — there was no state to hook.
- **Opacity would not mask it.** The obvious idea — toggle the window opaque on
  leave, transparent on return — fails on inspection: the alarming content (the
  collage layers, clock, favorites) sits *above* the `wallpaper_backdrop` view,
  so painting the backdrop opaque changes only the fill behind them, not the
  mis-layered content itself. The defect is the z-order of the whole task, not
  transparency.
- **The cost is permanent, the benefit is a one-off.** Any such toggle would fire
  on every navigation (a black recents thumbnail instead of the wallpaper, plus a
  relayout on the performance-critical resume path), to defend against a glitch
  seen once and not reproducible.

### Re-evaluation trigger

If this recurs *and* becomes reproducible (a specific gesture, device, or Android
build that reliably triggers it), escalate: file it, capture `dumpsys
SurfaceFlinger` / `dumpsys activity` at the moment, and reconsider whether a
narrow mitigation is warranted. A single non-reproducible sighting stays here as
a recognition note only.
