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
- Detects all home gestures (4 swipes + long-press)
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

`viewModel.onLongPress()` routes through its own use case (settings open).

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

Four user-facing gestures on the home screen (numbered for test
mapping):

| # | Gesture | Backend call | Effect |
|---|---|---|---|
| 1 | Swipe up | `viewModel.onFlingUp()` → `GestureDelegate.onFlingUp()` | Open AppDrawer |
| 2 | Swipe right (left→right finger motion) | `viewModel.onSwipeFromLeftToRight()` | Launch configured swipe-app |
| 3 | Swipe left (right→left finger motion) | `viewModel.onSwipeFromRightToLeft()` | Launch configured swipe-app |
| 4 | Long press | `viewModel.onLongPress()` (or wallpaper edit-mode exit, conditional) | Open settings (default) |

Swipe **down** is no longer a home gesture: the accessibility-service
subsystem that backed it (swipe-down → open notifications) has been
removed entirely, along with `onFlingDown()`, `RequestNotificationsUseCase`,
and `ScreenLockAccessibilityService`. The wrapper still discriminates a
downward swipe physically, but no callback is wired to it, so it is a
no-op on the home screen. (The AppDrawer's own `SwipeDownDismissLayout`
— swipe-down *inside* the drawer to dismiss — is a separate class and is
unaffected.)

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

### Step 2 — Update layout XML [DONE 2026-05-07]

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

**Implementation notes (2026-05-07):**

- **Wrapping strategy diverged from the original Step 2 plan**
  because the §8 decision 4 update (split-mode code stays
  physically in place during this migration) made it impossible to
  drop `favoritesContainer` / `gestureZone`. Instead of replacing
  `favoritesContainer` with `HomeGestureLayout`, the wrapper sits
  one level higher: it wraps `rootLayout` (the entire content
  layer between Layer 0 wallpaper and Layer 2 edit overlay).
  `favoritesContainer` and `gestureZone` are kept exactly as they
  were so the dead split-mode setup block remains compilable and
  reachable on paper. The cleanup branch (§6 step 6) removes them
  in one go.
- **`rootLayout` changed from `match_parent` to `0dp + constraints`**
  because `HomeGestureLayout` extends `ConstraintLayout` and its
  child uses the idiomatic constraint-based positioning (mirrors
  `fragment_app_drawer.xml` which wraps its children in
  `SwipeDownDismissLayout`). Visual layout result is identical
  (rootLayout still fills the wrapper); the change is XML-mechanical.
- **Wrapper id is `homeGestureRoot`, not `home_gesture_root`.**
  Camel-case matches the rest of the project's id naming
  (`favoritesScrollView`, `wallpaperEditOverlay`, etc.). The doc
  said "or similar"; this is the local convention.

