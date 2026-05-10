# Accepted Limitations

This document tracks known UX or behavioural limitations that are
*intentional consequences* of architectural decisions. They are not
bugs to be fixed; they are costs we have priced in. The purpose of
this file is to remind future-us why we accepted them, so we don't
re-litigate the decision every time the limitation is noticed.

---

## 1. Wallpaper pop-in on double-tap-to-lock

- **Status:** 🟡 Intentional / Documented
- **Frequency:** ≲ 5% of taps (empirical, Pixel 9a, GrapheneOS not tested)
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

The current implementation (commits c987f1d, 96ee9a1, and the
PreDraw-sync follow-up) reduces the pop-in from "every tap" to
"occasional." Closing the residual gap would require leaving the
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
- The launcher is forked into a "developer build" variant where
  banking compatibility is no longer a constraint.
