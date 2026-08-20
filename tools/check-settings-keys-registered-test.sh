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

# ── Fixture 4: SUBSTRING collision, exact — unregistered COLOR vs registered
#    TEXT_COLOR MUST flag (token match, not index() substring). This is the
#    data-loss false-negative the multi-agent review found. ──
f4="$tmpdir/ColorRepositoryImpl.kt"
cat > "$f4" <<'EOF'
class ColorRepositoryImpl : OwnsSettingsStoreKeys {
    private object PreferencesKeys {
        val TEXT_COLOR = intPreferencesKey("text_color")
        val COLOR = intPreferencesKey("color")
    }
    override fun ownedExactKeys(): Set<String> = setOf(
        PreferencesKeys.TEXT_COLOR.name,
    )
}
EOF
expected4="$f4:4:         val COLOR = intPreferencesKey(\"color\")"
actual4=$(awk -f "$awk_script" "$f4")
if [ "$actual4" != "$expected4" ]; then
  echo "✗ Fixture 4 (exact substring collision) regression:"
  diff <(printf '%s\n' "$expected4") <(printf '%s\n' "$actual4") || true
  fail=1
else
  echo "✓ Fixture 4: unregistered COLOR flags although it is a substring of registered TEXT_COLOR."
fi

# ── Fixture 5: SUBSTRING collision, prefix — unregistered NAME vs registered
#    KEY_NAME_PREFIX MUST flag. ──
f5="$tmpdir/NamePrefixRepositoryImpl.kt"
cat > "$f5" <<'EOF'
class NamePrefixRepositoryImpl : OwnsSettingsStoreKeys {
    override fun ownedKeyPrefixes(): Set<String> = setOf(AppConstants.KEY_NAME_PREFIX)
    suspend fun write(pkg: String) {
        val k1 = stringPreferencesKey(AppConstants.KEY_NAME_PREFIX + pkg)
        val k2 = stringPreferencesKey(AppConstants.NAME + pkg)
    }
}
EOF
expected5="$f5:5:         val k2 = stringPreferencesKey(AppConstants.NAME + pkg)"
actual5=$(awk -f "$awk_script" "$f5")
if [ "$actual5" != "$expected5" ]; then
  echo "✗ Fixture 5 (prefix substring collision) regression:"
  diff <(printf '%s\n' "$expected5") <(printf '%s\n' "$actual5") || true
  fail=1
else
  echo "✓ Fixture 5: unregistered NAME prefix flags although it is a substring of registered KEY_NAME_PREFIX."
fi

# ── Fixture 6: TYPE ANNOTATION — an explicitly-typed unregistered key MUST still
#    be detected as a declared key and flagged (the val NAME[: Type] = regex). ──
f6="$tmpdir/TypedRepositoryImpl.kt"
cat > "$f6" <<'EOF'
class TypedRepositoryImpl : OwnsSettingsStoreKeys {
    private object PreferencesKeys {
        val WIDGET_ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("widget_enabled")
    }
    override fun ownedExactKeys(): Set<String> = emptySet()
}
EOF
expected6="$f6:3:         val WIDGET_ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey(\"widget_enabled\")"
actual6=$(awk -f "$awk_script" "$f6")
if [ "$actual6" != "$expected6" ]; then
  echo "✗ Fixture 6 (typed declaration) regression:"
  diff <(printf '%s\n' "$expected6") <(printf '%s\n' "$actual6") || true
  fail=1
else
  echo "✓ Fixture 6: explicitly-typed unregistered key is detected and flags."
fi

# ── Fixture 7: WRAPPED prefix key — the PreferencesKey( opening and its
#    PREFIX + entity argument split across two lines. The prefix branch now has
#    the same n+1 lookahead as the exact branch, so an unregistered wrapped
#    prefix MUST still flag (and exactly once, on the opening line). ──
f7="$tmpdir/WrapPrefixRepositoryImpl.kt"
cat > "$f7" <<'EOF'
class WrapPrefixRepositoryImpl : OwnsSettingsStoreKeys {
    override fun ownedKeyPrefixes(): Set<String> = setOf(AppConstants.KEY_NAME_PREFIX)
    suspend fun write(pkg: String) {
        val k = stringPreferencesKey(
            AppConstants.MISSING_PREFIX + pkg)
    }
}
EOF
expected7="$f7:4:         val k = stringPreferencesKey("
actual7=$(awk -f "$awk_script" "$f7")
if [ "$actual7" != "$expected7" ]; then
  echo "✗ Fixture 7 (wrapped prefix key) regression:"
  diff <(printf '%s\n' "$expected7") <(printf '%s\n' "$actual7") || true
  fail=1
else
  echo "✓ Fixture 7: unregistered prefix wrapped across the open-paren flags once on the opening line."
fi

# ── Fixture 8: an unregistered EXACT key on the line directly ABOVE a dynamic
#    prefix key MUST still flag. The exact branch appends n+1 only for a real
#    wrap now; appending unconditionally let the next line's `(PREFIX + x)`
#    misclassify this exact key as a prefix key and skip it (round-3 finding). ──
f8="$tmpdir/AdjacentRepositoryImpl.kt"
cat > "$f8" <<'EOF'
class AdjacentRepositoryImpl : OwnsSettingsStoreKeys {
    override fun ownedKeyPrefixes(): Set<String> = setOf(AppConstants.KEY_NAME_PREFIX)
    override fun ownedExactKeys(): Set<String> = emptySet()
    private val NEW_TOGGLE = booleanPreferencesKey("new_toggle")
    private val dynamicSample = stringPreferencesKey(AppConstants.KEY_NAME_PREFIX + "x")
}
EOF
expected8="$f8:4:     private val NEW_TOGGLE = booleanPreferencesKey(\"new_toggle\")"
actual8=$(awk -f "$awk_script" "$f8")
if [ "$actual8" != "$expected8" ]; then
  echo "✗ Fixture 8 (exact key adjacent to prefix key) regression:"
  diff <(printf '%s\n' "$expected8") <(printf '%s\n' "$actual8") || true
  fail=1
else
  echo "✓ Fixture 8: unregistered exact key directly above a prefix key still flags (not misclassified)."
fi

if [ "$fail" -eq 0 ]; then
  echo "✓ settings-keys-registered awk: all fixtures behaved as expected."
  exit 0
fi
echo "✗ settings-keys-registered awk regression — see diffs above."
exit 1
