# DRAWER_FOLDERS_SPEC — Folders in the App Drawer as a derived, self-healing projection

> **Generated** against `main` @ `bebd027`. New-feature spec for nyx (deep alpha,
> no migration constraints).
>
> **Focus:** add folders to the app drawer **without** turning the drawer into a
> second home screen. The drawer stays a *derived, self-healing* view over the
> live system app list; the only persisted datum is folder **membership**. Folder
> *identity + rendering* is reused from the existing home/dock implementation;
> folder *placement/persistence* is drawer-specific and deliberately position-free.
>
> **Not in focus:** custom ordering of drawer entries (stays alphabetical),
> folder-in-folder, folder↔folder merges, shared identity between home folders and
> drawer folders, syncing an app between a drawer folder and the home layout.
>
> **Status:** DRAFT v1.0. Signatures in §3–§9 are proposals against the real nyx
> code. Review round 1 pending.
>
> **Relation to the home layout:** the home layout (`HomeLayout`) binds items to
> paged cells (`PlacedItem`/`CellPos`) and to a seatless dock list. The drawer has
> **no persisted, positioned layout** — it is a live alphabetical list from the
> system. That asymmetry is the whole design: home = curated + positioned, drawer =
> derived + membership-only. See §2 for why we do **not** reuse `HomeLayout` here.

---

## §0 Inventory (as-is)

- **Folder model (reusable):** `HomeItem.Folder(id, title, members: List<ComponentKey>)`
  (`nyx/domain/.../home/model/HomeLayout.kt`). Members are a flat, ordered,
  unique `List<ComponentKey>`; title blank ⇒ localized default; the folder icon is
  derived, never stored. The type carries **nothing positional** — placement lives
  only in the `PlacedItem` wrapper (grid) or is absent (dock).
- **Folder rendering (reusable):** `FolderIconRenderer`
  (`nyx/data/.../data/icon/FolderIconRenderer.kt`) composes a closed-folder icon
  from up to 4 members; input is `List<ComponentKey>` + size — container-agnostic.
  `HomeCell` + `bindLaunchableCell` + `loadIconGated`
  (`nyx/app/.../home/IconBinding.kt`) already share App/Folder bind+tap wiring
  across grid, dock, and the drawer's member adapter.
- **Membership ops (to be extracted):** create-from-two-apps, add, remove,
  rename, auto-dissolve, uniqueness — currently entangled with cell placement
  inside `HomeLayoutTransition`
  (`nyx/domain/.../home/transition/HomeLayoutTransition.kt`).
- **Opened folder overlay:** currently wired inline in `MainActivity.openFolder`
  (`R.id.folder_overlay` + `FolderMemberAdapter` +
  `RecyclerView`/`GridLayoutManager`).
- **Drawer (as-is):** flat, alphabetical, live-from-system, **not persisted**.
  `GetDrawerAppsUseCase` → `List<LauncherApp>` (sorted by `customName ?: label`),
  exposed as `HomeViewModel.drawerApps: StateFlow`. UI is `AppDrawerFragment` +
  `AppDrawerAdapter` (RecyclerView grid, View-based — nyx is **not** Compose). The
  drawer has **zero** awareness of folders today.

---

## §1 Target picture

- The drawer is a **projection**: displayed content is derived from
  (live system apps) ⊕ (persisted folder membership). Apps not in a folder show
  alphabetically; new installs appear automatically; uninstalls fall out for free.
- The **only** persisted drawer state is `List<DrawerFolder>` (membership + title).
- Folders render as a **pinned block at the top** of the drawer (decision **D-2**),
  above the alphabetical app grid.
- Folder membership operations are a **shared, position-free `FolderMembership`
  module** extracted from `HomeLayoutTransition` (decision **D-4**), used by both
  home and drawer.
- Home folders and drawer folders are **independent** (decision **D-5**): only the
  type shape and the renderer are shared, never the persisted state.

---

## §2 Decisions

- **D-1 (mode):** Projection, not curated. Persist only `List<DrawerFolder>`; the
  displayed list is derived. Rationale: the drawer is the "find anything" surface;
  a curated drawer just duplicates the home screen and blurs the distinction.
- **D-2 (placement):** Folders in a **pinned block at the top**, ordered
  alphabetically by title among themselves; loose apps alphabetical below. Optional
  section divider between the two zones (own RecyclerView view-type).
