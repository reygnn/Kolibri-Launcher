#!/usr/bin/env bash
# =============================================================================
# Regression test for tools/check-exception-breadth.awk
# =============================================================================
#
# Runs the awk core against a synthetic fixture and asserts that a bare
# `catch (e: Exception)` flags while `Throwable`, a narrowed type, an
# `Exception sufficient` marker, and a comment-only mention stay silent. Sibling
# of check-conventions-test.sh, same shape and rationale.
#
# The fragile parts this pins:
#   - only `catch (ident: Exception)` flags; `catch (ident: Throwable)` and
#     narrowed types (`IOException`, `SecurityException`) do not.
#   - the `Exception sufficient` marker within ±5 lines exempts.
#   - a comment line mentioning the catch never flags.
#
# NOT wired into Gradle by design — manual-rerun test, not a CI gate.
#
# Run via:
#   ./tools/check-exception-breadth-test.sh
#
# Exit code:
#   0   awk output matched expectations
#   1   regression — actual output differs from expected
#   2   environment problem (missing awk script, mktemp failure)
# =============================================================================

set -u
script_dir="$(cd "$(dirname "$0")" && pwd)"
awk_script="$script_dir/check-exception-breadth.awk"

if [ ! -f "$awk_script" ]; then
  echo "ERROR: awk script not found: $awk_script" >&2
  exit 2
fi

tmpdir=$(mktemp -d) || { echo "ERROR: mktemp failed" >&2; exit 2; }
trap 'rm -rf "$tmpdir"' EXIT

fixture="$tmpdir/BoundaryFixture.kt"
cat > "$fixture" <<'EOF'
// Synthetic fixture — exercises every path of the exception-breadth awk.

fun case1BareException() {
    try {
        createBitmap(w, h)
    } catch (e: Exception) {
        // CASE 1: bare Exception at an allocation boundary. MUST flag.
        handle(e)
    }
}

fun case2Throwable() {
    try {
        composeToBitmap()
    } catch (e: Throwable) {
        // CASE 2: widened to Throwable. Must NOT flag.
        handle(e)
    }
}

fun case3Narrowed() {
    try {
        stream.read()
    } catch (e: IOException) {
        // CASE 3: narrowed type. Must NOT flag.
        handle(e)
    }
}

fun case4Marked() {
    try {
        contentResolver.openInputStream(uri)?.use { true }
    } catch (e: Exception) {
        // CASE 4: pure-I/O probe — Exception sufficient (no allocation).
        false
    }
}

// CASE 5: a comment mentioning catch (e: Exception) must never flag.
EOF

# Only CASE 1 (the `catch (e: Exception)` on line 6) should flag.
expected="$fixture:6:     } catch (e: Exception) {"
actual=$(awk -f "$awk_script" "$fixture")

if [ "$actual" = "$expected" ]; then
  echo "✓ exception-breadth awk: 1 expected violation on synthetic fixture"
  echo "  (CASE 1 bare catch(Exception) at allocation boundary)."
  echo "✓ All other cases (Throwable, narrowed IOException, Exception-sufficient"
  echo "  marker, comment-only mention) correctly silent."
  exit 0
fi

echo "✗ exception-breadth awk regression — actual output differs from expected."
echo
echo "── EXPECTED ──"; echo "$expected"
echo
echo "── ACTUAL ──"; echo "$actual"
echo
echo "── DIFF (expected vs actual) ──"
diff <(printf '%s\n' "$expected") <(printf '%s\n' "$actual") || true
exit 1
