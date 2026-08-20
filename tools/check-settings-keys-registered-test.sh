#!/usr/bin/env bash
# =============================================================================
# Regression test for tools/check-settings-keys-registered.awk
# =============================================================================
#
# Runs the awk core against synthetic OwnsSettingsStoreKeys owner fixtures and
# asserts:
#   - an exact key declared but NOT registered in ownedExactKeys() is flagged,
#     while registered exact keys (incl. multi-line wrapped decls) stay silent;
#   - a dynamic prefix key whose prefix IS in ownedKeyPrefixes() stays silent;
#   - a dynamic prefix key whose prefix is NOT registered is flagged;
#   - lowercase local `val` keys never trigger the exact branch.
#
# Sibling of check-purge-completeness-test.sh, same shape and rationale. NOT
# wired into Gradle by design — manual-rerun test, not a CI gate.
#
# Run via:
#   ./tools/check-settings-keys-registered-test.sh
#
# Exit code:
#   0   awk output matched expectations
#   1   regression — actual output differs from expected
#   2   environment problem (missing awk script, mktemp failure)
# =============================================================================

set -u
script_dir="$(cd "$(dirname "$0")" && pwd)"
awk_script="$script_dir/check-settings-keys-registered.awk"

if [ ! -f "$awk_script" ]; then
  echo "ERROR: awk script not found: $awk_script" >&2
  exit 2
fi

tmpdir=$(mktemp -d) || { echo "ERROR: mktemp failed" >&2; exit 2; }
trap 'rm -rf "$tmpdir"' EXIT

fail=0

# ── Fixture 1: exact owner, one key missing from ownedExactKeys() — MUST flag ──
f1="$tmpdir/FooRepositoryImpl.kt"
cat > "$f1" <<'EOF'
class FooRepositoryImpl : FooRepository, OwnsSettingsStoreKeys {
    private object PreferencesKeys {
        val ALPHA = stringPreferencesKey("alpha")
        val BETA = stringPreferencesKey("beta")
        val GAMMA = intPreferencesKey("gamma")
    }
    override fun ownedExactKeys(): Set<String> = setOf(
        PreferencesKeys.ALPHA.name,
        PreferencesKeys.BETA.name,
    )
}
EOF
# Only GAMMA (line 5) is unregistered.
expected1="$f1:5:         val GAMMA = intPreferencesKey(\"gamma\")"
actual1=$(awk -f "$awk_script" "$f1")
if [ "$actual1" != "$expected1" ]; then
  echo "✗ Fixture 1 (missing exact key) regression:"
  diff <(printf '%s\n' "$expected1") <(printf '%s\n' "$actual1") || true
  fail=1
else
  echo "✓ Fixture 1: only the unregistered exact key flags; registered ones silent."
fi

# ── Fixture 2: prefix owner, prefix registered + multi-line exact — silent ──
f2="$tmpdir/GoodNamesRepositoryImpl.kt"
cat > "$f2" <<'EOF'
class GoodNamesRepositoryImpl : OwnsSettingsStoreKeys {
    private object PreferencesKeys {
        val ONE = stringPreferencesKey("one")
        val TWO =
            floatPreferencesKey("two")
    }
    override fun ownedExactKeys(): Set<String> = setOf(
        PreferencesKeys.ONE.name,
        PreferencesKeys.TWO.name,
    )
    override fun ownedKeyPrefixes(): Set<String> = setOf(AppConstants.KEY_NAME_PREFIX)
    suspend fun write(pkg: String) {
        val nameKey = stringPreferencesKey(AppConstants.KEY_NAME_PREFIX + pkg)
    }
}
EOF
actual2=$(awk -f "$awk_script" "$f2")
if [ -n "$actual2" ]; then
  echo "✗ Fixture 2 (fully registered) should be silent, got:"
  echo "$actual2"
  fail=1
else
  echo "✓ Fixture 2: registered exact (incl. wrapped) + registered prefix are silent;"
  echo "  lowercase local val is not an exact key."
fi

# ── Fixture 3: prefix key whose prefix is NOT registered — MUST flag ──
f3="$tmpdir/BadNamesRepositoryImpl.kt"
cat > "$f3" <<'EOF'
class BadNamesRepositoryImpl : OwnsSettingsStoreKeys {
    override fun ownedKeyPrefixes(): Set<String> = emptySet()
    suspend fun write(pkg: String) {
        val nameKey = stringPreferencesKey(SOME_PREFIX + pkg)
    }
}
EOF
expected3="$f3:4:         val nameKey = stringPreferencesKey(SOME_PREFIX + pkg)"
actual3=$(awk -f "$awk_script" "$f3")
if [ "$actual3" != "$expected3" ]; then
  echo "✗ Fixture 3 (unregistered prefix) regression:"
  diff <(printf '%s\n' "$expected3") <(printf '%s\n' "$actual3") || true
  fail=1
else
  echo "✓ Fixture 3: dynamic prefix key with no matching ownedKeyPrefixes() entry flags."
fi

if [ "$fail" -eq 0 ]; then
  echo "✓ settings-keys-registered awk: all fixtures behaved as expected."
  exit 0
fi
echo "✗ settings-keys-registered awk regression — see diffs above."
exit 1