- **D-3 (search):** Search **flattens folders** — members are found individually as
  app hits; folder titles are not searchable (v1). In active search mode the folder
  block is hidden and only the flattened app set is shown.
- **D-4 (membership module):** Extract `FolderMembership` from
  `HomeLayoutTransition` **now**, as step 1, before drawer work. Existing home
  transition tests guard the extraction.
- **D-5 (store separation):** Own `DrawerFoldersRepository`, separate from
  `HomeLayoutRepository`. `DrawerFolder` is a slim twin of `HomeItem.Folder`, not a
  reuse of the home type, so the two evolve independently.
- **D-6 (reconcile):** Reconcile lazily — the displayed list is always correct via
  intersection with the live app set; dead members/empty folders are purged on the
  next `update{}` write. See §9.

---

## §3 Domain model

```kotlin
@JvmInline
value class DrawerFolderId(val raw: String)   // stable, position-independent

data class DrawerFolder(
    val id: DrawerFolderId,
    val title: String,                 // blank ⇒ localized default name
    val members: List<ComponentKey>,   // ordered, unique, >= 2 (see invariants)
)

// Persisted drawer state: membership only, no positions.
data class DrawerFolders(val folders: List<DrawerFolder>) {
    companion object { val EMPTY = DrawerFolders(emptyList()) }
}

// Derived, never-persisted display entry.
sealed interface DrawerEntry {
    data class App(val app: LauncherApp) : DrawerEntry
    data class Folder(
        val id: DrawerFolderId,
        val title: String,
        val members: List<ComponentKey>,
    ) : DrawerEntry
}
```

`ComponentKey` (`:core`) and `LauncherApp` (domain) are reused unchanged. Domain
types stay annotation-free; serialization lives in the data layer (§4).

---

## §4 Persistence

- **Repository (domain):** `DrawerFoldersRepository`
  - `fun folders(): Flow<DrawerFolders>` — cold, current membership.
  - `suspend fun update(block: (DrawerFolders) -> DrawerFolders)` — atomic
    read-modify-write under a writer mutex. Mirrors `HomeLayoutRepository`.
- **Impl (data):** `DataStore<Preferences>`, **one** versioned JSON blob under a
  dedicated key. Own DTOs (`DrawerFolderDto`; reuse `ComponentKeyDto`) + serializer;
  missing/undecodable blob ⇒ `DrawerFolders.EMPTY`. DTO mirror + mappers, same
  pattern as `HomeLayoutDto` / `HomeLayoutMappers`.

---

## §5 Derived drawer content (projection)

`GetDrawerContentUseCase`:

```kotlin
combine(
    getDrawerApps(),                 // List<LauncherApp>, unchanged source
    drawerFoldersRepository.folders() // DrawerFolders
) { apps, folders ->
    val byKey = apps.associateBy { it.key }
    val reconciled = folders.folders
        .map { it.copy(members = it.members.filter { k -> k in byKey }) }
        .filter { it.members.size >= 2 }            // auto-dissolve on reconcile
    val memberKeys = reconciled.flatMap { it.members }.toSet()

    val folderEntries = reconciled
        .sortedBy { it.displayTitle() }             // D-2: block, alpha by title
        .map { DrawerEntry.Folder(it.id, it.title, it.members) }
    val appEntries = apps
        .filterNot { it.key in memberKeys }
        .sortedBy { it.displayName().lowercase() }  // loose apps, alpha
        .map { DrawerEntry.App(it) }

    folderEntries + appEntries                       // pinned folders, then apps
}
```

- `GetDrawerAppsUseCase` stays the source of truth for "which apps exist".
- The projection is pure and fully testable off the Android runtime.
- Reconcile here is **display-only**; persistence cleanup is lazy (§9).

---

## §6 Invariants

- **DFOLD-INV-1:** A persisted `DrawerFolder` has ≥ 2 **resolvable** members at
  every persisted point; dropping to 1 ⇒ auto-dissolve, the remaining member
  returns to the loose app pool.
- **DFOLD-INV-2:** Members are unique within a folder (`ComponentKey`).
- **DFOLD-INV-3:** An app is in **at most one** drawer folder. Adding it to folder B
  removes it from folder A.
- **DFOLD-INV-4:** No member points to a not-currently-installed app **in the
  displayed list** (reconcile, §5/§9). Persisted state may briefly retain dead
  members until the next write (D-6).
- **DFOLD-INV-5:** `id` is stable across rename and member changes.
- **DFOLD-INV-6:** Drawer folders and home folders share no identity or state; an
  app may be in a home folder and a drawer folder simultaneously and independently.

