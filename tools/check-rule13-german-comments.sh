#!/usr/bin/env bash
# =============================================================================
# Kolibri Launcher — Rule 13 (German-comment) linter
# =============================================================================
#
# CLAUDE.md Rule 13 says new or rewritten source-code comments must be
# English; pre-existing German comments stay. This linter runs against a
# git diff so legacy lines are not in scope — only `+` lines (added or
# modified) are checked.
#
# Run via:
#   ./gradlew checkRule13
#   ./tools/check-rule13-german-comments.sh         (standalone)
#
# Comparison base:
#   CHECK_BASE env var, default `origin/main` with fallback to `main` when
#   `origin/main` is not a known ref locally. Override for a one-off check
#   against a different branch:
#     CHECK_BASE=feature/foo ./tools/check-rule13-german-comments.sh
#
# Diff scope:
#   `git diff <base>...HEAD -- '*.kt' '*.kts'` plus working-tree changes
#   relative to HEAD. Scoping to .kt/.kts only — .xml is not scanned in v1
#   because the only meaningful comments in this project's XML live in the
#   AndroidManifest, where deliberate German prose is absent today.
#
# Exit code:
#   0   no Rule 13 violations in the diff
#   1   ≥1 violation
#   2   environment problem (missing awk script, bad base ref, etc.)
# =============================================================================

set -u

script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
awk_script="$script_dir/check-rule13-german-comments.awk"

if [ ! -f "$awk_script" ]; then
  echo "ERROR: awk script not found: $awk_script" >&2
  exit 2
fi

# Resolve comparison base. Prefer origin/main; fall back to main if the
# remote-tracking ref is not present (e.g. fresh clone without fetch).
base="${CHECK_BASE:-}"
if [ -z "$base" ]; then
  if git -C "$repo_root" rev-parse --verify --quiet origin/main >/dev/null; then
    base="origin/main"
  elif git -C "$repo_root" rev-parse --verify --quiet main >/dev/null; then
    base="main"
  else
    echo "ERROR: no comparison base — set CHECK_BASE or fetch origin/main" >&2
    exit 2
  fi
fi

if ! git -C "$repo_root" rev-parse --verify --quiet "$base" >/dev/null; then
  echo "ERROR: comparison base not resolvable: $base" >&2
  exit 2
fi

# Two-stage diff: committed-since-base AND working-tree changes. The
# concatenation gives full coverage for local dev; CI typically has a
# clean working tree so the second stage is a no-op there.
committed=$(git -C "$repo_root" diff "$base"...HEAD -- '*.kt' '*.kts')
working=$(git -C "$repo_root" diff HEAD -- '*.kt' '*.kts')

violations=$(printf "%s\n%s\n" "$committed" "$working" \
  | awk -f "$awk_script")

if [ -z "$violations" ]; then
  echo "✓ Rule 13: no German comments in diff against $base."
  exit 0
fi

echo "═══ Rule 13 — German comments added in diff against $base ═══"
echo
# Reformat for readability: <path>:<line>:<text>  →  ✗ <path>:<line>\n   <text>
printf "%s\n" "$violations" | awk -F: '
{
  path = $1
  line = $2
  # Reassemble the comment text — it may have contained colons.
  text = $3
  for (i = 4; i <= NF; i++) text = text ":" $i
  printf "✗ %s:%s\n   %s\n", path, line, text
}'

count=$(printf "%s\n" "$violations" | wc -l | tr -d ' ')
echo
echo "$count violation(s)."
exit 1
