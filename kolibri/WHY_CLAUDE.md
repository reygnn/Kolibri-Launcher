# WHY_CLAUDE.md

> Why I work with Claude — and why branches stay sacred anyway.

---

## The Upsides

### The Sunny-Patio Workflow

While Claude is off on the build server wrestling with Android cruft, Gradle dependencies, and obscure stack traces, I'm on the patio: cold drink in hand, Charlotte de Witte in the headphones, focused on what actually matters — the architecture and product calls you can't delegate and shouldn't try to.

This isn't laziness. It's a division of labor that simply wasn't possible two years ago.

### Bug Hunting in Seconds

Some bugs are so obscure you would never find them on your own. My favorite example: the infamous _"I cannot scroll anymore"_ GitHub issue. Hours of digging through stack traces, reading issue threads, comparing library versions, spelunking through other people's repos for workarounds — **or** you let Claude do the dirty work, and it pulls together the right thread, the right workaround, and the matching library version in seconds.

That, by the way, is exactly what pushed me onto **Claude Pro** — and onto **Max** while on vacation. Some investments pay for themselves.

### Attention That Scales

My brain is the scarce resource, not my typing speed. Claude takes the work that's repetitive, researchable, or grinding. I take the work that needs judgment, taste, and product sense. That's the right split.

### Testing & Review — Out of Hell, Into the Background

The old test loop was a morale killer: Robolectric setup that was a multi-hour PITA (manifest issues, SDK versions, missing resources), kick off tests, wait, read output, debug, repeat. Plus review rounds re-litigating mock setup and dispatcher handling, every single time.

It looks different now:

- **Unit tests run entirely in the background.** I don't trigger them, I don't watch them.
- **Claude sees the failures directly** — stack trace, assertion diff, all of it — and fixes them without needing to ask.
- **Conventions get written down once** (test setup, mocking style, dispatcher handling) and are followed from then on. No more debates in review about things that belong in a `CLAUDE.md`.
- **Robolectric?** Used to be a multi-hour PITA. Now a non-issue.

The **review process** has gotten radically simpler as a result. I'm looking at architecture, naming, and taste — not at whether someone wired up a mock correctly or picked the right test dispatcher. That doesn't just save time; it saves the kind of mental energy you don't have left at the end of the day.

---

## The "Downsides"

_(really just clean engineering you should be doing anyway)_

### Always Work in Separate Branches

New features and any non-trivial change belong in **separate Git branches**, no exceptions. Not because Claude makes a mess, but because:

- You want to be able to back out cleanly at any time.
- You want changes to stay reviewable and traceable.
- You want to merge when it suits you — not when the build server happens to finish.

This isn't an AI-specific problem. It's just **good engineering practice**, and AI workflows make it more visible and more important, simply because more code lands in less time.

---

## Bottom Line

The deal is simple: I make the calls, Claude does the work, and Git keeps us both honest.
