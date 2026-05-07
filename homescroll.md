# homescroll.md

Handover document for migrating `HomeFragment` away from its split-screen
gesture layout to a single-pass gesture-detecting wrapper. Compiled
2026-05-06 from a working session that analyzed the current
implementation, the proven `SwipeDownDismissLayout` pattern in the
AppDrawer, and the corresponding instrumented test pattern. A future
session should be able to pick up here without re-discovering the file
structure or the design rationale.

---

## 1. Goal

Replace the current dual-mode (split-screen vs. full-screen) gesture
handling in `HomeFragment` with a single-mode `dispatchTouchEvent`-based
wrapper that:

- Lets favorites use the full vertical screen height in all cases, even
  when there are more favorites than fit on screen.
- Detects all five home gestures (4 swipes + double-tap + long-press)
  anywhere on the screen via velocity discrimination, exactly mirroring
  the proven `SwipeDownDismissLayout` pattern in the AppDrawer.
- Lets slow drags pass through to the underlying `ScrollView` so normal
  vertical scrolling continues to work.
- Eliminates the conditional split-mode XML and the `gestureZone` view.

The user has been dissatisfied with the split-screen approach for
months. The trigger for this work was the observation that
`SwipeDownDismissLayout` in the AppDrawer cleanly discriminates fast
swipe from slow scroll on the same `ScrollView` axis — proving the
single-mode approach is viable.

---

## 2. Current implementation (status 2026-05-06)

### 2.1 HomeFragment touch wiring

File: `app/src/main/java/com/github/reygnn/kolibri_launcher/ui/home/HomeFragment.kt`
(~1989 lines).

Relevant call sites:

- **`gestureDetector` field** at line 322. Holds the
  `android.view.GestureDetector` instance.
- **`setupGestures()`** at lines 1380–1410. Builds the `GestureDetector`
  with `createGestureListener()` and attaches a `setOnTouchListener` to
  `binding.rootLayout` (line 1399). The listener short-circuits in split
  mode (`_needsSplit.value == true`) by returning `false`, otherwise
  feeds the event to the detector.
- **`createGestureListener()`** at lines 1412–1465. Returns a
  `GestureDetector.SimpleOnGestureListener` with overrides:
  - `onLongPress` (1428): dispatches to `viewModel.onLongPress()`
    (or, in wallpaper edit mode, exits the mode via
    `viewModel.onSetWallpaperEditMode(false)`).
  - `onDoubleTap` (1437): dispatches to `viewModel.onDoubleTapToLock()`.
  - `onFling` (1442): runs `SwipeGestureAnalyzer.analyze(...)` on the
    raw flingdiff/velocity values and routes to one of four
    `viewModel.*` calls.

### 2.2 Split-mode setup

File: `HomeFragment.kt`, lines 940–987 (the conditional setup block).

When `_needsSplit.value == true`:

- `binding.gestureZone.isVisible = true` (the right-hand sliver becomes
  the active touch surface).
- `customScrollView.allowIntercept = true` — `NonInterceptingScrollView`
  is allowed to intercept touches (so the favorites list scrolls).
- `binding.gestureZone.setOnTouchListener { ... gestureDetector.onTouchEvent(event) }`
  — gestures are detected only on this sliver.

When `_needsSplit.value == false`:

- `binding.gestureZone.isVisible = false`.
- `customScrollView.allowIntercept = false` — the
  `NonInterceptingScrollView` becomes touch-transparent and forwards
  events up to the root layout.
- `customScrollView.setOnTouchListener(null)`.
- The `binding.rootLayout` listener (set in `setupGestures()`) is the
  active gesture surface.

### 2.3 NonInterceptingScrollView

File: `app/src/main/java/com/github/reygnn/kolibri_launcher/ui/home/NonInterceptingScrollView.kt`
(48 lines).

A subclass of `ScrollView` that toggles between intercepting and
non-intercepting based on its `allowIntercept` field. In full mode
(`allowIntercept = false`), `onInterceptTouchEvent` and `onTouchEvent`
both short-circuit to `false` so events bubble up to the parent for
gesture handling. The `wallpaperTouchInterceptor` view in the layer
above the scroll view exists as a parallel gating mechanism for the
wallpaper-edit overlay (separate concern, not gesture-related).

### 2.4 SwipeGestureAnalyzer

