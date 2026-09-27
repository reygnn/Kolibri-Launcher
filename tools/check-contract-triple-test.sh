#!/usr/bin/env bash
# =============================================================================
# Regression test for tools/check-contract-triple.sh
# =============================================================================
#
# The contract-triple check is filesystem-shaped, not text-shaped: it decides
# from FILE EXISTENCE plus a marker grep. So this test builds a synthetic repo
# skeleton in a tmpdir and runs the real script against it, rather than feeding
# a fixture to an awk core like its siblings do.
#
# The fragile parts this pins:
#   - a full triple (contract + fake test + impl test) stays silent.
#   - a missing impl contract test flags …
#   - … unless the contract carries `NO IMPL CONTRACT TEST (ADR)`, which
#     exempts the impl half ONLY.
#   - `NO CONTRACT TEST (ADR)` exempts the whole triple.
#   - the fake test is still required under a NO-IMPL marker (the half-exempt
#     case that would be easy to get wrong).
#   - the `*Repository` name filter: a capability interface (Purgeable) or a
#     non-repository service interface is out of scope and must not flag.
#
# NOT wired into Gradle by design — manual-rerun test, not a CI gate.
#
# Run via:
#   ./tools/check-contract-triple-test.sh
#
# Exit code:
#   0   output matched expectations
#   1   regression — actual output differs from expected
#   2   environment problem (missing script, mktemp failure)
# =============================================================================

set -u
script_dir="$(cd "$(dirname "$0")" && pwd)"
target="$script_dir/check-contract-triple.sh"

if [ ! -f "$target" ]; then
  echo "ERROR: script not found: $target" >&2
  exit 2
fi

tmpdir=$(mktemp -d) || { echo "ERROR: mktemp failed" >&2; exit 2; }
trap 'rm -rf "$tmpdir"' EXIT

# Minimal repo skeleton: tools/ (so the script resolves repo_root as its
# parent), the domain main source set, and the testFixtures contract dir.
mkdir -p "$tmpdir/tools" \
         "$tmpdir/domain/src/main/kotlin" \
         "$tmpdir/domain/src/testFixtures/kotlin" \
         "$tmpdir/app/src/test/kotlin"
cp "$target" "$tmpdir/tools/"

iface="$tmpdir/domain/src/main/kotlin"
fixt="$tmpdir/domain/src/testFixtures/kotlin"
tests="$tmpdir/app/src/test/kotlin"

# CASE 1 — full triple. Must stay silent.
echo 'interface CompleteRepository' > "$iface/CompleteRepository.kt"
echo '// contract' > "$fixt/CompleteRepositoryContract.kt"
echo '// fake' > "$tests/FakeCompleteRepositoryContractTest.kt"
echo '// impl' > "$tests/CompleteRepositoryImplContractTest.kt"

# CASE 2 — impl contract test missing, no marker. MUST flag.
echo 'interface MissingImplRepository' > "$iface/MissingImplRepository.kt"
echo '// contract' > "$fixt/MissingImplRepositoryContract.kt"
echo '// fake' > "$tests/FakeMissingImplRepositoryContractTest.kt"

# CASE 3 — impl missing but exempted by the impl-only marker. Must stay silent.
echo 'interface ImplExemptRepository' > "$iface/ImplExemptRepository.kt"
echo ' * NO IMPL CONTRACT TEST (ADR) — impl needs a full dependency stack.' \
  > "$fixt/ImplExemptRepositoryContract.kt"
echo '// fake' > "$tests/FakeImplExemptRepositoryContractTest.kt"

# CASE 4 — whole triple exempted. Must stay silent even with no tests at all.
echo 'interface FullyExemptRepository' > "$iface/FullyExemptRepository.kt"
echo ' * NO CONTRACT TEST (ADR) — system-API impl, tautological fake.' \
  > "$fixt/FullyExemptRepositoryContract.kt"

# CASE 5 — impl-only marker does NOT excuse a missing fake test. MUST flag,
# and must name ONLY the fake.
echo 'interface HalfExemptRepository' > "$iface/HalfExemptRepository.kt"
echo ' * NO IMPL CONTRACT TEST (ADR) — impl half only.' \
  > "$fixt/HalfExemptRepositoryContract.kt"

# CASE 6 — no contract file at all. MUST flag.
echo 'interface OrphanRepository' > "$iface/OrphanRepository.kt"

# CASE 7 — not named *Repository: out of scope, must never flag.
echo 'interface Purgeable' > "$iface/Purgeable.kt"
echo 'interface WallpaperBitmapLuminance' > "$iface/WallpaperBitmapLuminance.kt"

expected="HalfExemptRepository: missing FakeHalfExemptRepositoryContractTest (add it, or document the gap in HalfExemptRepositoryContract.kt with a \`NO CONTRACT TEST (ADR)\` / \`NO IMPL CONTRACT TEST (ADR)\` marker)
MissingImplRepository: missing MissingImplRepositoryImplContractTest (add it, or document the gap in MissingImplRepositoryContract.kt with a \`NO CONTRACT TEST (ADR)\` / \`NO IMPL CONTRACT TEST (ADR)\` marker)
OrphanRepository: no OrphanRepositoryContract.kt (Rule 2 requires a contract or a documented ADR)"

actual=$(bash "$tmpdir/tools/check-contract-triple.sh" | sort)

if [ "$actual" = "$expected" ]; then
  echo "✓ contract-triple: 3 expected violations on the synthetic skeleton"
  echo "  (missing impl test, missing fake under an impl-only marker, no contract)."
  echo "✓ Full triple, NO CONTRACT TEST (ADR), NO IMPL CONTRACT TEST (ADR) with a"
  echo "  fake present, and non-*Repository interfaces correctly silent."
  exit 0
fi

echo "✗ contract-triple regression — actual output differs from expected."
echo
echo "── EXPECTED ──"; echo "$expected"
echo
echo "── ACTUAL ──"; echo "$actual"
echo
echo "── DIFF (expected vs actual) ──"
diff <(printf '%s\n' "$expected") <(printf '%s\n' "$actual") || true
exit 1
