# `:data` testFixtures with Kotlin — investigation log

**Status:** **RESOLVED 2026-05-03.** A one-line `gradle.properties` flag
unblocks the AGP/KGP integration gap. Documented in detail below so
that a future maintainer who hits a regression on the same fingerprint
recognizes it immediately. The original investigation history is kept
verbatim under "History" because the symptom and the failed-workaround
list remain useful diagnostic context.

**Tool versions at time of resolution:**

| Tool | Version |
|---|---|
| Android Gradle Plugin | `8.13.2` |
| Kotlin Gradle Plugin   | `2.2.21` |
| Gradle wrapper | `8.14.3` |
| `kotlin-android` plugin | applied via `alias(libs.plugins.kotlin.android)` |

If you are reading this in a future session and any of these have changed
significantly, the flag below may have become unnecessary (and may even
have been renamed or removed if the feature graduated out of experimental
status). **Re-check by trying the flag-free path first.**

---

## TL;DR — the fix

Add this single line to `gradle.properties`:

```properties
android.experimental.enableTestFixturesKotlinSupport=true
```

That is the entire unblock. With the flag set:

1. KGP registers `compileDebugTestFixturesKotlin` (and the release
   sibling) for `com.android.library` modules that have
   `android.testFixtures.enable = true`.
2. `.kt` files in `src/testFixtures/` actually get compiled to bytecode.
3. The variant artifact (`bundleDebugTestFixturesAar`) contains the
   `.class` files.
4. Consumers (`testImplementation(testFixtures(project(":data")))`)
   resolve the symbols cleanly — no more "private in file".

The flag has existed since AGP 8.5, so AGP 8.13 has had it for ~5
releases. The reason it took an investigation to find: **the flag is
not in the official Android Gradle DSL documentation** (because the
feature is still gated behind `experimental.`). The undocumented status
is the entire reason this was hard to discover offline.

Verify with:

```
$ ./gradlew :data:tasks --all | grep -iE 'compile.*TestFixtures.*Kotlin'
compileDebugTestFixturesKotlin
compileReleaseTestFixturesKotlin
```

If those two lines appear, the integration is wired and Kotlin
fixtures work as they would in any pure-`java-test-fixtures` setup.

---

## Caveats worth knowing about

1. **`kotlin-stdlib` may need an explicit `testFixturesImplementation`
   dep on older AGPs.** The Kotlin Gradle Plugin auto-adds stdlib for
   normal source sets, but for the testFixtures source set this
   auto-add was historically missing. Auto-add was scheduled for
   AGP 8.9. On AGP 8.13.2 (which this project runs) it appears to be
   automatic — we did **not** need an explicit
   `testFixturesImplementation(libs.kotlin.stdlib)`. If a future bump
   produces a `kotlin.Unit not found` style error, that's where to
   look first.

2. **The flag is experimental.** Future AGPs may rename it or drop it
   once the feature graduates. If the flag stops being recognized after
   a bump, search the AGP/KGP changelogs for the feature's promotion
   path. Until that happens, this file is the reason the line stays
   in `gradle.properties`.

3. **`gradle.properties` placement matters.** Project root
   (`/gradle.properties`), not module-level. The flag is read by AGP
   at configuration time across the whole build.

---

## What this enabled

Before the flag, the §13 plan was blocked: `FakeDataStore` had to live
in `:app/src/test/java/.../fakes/` because `:data/src/testFixtures/`
silently dropped Kotlin sources. After the flag landed:

1. `:data/build.gradle.kts` got `android.testFixtures.enable = true`
   plus `testFixturesImplementation` deps for
   `androidx.datastore.preferences` and `kotlinx.coroutines.core`.
2. `FakeDataStore.kt` moved from `:app/src/test/java/.../fakes/` to
   `:data/src/testFixtures/java/.../fakes/`.
3. `:app/build.gradle.kts` got
   `testImplementation(testFixtures(project(":data")))`.
4. All consumer imports stayed identical (same package
   `com.github.reygnn.kolibri_launcher.fakes`).

Full test suite green after the migration — no consumer test code had
to change.

The further §13 step (move `*RepositoryImpl*Test.kt` files from
`:app/src/test/data/` to `:data/src/test/data/`) is now unblocked but
not part of this fix. See `TODO.md` §13 for the remaining incremental
move and the per-file Robolectric / Hilt-Test caveats.

---

## References

- **JetBrains tracking ticket:** **KT-50667** — *Support compiling
  android testFixtures sources in the kotlin-android gradle plugin*.
- **Write-up of the undocumented-flag situation:**
  `brightinventions.pl/blog/current-test-fixtures-support-android`.
