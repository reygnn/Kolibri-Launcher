# Icon-launcher — additional hard rules

> Paste into the icon-launcher repo's `CLAUDE.md` after the inherited rule block.
>
> **The 13 hard rules of `Kolibri-Launcher` apply unchanged** — Repository interface
> always (1), contract-test triple per repository (2), impl wins on a red contract (3),
> Hilt is the DI framework (4), DataStore Preferences is the only app storage (5),
> respect the version pins (6), multi-layer crash-safety (7), ACRA opt-in (8), error
> logging via `TimberWrapper` (9), JVM-first / value-bar testing (10), no catch around a
> can't-throw (11), and the rest. The following are **icon-specific additions**; each
> delegates to a named spec for the detail. Reference them the same way — by bare name.

---

14. **The domain knows no pixels.** No `Bitmap`, `Drawable`, `AdaptiveIconDrawable`,
    `Canvas` or `LauncherApps` import ever crosses into `:domain`. An icon is *referenced*
    there (`ComponentKey` / `IconRef`), never rendered; resolving, compositing and caching
    live in `:data`. Same discipline that keeps the domain Parcelable-/`@StringRes`-free.
    → `ICON_HOME_MODEL_SPEC` §0.2 (IHM-INV-1).

15. **Every mutating home operation is a pure transition under a thin use-case.**
    `HomeLayoutTransition.*` and `HomeLayoutReconciler.reconcile` take state in, return
    state out — no repository, no dispatcher, no Android type, no clock/RNG (IDs arrive as
    an injected `ItemIdFactory`). The use-case is only `read → transform → save`. Policy is
    a truth table; the shell is the I/O. Don't fold the two together.
    → `MOVE_ITEM_SPEC` §0, and the whole use-case family.

16. **Use-cases return sealed result identifiers, never a bare `HomeLayout`.**
    `MoveResult` / `FolderEditResult` / `ReconcileResult` / `LayoutEdit`. `:app` maps them
    to animation / announce / toast; `:domain` stays string- and `@StringRes`-free. A
    `layout == null` result (`NoOp` / `Rejected` / `Unchanged` / `Skipped`) means **no
    DataStore write and no Flow tick** — never save on a non-change.
    → `MOVE_ITEM_SPEC` §2, `HOME_EDIT_USECASES_SPEC` §1.

17. **App uniqueness is a layout invariant.** Each `ComponentKey` appears at most once
    across `items` ∪ all folder `members` ∪ `dock`. It is *established* at the edge
    (Reconcile / import — fail-closed dedup, precedence Dock > Grid > Folder) and
    *preserved* by every transition. "Add an app that's already present" is therefore an
    **unreachable** state → `silentError`, not `NoOp` and not `Rejected`.
    → `ICON_HOME_MODEL_SPEC` IHM-INV-7, `RECONCILE_HOME_LAYOUT_SPEC` RHL-INV-4.

18. **Folders always have ≥ 2 members.** Born with exactly two (`FolderCreated`); the
    moment a removal or a prune drops one to a single member it **auto-dissolves** in the
    same transition — the survivor is promoted to a top-level app at the folder's old
    position, the folder id retired. A one-member folder never persists between calls.
    → `REMOVE_FROM_FOLDER_SPEC` RFF-INV-1/-2.

19. **Reconcile is fail-closed.** Prune the layout only against a genuine
    `AppLoadResult.Loaded`. An `Error` (or empty-because-failed) enumeration → `Skipped`,
    zero mutation, zero save. A transient PackageManager failure must never empty a home
    screen. Every change the pass does is counted in `ReconcileReport` — no silent prune.
    → `RECONCILE_HOME_LAYOUT_SPEC` RHL-INV-1/-5 (inherits the `R-INV` family and the
    `AppLoadResult` envelope from `INSTALLED_APPS_LOAD_SPEC`).

20. **The icon cache is hand-rolled, byte-bounded, and its policy is Android-free.** No
    Coil, no Glide. `LruBudget`, `IconCacheKey` and request-coalescing are pure JVM;
    only `IconRasterizer`, disk write/decode and `LauncherApps` touch Android. Memory is
    bounded by *bytes* (`allocationByteCount`), not item count. `evict(pkg)` keeps memory
    **and** disk coherent. Never manually `recycle` a shared cached bitmap.
    → `ICON_LOADER_SPEC` (ICL-INV-*).

21. **Preconditions are `silentError`, never dressed-up rejects.** Unknown `ItemId`,
    `member ∉ folder`, a rename target that isn't a folder, a duplicate-add — these are
    programmer errors, not user outcomes. They go through `silentError` (loud in DEBUG,
    no-op in RELEASE); they are never returned as a user-facing `Rejected`. `Rejected` is
    reserved for real user-reachable outcomes (occupied target, off-grid, dock full).
    → extends rule 11; `MOVE_ITEM_SPEC` MIU-INV-2.

22. **The home layout is one versioned JSON blob in DataStore; its DTOs live in
    `:data`.** No Room. `@Serializable` DTOs + mappers sit in `:data` (mirroring
    `DomainMessageMappers`); the `:domain` data classes stay annotation-free. Schema
    version in both the key (`home_layout_v1`) and the blob.
    → `ICON_HOME_MODEL_SPEC` §7-E1.

---

## Test posture (rule 10, sharpened)

The drop / extraction / reconcile **matrices are truth-table tests on the JVM** — they
*are* the test plans. Robolectric and `androidTest` are reserved for what genuinely needs
a device: adaptive-icon compositing and rasterization, the drag-and-drop gesture layer,
real `LauncherApps`, and `onTrimMemory`. Pure policy that is already JVM-pinned gets **no**
second instrumented test. One dispatcher via `MainDispatcherRule`; never a separate
`TestScope` / `StandardTestDispatcher` (`TESTING_CONVENTIONS.kt`).

Design detail for any of the above lives in `docs/specs/` (see its `README`), referenced
from code by bare spec name — e.g. a comment `// MOVE_ITEM_SPEC §7-D2` resolves there.
