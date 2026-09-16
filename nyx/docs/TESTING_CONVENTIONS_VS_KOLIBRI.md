# Why Nyx's and Kolibri's testing conventions differ (and why that's fine)

A note for anyone who notices that Nyx and Kolibri don't test *identically* and
wonders whether that's drift. It isn't — the one real difference is deliberate,
documented at the source, and narrower than it looks.

## TL;DR

- The **testing conventions are shared**, not forked. Nyx reuses Kolibri's rules
  and their enforcement (linters/detectors) rather than copying them, and points
  at Kolibri's `TESTING_CONVENTIONS.kt` as the canonical statement.
- There is exactly **one** concrete runtime divergence: the *flavour* of the
  single test dispatcher.
  - **Kolibri** → `UnconfinedTestDispatcher` (eager).
  - **Nyx** and **`:common-ui`** → `StandardTestDispatcher` (lazy, the
    family-documented default).
- That choice is a **per-module constructor argument on a shared base class**,
  labelled "a deliberate per-module decision, NOT drift" in the code itself.
- The invariant that actually prevents flaky coroutine tests — *use ONE
  dispatcher source everywhere* — is **identical** in both projects.

## What is actually shared

The rule's *plumbing* lives once, in `:core` test fixtures:

```
core/src/testFixtures/.../testing/MainDispatcherRuleBase.kt
    open class MainDispatcherRuleBase(val testDispatcher: TestDispatcher) : TestWatcher()
        // starting() -> Dispatchers.setMain(testDispatcher)
        // finished() -> Dispatchers.resetMain()
```

Each module keeps a tiny no-arg subclass that only supplies its dispatcher:

```
kolibri/domain/.../rule/MainDispatcherRule.kt
    class MainDispatcherRule : MainDispatcherRuleBase(UnconfinedTestDispatcher())

nyx/domain/.../testing/MainDispatcherRule.kt
    class MainDispatcherRule : MainDispatcherRuleBase(StandardTestDispatcher())

common-ui/src/test/.../MainDispatcherRule.kt
    class MainDispatcherRule : MainDispatcherRuleBase(StandardTestDispatcher())
```

So the JUnit `TestWatcher` logic, the `setMain`/`resetMain` lifecycle, and the
"one dispatcher instance, exposed to both `runTest(...)` and the code under test"
contract are one shared implementation. The base even exposes the *same instance*
under two accessor names (`testDispatcher` and `dispatcher`) purely so neither
project's ~330 existing call sites had to change when three hand-copied rules
were merged onto it. That is the opposite of drift: the shared thing was
de-duplicated, and only the one genuinely-different knob was left as a parameter.

The non-negotiable convention is also identical in both projects
(Kolibri's `TESTING_CONVENTIONS.kt`, "COROUTINE TEST DISPATCHER"):

> Use ONE dispatcher everywhere. Never mix dispatcher instances.
> `mainDispatcherRule.testDispatcher` is the single source, passed to `runTest`
> and to any scope/code that reads `Dispatchers.Main`.

Everything else in the test philosophy is the same across the family: JUnit 4 +
JVM-first, MockK only (no Mockito), the repository contract-test triple, and
"testable logic lives outside Android-runtime classes."

## The one difference: eager vs. lazy dispatcher

| | Kolibri | Nyx / `:common-ui` |
|---|---|---|
| Dispatcher | `UnconfinedTestDispatcher` | `StandardTestDispatcher` |
| Execution | **Eager** — a launched coroutine runs immediately, up to its first real suspension | **Lazy** — launched coroutines run only on `advanceUntilIdle()` / `runCurrent()` |
| Practical effect in a test | Often no `advanceUntilIdle()` needed | You call `advanceUntilIdle()` to let launched work run |

### Why Kolibri uses the eager one

Kolibri's test suite is large and pre-dates the split. It was written against
`UnconfinedTestDispatcher` semantics — hundreds of tests implicitly assume a
launched coroutine has already run by the next line. The dispatcher flavour is,
as the base class puts it, "entrenched in its test suite." Kolibri's own
`TESTING_CONVENTIONS.kt` documents the exact failure mode that motivated the
single-source rule: mixing an `Unconfined` rule with a separate `Standard`
dispatcher made `advanceUntilIdle()` advance only one scheduler and produced
100% failure with useless error messages. Kolibri solved that by unifying on
*its* dispatcher, not by rewriting every test.

### Why Nyx (and `:common-ui`) use the lazy one

Nyx is greenfield. With no legacy suite to preserve, it adopts the
**family-documented default**, `StandardTestDispatcher` — the flavour the wider
convention recommends because *lazy* execution makes coroutine ordering explicit:
nothing runs until the test says `advanceUntilIdle()`, so "launched but never
awaited" bugs surface in the test instead of being hidden by eager execution.
`:common-ui` is shared code with no entrenched history either, so it matches Nyx.

## Why this is OK — my take

1. **The part that matters is identical.** Flakiness in coroutine tests comes
   from *mixing* dispatcher instances, not from which single flavour you pick.
   Both projects enforce the same "one dispatcher source" rule; both are
   internally consistent. A test author in either project writes the same shape
   (`@get:Rule val mainDispatcherRule`, `runTest(mainDispatcherRule.dispatcher)`).
   The only felt difference is whether you need `advanceUntilIdle()` — a local,
   obvious thing, not a correctness trap.

2. **It's a labelled decision, not accidental divergence.** The difference is a
   single constructor argument on a shared base, with a comment that spells out
   the reason and names each side. Nobody has to reverse-engineer intent; drift
   is when two copies quietly grow apart, and here there are no copies to grow.

3. **Forcing uniformity has a real cost and no benefit.** Converting Kolibri to
   `StandardTestDispatcher` was tried and breaks a large batch of its tests
   (they rely on eager execution), for zero behavioural gain — the production
   code is unaffected either way. Paying a large, risky test-rewrite bill to make
   one constructor argument match, when the safety-relevant rule already matches,
   is a bad trade. The base-class approach was chosen precisely to get the
   sharing (no duplicated plumbing) without the rewrite.

4. **Each choice fits its project.** Legacy code keeps the convention its suite
   was built on; new code adopts the recommended default. That is the healthy
   way a shared standard evolves — new modules move to the better default while
   old ones stay stable until a conversion is actually worth it.

In short: this is *shared conventions with one deliberately-parameterised knob*,
not two diverging standards. The seam is in exactly the right place.

## When to revisit

Reconsider only if Kolibri's suite is being reworked for another reason anyway
(so the `Unconfined → Standard` conversion rides along at no extra cost), or if a
future shared-code module is somehow forced to run tests under *both* flavours
(it isn't today — `:common-ui` simply picked `Standard`). Short of that, leave
the two subclasses as they are; the shared base is the thing to keep aligned.

## Pointers

- Shared plumbing + the rationale comment:
  `core/src/testFixtures/.../testing/MainDispatcherRuleBase.kt`
- Canonical test conventions (referenced by Nyx, not duplicated):
  `kolibri/app/src/test/CLAUDE.md` and
  `kolibri/app/src/test/java/.../TESTING_CONVENTIONS.kt`
- How Nyx reuses (rather than forks) Kolibri's rules and their enforcement:
  the "Shared rules" section of `nyx/CLAUDE.md`.
