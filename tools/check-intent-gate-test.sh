#!/usr/bin/env bash
# =============================================================================
# Regression test for tools/check-intent-gate.awk (Rule 9, report-by-intent)
# =============================================================================
#
# Runs the awk core against a synthetic fixture and asserts that exactly the
# bare `Timber.e(` sites WITHOUT a `pre-wiring bare` marker are flagged, and
# nothing else (reportToAcra / silentError / Log.e / Timber.tag(...).e /
# Timber.d|w / marked pre-wiring sites all pass).
#
# NOT wired into Gradle by design — manual-rerun test, not a CI gate.
# Production lint runs via `./gradlew checkConventions`.
#
# Run via:  ./tools/check-intent-gate-test.sh
# Exit code: 0 = matched expectations, 1 = regression, 2 = environment problem.
# =============================================================================

set -u
script_dir="$(cd "$(dirname "$0")" && pwd)"
awk_script="$script_dir/check-intent-gate.awk"

if [ ! -f "$awk_script" ]; then
  echo "ERROR: awk script not found: $awk_script" >&2
  exit 2
fi

tmpdir=$(mktemp -d) || { echo "ERROR: mktemp failed" >&2; exit 2; }
trap 'rm -rf "$tmpdir"' EXIT

fixture="$tmpdir/Fixture.kt"
cat > "$fixture" <<'EOF'
// Synthetic fixture for the intent-gate check. Lines that MUST flag carry the
// token SHOULDFLAG; lines that must NOT carry NOFLAG.

fun case1BareTimberE() {
    try { work() } catch (e: Throwable) {
        Timber.e(e, "case1 SHOULDFLAG bare Timber.e")
    }
}

fun case2PreWiringMarked() {
    try { work() } catch (e: Exception) {
        // pre-wiring bare Timber.e: runs before KolibriLog is wired.
        Timber.e(e, "case2 NOFLAG marked pre-wiring")
    }
}

fun case3ReportToAcra() {
    try { work() } catch (e: Throwable) {
        TimberWrapper.reportToAcra(e, "case3 NOFLAG reportToAcra")
    }
}

fun case4SilentError() {
    try { work() } catch (e: Throwable) {
        TimberWrapper.silentError(e, "case4 NOFLAG silentError")
    }
}

fun case5LogE() {
    try { work() } catch (e: Throwable) {
        Log.e("Tag", "case5 NOFLAG android Log.e", e)
    }
}

fun case6TaggedTimberE() {
    try { work() } catch (e: Throwable) {
        Timber.tag("X").e(e, "case6 NOFLAG tagged")
    }
}

fun case7DebugAndWarn() {
    Timber.d("case7 NOFLAG debug")
    Timber.w(anException, "case7 NOFLAG warn")
}

fun case8MarkerTooFarAway() {
    // pre-wiring bare marker here is more than five lines above the call.
    val a = 1
    val b = 2
    val c = 3
    val d = 4
    val e2 = 5
    val f = 6
    Timber.e(err, "case8 SHOULDFLAG marker out of window")
}
EOF

actual=$(awk -f "$awk_script" "$fixture")

fail=0

# 1) Every flagged line must be a SHOULDFLAG line.
if printf '%s\n' "$actual" | grep -q 'NOFLAG'; then
  echo "FAIL: a NOFLAG line was flagged:" >&2
  printf '%s\n' "$actual" | grep 'NOFLAG' >&2
  fail=1
fi

# 2) Both SHOULDFLAG lines must be flagged.
for tok in "case1 SHOULDFLAG" "case8 SHOULDFLAG"; do
  if ! printf '%s\n' "$actual" | grep -qF "$tok"; then
    echo "FAIL: expected flag missing for: $tok" >&2
    fail=1
  fi
done

# 3) Exactly two lines flagged (the two SHOULDFLAG sites).
count=$(printf '%s\n' "$actual" | grep -c 'Timber\.e(')
if [ "$count" -ne 2 ]; then
  echo "FAIL: expected 2 flagged lines, got $count" >&2
  printf '%s\n' "$actual" >&2
  fail=1
fi

if [ "$fail" -eq 0 ]; then
  echo "OK: check-intent-gate.awk matched expectations (2 flagged, all NOFLAG pass)."
  exit 0
fi
exit 1