- AGP testFixtures DSL: the `android.testFixtures` block (the
  enable-flag for the variant itself; the Kotlin support flag is the
  separate undocumented one that this file is about).

---

# History

The original investigation log follows verbatim from before the
resolution — kept because the symptom fingerprint, the failed
workarounds, and the hypothesis the investigation arrived at are still
useful debugging context for future regressions.

---

## What we wanted

Brocken B (Test-Isolation pro Modul, see `TODO.md` §13) moves test code
from `:app/src/test/` into module-local test source sets so that
`./gradlew :domain:test` and `./gradlew :data:test` actually do work
instead of running `NO-SOURCE`. For `:domain` (a `kotlin("jvm")`
module), this worked cleanly with the `java-test-fixtures` plugin
plus `:domain/src/testFixtures/`.

For `:data` we wanted the same:

1. Move `FakeDataStore` (which wraps `androidx.datastore.preferences.*`
   — an AAR-only artefact, so it cannot live in `:domain`) into
   `:data/src/testFixtures/`.
2. Move ~12 Repository-Impl tests
   (`FavoritesRepositoryImpl*Test`, `BackupRepositoryImpl*Test` etc.)
   from `:app/src/test/data/` into `:data/src/test/data/`.
3. Have `:app`'s remaining tests consume the `:data` testFixtures via
   `testImplementation(testFixtures(project(":data")))`.

That move is **blocked** by what looks like a
`kotlin-android`/AGP integration gap.

## Symptom

When `FakeDataStore.kt` lives at
`data/src/testFixtures/{java,kotlin}/com/.../fakes/FakeDataStore.kt`
and `:app/build.gradle.kts` declares
`testImplementation(testFixtures(project(":data")))`, every consumer
in `:app/src/test/` fails to compile with this exact pair of errors,
once per call site:

```
e: Unresolved reference 'FakeDataStore'.
e: Cannot access 'class FakeDataStore : DataStore<Preferences>': it is
   private in file.
```

Multiple `Unresolved reference 'updateDataCallCount' / 'setInitialData'
/ 'makeEditFail' / 'makeReadFail'` errors follow — those are member
references on `FakeDataStore` that obviously can't resolve once the
class itself is unreachable.

The two error messages on the same line are not contradictory: Kotlin
parses the source so it knows the class declaration exists, but
treats it as effectively private because no `.class` file has been
produced for it. (Compare to a normal "unresolved reference" you'd
get if the `.kt` file were missing entirely.) That mismatch is the
fingerprint of *the source set is registered, but no Kotlin
compilation task ran for it*.

## Reproducing it

To reproduce in the (pre-fix) repo state:

1. Apply the AGP-native testFixtures block in `data/build.gradle.kts`:

   ```kotlin
   android {
       // …
       @Suppress("UnstableApiUsage")
       testFixtures {
           enable = true
       }
   }
   ```

2. Place a Kotlin file at
   `data/src/testFixtures/java/com/github/reygnn/kolibri_launcher/fakes/FakeDataStore.kt`
   (move the existing one from `app/src/test/java/.../fakes/`).

3. Add the consumer dep in `app/build.gradle.kts`:

   ```kotlin
   testImplementation(testFixtures(project(":data")))
   ```

4. Run `./gradlew :app:compileDebugUnitTestKotlin`.

   Observed: the errors above. The build fails before any tests run.

5. Inspect the task list for the testFixtures variant:

   ```
   $ ./gradlew :data:tasks --all | grep -iE 'TestFixtures.*Kotlin'
   (no output)

   $ ./gradlew :data:tasks --all | grep -iE 'TestFixtures'
   compileDebugTestFixturesSources
   compileDebugTestFixturesJavaWithJavac     # ← Java compile, exists
   compileReleaseTestFixturesSources
   compileReleaseTestFixturesJavaWithJavac
   bundleDebugTestFixturesAar
   assembleDebugTestFixtures
   …
   ```

   AGP creates the source set, the variant, and Java + AAR-bundling
   tasks. **It never registers a `compileDebugTestFixturesKotlin` task**,
   which means `.kt` files in the testFixtures source set are never
   compiled. The Java task picks up zero sources (`NO-SOURCE` in the
   build log) and the variant artefact is therefore empty.

That last observation is the actual root cause of the
"private in file" error: there is no compiled bytecode for
`FakeDataStore` in the published testFixtures variant artefact, so
`:app`'s test compile-classpath gets a class that exists in source but
not in any `.class` form.

## What I tried (chronological, all rolled back)

Each of these was independently committed as part of an exploration
branch and then reverted because the symptom persisted.

