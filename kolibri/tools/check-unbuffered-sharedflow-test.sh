#!/usr/bin/env bash
# =============================================================================
# Regression test for tools/check-unbuffered-sharedflow.awk
# =============================================================================
#
# Runs the awk core against a synthetic fixture and asserts that exactly the
# expected `MutableSharedFlow(...)` constructions are flagged. Sibling of
# check-flow-catch-rethrow-test.sh, same shape and rationale: pin the fragile
# detection logic without depending on production source files.
#
# The fragile parts this pins:
#   - construction vs. type annotation: only `MutableSharedFlow<...>(` flags;
#     `: MutableSharedFlow<Unit>` (no call paren) does not.
#   - buffered detection: `replay`/`extraBufferCapacity` != 0 (named or a
#     non-literal constant) and a positional replay > 0 count as buffered;
#     an empty arg list and explicit `= 0` do not.
#   - multi-line arg lists (replay / extraBufferCapacity on their own lines).
#   - nested generics (`<Event<Data>>`) before the call paren.
#   - the `rendezvous intended` escape-hatch marker.
#   - comment-only mentions and import lines never flag.
#
# NOT wired into Gradle by design — manual-rerun test, not a CI gate.
# Production lint runs via `./gradlew checkConventions`, which uses the same
# awk against every main source (global scan).
#
# Run via:
#   ./tools/check-unbuffered-sharedflow-test.sh
#
# Exit code:
#   0   awk output matched expectations
#   1   regression — actual output differs from expected
#   2   environment problem (missing awk script, mktemp failure)
# =============================================================================

set -u
script_dir="$(cd "$(dirname "$0")" && pwd)"
awk_script="$script_dir/check-unbuffered-sharedflow.awk"

if [ ! -f "$awk_script" ]; then
  echo "ERROR: awk script not found: $awk_script" >&2
  exit 2
fi

tmpdir=$(mktemp -d) || { echo "ERROR: mktemp failed" >&2; exit 2; }
trap 'rm -rf "$tmpdir"' EXIT

fixture="$tmpdir/SharedFlowFixture.kt"
cat > "$fixture" <<'EOF'
// Synthetic fixture — exercises every path of the unbuffered-SharedFlow awk.
import kotlinx.coroutines.flow.MutableSharedFlow

// CASE 1: empty construction, unbuffered. MUST flag.
val c1 = MutableSharedFlow<PackageEvent>()

// CASE 2: replay = 1, buffered. Must NOT flag.
val c2 = MutableSharedFlow<Unit>(replay = 1)

// CASE 3: extraBufferCapacity = 1, buffered. Must NOT flag.
val c3 = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

// CASE 4: explicit zero buffer. MUST flag.
val c4 = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 0)

// CASE 5: multi-line buffered. Must NOT flag.
val c5 = MutableSharedFlow<Event<ErrorData>>(
    replay = 5,
    extraBufferCapacity = 10
)

// CASE 6: multi-line explicit zeros. MUST flag (on the construction line).
val c6 = MutableSharedFlow<Unit>(
    replay = 0,
    extraBufferCapacity = 0
)

// CASE 7: type annotation, not a construction. Must NOT flag.
private val c7: MutableSharedFlow<Unit>

// CASE 8: positional replay = 1. Must NOT flag.
val c8 = MutableSharedFlow<Unit>(1)

// CASE 9: positional zero replay. MUST flag.
val c9 = MutableSharedFlow<Unit>(0)

// CASE 10: non-literal buffer (constant). Must NOT flag.
val c10 = MutableSharedFlow<Unit>(extraBufferCapacity = BUFFER_SIZE)

// CASE 11: unbuffered but rendezvous intended marker. Must NOT flag.
val c11 = MutableSharedFlow<Unit>()  // rendezvous intended

// CASE 12: comment-only mention: val x = MutableSharedFlow<Unit>() ignore.

// CASE 13: nested generic, empty. MUST flag.
val c13 = MutableSharedFlow<Event<Data>>()

// CASE 14: unrelated Channel(BUFFERED). Must NOT flag.
val c14 = Channel<Unit>(Channel.BUFFERED)
EOF

# Expected flags: CASE 1 (l.5), CASE 4 (l.14), CASE 6 (l.23), CASE 9 (l.35),
# CASE 13 (l.46). Line numbers are counted against the heredoc above — recount
# if edited.
expected="$fixture:5: val c1 = MutableSharedFlow<PackageEvent>()
$fixture:14: val c4 = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 0)
$fixture:23: val c6 = MutableSharedFlow<Unit>(
$fixture:35: val c9 = MutableSharedFlow<Unit>(0)
$fixture:46: val c13 = MutableSharedFlow<Event<Data>>()"

actual=$(awk -f "$awk_script" "$fixture")

if [ "$actual" = "$expected" ]; then
  echo "✓ unbuffered-SharedFlow awk: 5 expected violations on synthetic fixture"
  echo "  (CASE 1 empty, CASE 4 explicit zeros, CASE 6 multi-line zeros,"
  echo "  CASE 9 positional zero replay, CASE 13 nested-generic empty)."
  echo "✓ All other cases (replay/extraBufferCapacity > 0, multi-line buffered,"
  echo "  type annotation, positional replay > 0, non-literal buffer,"
  echo "  rendezvous-intended marker, comment-only mention, import, Channel)"
  echo "  correctly silent."
  exit 0
fi

echo "✗ unbuffered-SharedFlow awk regression — actual output differs from expected."
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