File: `app/src/main/java/com/github/reygnn/kolibri_launcher/ui/home/SwipeGestureAnalyzer.kt`
(51 lines, pure-logic, JVM-testable).

Decides between TOWARDS_LEFT / TOWARDS_RIGHT / UP / DOWN / IGNORED
based on dominant axis, distance threshold (`AppConstants.SWIPE_THRESHOLD`),
and velocity threshold (`AppConstants.SWIPE_VELOCITY_THRESHOLD`).
Identical logical structure to the velocity discriminator in
`SwipeDownDismissLayout` (just with floats from `GestureDetector`
instead of derivations from `MotionEvent` deltas). Already covered by
JVM unit tests in
`app/src/test/java/com/github/reygnn/kolibri_launcher/ui/SwipeGestureAnalyzerTest.kt`.

### 2.5 Layout XML

File: `app/src/main/res/layout/fragment_home.xml`.

Relevant structure (lines ~85–118):

```
<LinearLayout id="favoritesContainer" orientation="horizontal">
  <NonInterceptingScrollView id="favoritesScrollView" weight=1>
    <LinearLayout id="appList" orientation="vertical" />
  </NonInterceptingScrollView>
  <View id="gestureZone" weight=0 visibility="gone" />
</LinearLayout>
```

The `weight` values on these two children are toggled at runtime to
produce the split mode. Default is full screen for the scrollview;
when `_needsSplit` flips, the gestureZone takes a configured fraction
of the horizontal space.

### 2.6 Gesture backend (unchanged by this migration)

File: `app/src/main/java/com/github/reygnn/kolibri_launcher/ui/main/delegate/GestureDelegate.kt`.

Already extracted from HomeFragment per Rule 10. Methods:

- `onFlingUp()` → emits `UiEvent.ShowAppDrawer`.
- `onFlingDown()` → calls `RequestNotificationsUseCase()` which
  ultimately calls `ScreenLockAccessibilityService.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)`.
  Note: this is *not* the system status bar pull. It's an in-app gesture
  that goes through the accessibility service to programmatically
  expand the notification panel from anywhere on the home screen.
  The accessibility service is opt-in; if disabled, a one-time toast
  prompts the user to enable it.
- `onSwipeFromRightToLeft()` / `onSwipeFromLeftToRight()` → call
  `HandleSwipeActionUseCase(SwipeSlot.X)` and emit `UiEvent.LaunchApp`.

`viewModel.onLongPress()` and `viewModel.onDoubleTapToLock()` route
through their own use cases (settings open / display lock).

**This entire backend stays intact.** The migration only changes how
touches are detected and routed to these methods.

---

## 3. The proven pattern (`SwipeDownDismissLayout`)

File: `app/src/main/java/com/github/reygnn/kolibri_launcher/ui/appdrawer/SwipeDownDismissLayout.kt`
(162 lines, single-purpose, well-documented).

### 3.1 Why `dispatchTouchEvent`, not `onInterceptTouchEvent`

This is documented in the class KDoc (lines 15–28) and is critical to
preserve in the migration:

> RecyclerView calls `requestDisallowInterceptTouchEvent(true)` on its
> parent the moment it detects vertical movement past `touchSlop`. From
> that moment on, `onInterceptTouchEvent` on the parent is NEVER called
> for the rest of the gesture. So every "obvious" approach —
> `GestureDetector` on the parent, `OnTouchListener` on the parent,
> `onInterceptTouchEvent` override — silently fails mid-scroll.

`dispatchTouchEvent` is the only escape hatch: it always fires on the
parent regardless of `disallowIntercept`. ScrollView calls
`requestDisallowInterceptTouchEvent` in the same situations as
RecyclerView, so the same constraint applies.

### 3.2 Three tuning constants

Lines 63–69:

```kotlin
private val minSwipeDistancePx = ViewConfiguration.get(context).scaledTouchSlop * 4
private val minVerticalVelocityPxPerMs = 1.2f
private val verticalDominanceFactor = 1.5f
```

A gesture fires only when ALL THREE thresholds are simultaneously met:
distance > minSwipeDistancePx, vertical velocity > 1.2 px/ms, and
`abs(dy) > abs(dx) * 1.5`. This three-AND structure is what makes the
discrimination empirically reliable: any one threshold alone fails on
some natural-motion edge case.