### Attempt 1 — `java-test-fixtures` Gradle plugin

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `java-test-fixtures`        // ← added
    // …
}
```

Outcome: plugin conflict at sync time. The
`java-test-fixtures` plugin from Gradle (which works perfectly for
`:domain` because it's `kotlin("jvm")`) is **not compatible with
`com.android.library`** — it expects the `java-library` plugin to be
applied. AGP exposes its own equivalent via `android.testFixtures`,
which is what attempt 2 used.

### Attempt 2 — AGP-native `android.testFixtures.enable = true`

```kotlin
android {
    @Suppress("UnstableApiUsage")
    testFixtures {
        enable = true
    }
}

dependencies {
    testFixturesImplementation(libs.androidx.datastore.preferences)
    testFixturesImplementation(libs.kotlinx.coroutines.core)
}
```

Outcome: described above. AGP wires the variant; `kotlin-android` does
not register a Kotlin compile task for it; `FakeDataStore.kt` is never
compiled; consumers see "private in file".

### Attempt 3 — explicit Kotlin source dir under the testFixtures source set

```kotlin
android {
    // …
    testFixtures { enable = true }

    sourceSets {
        getByName("testFixtures").java.srcDir("src/testFixtures/kotlin")
    }
}
```

Plus moved the file to
`data/src/testFixtures/kotlin/com/.../FakeDataStore.kt`.

Outcome: `compileDebugTestFixturesJavaWithJavac` now picked up the
directory (it accepts both `.java` and, in mixed-source modes,
delegates to Kotlin), but Kotlin compilation still wasn't happening
for that variant — same symptom downstream. The directory was visible
to Java but no KGP task ran on it.

This confirms the missing piece is the Kotlin compile task
registration, not the source set definition itself.

## Hypothesised root cause (now confirmed)

The `kotlin-android` plugin integrates with AGP via the variants API
(`androidComponents { onVariants { … } }`). It registers Kotlin
compile tasks for the variants it knows about: `debug`, `release`,
`debugUnitTest`, `releaseUnitTest`, `debugAndroidTest`. The
**`testFixtures` variant** (`debugTestFixtures`,
`releaseTestFixtures`) is a separate variant added by AGP 8+, and the
Kotlin compile task for it does not get registered automatically by
the version of `kotlin-android` shipped with KGP 2.2.21
**unless `android.experimental.enableTestFixturesKotlinSupport=true`
is set in `gradle.properties`**.

The hypothesis was right; what the offline investigation could not
turn up was the existence of the gating flag. With the flag set, KGP
registers `compileDebugTestFixturesKotlin` and the chain works.

## Workarounds I considered and rejected (still relevant)

The list below is preserved as a reminder of what NOT to do if the
flag-based fix ever stops working — the alternatives are still worse
than just keeping the flag pinned to whatever it gets renamed to.

| Workaround | Why rejected |
|---|---|
| Hand-register a Kotlin compile task in `androidComponents.beforeVariants { … }`, wire output to the variant artefact | Internal-API hack, fragile across AGP/KGP upgrades, hard to debug if it breaks. Out of proportion to the win. |
| Convert `FakeDataStore` to Java | Loses Kotlin features (default args, the small-but-real `FakeDataStore` API uses them). Also: it's a `:data`-test concept, the rest of the codebase is Kotlin — Java would be a foreign-feeling outlier. |
| New `:test-fixtures-data` Gradle module dedicated to test fixtures | Adds a whole module to the build for one file. Build-time and reader-overhead cost > the win. |
| Move `FakeDataStore` to `:data/src/main/` with `@VisibleForTesting` | Test-only code in production APK. Direct violation of the project's layering — the Brocken-C cleanup explicitly removed inverse leaks like this. Hard no. |
| Move `FakeDataStore` to `:domain/src/testFixtures/` | `:domain` is `kotlin("jvm")` — it cannot consume the `androidx.datastore.preferences` AAR (the same blocker that pushed Brocken-C work to introduce `KolibriLog`). Hard no for the same reason. |

## Why this file lives in `:data`

This is local to `:data`'s build configuration. It would be confusing
to put under `docs/` (we don't have a `docs/` tree, and it isn't
project-wide architecture). It would also be misleading in
`KNOWN_ISSUES.md`, which is reserved for runtime / Android-framework
issues that cannot be fixed in app code (StrictMode, OneUI). This is
neither: it is a build-tooling integration gap with a documented
unblock that future maintainers should be able to recognize and
re-apply if it ever regresses.

`TODO.md` §13 contains the high-level status; this file is the deep
dive a future maintainer needs to avoid redoing the investigation
from scratch.
