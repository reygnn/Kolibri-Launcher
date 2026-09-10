#!/usr/bin/env bash
# =============================================================================
# Rule 2 — contract-test triple completeness
# =============================================================================
#
# CLAUDE.md Rule 2: every repository interface gets `XyzRepositoryContract`
# (abstract, @Test methods) + `FakeXyzRepositoryContractTest` +
# `XyzRepositoryImplContractTest`. If fake and impl drift, the contract catches
# it — that is the entire safety net behind Rule 1 (ViewModels depend only on
# the interface) and Rule 3 (when a contract goes red, the impl wins).
#
# The rule was honor-system until this check existed: a new repository whose
# author writes the fake test but skips the impl half loses exactly the drift
# detection the triple exists for, and nothing goes red — the suite is still
# green, just blind.
#
# ACCEPTED EXEMPTIONS — Rule 2 allows justified gaps, "justified per-repo in the
# corresponding contract KDoc". This check requires that justification to carry
# one canonical, greppable token, for the same reason `Exception sufficient` is
# its own token: the three pre-existing exemptions each argued their case under
# a different self-invented heading ("KEIN CONTRACT TEST", "BEWUSST DÜNN,
# FAKE-ONLY", "CONTRACT TEST (BEWUSST DÜNN)"), all thorough, none findable by a
# single grep. Prose quality is not the problem a linter can check; a stable
# token is.
#
#   NO CONTRACT TEST (ADR)       exempts the whole triple — the contract file
#                                documents why neither half exists (system-API
#                                impl, tautological fake, …).
#   NO IMPL CONTRACT TEST (ADR)  exempts the impl half ONLY. The fake contract
#                                test is still required. This is the shape where
#                                the impl needs a whole dependency stack or real
#                                Android I/O, but the fake is still worth pinning.
#
# The marker must appear in the CONTRACT file (that is where a reader looking at
# the contract will be standing), not in the interface or in CLAUDE.md — a list
# in CLAUDE.md is not visible to someone editing the contract.
#
# SCOPE: interfaces named `*Repository` under :domain. Capability interfaces
# (`Purgeable`) and non-repository service interfaces (`WallpaperBitmapLuminance`)
# are out of scope by that name filter — they are not data-access repositories
# and Rule 2 does not reach them.
#
# Run via `./gradlew checkConventions` (this file is invoked from
# check-conventions.sh) or directly.
#
# Output: one line per violation, `<Interface>: <what is missing>`.
# Exit code: always 0 — printing alone signals violations to the caller, same
# contract as the awk cores.
# =============================================================================
set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"

domain_main="$repo_root/domain/src/main"
if [ ! -d "$domain_main" ]; then
  echo "ERROR: domain main source set not found: $domain_main" >&2
  exit 2
fi

# All `*Repository` interfaces declared in :domain. Anchored on the declaration
# so a file that merely mentions one does not count.
while IFS= read -r iface_file; do
  name="$(basename "$iface_file" .kt)"

  contract="$(find "$repo_root/domain/src/testFixtures" -name "${name}Contract.kt" 2>/dev/null | head -1)"
  if [ -z "$contract" ]; then
    echo "$name: no ${name}Contract.kt (Rule 2 requires a contract or a documented ADR)"
    continue
  fi

  # Markers are read from the contract file only.
  if grep -q "NO CONTRACT TEST (ADR)" "$contract"; then
    continue
  fi
  impl_exempt=0
  grep -q "NO IMPL CONTRACT TEST (ADR)" "$contract" && impl_exempt=1

  fake="$(find "$repo_root" -name "Fake${name}ContractTest.kt" -not -path '*/build/*' 2>/dev/null | head -1)"
  impl="$(find "$repo_root" -name "${name}ImplContractTest.kt" -not -path '*/build/*' 2>/dev/null | head -1)"

  missing=""
  [ -z "$fake" ] && missing="Fake${name}ContractTest"
  if [ -z "$impl" ] && [ "$impl_exempt" -eq 0 ]; then
    [ -n "$missing" ] && missing="$missing, "
    missing="${missing}${name}ImplContractTest"
  fi

  if [ -n "$missing" ]; then
    echo "$name: missing $missing (add it, or document the gap in ${name}Contract.kt with a \`NO CONTRACT TEST (ADR)\` / \`NO IMPL CONTRACT TEST (ADR)\` marker)"
  fi
done < <(grep -rlE '^(internal )?interface [A-Za-z0-9_]*Repository\b' --include='*.kt' "$domain_main" | sort)

exit 0
