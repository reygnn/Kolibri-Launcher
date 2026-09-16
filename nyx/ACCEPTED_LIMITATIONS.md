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
