#!/usr/bin/env bash
# =============================================================================
# Regression test for tools/check-test-dispatcher.awk (A7) and
# tools/check-test-assertions.awk (A12)
# =============================================================================
# Runs both awk cores against synthetic fixtures and asserts exactly the
# expected lines flag. Same shape as the sibling *-test.sh files: NOT wired
# into Gradle, manual rerun when the detectors change.
#
# Exit code: 0 ok, 1 regression, 2 environment problem.
# =============================================================================
set -u
d="$(cd "$(dirname "$0")" && pwd)"
tmp=$(mktemp -d) || { echo "ERROR: mktemp failed" >&2; exit 2; }
trap 'rm -rf "$tmp"' EXIT
fail=0

check() { # name awk file expected-line-numbers
  local got; got=$(awk -f "$2" "$3" | cut -d: -f2 | tr '\n' ' ' | sed 's/ $//')
  if [ "$got" != "$4" ]; then echo "FAIL $1: expected lines [$4], got [$got]"; awk -f "$2" "$3"; fail=1; else echo "ok   $1"; fi
}

cat > "$tmp/DispatcherFixture.kt" <<'KT'
class DispatcherFixture {
  val s1 = TestScope()                                             // 2 flag
  val d1 = StandardTestDispatcher()                                // 3 flag
  fun a() = runTest { val j = launch(UnconfinedTestDispatcher()) { } } // 4 flag (use recordEmissions)
  fun b() = runTest { backgroundScope.launch(UnconfinedTestDispatcher()) { } } // 5 flag
  fun c() = runTest { launch(UnconfinedTestDispatcher(testScheduler)) { } }    // 6 flag
  val e = StandardTestDispatcher(testScheduler)                    // 7 ok
  val f = UnconfinedTestDispatcher(mainDispatcherRule.testDispatcher.scheduler) // 8 ok
  val g = Repo(UnconfinedTestDispatcher())                         // 9 flag
  val h = StandardTestDispatcher(otherScheduler)                   // 10 flag
  // val c1 = TestScope()                                          // 11 comment ok
  /* StandardTestDispatcher() */                                   // 12 comment ok
  /**
   * TestScope() in KDoc                                           // 14 ok
   */
  init { Dispatchers.setMain(d1) }                                 // 16 flag
  val i = MyTestScope()                                            // 17 ok (other identifier)
}
KT
check "A7 fixture" "$d/check-test-dispatcher.awk" "$tmp/DispatcherFixture.kt" "2 3 4 5 6 9 10 16"

mkdir -p "$tmp/rule"
cat > "$tmp/rule/MainDispatcherRule.kt" <<'KT'
class MainDispatcherRule : TestWatcher() {
  val testDispatcher: TestDispatcher = StandardTestDispatcher()
  override fun starting(d: Description) { Dispatchers.setMain(testDispatcher) }
}
KT
check "A7 rule file exempt" "$d/check-test-dispatcher.awk" "$tmp/rule/MainDispatcherRule.kt" ""

cat > "$tmp/rule/RecordEmissions.kt" <<'KT'
fun <T> TestScope.recordEmissions(flow: Flow<T>, into: MutableCollection<in T>): Job =
    launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { into.add(it) } }
KT
check "A7 recorder file exempt" "$d/check-test-dispatcher.awk" "$tmp/rule/RecordEmissions.kt" ""
printf 'val r = launch(UnconfinedTestDispatcher(testScheduler)) { }\n' > "$tmp/Other.kt"
check "A7 testScheduler outside recorder" "$d/check-test-dispatcher.awk" "$tmp/Other.kt" "1"

cat > "$tmp/AssertFixture.kt" <<'KT'
import org.junit.Assert.assertEquals
import org.junit.Assert
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.Test
import com.google.common.truth.Truth.assertThat
class AssertFixture {
  @Test(expected = IllegalStateException::class)
  fun a() { Assert.assertTrue(true) }
  fun b() { org.junit.Assert.fail("x") }
  fun c() { assertThat(1).isEqualTo(1) }
  // Assert.assertTrue(false) in a comment
  fun d() { assertFailsWith<IllegalStateException> { error("x") } }
}
KT
check "A12 fixture" "$d/check-test-assertions.awk" "$tmp/AssertFixture.kt" "1 2 3 9 10 11"

[ "$fail" -eq 0 ] && echo "✓ A7/A12 detectors OK" || exit 1