### 3.3 Three invariants the KDoc commits the maintainer to

From the `dispatchTouchEvent` KDoc (lines 84–119):

1. **Read-only inspection until trigger.** ACTION_DOWN snapshots
   coordinates and time. ACTION_MOVE checks the predicates but does
   NOT consume the event. Children receive every event normally via
   `super.dispatchTouchEvent` and can scroll/click/long-press as
   usual. This is what makes "slow drag still scrolls the list" work.

2. **One-shot trigger.** Once predicates align, `triggered = true`,
   ACTION_CANCEL is sent down, the callback fires, and every remaining
   event of the gesture is consumed. The flag resets only on the next
   ACTION_DOWN. Without the one-shot guard, a held finger past
   threshold would re-fire every frame.

3. **ACTION_CANCEL synthesis** (lines 157–161). On trigger, the parent
   sends a synthesized ACTION_CANCEL down to its children to release
   any in-progress scroll cleanly. Without this, the dismiss animation
   would play over a still-fling-ing scroll.

The KDoc explicitly forbids replacing these with simpler patterns;
mirror the same discipline in the new layout.

---

## 4. The proven test pattern (`AppDrawerSwipeDismissTest`)

File: `app/src/androidTest/java/com/github/reygnn/kolibri_launcher/ui/appdrawer/AppDrawerSwipeDismissTest.kt`
(~160 lines).

### 4.1 Why instrumented (not Robolectric)

From the class KDoc (lines 41–44):

> That code is exactly the sort of touch-pipeline logic Robolectric
> cannot honestly cover: touch slop, velocity tracker, the
> `dispatchTouchEvent` callback chain.

