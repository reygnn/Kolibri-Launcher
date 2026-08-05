#!/usr/bin/env bash
# =============================================================================
# Regression test for tools/check-flow-catch-rethrow.awk
# =============================================================================
#
# Runs the awk core against a synthetic fixture and asserts that exactly the
# expected `Flow.catch { }` arms are flagged. Sibling of
# check-cancellation-rethrow-test.sh, same shape and rationale: pin the
# detection logic (report-level-log AND missing-guard) without depending on
# production source files.
#
# The fragile parts this pins:
#   - report-level-log detection: silentError / KolibriLog.w|e / Timber.w|e
#     trigger; KolibriLog.d (below the AcraTree WARN gate) does not.
#   - guard detection: `is CancellationException`, or a rethrow of a caught
#     VARIABLE (`throw e`, lowercase). `throw NewException(…)` (uppercase,
#     constructed) is NOT a guard.
#   - brace-balanced body capture across a nested try/catch.
#
# NOT wired into Gradle by design — manual-rerun test, not a CI gate.
# Production lint runs via `./gradlew checkConventions`, which uses the same
# awk against every main source (global scan).
#
# Run via:
#   ./tools/check-flow-catch-rethrow-test.sh
#
# Exit code:
#   0   awk output matched expectations
#   1   regression — actual output differs from expected
#   2   environment problem (missing awk script, mktemp failure)
# =============================================================================

set -u
script_dir="$(cd "$(dirname "$0")" && pwd)"
awk_script="$script_dir/check-flow-catch-rethrow.awk"

if [ ! -f "$awk_script" ]; then
  echo "ERROR: awk script not found: $awk_script" >&2
  exit 2
fi

tmpdir=$(mktemp -d) || { echo "ERROR: mktemp failed" >&2; exit 2; }
trap 'rm -rf "$tmpdir"' EXIT

fixture="$tmpdir/FlowFixture.kt"
cat > "$fixture" <<'EOF'
// Synthetic fixture — exercises every path of the Flow.catch rethrow awk.

fun case1Unguarded() = flow
    .catch { e ->
        // CASE 1: logs silentError, no guard. MUST trigger.
        TimberWrapper.silentError(e, "boom")
        emit(fallback)
    }

fun case2Guarded() = flow
    .catch { e ->
        // CASE 2: explicit guard first. Must NOT trigger.
        if (e is CancellationException) throw e
        TimberWrapper.silentError(e, "boom")
        emit(fallback)
    }

fun case3NarrowElseThrow() = flow
    .catch { e ->
        // CASE 3: DataStoreReadFlow idiom — else throw e propagates cancel.
        if (e is IOException) {
            TimberWrapper.silentError(e, "io")
            emit(empty())
        } else throw e
    }

fun case4NoLog() = flow
    .catch { e ->
        // CASE 4: emits a fallback, never logs. Cannot flood ACRA.
        emit(fallback)
    }

fun case5DebugOnly() = flow
    .catch { e ->
        // CASE 5: KolibriLog.d is below the WARN gate. Must NOT trigger.
        KolibriLog.d("swallowed: $e")
        emit(fallback)
    }

fun case6ThrowNewNotGuard() = flow
    .catch { e ->
        // CASE 6: logs, and only throws a NEW exception (uppercase, not a
        // rethrow of the caught var). Still a swallow. MUST trigger.
        TimberWrapper.silentError(e, "boom")
        throw IllegalStateException("wrapped")
    }

fun case7KolibriWarnUnguarded() = flow
    .catch { e ->
        // CASE 7: KolibriLog.w reaches ACRA, no guard. MUST trigger.
        KolibriLog.w(e, "warn")
        emit(fallback)
    }

fun case8TimberErrorUnguarded() = flow
    .catch { e ->
        // CASE 8: Timber.e, no guard. MUST trigger.
        Timber.e(e, "err")
        emit(fallback)
    }

fun case9NestedGuarded() = flow
    .catch { e ->
        // CASE 9: InstalledAppsRepositoryImpl shape — top guard + nested try.
        if (e is CancellationException) throw e
        try {
            TimberWrapper.silentError(e, "x")
            emit(empty())
        } catch (inner: CancellationException) {
            throw inner
        } catch (inner: Throwable) {
            TimberWrapper.silentError(inner, "y")
        }
    }

fun case10SingleLineNoLog() = flow.catch { if (it is IOException) emit(empty()) else throw it }

// CASE 11: a .catch { e -> TimberWrapper.silentError(e, "x") } inside a comment.
EOF

# Expected triggers: CASE 1, CASE 6, CASE 7, CASE 8 (the `.catch { e ->` line of
# each). Line numbers are counted against the heredoc above — recount if edited.
expected="$fixture:4:     .catch { e ->
$fixture:41:     .catch { e ->
$fixture:49:     .catch { e ->
$fixture:56:     .catch { e ->"

actual=$(awk -f "$awk_script" "$fixture")

if [ "$actual" = "$expected" ]; then
  echo "✓ Flow.catch-rethrow awk: 4 expected violations on synthetic fixture"
  echo "  (CASE 1 silentError unguarded, CASE 6 logs + throw NewException only,"
  echo "  CASE 7 KolibriLog.w unguarded, CASE 8 Timber.e unguarded)."
  echo "✓ All other cases (explicit guard, narrowing else-throw, no-log fallback,"
  echo "  KolibriLog.d below WARN gate, nested-try top guard, single-line no-log"
  echo "  arm, comment-only mention) correctly silent."
  exit 0
fi

echo "✗ Flow.catch-rethrow awk regression — actual output differs from expected."
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
