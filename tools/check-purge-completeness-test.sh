#!/usr/bin/env bash
# =============================================================================
# Regression test for tools/check-purge-completeness.awk
# =============================================================================
#
# Runs the awk core against synthetic fixtures and asserts that a declared
# preference key missing from purgeRepository() is flagged, while every
# handled / exempt / dynamic / wholesale-clear shape stays silent. Sibling of
# check-flow-catch-rethrow-test.sh, same shape and rationale.
#
# The fragile parts this pins:
#   - a real (non-comment) remove counts as handled; a comment merely mentioning
#     the key does NOT (the AUDIT-4 loophole).
#   - a `purge-exempt NAME` marker exempts a key kept across reset.
#   - dynamic camelCase local keys are not required.
#   - a wholesale `.clear()` exempts the whole file.
#   - multi-line `val NAME =` \n `booleanPreferencesKey(...)` declarations.
#   - a file with no purgeRepository() is skipped.
#
# NOT wired into Gradle by design — manual-rerun test, not a CI gate.
#
# Run via:
#   ./tools/check-purge-completeness-test.sh
#
# Exit code:
#   0   awk output matched expectations
#   1   regression — actual output differs from expected
#   2   environment problem (missing awk script, mktemp failure)
# =============================================================================

set -u
script_dir="$(cd "$(dirname "$0")" && pwd)"
awk_script="$script_dir/check-purge-completeness.awk"

if [ ! -f "$awk_script" ]; then
  echo "ERROR: awk script not found: $awk_script" >&2
  exit 2
fi

tmpdir=$(mktemp -d) || { echo "ERROR: mktemp failed" >&2; exit 2; }
trap 'rm -rf "$tmpdir"' EXIT

fail=0

# ── Fixture 1: mixed — one missing key (MUST flag), everything else handled ──
f1="$tmpdir/MixedRepositoryImpl.kt"
cat > "$f1" <<'EOF'
class MixedRepositoryImpl {
    private object PreferenceKeys {
        val SORT_ORDER = stringPreferencesKey("sort_order")
        val TEXT_COLOR = intPreferencesKey("text_color")
        val MISSING_KEY = booleanPreferencesKey("missing_key")
        val WRAPPED_KEY =
            floatPreferencesKey("wrapped_key")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    }

    override suspend fun purgeRepository() {
        safeEdit { preferences ->
            preferences.remove(PreferenceKeys.SORT_ORDER)
            preferences.remove(PreferenceKeys.TEXT_COLOR)
            preferences.remove(PreferenceKeys.WRAPPED_KEY)
            // MISSING_KEY is only named in this comment — must NOT count.
            // purge-exempt: ONBOARDING_COMPLETED — kept across reset.
        }
    }
}
EOF

# Only MISSING_KEY (line 5) should flag.
expected1="$f1:5:         val MISSING_KEY = booleanPreferencesKey(\"missing_key\")"
actual1=$(awk -f "$awk_script" "$f1")
if [ "$actual1" != "$expected1" ]; then
  echo "✗ Fixture 1 (mixed) regression:"
  diff <(printf '%s\n' "$expected1") <(printf '%s\n' "$actual1") || true
  fail=1
else
  echo "✓ Fixture 1: only the comment-only-mentioned key flags; real remove,"
  echo "  wrapped decl and purge-exempt marker are silent."
fi

# ── Fixture 2: dynamic camelCase keys + wholesale clear — MUST stay silent ──
f2="$tmpdir/DynamicRepositoryImpl.kt"
cat > "$f2" <<'EOF'
class DynamicRepositoryImpl {
    suspend fun writeOne(pkg: String) {
        val usageKey = stringSetPreferencesKey(PREFIX + pkg)
        dataStore.edit { it[usageKey] = setOf() }
    }

    override suspend fun purgeRepository() {
        dataStore.edit { preferences -> preferences.clear() }
    }
}
EOF
actual2=$(awk -f "$awk_script" "$f2")
if [ -n "$actual2" ]; then
  echo "✗ Fixture 2 (dynamic + clear) should be silent, got:"
  echo "$actual2"
  fail=1
else
  echo "✓ Fixture 2: camelCase dynamic key not required; wholesale .clear() exempts file."
fi

# ── Fixture 3: no purgeRepository() at all — MUST be skipped (silent) ──
f3="$tmpdir/NoPurgeRepositoryImpl.kt"
cat > "$f3" <<'EOF'
class NoPurgeRepositoryImpl {
    private object PreferenceKeys {
        val SOME_KEY = stringPreferencesKey("some_key")
    }
}
EOF
actual3=$(awk -f "$awk_script" "$f3")
if [ -n "$actual3" ]; then
  echo "✗ Fixture 3 (no purge) should be silent, got:"
  echo "$actual3"
  fail=1
else
  echo "✓ Fixture 3: file without purgeRepository() is skipped."
fi

if [ "$fail" -eq 0 ]; then
  echo "✓ purge-completeness awk: all fixtures behaved as expected."
  exit 0
fi
echo "✗ purge-completeness awk regression — see diffs above."
exit 1
