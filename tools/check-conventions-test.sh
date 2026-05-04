#!/usr/bin/env bash
# =============================================================================
# Regression test for tools/check-rule11-annotation.awk
# =============================================================================
#
# Runs the awk core against synthetic fixtures and asserts that exactly
# the expected catches are flagged. Catches drift in the regex (e.g. a
# refactor that breaks line-broken-marker detection) without depending
# on production source files.
#
# NOT wired into Gradle by design — this is a manual-rerun test, not a
# CI gate. Production lint runs via `./gradlew checkConventions` which
# uses the same awk script against the whitelist.
#
# Run via:
#   ./tools/check-conventions-test.sh
#
# Exit code:
#   0   awk output matched expectations
#   1   regression — actual output differs from expected
#   2   environment problem (missing awk script, mktemp failure)
# =============================================================================

set -u
script_dir="$(cd "$(dirname "$0")" && pwd)"
awk_script="$script_dir/check-rule11-annotation.awk"

if [ ! -f "$awk_script" ]; then
  echo "ERROR: awk script not found: $awk_script" >&2
  exit 2
fi

tmpdir=$(mktemp -d) || { echo "ERROR: mktemp failed" >&2; exit 2; }
trap 'rm -rf "$tmpdir"' EXIT

fixture="$tmpdir/Fixture.kt"
cat > "$fixture" <<'EOF'
// Synthetic fixture — exercises every code path of the Rule 11 awk
// check. Each catch carries a `// CASE n:` tag so failure messages
// can be matched back to the intent.

fun case1AnnotatedDirect() {
    try { work() }
    catch (e: Throwable) {
        // CASE 1: Catch kept (Expected error, four-category frame).
        // Single-line marker → must NOT trigger.
        log(e)
    }
}

fun case2AnnotatedLineBroken() {
    try { work() }
    catch (e: Throwable) {
        // CASE 2: Inner catch kept (Expected error, four-category
        // frame): marker phrase split across two lines. The
        // `[Cc]atch[a-z]* kept` matcher fires on line 1 → must NOT
        // trigger.
        log(e)
    }
}

fun case3UnannotatedThrowable() {
    try { work() }
    catch (e: Throwable) {
        // CASE 3: no marker comment of any form. MUST trigger.
        log(e)
    }
}

fun case4UnannotatedException() {
    try { work() }
    catch (e: Exception) {
        // CASE 4: broad Exception type, no marker. MUST trigger.
        log(e)
    }
}

fun case5NarrowedType() {
    try { work() }
    catch (e: SecurityException) {
        // CASE 5: narrowed type, no marker. Per Rule 11 narrowing is
        // the goal — must NOT trigger.
        log(e)
    }
}

fun case6CancellationRethrow() {
    try { work() }
    catch (e: CancellationException) {
        // CASE 6: Rethrow per canonical Kotlin coroutines guidance.
        // Must NOT trigger (Rethrow marker accepted).
        throw e
    }
}

fun case7TripleCatchThrowableArm() {
    try { work() }
    catch (e: ActivityNotFoundException) {
        // Triple-catch kept (Expected error, four-category frame):
        // narrowed branch — not checked anyway.
        toast()
    }
    catch (e: SecurityException) {
        // narrowed — not checked.
        toast()
    }
    catch (e: Throwable) {
        // CASE 7: Throwable arm of the Triple-catch kept above
        // (four-category frame). Marker on its own line within
        // window → must NOT trigger.
        toast()
    }
}

fun case8KDocComment() {
    /**
     * } catch (e: Throwable) {  // CASE 8: appears inside KDoc.
     */
    work()
}
EOF

# CASE 8: the KDoc-only line above starts with `*` — the awk's
# leading-comment guard skips it. So the only triggers we expect
# are CASE 3 (line 27) and CASE 4 (line 35) of the heredoc above.
expected="$fixture:27:     catch (e: Throwable) {
$fixture:35:     catch (e: Exception) {"

actual=$(awk -f "$awk_script" "$fixture")

if [ "$actual" = "$expected" ]; then
  echo "✓ Rule 11 awk: 2 expected violations on synthetic fixture (CASE 3 + CASE 4)."
  echo "✓ All other cases (annotated direct/line-broken, narrowed type,"
  echo "  CancellationException rethrow, Triple-catch Throwable arm,"
  echo "  KDoc-only catch) correctly silent."
  exit 0
fi

echo "✗ Rule 11 awk regression — actual output differs from expected."
echo
echo "── EXPECTED ──"
echo "$expected"
echo
echo "── ACTUAL ──"
echo "$actual"
echo
echo "── DIFF (expected vs actual) ──"
diff <(printf '%s\n' "$expected") <(printf '%s\n' "$actual") || true
exit 1