This matches the bar in CLAUDE.md Rule 10 ("cannot be covered reliably
by Robolectric") — instrumented test is the right tool. The
`HomeGestureLayout` follows the same rule.

### 4.2 Test setup pattern

```kotlin
@HiltAndroidTest
class HomeGestureLayoutTest {
    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @Inject lateinit var settings: SettingsRepository

    @Before fun setUp() {
        hiltRule.inject()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            settings.setOnboardingCompleted()
            CrashReportConsentStore.saveConsent(ctx, consent = false)
        }
    }
    // ...
}
```

### 4.3 Pre-warm + launch boilerplate

```kotlin
val ctx = InstrumentationRegistry.getInstrumentation().targetContext

// PackageManager pre-warm avoids cold-start RootViewPicker timeouts.
val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
ctx.packageManager.queryIntentActivities(launcherIntent, 0)

val launchIntent = Intent(ctx, MainActivity::class.java)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
ActivityScenario.launch<MainActivity>(launchIntent).use {
    // ...
}
```

### 4.4 Swipe action template

`Espresso.swipeDown()` / `swipeUp()` use fixed coordinate ratios that
can hit the wrong area. Use a custom `GeneralSwipeAction` instead.
Lessons from the AppDrawer test:

- Use `GeneralLocation.VISIBLE_CENTER` as origin, NOT `TOP_CENTER` —
  TOP_CENTER lands at y=0 which is the system status bar window;
  events there never reach the layout.
- Use `Swipe.FAST` (the `Swipe.SLOW` variant is for the negative
  pass-through tests).
- A half-screen FAST diagonal blows past all three thresholds on any
  display density; no per-device tuning needed.

```kotlin
onView(withId(R.id.home_gesture_root)).perform(
    actionWithAssertions(
        GeneralSwipeAction(
            Swipe.FAST,
            GeneralLocation.VISIBLE_CENTER,
            GeneralLocation.TOP_CENTER,    // for swipe-up
            Press.FINGER,
        )
    )
)
```

For each direction, the second `GeneralLocation` argument changes:
`TOP_CENTER` (up), `BOTTOM_CENTER` (down), `CENTER_LEFT` (left),
`CENTER_RIGHT` (right).

### 4.5 Assertion patterns

Visibility checks (no `awaitUntil` wrap — let Espresso's RootViewPicker
patience handle it):

```kotlin
onView(withId(R.id.apps_recycler_view)).check(matches(isDisplayed()))
```

Post-action state verification (with `awaitUntil` and try-catch):

```kotlin
awaitUntil(
    timeoutMs = 5_000,
    describe = { "AppDrawer never opened after swipe-up" },
) {
    try {
        onView(withId(R.id.apps_recycler_view)).check(matches(isDisplayed()))
        true
    } catch (_: Throwable) {
        false
    }
}
```

`awaitUntil` is in `app/src/androidTest/java/com/github/reygnn/kolibri_launcher/support/`.

### 4.6 Programmatic navigation

When a test needs to reach the home screen specifically (default
launcher), `ActivityScenario.launch<MainActivity>` is enough. When a
test needs to traverse navigation (e.g. starting at home, going to
AppDrawer), use:

```kotlin
InstrumentationRegistry.getInstrumentation().runOnMainSync {
    val activity = currentResumedActivity<MainActivity>()
        ?: error("MainActivity not RESUMED — cannot navigate")
    val nav = activity.findNavController(R.id.nav_host_fragment)
    nav.navigate(R.id.action_homeFragment_to_appDrawerFragment)
}
```

`currentResumedActivity()` is a private inline function in the
existing `AppDrawerSwipeDismissTest`; copy it as-is.

---

## 5. Gesture inventory

Five user-facing gestures on the home screen (numbered for test
mapping):

| # | Gesture | Backend call | Effect |
|---|---|---|---|
| 1 | Swipe up | `viewModel.onFlingUp()` → `GestureDelegate.onFlingUp()` | Open AppDrawer |
| 2 | Swipe down | `viewModel.onFlingDown()` → `GestureDelegate.onFlingDown()` | Open notifications via accessibility service |
| 3 | Swipe right (left→right finger motion) | `viewModel.onSwipeFromLeftToRight()` | Launch configured swipe-app |
| 4 | Swipe left (right→left finger motion) | `viewModel.onSwipeFromRightToLeft()` | Launch configured swipe-app |
| 5a | Double tap | `viewModel.onDoubleTapToLock()` | Display lock via accessibility |
| 5b | Long press | `viewModel.onLongPress()` (or wallpaper edit-mode exit, conditional) | Open settings (default) |

The **wallpaper edit-mode** carve-out for long-press is a special
case worth preserving: when `viewModel.isWallpaperEditMode.value == true`,
long-press must call `viewModel.onSetWallpaperEditMode(false)` instead.

---

## 6. Migration plan

### Step 1 — Build `HomeGestureLayout` [DONE 2026-05-07]

New file: `app/src/main/java/com/github/reygnn/kolibri_launcher/ui/home/HomeGestureLayout.kt`.

- Extends `ConstraintLayout`, `@JvmOverloads constructor`.
- Public API: six nullable callback fields (`onSwipeUp`, `onSwipeDown`,
  `onSwipeLeft`, `onSwipeRight`, `onDoubleTap`, `onLongPress`).
  No `gesturesEnabled` boolean — gating is per-callback via null
  (decision, see §8). Wallpaper-edit-mode sets the four swipe
  callbacks + `onDoubleTap` to `null` and keeps `onLongPress` wired,
  with the long-press callback handling its dual behavior internally.
- Tuning constants: start with the SwipeDownDismissLayout values
  (`scaledTouchSlop * 4`, `1.2f`, `1.5f`). One shared velocity
  threshold for all four directions (decision, see §8). Split into
  per-direction constants only if real-device feel turns out uneven.
- Per-gesture state: `downX`, `downY`, `downTime`, and a
  `triggered: GestureType?` (sealed class with the four directional
  cases — tap-based gestures don't need to set this since they fire
  from the embedded `GestureDetector` on ACTION_UP).
- `dispatchTouchEvent` body:
  1. If `!gesturesEnabled`, fall through to `super.dispatchTouchEvent`.
  2. Forward event to the embedded `GestureDetector` for tap detection.
  3. On ACTION_DOWN: snapshot down* fields, reset `triggered`.
  4. On ACTION_MOVE with `triggered == null`: compute deltas, determine
     dominant axis via `dominanceFactor`, evaluate direction-specific
     velocity threshold, on match: set `triggered`, send ACTION_CANCEL
     down, invoke the appropriate callback, return `true`.
  5. After trigger (`triggered != null`): consume all remaining events
     of the gesture by returning `true`.
- Embedded `GestureDetector.SimpleOnGestureListener`:
  - `onDoubleTap(e)` → invoke `onDoubleTap`, return `true`.
  - `onLongPress(e)` → invoke `onLongPress`. Note: `onLongPress` is
    a `void` callback in `SimpleOnGestureListener`, not a `Boolean`
    return; it fires asynchronously after ~500 ms hold.
- ACTION_CANCEL synthesis helper: copy as-is from
  `SwipeDownDismissLayout.cancelChildGesture()`.

Mirror the KDoc structure of `SwipeDownDismissLayout` — the three
invariants from §3.3 must be visible to future maintainers.

Estimated size: ~200–250 lines (vs. 162 for `SwipeDownDismissLayout`,
the delta is the four-direction expansion + GestureDetector
integration + sealed-class trigger state).

**Implementation notes (2026-05-07):**

- **§8.5 (analyzer reuse) crystallized as parameterization, not
  drop-in reuse.** Reading both call sites end-to-end exposed a
  scale mismatch: `AppConstants.SWIPE_THRESHOLD = 50` (px) and
  `SWIPE_VELOCITY_THRESHOLD = 50` (px/sec) are calibrated for
  `GestureDetector.onFling`'s VelocityTracker-smoothed output, while
  `SwipeDownDismissLayout`'s values (`scaledTouchSlop * 4`,
  `1.2 px/ms` = 1200 px/sec, `1.5x` dominance) are calibrated for
  raw-dispatch deltas — the latter's velocity threshold is ~24×
  higher. Reusing the analyzer as-is with the existing AppConstants
  values would have triggered on every casual finger movement.
  Resolution: `SwipeGestureAnalyzer` now takes `distanceThreshold`,
  `velocityThreshold`, and `dominanceFactor` (default `1f`) as
  constructor parameters. HomeFragment's pre-existing call site at
  line 356 was updated to pass AppConstants values explicitly (will
  be deleted in Step 3). The existing JVM test
  `SwipeGestureAnalyzerTest` was updated to construct the analyzer
  with explicit `50f / 50f` and default dominance — it preserves
  the original semantics and all boundary tests still pass.
