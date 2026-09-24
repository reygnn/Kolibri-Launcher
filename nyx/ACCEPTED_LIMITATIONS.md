# ACCEPTED_LIMITATIONS.md — Nyx

Intentional UX / behavioural limitations that are direct consequences of an
architectural decision. Each entry carries the rationale and a re-evaluation
trigger. Sibling of the same doc in Kolibri — before "fixing" a perceived UX bug
in one of these areas, check here first.

---

## Drag-based organisation is not operable by TalkBack / switch access

**What:** Reordering the home grid and organising the app drawer into folders
(create a folder by dropping one app onto another, add to a folder, extract a
member) are reachable ONLY through a finger-drag gesture. Accessibility services
that intercept touch to build their own gestures (TalkBack, switch access) cannot
drive these interactions, so those users cannot reorder the home screen or manage
drawer folders.

**Why:** Nyx uses a custom touch-tracking drag engine (`DragLayer` /
`DragController` / `DropZone`, HOME_DRAG_ENGINE_SPEC) instead of the framework
`View.startDragAndDrop` path, because the home/drawer drag needs a single window
that owns the whole gesture stream (drops up to the display edge, drawer-fold vs.
home hand-off, the armed long-press → menu-or-drag decision). A custom engine
exposes no framework `AccessibilityAction`s for dragging, and the framework
provides no default accessible drag affordance for it.

**Not blocking:** The core paths stay accessible — tap to launch, and the
drawer-app long-press context menu offers "Add to home" (plus App info /
Uninstall). Drawer folders and home reordering are organisational conveniences,
not a path to launching apps. Drawer folder tiles announce their role + member
count to TalkBack (`drawer_folder_a11y`) so a folder is distinguishable from an
app of the same name.

**Re-evaluate when:** a11y parity becomes a goal. The sanctioned path is then to
add non-drag alternatives — context-menu entries ("Add to folder…" / "Remove from
folder") or custom `AccessibilityAction`s on the tiles — NOT to move off the
custom drag engine.

---

## A home item can be pruned during a restore that exposes no install session

**What:** After the fail-closed reconcile (AUDIT-1 F7, RHL-INV-6), a home/dock
placement is pruned only when its package is BOTH absent from a fresh app
enumeration AND fails two independent keep-checks: cross-surface presence
(`PackageManagerPresence`) and an active-install/restore-session probe
(`PackageManagerInstallSessions`). In the normal restore flow the session probe
keeps a not-yet-reinstalled app as a "promise" until its install session
completes. The residual gap: if a package is genuinely not installed yet AND no
discoverable `PackageInstaller` session represents its pending restore, both
keep-checks say "gone" and the placement is pruned — its position/folder
membership is not recovered when the app later appears (it returns to the drawer,
not to its old spot).

**Why:** The reconcile candidate finder is the fresh enumeration, which can be
partial mid-restore. Presence (fixes the shared-transient failure mode) cannot
help here because the app really is absent at that instant. The session probe
(the Launcher3 mechanism) closes the common case but depends on a session being
visible to nyx: `getAllSessions()` requires nyx to be the active default launcher
for foreign sessions to carry a package name, and some restore agents may not
surface a per-package session at all. When no session is visible, there is no
signal left that distinguishes "being restored" from "uninstalled", and the
gate's fail-safe covers only query *errors*, not a legitimate empty result.

**Not blocking:** Requires the conjunction of an active restore, a package not yet
reinstalled at the moment a reconcile runs, and no discoverable session for it —
and even then only affects placement, never app access (the app relaunches from
the drawer once restored). Kolibri accepts the same residual for its component-
bound stores; neither launcher adds a sanity-floor.

**Re-evaluate when:** field reports show lost home placements after a device
transfer. The sanctioned next steps are, in order: (1) also honor the
`ACTION_SESSION_COMMITTED` broadcast / a longer restore-aware defer window so a
reconcile does not run while a restore is in progress; (2) a keep-last-good count
floor (skip the reconcile when the enumeration count drops implausibly, not only
at zero). Do NOT relax the presence fail-safe.
