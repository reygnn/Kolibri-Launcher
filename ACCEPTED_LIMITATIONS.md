# Accepted Limitations

This document tracks known UX or behavioural limitations that are
*intentional consequences* of architectural decisions. They are not
bugs to be fixed; they are costs we have priced in. The purpose of
this file is to remind future-us why we accepted them, so we don't
re-litigate the decision every time the limitation is noticed.

---

## 1. Wallpaper pop-in on double-tap-to-lock

- **Status:** 🟡 Intentional / Documented
- **Frequency:** ≲ 5% of taps (observed, not instrumented; Pixel 9a,
  GrapheneOS not tested)
- **Affected:** All devices; severity varies with display refresh rate
  and AOD configuration

### Explanation

A black overlay (`HomeFragment#lockTransitionOverlay`) masks the
keyguard fade-in transition during double-tap-to-lock. In a small
fraction of taps a single-frame lockscreen-wallpaper pop-in remains
visible immediately before the display powers off. Full mechanism
documented in the KDoc of `GestureDelegate#onDoubleTapToLock`.

### Why it exists

The pop-in is the visible residue of using `AccessibilityService` +
`GLOBAL_ACTION_LOCK_SCREEN` instead of `PowerManager#goToSleep`. The
latter would require either:

1. Signing the launcher with the platform key (only possible on
   custom-built ROMs we maintain ourselves), or
2. Granting `DEVICE_POWER` via Magisk + `/system/priv-app/` overlay
   (breaks Play Integrity → breaks AKB TWINT, SBB Mobile, banking),
   or
3. Forking GrapheneOS and self-signing OS builds (≈ 50–100 builds
   per year, single-maintainer burden, no Play Integrity).

### Why we don't fix it further

The current implementation (commits c987f1d, 96ee9a1, and c88c14b
for the PreDraw-sync follow-up) reduces the pop-in from "every tap"
to "occasional." Closing the residual gap would require leaving the
AccessibilityService model entirely. The trade — banking apps stop
working — is strictly worse than the cosmetic cost. See also the
Adrian-Monk-grade temptation to root or fork GrapheneOS, both
considered and rejected.

### Trigger for re-evaluation

Reopen this entry if any of the following changes:

- Google publishes an `AccessibilityService` API extension that
  allows synchronous power-off without keyguard composite.
- A future Pixel/Android version changes the keyguard window
  composition such that the overlay-z-order workaround breaks.
- Relevant banking apps drop the Play Integrity *Strong* requirement
  (or establish another auth path that does), which would re-open
  the Magisk + `/system/priv-app/` overlay route and put
  `PowerManager#goToSleep` back on the table.
- The launcher is forked into a "developer build" variant where
  banking compatibility is no longer a constraint.

---

## 2. AppDrawer AUTO-mode classifier ignores layer composition

- **Status:** 🟡 Intentional / Documented
- **Frequency:** Two narrow shapes — see "When the heuristic punts"
- **Affected:** Multi-layer Kolibri-internal wallpapers in AUTO mode

### Explanation

`ClassifyWallpaperUseCase` decides between LIGHT and DARK by
inspecting *one* signal at a time, in priority order:

1. Kolibri-internal wallpaper, but only `layers[0]` (the bottom-
   most layer in render order) — and only when it's "opaque
   enough to dominate perception". Two cooperating gates make that
   call: a **layer-level** alpha gate (`WallpaperLayerState.alpha`
   ≥ 0.8 and Normal blend) and a **pixel-level** coverage gate
   (≥ 50% of pixels with alpha ≥ 80% inside the bitmap itself).
   Either gate failing routes the classifier to the next signal.
2. System-wallpaper `colorHints` (`HINT_SUPPORTS_DARK_TEXT`).
3. Fallback: DARK.

The classifier never composites multiple Kolibri layers together
to estimate the user-perceived background. When `layers[0]` fails
either dominance gate, the classifier punts to the system
signal — even if a *higher* layer (e.g. `layers[1]`) is fully
opaque and dominates the actual composition.

### When the heuristic punts (the two known soft spots)

- **Transparent `layers[0]` + opaque `layers[1]+`.** A user with
  `[transparent grey detail, opaque blue background]` would have
  the blue dominate visually. The classifier sees `layers[0].alpha
  < 0.8`, falls through to the system signal, and may pick the
  wrong surface. (In practice the typical Kolibri-multi-layer
  setup is "opaque background at index 0, detail overlays above"
  — the inverse — so this case is uncommon.)
- **Opaque `layers[0]` with non-Normal blend.** An opaque layer
  with e.g. MULTIPLY at alpha 1.0 over a bright underlying image
  visually darkens the result, but the classifier punts to the
  system signal rather than approximate the blend. Documented in
  the use-case KDoc.

### Why we don't fix it further

Compositing layers properly requires loading every layer's
bitmap, allocating an N×M ARGB buffer, running the blend ops in
order, and sampling luminance from the result. That's a real
mini-render pipeline. The cost-vs.-coverage trade is unfavourable
right now: the alpha-gate-on-`layers[0]` heuristic is correct for
the common shape (opaque bottom + detail overlays + the
single-layer case), and shipping a partially-correct compositor
would only paper over edge cases that the user can already work
around by picking LIGHT or DARK manually (the explicit overrides
exist for exactly this).

### Trigger for re-evaluation

Reopen this entry if any of the following changes:

- A user reports that AUTO consistently picks the wrong surface
  for a multi-layer wallpaper they actually use, *and* the
  manual LIGHT/DARK override is unsatisfactory (e.g., they
  alternate between wallpapers that legitimately need different
  surfaces).
- The wallpaper editor grows a "preview composite as bitmap"
  step that already runs the render pipeline — at that point the
  classifier could reuse the result for free.
- Multi-layer alpha conventions in the codebase shift (e.g.,
  `layers[0]` becomes the *top* layer instead of the bottom by
  some refactor) — the alpha-gate semantics would need to follow.
