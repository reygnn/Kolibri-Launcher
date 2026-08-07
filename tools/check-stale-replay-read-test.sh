#!/usr/bin/env bash
# =============================================================================
# Regression test for tools/check-stale-replay-read.awk
# =============================================================================
# Runs the awk core against a synthetic fixture and asserts that exactly the
# UNMARKED hot-flow `.first()` / `.firstOrNull()` point-reads are flagged, and
# that the lookalikes are NOT: a cold `dataStore.data.first()`, a collection
# `list.first()`, a snapshot call, a `.first()` mention inside a comment, a
# token-boundary near-miss, and — the marker axis — a hot-flow read carrying a
# `stale-replay ok` marker within the ±5-line window.
#
# Pins the fragile parts: token-boundary before the flow name (no match on
# `myFavoriteComponentsFlow`), comment-line skipping, firstOrNull support, and
# marker suppression.
#
# NOT wired into Gradle — manual-rerun test, like the sibling *-test.sh files.
#   0  matched expectations   1  regression   2  environment problem
# =============================================================================
set -u
script_dir="$(cd "$(dirname "$0")" && pwd)"
awk_script="$script_dir/check-stale-replay-read.awk"
[ -f "$awk_script" ] || { echo "ERROR: awk not found: $awk_script" >&2; exit 2; }

fixture="$(mktemp)" || exit 2
trap 'rm -f "$fixture"' EXIT

cat > "$fixture" <<'EOF'
val a = favoritesRepository.favoriteComponentsFlow.first()
val b = favoritesOrderRepository.favoriteComponentsOrderFlow.firstOrNull()
val c = fabPositionFlow.first()
val cold = dataStore.data.first()[KEY]
val list = installedApps.filter { it.x }.first()
val snap = favoritesRepository.getFavoriteComponentsSnapshot()
val nb = myFavoriteComponentsFlow.first()
// note: a .first() on favoriteComponentsFlow.first() would be stale
 * bypasses the hot-shared favoriteComponentsFlow replay cache
// stale-replay ok: warm subscriber lives here
val marked = favoriteComponentsFlow.first()
EOF

# Expected: lines 1, 2, 3 flagged (unmarked hot reads). Lines 4 (cold), 5
# (collection), 6 (snapshot), 7 (token-boundary), 8 (// comment), 9 (* comment)
# and 11 (marked within ±5 of the line-10 marker) must NOT be flagged.
actual="$(awk -v hot='favoriteComponentsFlow|favoriteComponentsOrderFlow|fabPositionFlow' \
              -f "$awk_script" "$fixture" | sed -E 's/^[^:]+:([0-9]+):.*/\1/' | paste -sd, -)"
expected="1,2,3"

if [ "$actual" = "$expected" ]; then
  echo "PASS: flagged lines = $actual (expected $expected)"
  exit 0
else
  echo "FAIL: flagged lines = '$actual', expected '$expected'" >&2
  awk -v hot='favoriteComponentsFlow|favoriteComponentsOrderFlow|fabPositionFlow' -f "$awk_script" "$fixture" >&2
  exit 1
fi