### Step 3 — Wire up in HomeFragment [DONE 2026-05-07]

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
  binding.homeGestureRoot.onLongPress   = {
      if (viewModel.isWallpaperEditMode.value) viewModel.onSetWallpaperEditMode(false)
      else viewModel.onLongPress()
  }
  ```

**Implementation notes (2026-05-07):**

- **Step-2-and-Step-3 collapsed into one commit.** The original
  Step 7 plan in §6 already grouped XML and HomeFragment wiring
  together. Doing them sequentially would have created an
  incoherent intermediate state where two gesture detectors
  (the old `binding.rootLayout.setOnTouchListener` + the new
  `HomeGestureLayout.dispatchTouchEvent`) both fired on every
  touch. Combined commit avoids the conflict entirely.
- **Wallpaper-edit-mode gating uses null-toggling via Observer 8.**
  The doc's §3 decision was "nullable per-gesture callbacks; edit
  mode toggles non-long-press to null". Concrete shape:
  `setupHomeGestures()` wires all six callbacks at Fragment-create
  time (the long-press one with the dual-behavior body internally).
  A new helper `applyWallpaperEditModeToGestures(isEditMode)` is
  invoked from Observer 8 (the existing wallpaper-edit-mode
  observer). On entry to edit mode it nulls the four swipes plus
  double-tap; on exit it re-wires them via
  `wireDirectionalGestureCallbacks()`. Long-press stays wired
  always; its body branches internally based on
  `viewModel.isWallpaperEditMode.value`.
- **`gestureDetector` field stayed as vestigial `var ... = null`**
  even though no code path writes to it any more. Removing it
  entirely would have required a small surgery on the dead
  split-mode setup block (lines 953–958, which references
  `gestureDetector?.onTouchEvent`) — and §8 decision 4 says split
  code stays physically in place. Keeping the field as a
  vestigial null is the minimum-surgery option; the cleanup
  branch removes both the field and the dead block in one go.
  Same reasoning for the `gestureDetector = null` line in the
  teardown block (line 1954).
- **`swipeAnalyzer` field deleted.** Unlike `gestureDetector`,
  it was only referenced by `setupGestures` /
  `createGestureListener` themselves (now deleted), so removing
  it is clean. `HomeGestureLayout` has its own internal
  `SwipeGestureAnalyzer` instance with stricter calibration —
  the AppConstants-tuned analyzer field has no remaining caller.
- **Split mode is deactivated by hardcoding the StateFlow assignment**
  in `checkAndEmitScrollState()` to `_needsSplit.value = false`
  (instead of `= shouldSplit`). The `shouldSplit` computation
  itself stays alive (still calls `splitModeCalculator.shouldSplit`
  with all the threshold logic), but the result is dropped. This
  is the minimum-surgery deactivation per §8 decision 4 —
  cleanup branch removes the `splitModeCalculator` call and the
  whole `LayoutDelegate` split-mode plumbing. All the code that
  reads `_needsSplit.value` (lines 1042 border decorator, 1762
  scroll-state verifier, observer 2 layout adjustment) just sees
  permanent `false` and short-circuits to the no-split branch.

### Step 4 — Tests [DONE 2026-05-07]

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

**Implementation notes (2026-05-07):**

- **Major reduction from 12 tests to 2.** Reading
  `app/src/androidTest/INSTRUMENTED_TESTING_NOTES.kt` (the canonical
  conventions for instrumented tests, separate from
  `TESTING_CONVENTIONS.kt`) surfaced rule 8: "keep this source set
  small. Each test must target something that JVM + Robolectric
  structurally cannot reach. If a test could pass as Robolectric,
  write it there instead — feedback loop is 50× faster." The
  doc's 12-test plan predates that file, and the two sources are
  in tension. Resolution: the notes file wins because it's the
  newer convention and the instrumented suite is meant to stay
  lean. The committed test file
  (`HomeGestureLayoutTest.kt`) ships exactly two tests:
  - `fastSwipeUpOnHome_opensAppDrawer` — end-to-end positive:
    Wrapper attached, FAST swipe-up classified as UP, `onSwipeUp`
    callback fires, `viewModel.onFlingUp()` → `GestureDelegate`
    → `UiEvent.ShowAppDrawer` → `MainActivity` collector →
    `nav.navigate(...)` → `apps_recycler_view` becomes visible.
    A single test exercises the whole wiring path that this
    migration introduces.
  - `slowDragUpOnHome_doesNotOpenAppDrawer` — pass-through
    negative: SLOW drag stays under the `1.2 px/ms` velocity
    threshold, analyzer returns IGNORED, `super.dispatchTouchEvent`
    forwards to the ScrollView, no callback fires, AppDrawer never
    opens.
- **Why the other 10 tests were dropped.** `swipeDown`, `swipeLeft`,
  `swipeRight` route to system-level effects (notifications via
  accessibility service, configured swipe-app launch) that have
  no observable side effect inside the app's view tree without
  adding a test-only callback hook to the wrapper — which would
  pollute the production API surface. The analyzer's
  direction-discrimination is already JVM-tested in
  `SwipeGestureAnalyzerTest`; the wrapper's wiring per direction
  is a one-line callback assignment that's symmetrical across all
  four. Per real-device validation in §6 Step 5, per-direction
  feel is verified by hand. Long-press and double-tap (system
  lock, customization dialog) are also dropped for similar
  reasons; the wallpaper-edit-mode carve-out for long-press
  belongs in a JVM/Robolectric test of `HomeFragment` rather than
  an instrumented one.
- **`Thread.sleep(1500)` in the negative test is intentional, not
  an anti-pattern.** Rule 7 in the testing notes forbids
  `Thread.sleep` for *synchronization* (waiting until a state
  becomes true). The negative test is the inverse — verifying
  that no nav transition fires within a window long enough that
  one would have completed if it were going to. There's no
  positive condition for `awaitUntil` to converge on, so a fixed
  window is the right idiom here. 1500 ms covers the
  `GestureDelegate.launchSafe` coroutine hop, the `UiEvent`
  SharedFlow emission, the MainActivity collector, the
  `FragmentManager.commit`, and the AppDrawer view inflation
  — a real misfire would surface well within this window.
  Annotated inline with this reasoning so a future reviewer
  doesn't "fix" it back to the rule.
- **VISIBLE_CENTER → TOP_CENTER, not TOP_CENTER → BOTTOM_CENTER**,
  per rule 11 in the testing notes (TOP_CENTER as origin lands
  at y=0 = system status bar window). Mirrors
  `AppDrawerSwipeDismissTest`'s pattern.
- **Tests revealed a real architectural bug in `HomeGestureLayout`;
  fix landed alongside Step 4.** First emulator run (Pixel 9a AVD)
  hit a UI-blocking misfire: every fast swipe was misinterpreted
  as a long-press, because `dispatchTouchEvent` only ever received
  ACTION_DOWN — the subsequent ACTION_MOVE / ACTION_UP events for
  the same gesture were routed away from the wrapper.

  **Root cause:** when a `ViewGroup.dispatchTouchEvent` returns
  `false` for ACTION_DOWN, the framework treats that branch as
  rejecting the gesture and routes every later event for that
  gesture to a different sibling or up to the grandparent.
  `HomeGestureLayout`'s ACTION_DOWN code path called
  `super.dispatchTouchEvent`, which routed to children. The
  immediate child is `NonInterceptingScrollView`, which is locked
  to `allowIntercept = false` per §6 step 6 — by design it does
  NOT claim DOWN. With no other clickable descendant in the swipe
  region, super returned `false`, the wrapper returned `false`,
  the parent `wallpaperContainer` excluded the wrapper from the
  rest of the gesture, and every subsequent ACTION_MOVE bypassed
  `dispatchTouchEvent` entirely. The embedded
  `GestureDetector`'s long-press timer (~500 ms after DOWN) then
  fired because nothing cancelled it, and the user saw the
  Customize dialog open instead of the AppDrawer.

  **Fix:** `HomeGestureLayout.dispatchTouchEvent` now claims
  ACTION_DOWN unconditionally (`return true` in the DOWN branch
  after `super.dispatchTouchEvent`). The grandparent then keeps
  the wrapper as the active branch for the rest of the gesture
  and every event flows through. Children still receive each
  event normally via `super.dispatchTouchEvent`; the override of
  the DOWN return value only affects what the *grandparent*
  thinks about us, not what the children get to handle.

  **Why `SwipeDownDismissLayout` doesn't need the same
  workaround:** its single child is a `RecyclerView` which always
  claims ACTION_DOWN, so its `super.dispatchTouchEvent` returns
  true and the original "claim only when triggered" logic
  works. That's an accident of the AppDrawer's contents, not a
  property of the dispatchTouchEvent pattern. Fix is documented
  inline in `HomeGestureLayout.dispatchTouchEvent`'s body.

  **Production impact had this not been caught:** every fresh
  install (empty favorites list, no clickable descendants under
  the wrapper at most touch points) would have shown the
  Customize dialog on every tap. JVM/Robolectric tests of the
  analyzer pass — the bug only manifests in the real touch
  pipeline. This is exactly the kind of dispatch-routing failure
  `INSTRUMENTED_TESTING_NOTES.kt` rule 8 says instrumented tests
  exist to catch.

- **Test results on Pixel 9a AVD: both pass.** Run via
  `./gradlew :app:connectedDebugAndroidTest`.

### Step 5 — Real-device validation [DONE 2026-05-07]

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

**In-progress notes (2026-05-07):**

- **First real-device round found a second bug: ScrollView never
  scrolls when favorites overflow.** Fast swipes worked, all four
  directions detected, gestures wired correctly — but slow vertical
  drag with an overflowing favorites list did nothing. Root cause:
  the original full-mode setup in
  `HomeFragment.adjustScrollViewWidth` deliberately set
  `customScrollView.allowIntercept = false` (plus `isScrollContainer
  / isClickable / isFocusable / isFocusableInTouchMode = false`)
  because the OLD design required touches to bubble up past the
  ScrollView so `rootLayout`'s `OnTouchListener` (the gesture
  detector) could see them. With the new `HomeGestureLayout`
  intercepting at parent level via `dispatchTouchEvent`, that
  bypass route is gone — but the migration commit left those flags
  at their full-mode `false` defaults. The ScrollView consequently
  refused to claim ACTION_DOWN, and Android's normal scroll
  pipeline never engaged. The instrumented `slowDragUpOnHome…`
  test missed it because the AVD launched with an empty favorites
  list (no overflow → nothing to scroll → nothing to detect).

  **Fix:** in the post-deactivation `else` branch (the only one that
  ever runs now that `_needsSplit` is forced false), all five flags
  are flipped to `true`, matching what split mode used to set on
  its scrollable side. `setOnTouchListener(null)` stays — the
  ScrollView uses its own `onTouchEvent` for scroll handling. The
  `_needsSplit` deactivation in §8 decision 4 still holds; only
  the hardcoded `false` ScrollView flags were wrong.

  **Lesson for the test budget rationale in Step 4:** dropping the
  positive scroll-pass-through test (`swipeUpSlow_scrollsFavorites…`
  in the original 12-test plan) was a mistake. The negative test
  I kept (`slowDragUpOnHome_doesNotOpenAppDrawer`) only verifies
  that the wrapper does not misfire on slow drags — it doesn't
  verify the ScrollView still scrolls. A "favorites scroll on slow
  drag with overflowing content" test belongs in `androidTest/`,
  not just real-device validation; deferring it to the cleanup
  branch (because of the test-fixture cost of populating real
  resolvable favorites at install time) is acceptable but the gap
  exists.

  **Status of round 2:** new APK pushed; user found two more bugs
  in real-device validation.

- **Round-2 bug A: ScrollStateVerifier kept undoing the scroll
  fix.** The `allowIntercept = true` in `adjustScrollViewWidth`'s
  else-branch was correct, but `verifyAndFixScrollState` runs
  periodically (called from six `safePost { scheduleScrollVerification() }`
  sites — onResume, layout listeners, render-end, etc.) and uses
  `ScrollStateVerifier.verify` which says: `!currentSplitState &&
  allowIntercept` → `FixFullMode` → forces `customScrollView.allowIntercept = false`.
  In the new wrapper world that expectation is INVERTED: split
  mode permanently off, allowIntercept must stay true. So every
  periodic verification silently undid the scroll fix within
  milliseconds. Fix: the `FixFullMode` branch is now a no-op
  (logs a debug line, doesn't write the flag); the verifier still
  runs but its only writer for the post-deactivation case is
  defanged. The whole verifier path is removed by the cleanup
  branch.

- **Round-2 bug B: long-press on a favorite triggered both the
  app-context-menu and the customization dialog.** The wrapper's
  embedded `tapDetector` was being fed every event unconditionally,
  so it scheduled its own long-press timer in parallel with the
  favorite button's own long-click pipeline. Both ~500ms timers
  fired, both dialogs opened. Long-press on empty space (no
  clickable descendant) produced only the wrapper's customization
  dialog — correct. Fix: track whether `super.dispatchTouchEvent`
  on ACTION_DOWN returned true (a descendant claimed the gesture).
  If yes, skip feeding `tapDetector` for the rest of the gesture.
  If no (empty-space touch), the wrapper's tap detector stays the
  sole long-press / double-tap surface, preserving the
  Customize-on-empty-space and double-tap-to-lock behaviors. The
  swipe analyzer in the MOVE branch is unaffected — it always
  runs regardless of who claimed DOWN, so a fast swipe that
  starts on a favorite still triggers the wrapper's
  directional callbacks (and the favorite's own click is
  cancelled by the synthesized ACTION_CANCEL in
  `cancelChildGesture`).

  **Status of round 3:** v3 APK pushed; user found a finer
  refinement issue — the favorite buttons were full-width
  (`MATCH_PARENT`), so any touch in the row claimed by the button
  even outside the visible text. After discussion the design also
  changed: the user no longer wants long-press on home to
  double-mean "context menu (on favorite) vs. settings (in void)".
  Long-press now has *one* meaning per zone — never a global
  customization gesture in normal mode. Round-4 fix below.

- **Round-4 fix: text-only button hit area + true-isLongClickable
  hit test.** Two changes together:
  1. Favorite-button `layoutParams.width` flipped from
     `MATCH_PARENT` to `WRAP_CONTENT`. The `gravity = START |
     CENTER_VERTICAL` already aligned the text to the row's
     leading edge; now the button's hit-test region only covers
     the actual text. `maxLines = 1` + `ellipsize = END` continue
     to handle long names by stretching to the available row
     width as needed.
  2. The wrapper's `childClaimedDown` was sourced from
     `super.dispatchTouchEvent`'s consumed signal. That signal is
     `true` whenever the ScrollView claims DOWN itself — and
     ScrollView's `onTouchEvent` always returns `true` on DOWN
     when it has children, regardless of whether any child
     claimed. Result: the wrapper's tap detector was suppressed
     in the empty space inside the scroll viewport too, exactly
     where the user expects long-press to fire the wrapper's
     customization dialog. Replaced with a manual hit-test
     `hasLongClickableDescendantAt(x, y)` that walks the visible
     view tree from the wrapper downward, mirroring
     `ViewGroup.dispatchTouchEvent`'s axis-aligned hit-test, and
     returns true only when a view on the path has
     `isLongClickable = true`. Setting `setOnLongClickListener`
     toggles `isLongClickable` to true, so favorite buttons
     register as suppressors; the ScrollView, the LinearLayouts,
     the (clickable-but-not-long-clickable) clock/date/battery
     TextViews, and the empty appList areas all register as
     non-suppressors and let the wrapper's tap detector run.

  **Status of round 4:** the v4 build was prepared but never
  shipped — during the pre-push review the user pushed back on
  using long-press for two semantically different actions
  (favorite app-context-menu and global customization-options
  dialog). The settings gesture moved instead.

- **Round-5 redesign: long-press settings moves to the
  top-status-area zone.** The earlier dual-meaning long-press
  ("on favorite = app menu, elsewhere = customize") was
  re-evaluated after the user installed v4-prep on a real device
  and found it unintuitive. New rule: long-press never means
  customize as a *global* home gesture. Customize fires only when
  the long-press lands inside the top status row (clock + date +
  battery + the empty horizontal space at that level). On a
  favorite, long-press means app-context-menu (Android-list
  standard, kept). Anywhere else on home (between the status row
  and the favorites list, beside a short favorite, in empty
  scroll space below the favorites), long-press is a no-op.

  Implementation (cumulative; the v4 changes from round 4 stay
  in place — text-only favorite buttons + the manual hit-test
  `hasLongClickableDescendantAt` in `HomeGestureLayout`):

  1. `fragment_home.xml`: `timeContainer` width flipped from
     `wrap_content` to `match_parent`. The contained TextViews
     remain `wrap_content` with start gravity, so the visible
     layout is unchanged — but the LinearLayout's hit-test region
     now spans the full row width, picking up the empty horizontal
     space to the right of the text. (`gravity = start` already in
     place; redundant for `wrap_content` children but harmless.)
  2. `HomeFragment.setupTopAreaLongPressForSettings`: new method,
     called from `onViewCreated` after `setupDoubleTapActions`. It
     wires `setOnLongClickListener { viewModel.onLongPress(); true }`
     on `timeContainer`, `timeText`, `dateText`, and `batteryText` —
     all four because each TextView is `clickable = true` (from the
     existing DoubleClickListener for OpenClock / OpenCalendar /
     OpenBatterySettings) and consumes ACTION_DOWN itself, so a
     long-click set on the parent alone wouldn't fire when the
     touch lands directly on a TextView. The four-listener fan-out
     covers every settings hit zone uniformly.
  3. `HomeFragment.setupHomeGestures`: the wrapper's `onLongPress`
     wiring is removed. In normal mode the wrapper's tap detector
     never has a long-press callback to invoke (its 500 ms timer
     still ticks but produces no UI side effect).
  4. `HomeFragment.applyWallpaperEditModeToGestures`: in edit
     mode, `onLongPress` is set to `{ viewModel.onSetWallpaperEditMode(false) }`
     so the user can still leave the overlay by holding their
     finger somewhere the `wallpaperTouchInterceptor` doesn't
     consume. On exit-edit-mode the callback is set back to null.
     This carve-out is the only place where the wrapper's
     long-press fires in normal usage.

  The wrapper's hit-test still suppresses double-fire: the
  `timeContainer` and the four TextViews now have
  `isLongClickable = true` (from `setOnLongClickListener`), so
  `hasLongClickableDescendantAt` returns true at any touch in the
  status row, the wrapper's tap detector is skipped, only the
  TextView's / timeContainer's own long-click listener fires.

  **Status of round 5:** APK (`0.99.63-wrapper.2` / 83) prepared;
  awaiting re-validation.

- **Round-6: long-press in clock-area + on favorite text confirmed
  working; swipe-up triggers reliably; scroll works on overflow.**
  User signed off on Step 5 in principle: long-press semantics are
  unambiguous per zone, the wrapper's directional analyzer fires
  correctly on swipe-up, the favorites ScrollView scrolls on slow
  drags. Double-tap and swipe-down were not validated in this
  round because the accessibility service couldn't be enabled
  (Android refuses for debug-signed installs after a re-install
  arc); they'll be exercised after the migration ships through
  the regular release-signed build path.

  The boundary feel between swipe-up and scroll is described as
  "optimization-worthy" — see TODO §20 (Gesture/Scroll Tuning UI).
  Hardcoding per-direction values for "the average user" was
  rejected as the wrong direction; instead, the three wrapper
  constants (`minSwipeDistancePx`, `minVelocityPxPerMs`,
  `dominanceFactor`) will eventually become user-tunable in a
  dedicated settings UI with live-preview sliders. The migration
  ships with the SwipeDownDismissLayout-validated defaults
  (`scaledTouchSlop * 4`, `1.2 px/ms`, `1.5x`); the tuning UI is
  a separate follow-up branch, not part of this work.

### Step 6 — Deactivate split mode (cleanup deferred) [DONE 2026-05-07]

Decision (see §8): split-mode code stays physically in place during
this migration. It is only deactivated, not removed. A separate
later branch handles the actual cleanup in one go.

In the migration commit:

- `_needsSplit` is hardcoded to emit `false` permanently (or the
  conditional in HomeFragment.kt:940–987 is forced to take the
  no-split branch).
- The split-mode setup block becomes unreachable but stays in the
  source.
- `NonInterceptingScrollView.allowIntercept = true` permanently
  (the migration commit initially set this to `false` mirroring the
  legacy full-mode setup, but that broke scroll-on-overflow — see
  Step 5 in-progress notes for the diagnosis); the dual-mode field
  stays until cleanup.
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

**Cleanup-branch implementation notes (2026-05-07):**

The cleanup ran on branch `chore/remove-split-mode-deadcode`,
ff-merged into `main` directly after the migration. Aggressive
scope (per user decision): the backup format also drops
`splitModeThreshold` — old backups that still carry the field
import correctly because `BackupSettings.splitModeThreshold` was
nullable-with-default, so the JSON deserializer just ignores
unknown / missing properties.

What was actually removed:

- HomeFragment fields: `_needsSplit`, `needsSplit`,
  `_orientationState`, `orientationState`, `wasInSplitMode`,
  `gestureDetector`, `verifyJob`, `splitModeCalculator`,
  `splitWeightCalculator`, `scrollStateVerifier`,
  `borderDecorator`, `orientationSynchronizer`.
- HomeFragment methods: `checkAndEmitScrollState`,
  `verifyAndFixScrollState`, `scheduleScrollVerification`,
  `safePost`, `checkScrollStateAfterNextLayout`,
  `checkAndSyncOrientation`, `adjustScrollViewWidth`. Six
  `safePost { scheduleScrollVerification() }` callsites and the
  `verifyAndFixScrollState()` call in `onResume` are gone.
  `onConfigurationChanged` shrunk to a one-line
  `lastSpacingInput = null` cache invalidation.
- Observers 2 (needsSplit + orientation → adjustScrollViewWidth)
  and 6 (splitModeThreshold → checkScrollStateAfterNextLayout)
  in `observeViewModel`.
- Eight Kotlin classes deleted: `SplitWeightCalculator`,
  `SplitModeCalculator`, `ScrollStateVerifier`, `VerifyResult`,
  `ScrollViewBorderDecorator`, `OrientationSynchronizer`,
  `SyncResult`, `NonInterceptingScrollView`. Their five JVM
  test files also gone (the contract test for OrientationSynchronizer
  was the only multi-Test one).
- XML: `<View id="gestureZone" />` removed; the favorites
  ScrollView is now a plain `<ScrollView>` with `match_parent`
  width (no more `weight=1`/`0dp` semantic).
- Domain layer: `GetSplitModeThresholdUseCase` + its test deleted;
  `SettingsRepository.splitModeThresholdFlow` /
  `setSplitModeThreshold(...)` removed from the interface;
  `SettingsRepositoryImpl` removed the corresponding flow,
  setter, DataStore key declaration (`PreferenceKeys.SPLIT_MODE_THRESHOLD`),
  and the `purgeRepository` `remove(...)` line.
- `LayoutDelegate.splitModeThreshold` and the
  `getSplitModeThresholdUseCase` constructor parameter; same on
  `LauncherViewModel`'s pass-through getter and constructor wiring.
- `BackupData.BackupSettings.splitModeThreshold` field removed;
  `BackupSerializer` no longer reads/writes the JSON key
  (`mergeWithStrictValues` and `parseStrictly`); `BackupDataAssembler`
  no longer reads from / writes to the repository for the field;
  `hasPowerUserSettings` flag in the export-summary now only
  considers `secureWindow` and `rotationLocked`.
- `SettingsFragment` lost its
  `splitModeThresholdPreference` field, `setOnPreferenceChangeListener`
  block, the flow-collection observer, and the teardown nulls.
- `app/src/main/res/xml/preferences.xml` lost the
  `<EditTextPreference android:key="split_mode_threshold" />`
  entry. Both `values/strings.xml` and `values-de/strings.xml`
  lost the eight split-mode strings each (title, summary,
  dialog title, dialog message, three desc variants, invalid
  toast, saved toast).
- `AppConstants` lost: `LANDSCAPE_SPLIT_SCROLL_WEIGHT`,
  `LANDSCAPE_SPLIT_GESTURE_WEIGHT`, `PORTRAIT_SPLIT_SCROLL_WEIGHT`,
  `PORTRAIT_SPLIT_GESTURE_WEIGHT`, `SPLIT_MODE_THRESHOLD_MIN`,
  `SPLIT_MODE_THRESHOLD_MAX`, `SPLIT_MODE_TINKERING_LIMIT`,
  `DEFAULT_SPLIT_MODE_THRESHOLD`, `PrefKeys.SPLIT_MODE_THRESHOLD`.
- `FakeSettingsRepository` and `SettingsRepositoryContract` lost
  their respective `splitModeThreshold` plumbing (the contract
  used to host five clamping tests; all five gone).
- 14 test files in `:app:test` and `:data:test` had stale refs
  removed; 43 tests deleted entirely (those whose only purpose
  was verifying split-mode behavior); 30+ tests had only the
  split-mode-specific lines stripped while keeping the test for
  the rest of its assertions. `TESTING_CONVENTIONS.kt` lost its
  example mention.
- `TODO.md` §19 (`LayoutDelegate.splitModeThreshold`
  cold-`.value`-read on `WhileSubscribed`-StateFlow) removed —
  the underlying flow no longer exists.

Backup-format change is the only externally observable effect:
Kolibri-Launcher backups produced from `0.99.64` and onwards no
longer carry the `split_mode_threshold` JSON property. Older
backups (from `v0.99.62-stable` and earlier) still import cleanly
— the field is silently dropped during JSON deserialization
because every `BackupSettings` field is `Int? = null` etc.

### Step 7 — Branch + commit + ff-merge + push [DONE 2026-05-07]

Per the user's solo workflow: branch (suggested name
`refactor/home-gesture-wrapper`), commit per step where it makes
sense as a unit (probably one commit for the new layout class +
test, one for the HomeFragment wiring + XML, one for the cleanup),
ff-merge to main, push, ask the user before deleting the branch.

**Implementation notes (2026-05-07):**

- **ff-merge landed cleanly into `main`** at commit `f9c0b48`.
  Branch `refactor/home-gesture-wrapper` had 13 commits between
  `5ee9f6c` (the original handover doc) and `f9c0b48` (the final
  version bump): the design-and-decisions doc, the wrapper class
  + analyzer parameterization (Step 1), the XML + HomeFragment
  wiring + GestureDelegate gate (Step 2+3), the instrumented test
  (Step 4), the ACTION_DOWN-claim fix (round 1), the verifier
  defang + first long-press double-fire fix (round 2), the
  text-only-buttons + hit-test (round 3), the long-press-redesign
  to clock-area (round 5), and the docs/release-version finalize
  pair (Step 5 done + version bump to 0.99.64 / 84). Cleanup
  starts in a separate branch (Step 6).
- **Wrapper-test pre-release deleted** with `gh release delete
  wrapper-test-2026-05-07 --cleanup-tag --yes` after the merge
  landed. The five APK iterations served their purpose during the
  real-device validation arc; production users won't see the tag.

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
