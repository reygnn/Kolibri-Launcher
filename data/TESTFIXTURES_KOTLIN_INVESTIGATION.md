# `:data` testFixtures with Kotlin — investigation log

**Status:** open / blocking. Last attempted **2026-05-03**.

**Tool versions at time of investigation:**

| Tool | Version |
|---|---|
| Android Gradle Plugin | `8.13.2` |
| Kotlin Gradle Plugin   | `2.2.21` |
| Gradle wrapper | `8.14.3` |
| `kotlin-android` plugin | applied via `alias(libs.plugins.kotlin.android)` |

If you are reading this in a future session and any of these have changed
significantly, **re-run the reproduction below first** before assuming
the conclusions still hold.

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

---

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

---

## Reproducing it

To reproduce in the current repo state:

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

---

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

---

## Hypothesised root cause

The `kotlin-android` plugin integrates with AGP via the variants API
(`androidComponents { onVariants { … } }`). It registers Kotlin
compile tasks for the variants it knows about: `debug`, `release`,
`debugUnitTest`, `releaseUnitTest`, `debugAndroidTest`. The
**`testFixtures` variant** (`debugTestFixtures`,
`releaseTestFixtures`) is a separate variant added by AGP 8+, and the
Kotlin compile task for it does not get registered automatically by
the version of `kotlin-android` shipped with KGP 2.2.21.

This is consistent with the empirical evidence (Java task exists,
Kotlin task doesn't), the error message ("seen in source, no
bytecode"), and the fact that a perfectly analogous setup works for
`:domain` (which uses `kotlin("jvm")` — a different plugin path that
handles `java-test-fixtures` natively).

**It is not 100 % verified.** I did not have web access during the
investigation to cross-check against the AGP/KGP issue tracker or the
Kotlin community-supported integration matrix. The above is the
strongest hypothesis given the symptom plus what each attempt did and
didn't move.

---

## Workarounds I considered and rejected

| Workaround | Why rejected |
|---|---|
| Hand-register a Kotlin compile task in `androidComponents.beforeVariants { … }`, wire output to the variant artefact | Internal-API hack, fragile across AGP/KGP upgrades, hard to debug if it breaks. Out of proportion to the win. |
| Convert `FakeDataStore` to Java | Loses Kotlin features (default args, the small-but-real `FakeDataStore` API uses them). Also: it's a `:data`-test concept, the rest of the codebase is Kotlin — Java would be a foreign-feeling outlier. |
| New `:test-fixtures-data` Gradle module dedicated to test fixtures | Adds a whole module to the build for one file. Build-time and reader-overhead cost > the win. |
| Move `FakeDataStore` to `:data/src/main/` with `@VisibleForTesting` | Test-only code in production APK. Direct violation of the project's layering — the Brocken-C cleanup explicitly removed inverse leaks like this. Hard no. |
| Move `FakeDataStore` to `:domain/src/testFixtures/` | `:domain` is `kotlin("jvm")` — it cannot consume the `androidx.datastore.preferences` AAR (the same blocker that pushed Brocken-C work to introduce `KolibriLog`). Hard no for the same reason. |

---

## What stayed in `:app/src/test/`

As a result, the following remained where they were:

- `app/src/test/java/com/.../fakes/FakeDataStore.kt`
- `app/src/test/java/com/.../data/*RepositoryImpl*Test.kt` — currently
  ~12 files, including the 9-strong `BackupRepositoryImpl*Test` family
  (Doomsday/Io/Isolation/Logic/Malformed/NamingConvention/Security/
  Strict/Wallpaper). All of these reach the production
  `XyzRepositoryImpl` from `:data` plus `FakeDataStore` and use
  Robolectric.

This is not architecturally wrong — these tests are Robolectric tests
either way, so they wouldn't have been faster in `:data:test` than
they are in `:app:test`. The Test-Time-Win that Brocken B promised
was always concentrated in the **310 pure-JVM tests in `:domain:test`**,
which did move successfully.

---

## How to revisit this

When AGP and / or KGP get bumped in the future, the smallest possible
re-check is:

1. Pull a working tree with this `:data/build.gradle.kts` already
   producing tests-green on main.
2. Apply the four-line change from "Reproducing it" §1+3 above.
3. `./gradlew :data:tasks --all | grep -iE 'TestFixtures.*Kotlin'`
4. If a `compileDebugTestFixturesKotlin` task now appears: the
   integration gap has been fixed. Move `FakeDataStore` and continue
   with the §13-Memo's "Was zukünftig passieren muss" steps in
   `TODO.md`.
5. If it still doesn't appear: this investigation log is still
   current. Diff the AGP/KGP changelogs since the dates above to see
   whether anything in their integration was touched, then check the
   Android Studio + Kotlin issue trackers for `testFixtures + kotlin
   + android-library`.

If you find the actual upstream issue and ticket number, add the link
under "References" below so the next visitor doesn't have to redo the
search.

---

## Why this file lives in `:data`

This is local to `:data`'s build configuration. It would be confusing
to put under `docs/` (we don't have a `docs/` tree, and it isn't
project-wide architecture). It would also be misleading in
`KNOWN_ISSUES.md`, which is reserved for runtime / Android-framework
issues that cannot be fixed in app code (StrictMode, OneUI). This is
neither: it is a build-tooling integration gap with a clear escape
path the moment the upstream tooling supports it.

`TODO.md` §13 contains the high-level status; this file is the deep
dive a future maintainer needs to avoid redoing the investigation
from scratch.

---

## References

- AGP testFixtures (general): the `android.testFixtures` block in the
  AGP DSL reference.
- KGP variant integration: the `kotlin-android` plugin's documented
  integration with AGP variants — search "kotlin-android testFixtures"
  in the Kotlin documentation and Android issue tracker.

(No exact issue link available; the investigation was offline. Add
one when you find it.)