---

## §7 Shared `FolderMembership` module (D-4)

Extracted from `HomeLayoutTransition` as pure functions, **no positions**:

```kotlin
object FolderMembership {
    fun create(a: ComponentKey, b: ComponentKey, id: () -> Id): Folder
    fun add(folder: Folder, key: ComponentKey): Folder            // no-op if present
    fun remove(folder: Folder, key: ComponentKey): RemoveResult   // Removed | Dissolved(remaining)
    fun rename(folder: Folder, title: String): Folder
}
```

- `HomeLayoutTransition` calls this for the membership part and keeps placement
  (`CellPos`/dock) separate; behavior and existing tests stay green.
- Drawer transitions **are** just this module over `DrawerFolders`.
- Id generation stays an injected factory lambda (domain remains RNG-free).

**This extraction is step 1 of implementation**, landed and verified before any
drawer-specific code.

---

## §8 Drop targets & transitions (drawer-internal)

A drawer-specific `DrawerDropTarget`, decoupled from the home `DropTarget` (which
carries `CellPos`/dock index):

- `OntoApp(targetKey)` → create a folder from (source, target) via
  `FolderMembership.create`.
- `OntoFolder(folderId)` → `add`.
- **Extract from folder:** long-press-drag a member out of the opened overlay →
  `remove` (auto-dissolve per INV-1). "Loose in the drawer" is implicit — there is
  no placement to compute.

All mutations go through `DrawerFoldersRepository.update{}`. No regridder, no span,
no paging.

---

## §9 Reconcile / edge cases

- **Uninstalled app:** disappears from `GetDrawerApps`; §5 intersects members with
  the live set so it drops from display immediately (INV-4). **Persistence is
  purged lazily** on the next `update{}` (D-6) — display is always correct.
- **Folder reconciled down to 1 member:** shown as a loose app; persistently
  dissolved on the next write (INV-1).
- **All members gone:** folder vanishes from display; deleted lazily.
- **App in both a home folder and a drawer folder:** allowed and independent
  (INV-6).

---

## §10 UI (View / RecyclerView — nyx is not Compose)

- **`AppDrawerAdapter`** gains view-types `App` and `Folder` (and optionally a
  zone `Divider`) from v1. Folder bind reuses `FolderIconRenderer` +
  `bindLaunchableCell` / `loadIconGated`. Tap on a folder opens the overlay.
- **Opened overlay:** reuse the existing pattern (RecyclerView +
  `FolderMemberAdapter`), but **lift it out of `MainActivity`** into a
  container-neutral `FolderOverlayController` shared by home and drawer. Title edit
  via `normalizeFolderTitle` + a drawer-store rename.
- **Layout (D-2):** pinned folder block on top; the block may use its own
  column count / padding to set itself apart from the app grid (visual detail,
  does not block the domain).
- **Search mode (D-3):** renders the flattened app set only; the folder block is
  hidden.

---

## §11 Tests (JUnit4 + JVM, MockK, `MainDispatcherRule`)

- `FolderMembership`: create/add/remove/rename, dissolve boundary, uniqueness —
  pure example/property tests, partly reusable from the existing home transition
  tests.
- `GetDrawerContentUseCase`: projection, D-2 ordering (folders-then-apps),
  loose-vs-folder split, reconcile intersection, auto-dissolve via reconcile.
- `DrawerFoldersRepositoryImpl`: RMW atomicity, blob fallback to EMPTY, DTO
  roundtrip (parallel to the recent mapper-roundtrip work).
- Serializer: undecodable ⇒ EMPTY; version field present.

---

## §12 Implementation order

1. Extract `FolderMembership` from `HomeLayoutTransition` (D-4); home tests green.
2. `DrawerFolder`/`DrawerFolders` domain + `DrawerFoldersRepository` + DataStore
   impl + DTO/serializer + tests.
3. `GetDrawerContentUseCase` + tests (pure projection).
4. `DrawerDropTarget` + drawer transitions over `FolderMembership`.
5. UI: `AppDrawerAdapter` folder view-type; extract `FolderOverlayController`;
   wire drag/drop create/add/extract; search-mode flattening.

---

## §13 Open / v2

- Searchable folder titles; sections / A-Z fast-scroll (A-Z applies to the app
  zone only, given D-2).
- Folder↔folder merge, reorder within a folder.
- Shared folder identity home↔drawer (deliberately excluded in v1).