- **Subtle analyzer behavior change at exact axis equality.** The
  old binary check (`if (absDiffX > absDiffY) horizontal else
  vertical`) defaulted equal magnitudes to vertical. The new
  symmetric form with `dominanceFactor = 1f` returns IGNORED for
  exact `|dx| == |dy|`. Real-world touch traces practically never
  produce exact equality so no existing test case fails; documented
  for completeness.
- **Tap detector also receives ACTION_CANCEL on trigger.** The doc
  described the embedded `GestureDetector` integration but didn't
  detail how its state should be reset when a directional swipe
  fires. `HomeGestureLayout.cancelChildGesture` synthesizes
  ACTION_CANCEL and feeds it to BOTH `super.dispatchTouchEvent` (the
  ScrollView and other children) AND the embedded `tapDetector`.
  Any in-progress long-press timer or tap-counting state aborts in
  the same step, so the gesture stays clean. Reflected in
  `cancelChildGesture`'s KDoc.
- **No try/catch around the public callbacks.** Mirrors
  `SwipeDownDismissLayout`, which deliberately doesn't wrap
  `onSwipeDown?.invoke()`. Consistent with Rule 11 (no defensive
  try/catch around simple invoke operations) — the consumer's
  existing safety nets (`launchSafe` inside `GestureDelegate`)
  already cover the realistic failure modes.
- **Final size: ~210 lines** (the estimate of 200–250 held).

### Step 2 — Update layout XML

File: `app/src/main/res/layout/fragment_home.xml`.

- Replace the outer `LinearLayout` (`favoritesContainer`) wrapping
  `favoritesScrollView` + `gestureZone` with a single
  `<HomeGestureLayout>` wrapping the scroll view alone. Drop the
  `gestureZone` view entirely.
- The `NonInterceptingScrollView` itself can stay initially
  (defensive); once the wrapper is proven, the `allowIntercept`
  dual-mode field becomes dead code and the class can be replaced
  with a plain `ScrollView`. Defer this cleanup to a follow-up
  commit on the same branch.
- Add an explicit `android:id="@+id/home_gesture_root"` (or similar)
  on the new layout so Espresso tests can target it.

### Step 3 — Wire up in HomeFragment

