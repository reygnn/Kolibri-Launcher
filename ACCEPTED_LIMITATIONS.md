# Accepted Limitations

This document tracks known UX or behavioural limitations that are
*intentional consequences* of architectural decisions. They are not
bugs to be fixed; they are costs we have priced in. The purpose of
this file is to remind future-us why we accepted them, so we don't
re-litigate the decision every time the limitation is noticed.

---

## 1. AppDrawer AUTO-mode classifier ignores layer composition

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

---

## 2. Clipboard read pulls a URI-backed clip in whole

- **Status:** 🟡 Intentional / Documented
- **Frequency:** Rare — needs a multi-megabyte, URI-backed `text/*` clip
- **Affected:** `MainActivity.readClipboard` (double-tap clipboard action)

### Explanation

`readClipboard` calls `ClipData.Item.coerceToText`, and
`ClipboardActionResolver.resolve` then discards everything past its
8192-character cap. For the overwhelmingly common case that is free:
`coerceToText` returns `item.text` immediately and no stream is ever
opened. But when the clip is URI-backed and the provider serves it as
`text/*`, the framework opens a typed asset FD and reads it to EOF into
an unbounded `StringBuilder` — so copying a 20 MB log file in a file
manager and then double-tapping allocates far more than the ~8 KB that
survives, in the HOME process of all places.

### Why it is accepted

The obvious fix does not work. Pre-checking the MIME type and taking
`item.text` for `text/plain` changes nothing, because that is already
the fast path *inside* `coerceToText`; the allocating branch is exactly
the fallback such a check would still route to. A real fix means opening
`openTypedAssetFileDescriptor` ourselves and reading a bounded number of
characters — i.e. re-implementing framework behaviour, including the
`htmlText` and Intent-item cases `coerceToText` also covers, in the one
process that must never crash.

Against that: the read already runs on `Dispatchers.IO`, so there is no
ANR exposure, only allocation. The trigger requires a clip that is
simultaneously URI-backed, several megabytes, and text-typed — which is
constructible but not something a launcher user stumbles into.

### Trigger for re-evaluation

Reopen this entry if any of the following changes:

- An OOM or a slow double-tap is actually observed via ACRA with a large
  clipboard item in the report.
- `ClipData.Item` gains a bounded read in a future Android release, at
  which point the fix becomes a one-liner.
- The clipboard feature grows a second consumer that needs the full text
  rather than a capped preview — the cap is what makes the whole read
  wasteful today.
