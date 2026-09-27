#!/usr/bin/env bash
# =============================================================================
# Regression test for tools/check-strings-parity.awk
# =============================================================================
#
# Runs the awk core against a synthetic locale pair and asserts that both drift
# directions flag while every legitimate shape stays silent. Sibling of
# check-exception-breadth-test.sh, same shape and rationale.
#
# The fragile parts this pins:
#   - a key only in the default locale flags as MISSING-DE.
#   - a key only in the translation flags as ORPHAN-DE.
#   - `translatable="false"` in the DEFAULT locale exempts (this is the one
#     that broke during development: an earlier regex captured only as far as
#     the name attribute, leaving `translatable="false"` — which follows it —
#     outside the captured text, so all 14 real exemptions flagged).
#   - `<string-array>` and `<plurals>` are covered, and `<string ` does NOT
#     also match `<string-array ` (which would count every array twice).
#   - `<item>` inside a plurals/array is not a named top-level resource and
#     must never flag.
#
# NOT wired into Gradle by design — manual-rerun test, not a CI gate.
#
# Run via:
#   ./tools/check-strings-parity-test.sh
#
# Exit code:
#   0   awk output matched expectations
#   1   regression — actual output differs from expected
#   2   environment problem (missing awk script, mktemp failure)
# =============================================================================

set -u
script_dir="$(cd "$(dirname "$0")" && pwd)"
awk_script="$script_dir/check-strings-parity.awk"

if [ ! -f "$awk_script" ]; then
  echo "ERROR: awk script not found: $awk_script" >&2
  exit 2
fi

tmpdir=$(mktemp -d) || { echo "ERROR: mktemp failed" >&2; exit 2; }
trap 'rm -rf "$tmpdir"' EXIT

cat > "$tmpdir/default.xml" <<'EOF'
<resources>
    <string name="in_both">Settings</string>
    <string name="only_default">Untranslated</string>
    <string name="not_translatable" translatable="false">12:34</string>
    <string-array name="array_in_both">
        <item>one</item>
        <item>two</item>
    </string-array>
    <string-array name="array_only_default">
        <item>alpha</item>
    </string-array>
    <plurals name="plural_in_both">
        <item quantity="one">%d app</item>
        <item quantity="other">%d apps</item>
    </plurals>
</resources>
EOF

cat > "$tmpdir/de.xml" <<'EOF'
<resources>
    <string name="in_both">Einstellungen</string>
    <string name="only_german">Verwaist</string>
    <string-array name="array_in_both">
        <item>eins</item>
        <item>zwei</item>
    </string-array>
    <plurals name="plural_in_both">
        <item quantity="one">%d App</item>
        <item quantity="other">%d Apps</item>
    </plurals>
</resources>
EOF

# Expected, sorted for determinism (awk's for-in iteration order is undefined):
#   only_default        -> MISSING-DE  (plain untranslated key)
#   array_only_default  -> MISSING-DE  (string-array is covered too)
#   only_german         -> ORPHAN-DE   (fossil of a renamed key)
# NOT expected: in_both, array_in_both, plural_in_both (present in both),
#   not_translatable (exempt), and any <item> (not a named resource).
expected="MISSING-DE: array_only_default
MISSING-DE: only_default
ORPHAN-DE: only_german"

actual=$(awk -f "$awk_script" "$tmpdir/default.xml" "$tmpdir/de.xml" | sort)

if [ "$actual" = "$expected" ]; then
  echo "✓ strings-parity awk: 3 expected violations on the synthetic locale pair"
  echo "  (2× MISSING-DE incl. a string-array, 1× ORPHAN-DE)."
  echo "✓ translatable=\"false\", keys present in both locales, <plurals>, and"
  echo "  <item> children correctly silent."
  exit 0
fi

echo "✗ strings-parity awk regression — actual output differs from expected."
echo
echo "── EXPECTED ──"; echo "$expected"
echo
echo "── ACTUAL ──"; echo "$actual"
echo
echo "── DIFF (expected vs actual) ──"
diff <(printf '%s\n' "$expected") <(printf '%s\n' "$actual") || true
exit 1
