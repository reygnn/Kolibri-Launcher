#!/usr/bin/env bash
# =============================================================================
# Regression test for the two fragment-teardown awk cores:
#   - tools/check-activity-result-placement.awk  (registerForActivityResult)
#   - tools/check-adapter-nulling.awk            (RecyclerView adapter null-out)
# =============================================================================
#
# Runs each awk against synthetic fixtures and asserts exactly the expected
# violations. Same shape and rationale as the sibling check-*-test.sh scripts:
# pin the fragile detection logic without depending on production sources.
#
# The fragile parts this pins:
#   - placement: enclosing-function resolution by brace depth (awk has no `\b`);
#     onViewCreated/onResume/etc. flag, onCreate/helper/field do not.
#   - adapter: Fragment-only scope; non-null assign requires `adapter = null` in
#     onDestroyView; the `adapter-nulling n/a` marker exempts; a fragment with
#     no adapter and an Activity are silent.
#
# NOT wired into Gradle by design — manual-rerun test, not a CI gate.
#
# Run via:
#   ./tools/check-fragment-teardown-test.sh
#
# Exit code:
#   0   all fixtures behaved as expected
#   1   regression — actual output differs from expected
#   2   environment problem (missing awk script, mktemp failure)
# =============================================================================

set -u
script_dir="$(cd "$(dirname "$0")" && pwd)"
placement_awk="$script_dir/check-activity-result-placement.awk"
adapter_awk="$script_dir/check-adapter-nulling.awk"

for a in "$placement_awk" "$adapter_awk"; do
  [ -f "$a" ] || { echo "ERROR: awk script not found: $a" >&2; exit 2; }
done

tmpdir=$(mktemp -d) || { echo "ERROR: mktemp failed" >&2; exit 2; }
trap 'rm -rf "$tmpdir"' EXIT
fail=0

# ── Placement fixture: only the onViewCreated call flags ─────────────────────
pf="$tmpdir/PlacementFragment.kt"
cat > "$pf" <<'EOF'
class PlacementFragment : Fragment() {
    private val fieldLauncher = registerForActivityResult(Contract()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        val okInCreate = registerForActivityResult(Contract()) { }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        badLauncher = registerForActivityResult(Contract()) { }
    }

    private fun helper() {
        val okInHelper = registerForActivityResult(Contract()) { }
    }
}
EOF
expected_p="$pf:9:         badLauncher = registerForActivityResult(Contract()) { }"
actual_p=$(awk -f "$placement_awk" "$pf")
if [ "$actual_p" != "$expected_p" ]; then
  echo "✗ Placement fixture regression:"
  diff <(printf '%s\n' "$expected_p") <(printf '%s\n' "$actual_p") || true
  fail=1
else
  echo "✓ Placement: only the onViewCreated call flags (field / onCreate / helper silent)."
fi

# ── Adapter fixture 1: assigns adapter, never nulls in onDestroyView → flag ───
af1="$tmpdir/LeakyFragment.kt"
cat > "$af1" <<'EOF'
class LeakyFragment : Fragment() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.list.adapter = myAdapter
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
EOF
expected_a1="$af1:3:         binding.list.adapter = myAdapter"
actual_a1=$(awk -f "$adapter_awk" "$af1")
if [ "$actual_a1" != "$expected_a1" ]; then
  echo "✗ Adapter fixture 1 (leak) regression:"
  diff <(printf '%s\n' "$expected_a1") <(printf '%s\n' "$actual_a1") || true
  fail=1
else
  echo "✓ Adapter 1: adapter assigned but not nulled in onDestroyView flags."
fi

# ── Adapter fixture 2: correct null-out → silent ─────────────────────────────
af2="$tmpdir/CleanFragment.kt"
cat > "$af2" <<'EOF'
class CleanFragment : Fragment() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.list.adapter = myAdapter
    }

    override fun onDestroyView() {
        binding.list.adapter = null
        super.onDestroyView()
    }
}
EOF
actual_a2=$(awk -f "$adapter_awk" "$af2")
if [ -n "$actual_a2" ]; then
  echo "✗ Adapter fixture 2 (clean) should be silent, got:"; echo "$actual_a2"; fail=1
else
  echo "✓ Adapter 2: assign + null-out in onDestroyView is silent."
fi

# ── Adapter fixture 3: escape marker + Activity + no-adapter fragment → silent ─
af3="$tmpdir/ExemptFragment.kt"
cat > "$af3" <<'EOF'
class ExemptFragment : Fragment() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.list.adapter = pooledAdapter  // adapter-nulling n/a: owned by pool
    }
}
EOF
af4="$tmpdir/SomeActivity.kt"
cat > "$af4" <<'EOF'
class SomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        binding.list.adapter = myAdapter
    }
}
EOF
actual_a3=$(awk -f "$adapter_awk" "$af3")
actual_a4=$(awk -f "$adapter_awk" "$af4")
if [ -n "$actual_a3" ] || [ -n "$actual_a4" ]; then
  echo "✗ Adapter fixture 3/4 should be silent, got:"; echo "$actual_a3"; echo "$actual_a4"; fail=1
else
  echo "✓ Adapter 3: escape marker exempts; Activity is out of scope."
fi

if [ "$fail" -eq 0 ]; then
  echo "✓ fragment-teardown awks: all fixtures behaved as expected."
  exit 0
fi
echo "✗ fragment-teardown awk regression — see diffs above."
exit 1
