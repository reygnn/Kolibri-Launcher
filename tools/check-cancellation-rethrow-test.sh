#!/usr/bin/env bash
# =============================================================================
# Regression test for tools/check-cancellation-rethrow.awk
# =============================================================================
#
# Runs the awk core against synthetic fixtures and asserts that exactly the
# expected catches are flagged. Sibling of check-conventions-test.sh, same
# shape and same rationale: pin the regex + upward-walk logic without
# depending on production source files.
#
# The upward walk (case (a) detection) is the fragile part — it decides
# whether a CancellationException arm belongs to the SAME try-statement as
# the broad catch below it. CASE 6 and CASE 7 are the two sides of that
# boundary and are the reason this file exists.
#
# NOT wired into Gradle by design — manual-rerun test, not a CI gate.
# Production lint runs via `./gradlew checkConventions`, which uses the same
# awk script against the whitelist.
#
# Run via:
#   ./tools/check-cancellation-rethrow-test.sh
#
# Exit code:
#   0   awk output matched expectations
#   1   regression — actual output differs from expected
#   2   environment problem (missing awk script, mktemp failure)
# =============================================================================

set -u
script_dir="$(cd "$(dirname "$0")" && pwd)"
awk_script="$script_dir/check-cancellation-rethrow.awk"

if [ ! -f "$awk_script" ]; then
  echo "ERROR: awk script not found: $awk_script" >&2
  exit 2
fi

tmpdir=$(mktemp -d) || { echo "ERROR: mktemp failed" >&2; exit 2; }
trap 'rm -rf "$tmpdir"' EXIT

fixture="$tmpdir/Fixture.kt"
cat > "$fixture" <<'EOF'
// Synthetic fixture — exercises every code path of the cancellation-
// rethrow awk check. Each catch carries a `// CASE n:` tag so failure
// messages can be matched back to the intent.

suspend fun case1RethrowArmAdjacent() {
    try { loader.load(uri) }
    catch (e: CancellationException) {
        throw e
    }
    catch (e: Throwable) {
        // CASE 1: structurally satisfied by the arm above. Must NOT
        // trigger, and must need no marker comment.
        log(e)
    }
}

suspend fun case2RethrowArmWithComments() {
    try { loader.load(uri) }
    catch (e: CancellationException) {
        // Rethrow per canonical: comments and a bare `throw` sit
        // between the two arms. The upward walk skips both.
        throw e
    }
    catch (e: Throwable) {
        // CASE 2: still structurally satisfied. Must NOT trigger.
        log(e)
    }
}

fun case3MarkedNoSuspension() {
    view.post {
        try { render() }
        catch (e: Throwable) {
            // CASE 3: No suspension point — plain Runnable. Marker
            // present, so this must NOT trigger.
            log(e)
        }
    }
}

suspend fun case4BareBroadCatch() {
    try { loader.load(uri) }
    catch (e: Throwable) {
        // CASE 4: the actual bug shape — no rethrow arm, no marker.
        // MUST trigger.
        log(e)
    }
}

suspend fun case5BareBroadException() {
    try { loader.load(uri) }
    catch (e: Exception) {
        // CASE 5: broad Exception type, same treatment. MUST trigger.
        log(e)
    }
}

suspend fun case6UnrelatedEarlierRethrow() {
    try { first() }
    catch (e: CancellationException) {
        throw e
    }
    catch (e: Throwable) {
        log(e)
    }

    doSomethingSubstantive()

    try { second() }
    catch (e: Throwable) {
        // CASE 6: the CancellationException arm far above belongs to a
        // DIFFERENT try. Substantive code stands between them, so the
        // upward walk stops before reaching it. MUST trigger.
        log(e)
    }
}

fun case7NarrowedType() {
    try { work() }
    catch (e: IOException) {
        // CASE 7: narrowed type cannot swallow a CancellationException.
        // Must NOT trigger.
        log(e)
    }
}

fun case8KDocComment() {
    /**
     * } catch (e: Throwable) {  // CASE 8: appears inside KDoc.
     */
    work()
}

suspend fun case9InBodyGuardSingleLine() {
    try { loader.load(uri) }
    catch (e: Throwable) {
        // CASE 9: the SettingsRepositoryImpl idiom — guard + throw on one
        // line inside the body. Correct code, must NOT trigger.
        if (e is CancellationException || e !is Exception) throw e
        log(e)
    }
}

suspend fun case10InBodyGuardMultiLine() {
    try { loader.load(uri) }
    catch (e: Throwable) {
        // CASE 10: same guard, block form. Must NOT trigger.
        if (e is CancellationException) {
            throw e
        }
        log(e)
    }
}

suspend fun case11RunCatchingBare() {
    val result = runCatching {
        // CASE 11: runCatching swallows CancellationException and has no
        // `catch` keyword at all. MUST trigger.
        loader.load(uri)
    }
    log(result)
}

fun case12RunCatchingMarked() {
    // CASE 12: No suspension point — pure parsing, no coroutine in sight.
    val result = runCatching { Integer.parseInt(raw) }
    log(result)
}

fun case13RunCatchingMentionedInProse() {
    // We deliberately avoid runCatching here so cancellation propagates.
    // CASE 13: prose mention on a comment line. Must NOT trigger.
    work()
}
EOF

# Expected triggers: CASE 4, CASE 5, CASE 6, CASE 11. Line numbers are
# counted against the heredoc above — recount them if the fixture is edited.
expected="$fixture:43:     catch (e: Throwable) {
$fixture:52:     catch (e: Exception) {
$fixture:70:     catch (e: Throwable) {
$fixture:116:     val result = runCatching {"

actual=$(awk -f "$awk_script" "$fixture")

if [ "$actual" = "$expected" ]; then
  echo "✓ Cancellation-rethrow awk: 4 expected violations on synthetic fixture"
  echo "  (CASE 4 bare catch, CASE 5 broad Exception, CASE 6 unrelated earlier"
  echo "  arm, CASE 11 bare runCatching)."
  echo "✓ All other cases (adjacent rethrow arm, rethrow arm behind comments,"
  echo "  in-body guard single-line + block form, 'no suspension point' marker,"
  echo "  narrowed type, KDoc-only catch, prose mention of runCatching)"
  echo "  correctly silent."
  exit 0
fi

echo "✗ Cancellation-rethrow awk regression — actual output differs from expected."
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