- Delete `setupGestures()` (lines 1380–1410), `createGestureListener()`
  (lines 1412–1465), and the `gestureDetector` field (line 322).
- Delete the split-mode setup block (lines 940–987) and any
  `_needsSplit`-related logic. Probably also the `LayoutDelegate`
  computation that drove `_needsSplit`; verify before removing.
- Wire the wrapper callbacks in `onViewCreated`:
  ```kotlin
  binding.homeGestureRoot.onSwipeUp     = { viewModel.onFlingUp() }
  binding.homeGestureRoot.onSwipeDown   = { viewModel.onFlingDown() }
  binding.homeGestureRoot.onSwipeLeft   = { viewModel.onSwipeFromRightToLeft() }
  binding.homeGestureRoot.onSwipeRight  = { viewModel.onSwipeFromLeftToRight() }
  binding.homeGestureRoot.onDoubleTap   = { viewModel.onDoubleTapToLock() }
  binding.homeGestureRoot.onLongPress   = {
      if (viewModel.isWallpaperEditMode.value) viewModel.onSetWallpaperEditMode(false)
      else viewModel.onLongPress()
  }
  ```
- The locking-in-progress short-circuit currently in
  `GestureDetector.onFling` (HomeFragment.kt:1445–1448) moves into
  `viewModel.onFling*` methods themselves: each method's first line
  becomes `if (isLockingInProgress) return` (decision, see §8). Per
  Rule 10 — JVM-testable, centralizes the gate, future call sites
  (e.g. hardware button) inherit it. Verify the actual flag name
  (`isLockingInProgress` or whichever) when implementing.

### Step 4 — Tests

Add `app/src/androidTest/java/com/github/reygnn/kolibri_launcher/ui/home/HomeGestureLayoutTest.kt`.

Per gesture, two tests: trigger (FAST swipe should fire callback) and
pass-through (SLOW drag should NOT fire callback and SHOULD scroll the
favorites list when content overflows).

```
swipeUpFast_opensAppDrawer
swipeUpSlow_scrollsFavoritesInsteadOfOpeningDrawer
swipeDownFast_triggersNotificationsCallback           // see open question on observability
swipeDownSlow_doesNotTriggerNotifications
swipeLeftFast_triggersLeftSwipeApp
swipeLeftSlow_doesNotTriggerSwipeApp
swipeRightFast_triggersRightSwipeApp
swipeRightSlow_doesNotTriggerSwipeApp
doubleTapOnHomeRoot_triggersDisplayLock                // see open question on observability
longPressOnHomeRoot_opensSettings
longPressInWallpaperEditMode_exitsEditMode             // covers the carve-out
favoriteItemTapStillLaunchesApp                        // sanity: wrapper doesn't break normal taps
```

For tests that need to assert on system-level effects (display lock,
notifications panel), add a test-only callback hook on the wrapper so
the test can observe "the callback fired" without needing to verify
the system-side effect. Same approach as the AppDrawer test, which
asserts on the structural signal (`appList` view becomes visible)
rather than the navigation system internals.

### Step 5 — Real-device validation

- Build a debug APK and install on a real device.
- Walk through each gesture in both states (favorites fit on screen,
  favorites overflow). Verify the four invariants by feel:
  - Slow vertical drag scrolls favorites, never opens AppDrawer or
    notifications.
  - Fast swipe always triggers the gesture, regardless of scroll
    position.
  - Slow horizontal drag scrolls nothing (ScrollView only takes
    vertical), no gesture fires.
  - Fast horizontal swipe triggers the configured swipe-app.
- Tune the three constants (`minSwipeDistancePx`,
  `minVerticalVelocityPxPerMs`, `dominanceFactor`) if any direction
  feels too sensitive or too unresponsive. Per-direction tuning is
  acceptable — natural thumb motion is asymmetric (down is easier
  than up).

### Step 6 — Deactivate split mode (cleanup deferred)

Decision (see §8): split-mode code stays physically in place during
this migration. It is only deactivated, not removed. A separate
later branch handles the actual cleanup in one go.

In the migration commit:

- `_needsSplit` is hardcoded to emit `false` permanently (or the
  conditional in HomeFragment.kt:940–987 is forced to take the
  no-split branch).
- The split-mode setup block becomes unreachable but stays in the
  source.
- `NonInterceptingScrollView.allowIntercept = false` permanently;
  the dual-mode field stays.
