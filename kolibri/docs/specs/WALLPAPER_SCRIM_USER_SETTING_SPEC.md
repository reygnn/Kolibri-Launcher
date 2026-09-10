# WALLPAPER_SCRIM_USER_SETTING_SPEC

Implementation plan for a **user-controlled home-screen wallpaper scrim** — a
single, opt-in, default-off dim that the user dials in to rescue legibility on
extreme wallpapers where the glyph outline alone is marginal.

> Status: **PLAN** (branch `feature/user-wallpaper-scrim`). Not yet implemented.
> Merge only on the maintainer's explicit command.

---

## 1. Goal & scope

### The problem this solves

The glyph outline (`ACCEPTED_LIMITATIONS.md §6`, `TextOutline` /
`OutlinedTextView` / `OutlinedButton`, 1.5 dp) is the legibility guarantee for
home text and it is *perfect for the common case*. It becomes **marginal** on a
narrow tail of extreme wallpapers — the reference case is a spatially bimodal
image (dark top half / near-white bottom half, hard boundary in the middle,
e.g. the maintainer's `Screenshot_20170917-135008.png`). There the AUTO
classifier's global light/dark answer sits on the luminance fence (median ≈ 0.5)
and whichever single colour it picks, one screen half fights it; on the white
half the thin dark outline is the *only* thing carrying white text, and it is
"fast nicht mehr" enough.

### The fix

A **user-set, opt-in, default-0 uniform black scrim** between the wallpaper and
the home content. The user dials in a *minimal* dim only when they run such a
wallpaper.

**Why uniform is enough (the asymmetry — this is the whole design):** a black
scrim of alpha α maps background luminance `L → L·(1−α)`. Darkening dampens
*absolutely* more where the background is bright:

| region                | L (approx) | at α = 0.20 | Δ absolute |
|-----------------------|-----------|-------------|-----------|
| dark top half         | 0.20      | 0.16        | −0.04     |
| near-white bottom half| 0.90      | 0.72        | −0.18     |

So a *minimal* uniform scrim disproportionately rescues the problem zone (the
bright half, where white text needs help) and leaves the already-good dark half
visually almost untouched. No gradient, no per-region sampling, no
luminance-driven target needed — the asymmetry does the work for free.

**The benefit is directional — it aids LIGHT text, not dark.** A uniform black
scrim uniformly *worsens* black-on-dark contrast. That is fine for the reference
case (white text over the bright bottom half) and harmless on the dark half at
minimal α, but because the scrim is deliberately decoupled from the classifier
(§1 non-goals), a *large* scrim on a median-≈0.5 wallpaper where the advisory
classifier picked black text would actively degrade the dark region. This is
another reason the value is a small user-set reserve, default 0 — not a large or
automatic dim. State this honestly; do not oversell it as a universal legibility
guarantee (the outline is the guarantee; the scrim only eases the light-text
case).

### Non-goals (explicit)

- **NOT** the reverted 2026-08-21 approach. That scrim was **automatic and
  luminance-driven** — it forced a *global luminance target* and over-darkened
  an already-dark wallpaper (the 93 %-black daily went muddy). See
  `ACCEPTED_LIMITATIONS.md §6` alt. 1 and `TODO.md §22`. This plan is the
  opposite: a dumb, manual, default-off value. The 93 %-black user leaves it at
  0 and nothing changes — the documented failure mode is structurally absent.
- **NOT coupled to the classifier.** The scrim value MUST NOT feed
  `ClassifyWallpaperUseCase` / `WallpaperBitmapLuminanceImpl` /
  `ObserveUiColorsUseCase`. That coupling *was* `WALLPAPER_LUMINANCE_BLEND_SPEC`
  (the failed 2445-line rewrite: `WallpaperContrastMath`, `ScrimState`,
  `PerceivedBackground`, `RegionLuminance`, region luminance). Legibility keeps
  being carried by the outline; the scrim only makes the outline's job easier.
- **NOT the AppDrawer.** The drawer paints its own solid surface via its own
  classifier path (`WallpaperSurface.toSurface`) and is untouched.
- **NO gradient, NO per-region contrast, NO contrast math, NO new domain
  models.** If any of these creep in, the plan has drifted back toward the
  reverted design — stop and re-read §1.

### What we deliberately keep from the old attempt

One lesson only: **bake the strength into the alpha byte of the background colour
and keep `View.alpha = 1f`.** Rationale on its own terms (the discarded
`ScrimRenderCalculator` was git-reset away and its backup tag deleted, so it is
not a live reference): a non-1 `View.alpha` on a full-screen leaf view forces the
renderer to allocate an offscreen `saveLayer` buffer to composite it; a solid
background colour whose alpha byte carries the strength composites directly with
no buffer. Everything else from the old attempt is discarded.

---

## 2. Design parameters (tunable — flagged for review)

| parameter | proposed value | rationale |
|-----------|---------------|-----------|
| `WALLPAPER_SCRIM_ALPHA_MIN` | `0.0f` | off |
| `WALLPAPER_SCRIM_ALPHA_MAX` | `0.5f` | headroom above the typical 0.15–0.25 band; still a "reserve", not a blackout |
| `DEFAULT_WALLPAPER_SCRIM_ALPHA` | `0.0f` | opt-in, default off — this is what keeps the §6/§22 failure mode absent |
| slider `stepSize` (XML) | `0.05f` | 10 steps across 0.0–0.5 |
| PrefKey string | `"wallpaper_scrim_alpha"` | matches the `PrefKeys` naming style |
| scrim colour | opaque black RGB, alpha baked from the setting | uniform dim |

The scrim is **home-only**. It is **hidden** (View `GONE`) when the effective
alpha is ≈ 0 **or** when wallpaper edit mode is active (so the user adjusts the
wallpaper against its true appearance — the old spec's S2 skip).

---

## 3. Which delegate owns the state

The value is a `SettingsRepository`-backed float surfaced in the
**LayoutCustomization dialog**, exactly like `layoutScale` / `verticalPadding` /
`contentTopMarginScale`. It therefore lives in **`LayoutDelegate`**, not
`WallpaperDelegate` (which owns wallpaper *files* and the edit session, not
persisted appearance prefs). HomeFragment already reads from both delegates via
`LauncherViewModel`, and gets the edit-mode gate from
`viewModel.isWallpaperEditMode` (`WallpaperDelegate`, re-exposed at
`LauncherViewModel.kt:287`).

---

## 4. The one piece of extractable logic (Rule 10)

Everything else is glue, but the alpha → render decision is a pure, worth-pinning
choice, so it goes in a small Android-free helper with a JVM test (mirroring the
old `ScrimRenderCalculator`, but ~10 lines, no `ScrimState`, no `TimberWrapper`):

**New file:** `app/src/main/java/com/github/reygnn/kolibri_launcher/ui/home/ScrimRender.kt`

```kotlin
package com.github.reygnn.kolibri_launcher.ui.home

import kotlin.math.roundToInt

/**
 * Pure render decision for the home wallpaper scrim (user-controlled dim).
 * Android-free so the skip logic + alpha→ARGB mapping are JVM-unit-testable
 * (Rule 10); the Fragment stays thin glue (HomeFragment.applyScrim).
 *
 * @return the opaque-black-tinted ARGB fill with the strength baked into the
 *   alpha byte (draw the View with View.alpha = 1f → no offscreen saveLayer),
 *   or null when the scrim must not draw at all (View GONE): edit mode, or a
 *   strength that rounds to a fully transparent byte.
 */
object ScrimRender {
    fun colorOrNull(alpha: Float, isEditMode: Boolean): Int? {
        if (isEditMode) return null
        val a = (alpha.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
        if (a == 0) return null
        return a shl 24 // opaque black RGB (0x000000), strength in the alpha byte
    }
}
```

**New test:** `app/src/test/java/com/github/reygnn/kolibri_launcher/ui/home/ScrimRenderTest.kt`
covering: `alpha = 0 → null`; `isEditMode = true → null` (even at max alpha);
`alpha = 0.2 → 0x33000000`-shaped (51 << 24); clamps out-of-range alpha; the
rounding boundary that produces `a == 0` returns null.

---

## 5. Every file to touch (grouped by layer, one-way chain `:domain → :data → :app`)

### 5.1 `:domain` — constants, interface, use cases, backup model

**A. `core/AppConstants.kt`**
- In the "Layout Defaults" group (near line 34–36): add
  `const val WALLPAPER_SCRIM_ALPHA_MIN = 0.0f` / `..._MAX = 0.5f`.
- In the defaults block (near line 48): add
  `const val DEFAULT_WALLPAPER_SCRIM_ALPHA = 0.0f`.
- In `object PrefKeys`, "Layout & Scaling" group (near line 228): add
  `const val WALLPAPER_SCRIM_ALPHA = "wallpaper_scrim_alpha"`.

**B. `domain/repository/SettingsRepository.kt`** (near line 27–28):
```kotlin
val wallpaperScrimAlphaStateFlow: Flow<Float>
suspend fun setWallpaperScrimAlpha(alpha: Float)
```

**C. `domain/usecase/LayoutSettingsUseCase.kt`**
- In `GetLayoutSettingsUseCase` (line 12–16): add
  `val wallpaperScrimAlpha: Flow<Float> = repository.wallpaperScrimAlphaStateFlow`.
- **Update the stale header comment** at line 8 (`// 1. Get Layout Settings (Alle
  5 Flows)`): it becomes 6 flows. Rewrite in English (a German rewrite trips
  `checkRule13`), e.g. `// 1. Get Layout Settings (all layout flows)`.
- New setter class beside `SetLayoutScaleUseCase` (line 20–22):
```kotlin
class SetWallpaperScrimAlphaUseCase @Inject constructor(private val repository: SettingsRepository) {
    suspend operator fun invoke(alpha: Float) = repository.setWallpaperScrimAlpha(alpha)
}
```

**D. `domain/model/BackupData.kt`** (near line 99, `val layoutScale: Float? = null`):
add the field to the settings backup model **with the snake_case alias every
sibling field carries** (line 98–99 shows `layoutScale` with `@JsonNames`):
```kotlin
@JsonNames("wallpaper_scrim_alpha")
val wallpaperScrimAlpha: Float? = null,
```
Omitting `@JsonNames` still round-trips (export writes the camelCase property,
import reads both via `getStrictFloat`) but breaks the model's
camelCase-write / snake_case-alias convention — add it to mirror `layoutScale`.

### 5.2 `:data` — impl (3 mandatory registration sites), backup read/write/parse

**E. `data/SettingsRepositoryImpl.kt`** — `floatPreferencesKey` already imported
(line 8). Four sites:
1. `object PreferenceKeys`, "Float Keys" group (near line 61): 
   `val WALLPAPER_SCRIM_ALPHA = floatPreferencesKey(AppConstants.PrefKeys.WALLPAPER_SCRIM_ALPHA)`
2. `ownedExactKeys()` (near line 88): add `PreferenceKeys.WALLPAPER_SCRIM_ALPHA.name,`
   — **enforced** by `checkConventions` (comment at lines 68–72); omit → build red.
3. `purgeRepository()` (near line 280): add
   `preferences.remove(PreferenceKeys.WALLPAPER_SCRIM_ALPHA)` — **enforced** by
   `tools/check-purge-completeness.awk`; omit → build red. (Not purge-exempt: a
   "reset settings" should return the scrim to 0.)
4. Flow + setter overrides (near line 226–230):
```kotlin
override val wallpaperScrimAlphaStateFlow: Flow<Float> =
    valueFlow(PreferenceKeys.WALLPAPER_SCRIM_ALPHA, AppConstants.DEFAULT_WALLPAPER_SCRIM_ALPHA)

override suspend fun setWallpaperScrimAlpha(alpha: Float) =
    putValue(PreferenceKeys.WALLPAPER_SCRIM_ALPHA, alpha)
```
`ownedKeyPrefixes()` is NOT overridden here and does NOT need touching (single
fixed key, not a dynamic family). No new `@IntoSet` binding: `SettingsRepositoryImpl`
is already bound in `RepositoryModule.kt:162`; registering in `ownedExactKeys()`
auto-wires storage-cleanup via `DataStoreMaintenanceRepositoryImpl`.

**F. `data/BackupDataAssembler.kt`** — three sites, mirroring layoutScale:
- Export read (near line 100): `val wallpaperScrimAlpha = settingsRepository.wallpaperScrimAlphaStateFlow.first()`
- Assemble into the model (near line 138): `wallpaperScrimAlpha = wallpaperScrimAlpha,`
- Restore (near line 317, mirroring the coerced `layoutScale` restore):
```kotlin
backup.settings.wallpaperScrimAlpha?.let {
    settingsRepository.setWallpaperScrimAlpha(
        it.coerceInSafe(AppConstants.WALLPAPER_SCRIM_ALPHA_MIN, AppConstants.WALLPAPER_SCRIM_ALPHA_MAX)
    )
}
```

**G. `data/BackupSerializer.kt`** — three sites:
- "has any settings" OR-chain (near line 161): add
  `backup.settings.wallpaperScrimAlpha != null ||`
- Strict-float parse (near line 323, mirroring layoutScale):
  `wallpaperScrimAlpha = settings.getStrictFloat("wallpaperScrimAlpha", "wallpaper_scrim_alpha") ?: base.wallpaperScrimAlpha,`
- Known-keys list (near line 390): add `"wallpaper_scrim_alpha"` alongside
  `"layout_scale", "vertical_padding_scale", ...`.

### 5.3 `:app` — delegate, ViewModel, dialog UI, home rendering, layouts

**H. `ui/main/delegate/LayoutDelegate.kt`**
- Constructor (near line 33–41): add `private val setWallpaperScrimAlphaUseCase: SetWallpaperScrimAlphaUseCase,`.
- State (mirror layoutScaleState, lines 45–55):
```kotlin
val wallpaperScrimAlphaState: StateFlow<Float> = getLayoutSettingsUseCase.wallpaperScrimAlpha
    .catch { e ->
        if (e is CancellationException) throw e
        TimberWrapper.silentError(e, "Error observing wallpaper scrim alpha")
        emit(AppConstants.DEFAULT_WALLPAPER_SCRIM_ALPHA)
    }
    .stateIn(
        scope = scope.coroutineScope,
        started = SharingStarted.Eagerly,
        initialValue = AppConstants.DEFAULT_WALLPAPER_SCRIM_ALPHA
    )
```
- Setter (mirror onSetLayoutScale, lines 107–114):
```kotlin
fun onSetWallpaperScrimAlpha(alpha: Float) = scope.launchSafe("Error setting wallpaper scrim alpha") {
    setWallpaperScrimAlphaUseCase(
        alpha.coerceInSafe(AppConstants.WALLPAPER_SCRIM_ALPHA_MIN, AppConstants.WALLPAPER_SCRIM_ALPHA_MAX)
    )
}
```
- `onResetLayoutSettings()` (near line 143–149): add
  `setWallpaperScrimAlphaUseCase(AppConstants.DEFAULT_WALLPAPER_SCRIM_ALPHA)`.

**I. `ui/main/LauncherViewModel.kt`**
- Add `SetWallpaperScrimAlphaUseCase` to the VM constructor (near lines 129–134).
- Pass into `LayoutDelegate(...)` (near lines 219–227).
- State pass-through (near line 280):
  `val wallpaperScrimAlphaState: StateFlow<Float> get() = layoutDelegate.wallpaperScrimAlphaState`
- Setter pass-through (near line 380):
  `fun onSetWallpaperScrimAlpha(alpha: Float) = layoutDelegate.onSetWallpaperScrimAlpha(alpha)`

**J. `res/layout/dialog_layout_customization.xml`** — add a label+slider+Space
triplet in the `LinearLayout` (e.g. after the top-margin block ~line 94):
```xml
<TextView
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:text="@string/layout_wallpaper_scrim"
    android:textAppearance="?attr/textAppearanceTitleMedium"
    android:textColor="?attr/colorOnSurface" />

<com.google.android.material.slider.Slider
    android:id="@+id/slider_wallpaper_scrim"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:valueFrom="0.0"
    android:valueTo="0.5"
    android:stepSize="0.05" />

<Space android:layout_width="match_parent" android:layout_height="@dimen/spacing_large" />
```
`valueFrom/valueTo` are also set from code (below); `stepSize` is XML-only.

**K. `ui/layoutcustomization/LayoutCustomizationDialogFragment.kt`**
- Config block in `setupControls()` (mirror lines 120–130):
```kotlin
binding.sliderWallpaperScrim.apply {
    valueFrom = AppConstants.WALLPAPER_SCRIM_ALPHA_MIN
    valueTo = AppConstants.WALLPAPER_SCRIM_ALPHA_MAX
    addOnChangeListener { _, value, fromUser ->
        safeRun("sliderWallpaperScrim.onChange") {
            if (fromUser) viewModel.onSetWallpaperScrimAlpha(value)
        }
    }
}
```
- `setupFadeOnTouch(binding.sliderWallpaperScrim)` (near line 187–189) for the
  dim-while-dragging UX consistency.
- Observer in `observeViewModel()` (mirror lines 225–231):
```kotlin
viewLifecycleOwner.lifecycleScope.launchSafe("observe.wallpaperScrim") {
    viewModel.wallpaperScrimAlphaState.collectLatest { alpha ->
        safeRun("apply.wallpaperScrim") { binding.sliderWallpaperScrim.value = alpha }
    }
}
```
- `onDestroyView` (near lines 88–89): add
  `binding.sliderWallpaperScrim.clearOnChangeListeners()` +
  `binding.sliderWallpaperScrim.clearOnSliderTouchListeners()`.
- The existing surface-tint sweep (`applyForegroundColorRecursive`, lines
  280–304) recolours the new label automatically — no extra wiring.

**L. `res/layout/fragment_home.xml`** — insert the scrim View between
`wallpaperView` (ends ~line 14) and the `HomeGestureLayout` content root
(`homeGestureRoot`, opens ~line 25). In a FrameLayout later children draw on
top, so this sits above the wallpaper, below content and the edit-overlay stub:
```xml
<!-- Layer 0.5: user-controlled home scrim. Above wallpaper, below content.
     Alpha baked into the background colour (View alpha stays 1 → no saveLayer).
     GONE unless the user set a non-zero scrim and not in wallpaper edit mode. -->
<View
    android:id="@+id/wallpaperScrim"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:visibility="gone" />
```

**M. `ui/home/HomeFragment.kt`**
- Add a private `applyScrim()` that reads both current values and renders via the
  pure helper (§4), teardown-safe:
```kotlin
private fun applyScrim() {
    val binding = _binding ?: return
    val color = ScrimRender.colorOrNull(
        alpha = viewModel.wallpaperScrimAlphaState.value,
        isEditMode = viewModel.isWallpaperEditMode.value,
    )
    if (color == null) {
        binding.wallpaperScrim.visibility = View.GONE
    } else {
        binding.wallpaperScrim.setBackgroundColor(color)
        binding.wallpaperScrim.visibility = View.VISIBLE
    }
}
```
- New `collectOnStarted` for the scrim value (its own block — it drives a View
  alpha, not the layout cache; do NOT fold into the layout `combine` at lines
  658–689, mirroring how favorites-alignment is collected separately):
```kotlin
collectOnStarted(
    flow = viewModel.wallpaperScrimAlphaState,
    errorTag = "wallpaperScrim",
    coroutineContext = Dispatchers.Main + fragmentExceptionHandler,
) { if (_binding != null) applyScrim() }
```
- Call `applyScrim()` from the existing edit-mode Observer 8 body (lines 620–641,
  which already handles `isWallpaperEditMode`) so entering/leaving edit mode
  re-evaluates the scrim.
- No `onDestroyView` line needed (plain View, no listeners; the collector is
  `viewLifecycleOwner`-scoped and auto-cancels).

### 5.4 Strings

**N. `res/values/strings.xml`** + **`res/values-de/strings.xml`** — add
`layout_wallpaper_scrim` to BOTH (parity enforced by
`tools/check-strings-parity.awk`). English "Wallpaper dimming" / German
"Hintergrund abdunkeln" (final wording TBD).

---

## 6. Tests

Two classes of test work: **new coverage** and **MANDATORY existing-test fixups**.
The fixups are NOT optional — because `LayoutDelegate` subscribes the new
`getLayoutSettingsUseCase.wallpaperScrimAlpha` flow *eagerly* at construction
(`stateIn(SharingStarted.Eagerly)`, §5.3-H), and the setter use case becomes a
required constructor param, the existing delegate/VM tests **fail to compile and
throw `MockKException` at construction** until fixed. Step 3 (`:app:test`) cannot
pass otherwise.

### 6.1 New coverage
- **`ScrimRenderTest.kt`** (new, JVM) — §4.
- **`SettingsRepositoryContract.kt`** (testFixtures) — add three tests mirroring
  layoutScale (default emit ~line 97, set-reflects-in-flow ~line 157, reset ~line
  221) for `wallpaperScrimAlpha`. Flows into both
  `FakeSettingsRepositoryContractTest` and `SettingsRepositoryImplContractTest`
  automatically (Rule 2 triple).
- **`FakeSettingsRepository.kt`** (testFixtures) — add the backing
  `MutableStateFlow`, the `var`, the override flow, the override setter, and the
  reset line (mirror lines 56/95–97/140/164–165/218).
- **`LayoutDelegateTest` / `LauncherViewModel` tests** — add positive coverage for
  the new state + setter (coercion clamps to MIN/MAX).

### 6.2 MANDATORY fixups to existing tests (compile/runtime breakers)
- **New required ctor arg `setWallpaperScrimAlphaUseCase`** — add at ALL SIX
  construction sites or `:app` test compilation fails:
  `LauncherViewModelContractTest.kt:244`, `LauncherViewModelTest.kt:240`,
  `LauncherViewModelDoomsdayTest.kt:156`, `LauncherViewModelSecurityTest.kt:169`,
  `MonolithicLauncherViewModelTest.kt:273`, and `LayoutDelegateTest.kt:76`.
- **Stub the new eager Get-flow in every NON-relaxed `getLayoutSettingsUseCase`
  mock** or it throws `MockKException` at construction — add
  `every { wallpaperScrimAlpha } returns flowOf(AppConstants.DEFAULT_WALLPAPER_SCRIM_ALPHA)`
  to: `LauncherViewModelContractTest.kt:204`, `LauncherViewModelTest.kt:179`,
  `LauncherViewModelDoomsdayTest.kt:113`, `LauncherViewModelSecurityTest.kt:133`,
  and the ~5 non-relaxed blocks in `LayoutDelegateTest.kt` (53, 136, 154, 180,
  200). `MonolithicLauncherViewModelTest.kt:202` is a *relaxed* mock → needs only
  the ctor arg, no stub.
- **`BackupSerializerRoundTripTest.kt` `FULLY_POPULATED`** (lines 46–92) asserts
  whole-object equality (`assertThat(parsed!!.settings).isEqualTo(FULLY_POPULATED)`).
  A nullable-default-null field left out of `FULLY_POPULATED` round-trips
  null→null and the test passes while covering NOTHING. **Add a distinctive
  non-default value** (e.g. `wallpaperScrimAlpha = 0.42f`) so the guard actually
  exercises the new field and its strict-parse alias.
- **Backup round-trip** — `BackupDataAssembler*Test.kt`: export → import restores
  the value; absent field → default.
- Purge/keep-list tests (`DataStoreMaintenanceRepositoryImplTest`, purge
  completeness) pass automatically once §5.2-E sites 2 & 3 are done — but if any
  asserts an exact keep-list *count*, bump it by one.

---

## 7. Enforcement gates that will fire (run before commit)

```bash
./gradlew checkConventions   # ownedExactKeys registration, purge completeness, strings parity, Toast/Timber/etc.
./gradlew checkRule13        # new comments/KDoc must be English
./gradlew test               # unit tests incl. contract triple + new ScrimRenderTest
./gradlew assembleDebug      # compile
```
Specifically:
- `check-settings-keys-registered` — needs §5.2-E site 2 (`ownedExactKeys`).
- `check-purge-completeness.awk` — needs §5.2-E site 3 (`purgeRepository`).
- `check-strings-parity.awk` — needs §5.4 (both locales).
- `checkRule13` — all new comments/KDoc English (this file too is English).

The new `LayoutDelegate` state adds a `Flow.catch { }` arm that logs at report
level (`silentError`). That arm **is globally enforced** by
`tools/check-flow-catch-rethrow.awk` (Rule 11): a logging `.catch` arm must
rethrow `CancellationException` first. So the `if (e is CancellationException)
throw e` line in §5.3-H is **required, not optional** — omit it and
`checkConventions` fails. (This is separate from the `cancel_files` positive
list, which covers `try/catch` clauses; `LayoutDelegate` is not on that list and
does not need to be — no new broad `try/catch`.)

**Not triggered:** no new broad `try/catch`, so no `cancel_files` / `oom_files` /
Rule-11 whitelist entry needed. No `preferences.xml` entry (layoutScale has none
either — dialog-driven).

---

## 8. Docs to update (do NOT skip — honesty about §6)

Introducing a translucent surface behind the home text **formally trips the
`ACCEPTED_LIMITATIONS.md §6` re-evaluation trigger** ("Home text stops sitting
directly over the wallpaper … the classifier becomes load-bearing again").

- **`ACCEPTED_LIMITATIONS.md §6`** — amend, don't ignore: record that an opt-in,
  default-0, *manual* scrim now exists; that it is materially different from the
  reverted auto-scrim (alt. 1); that the outline STILL carries the legibility
  guarantee (the scrim only eases it, is not luminance-coupled, default off) so
  §6's core decision stands; and that the classifier stays advisory for home
  text because the outline is unchanged.
- **`TODO.md §22`** — add a note that the manual user-scrim shipped as a distinct
  feature from the failed auto/blend attempt, cross-linking this spec, so the
  "do not re-litigate the scrim" guard is not read as forbidding *this* one.

---

## 9. Rollout order (small, compilable increments)

1. `:domain` — AppConstants, SettingsRepository iface, LayoutSettingsUseCase,
   BackupData model. (+ FakeSettingsRepository + contract tests → `./gradlew :domain:test`.)
2. `:data` — SettingsRepositoryImpl (4 sites), BackupDataAssembler,
   BackupSerializer. (`./gradlew :data:test` — contract impl + purge + backup.)
3. `:app` logic — `ScrimRender.kt` + `ScrimRenderTest.kt`, LayoutDelegate,
   LauncherViewModel. (`./gradlew :app:test`.)
4. `:app` UI — dialog XML + fragment, `fragment_home.xml` scrim View,
   HomeFragment wiring, strings (both locales).
5. Docs — §6 + §22 amendments.
6. Full gate sweep (§7), then on-device check against the reference bimodal
   wallpaper: scrim at ~0.2 rescues the bright half, dark half barely changes.

---

## 10. Open questions for review

- **Range/step:** MAX = 0.5, step 0.05 — right ceiling, or cap lower (0.4) to
  keep it unambiguously a "reserve"?
- **Reset semantics:** include the scrim in `onResetLayoutSettings()` (this plan:
  yes) — or leave it out so a layout reset doesn't silently undo a legibility
  aid the user relies on? (Purge/factory-reset still clears it regardless.)
- **String wording** (`layout_wallpaper_scrim`): "Wallpaper dimming" vs
  "Background dimming". Avoid "Legibility dim" — it oversells the scrim as a
  legibility guarantee (that is the outline's job; the scrim only eases the
  light-text case, see §1).
- **Off-grid slider values:** `stepSize = 0.05` but the restore/setter coerce
  only to MIN/MAX, not to the grid. A hand-edited or legacy backup value not on
  the 0.05 grid, assigned to the Material `Slider`, throws
  `IllegalArgumentException` (caught by `safeRun`/`silentError` → rethrows in
  DEBUG). Shared with the existing sliders and the app's own exports are always
  on-grid, so acceptable as-is; snap to step in restore/setter only if strictness
  is wanted.