- The `gestureZone` view stays in `fragment_home.xml` with
  `isVisible = false` permanently.
- `LayoutDelegate`'s split-mode computation stays.
- `ScrollViewBorderDecorator` stays (decision: removed in cleanup).
- `HomeFragmentRobolectricTest` and any `*-de` strings tied to
  split mode are reviewed in the cleanup branch, not here.

The cleanup branch later removes: `_needsSplit`, the
`LayoutDelegate` split bits, `gestureZone` from XML, the dual-mode
in `NonInterceptingScrollView` (replaced with plain `ScrollView`),
and `ScrollViewBorderDecorator`.

### Step 7 — Branch + commit + ff-merge + push

Per the user's solo workflow: branch (suggested name
`refactor/home-gesture-wrapper`), commit per step where it makes
sense as a unit (probably one commit for the new layout class +
test, one for the HomeFragment wiring + XML, one for the cleanup),
ff-merge to main, push, ask the user before deleting the branch.

---

## 7. Risk register

| Risk | Mitigation |
|---|---|
| `GestureDetector` and the velocity-based detector double-fire on the same touch sequence. | Use the `triggered` flag to gate: once the velocity detector triggers, ignore subsequent `GestureDetector` callbacks within the same gesture. In practice the gestures are mutually exclusive (a fast swipe can't also be a long-press), but defensive gating is cheap. |
| Tuning constants from the AppDrawer don't feel right on the home screen for some direction. | Allow per-direction velocity / distance constants. Tune on real device. The numeric values should be class constants with explanatory KDoc, mirroring the style in `SwipeDownDismissLayout`. |
| Wallpaper edit mode collides with the new wrapper. | Either (a) the wrapper's `gesturesEnabled = false` is toggled when entering edit mode, or (b) the long-press callback does the carve-out as shown in §6 step 3. The existing `wallpaperTouchInterceptor` view in the layer above (fragment_home.xml line 130) already handles the visual edit overlay; the wrapper sits below it. Verify there's no cross-talk before settling on the approach. |
| Removing the split mode changes accessibility / TalkBack semantics. | TalkBack interaction with custom touch is historically fragile. Add an a11y smoke test (or at minimum a manual TalkBack walk-through on real device) before considering the migration done. |
| Notification panel test cannot observe the system effect. | Add a test-only callback hook on `HomeGestureLayout` (or a `MutableSharedFlow<Unit>` exposed for testing) that fires whenever a gesture triggers, separate from the user-facing callback. The test asserts on the test-hook flow, not on the system-side effect. |
| `LayoutDelegate` (or whichever class today computes `_needsSplit`) has other consumers besides HomeFragment. | Grep for `_needsSplit` and `needsSplit` before deleting. If there are other consumers, the StateFlow must stay (or become a constant `false`); only the HomeFragment-side usage goes away. |
| `ScrollViewBorderDecorator.kt` was tied to split mode. | The decorator was originally added/removed conditionally on entering/leaving split mode (HomeFragment.kt:963: `borderDecorator.remove(binding.favoritesScrollView)`). With no split mode, decide whether the border should be permanent or removed entirely. Probably permanent if visible borders are still desired around the scrollable favorites; otherwise remove the decorator class too. |

---

## 8. Decisions made (2026-05-07)

The five questions left open in the original draft of this
document have been settled. Recorded here so a future session
doesn't re-litigate them.

1. **`HomeGestureLayout` and `SwipeDownDismissLayout` stay
   separate.** No premature unification. Once `HomeGestureLayout`
   is proven on the home screen, replacing `SwipeDownDismissLayout`
   with the same wrapper is a candidate follow-up — but explicitly
   not part of this migration.

2. **One shared velocity threshold for all four directions.**
   Start with the SwipeDownDismissLayout values
   (`scaledTouchSlop * 4`, `1.2f` px/ms, `1.5f` dominance). Only
   split into per-direction constants if real-device feel turns
   out uneven during §6 step 5.

3. **Nullable per-gesture callbacks, no `gesturesEnabled`
   boolean.** Each callback is null by default; the Fragment
   wires the ones it wants. Wallpaper-edit-mode toggles the
   four swipe callbacks + `onDoubleTap` to `null` while keeping
   `onLongPress` wired (the long-press callback handles its
   dual behavior — exit edit mode vs. open settings — internally).

4. **Cleanup of split-mode dead code is a separate later
   branch.** The migration commit only deactivates split mode
   (see §6 step 6 for what stays). Removal of `_needsSplit`,
   `gestureZone`, the `NonInterceptingScrollView` dual-mode,
   `LayoutDelegate` split-mode bits, and `ScrollViewBorderDecorator`
   happens in a follow-up branch after real-device validation.

5. **Reuse `SwipeGestureAnalyzer`.** Don't internalize the
   analysis logic into the wrapper. The concrete shape — call
   into the existing analyzer with deltas-derived velocities,
   or generalize the analyzer's interface so both layouts can
   share it — is decided during implementation (read both
   call sites end-to-end first). JVM tests in
   `SwipeGestureAnalyzerTest` stay relevant.

**Additional decisions captured during the same review:**

6. **Locking-in-progress check moves into `viewModel.onFling*`
   methods**, not the wrapper-callback site in HomeFragment. Per
   Rule 10 — JVM-testable, centralizes the gate, future callers
   inherit it. See §6 step 3.

7. **Branch-first applies to the migration itself.** The
   migration uses `refactor/home-gesture-wrapper` (or a name
   confirmed at session start). The cleanup follow-up gets its
   own branch.

---

## 9. References

Code:

- `app/src/main/java/com/github/reygnn/kolibri_launcher/ui/home/HomeFragment.kt`
  (lines 322, 940–987, 1380–1465 are the relevant call sites)
- `app/src/main/java/com/github/reygnn/kolibri_launcher/ui/home/NonInterceptingScrollView.kt`
- `app/src/main/java/com/github/reygnn/kolibri_launcher/ui/home/SwipeGestureAnalyzer.kt`
- `app/src/main/java/com/github/reygnn/kolibri_launcher/ui/main/delegate/GestureDelegate.kt`
- `app/src/main/java/com/github/reygnn/kolibri_launcher/ui/util/ScreenLockAccessibilityService.kt`
- `app/src/main/java/com/github/reygnn/kolibri_launcher/ui/appdrawer/SwipeDownDismissLayout.kt`
- `app/src/main/res/layout/fragment_home.xml`

Tests:

- `app/src/androidTest/java/com/github/reygnn/kolibri_launcher/ui/appdrawer/AppDrawerSwipeDismissTest.kt`
- `app/src/androidTest/java/com/github/reygnn/kolibri_launcher/ui/appdrawer/AppDrawerFragmentSearchTest.kt`
  (parallel reference for the `currentResumedActivity` helper and
  pre-warm pattern)
- `app/src/test/java/com/github/reygnn/kolibri_launcher/ui/SwipeGestureAnalyzerTest.kt`
  (existing JVM tests, may stay or go depending on §8 question 5)

Convention docs:

- `CLAUDE.md` Rule 10 (testable logic outside Android-runtime
  classes) and the `androidTest/` policy.
- `app/src/test/java/com/github/reygnn/kolibri_launcher/TESTING_CONVENTIONS.kt`,
  particularly the "ROBOLECTRIC + HILT — ACTIVITY / FRAGMENT TESTS"
  section. The `HomeGestureLayoutTest` is instrumented (not
  Robolectric) because of the touch-pipeline reasons in §4.1, but
  the broader Hilt setup mirrors the patterns in that section.
- `HISTORY.md` — context for why the gesture / Fragment story is
  what it is (the 500-androidTests deletion event, the recent
  Espresso revival).

---

## 10. Estimated effort

3–4 days of focused work, with the time roughly distributed as:

- New layout class (~200–250 lines) and unit-level checks: 1 day
- Layout XML migration + HomeFragment wiring + initial Robolectric
  smoke verification: 0.5 day
- 10–12 instrumented tests (5 gestures × {trigger, pass-through} +
  sanity tests): 1 day
- Real-device validation and constant tuning across the four
  swipe directions: 0.5 day
- Cleanup (NonInterceptingScrollView, `_needsSplit` removal, border
  decorator): 0.5 day

The branch should remain open and the split-mode code path should
remain reachable (e.g., behind a settings toggle or `BuildConfig.DEBUG`
flag) until the real-device validation passes. Only delete the old
code path after it's been demonstrably unused for the validation
period.
