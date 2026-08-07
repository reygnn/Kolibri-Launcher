#!/usr/bin/env bash
# =============================================================================
# Stale-replay whitelist — CANDIDATE DISCOVERY (report-only)
# =============================================================================
#
# The stale-replay gate (tools/check-stale-replay-read.awk, driven by the
# `stale_files` positive list in tools/check-stale-replay-read.sh) is BLIND by
# design to any file not on that list — it verifies only the reviewed files that
# legitimately point-read a hot flow. That is the right call for enforcement: a
# hot-flow point-read is safe or unsafe depending on whether a warm subscriber is
# live, which is a whole-program property with no syntactic tell, so a whole-tree
# hard gate would wrongly fail legitimate warm-context reads. But it means a NEW
# `.first()` / `.firstOrNull()` on a hot flow, added to a file NOT on the list, is
# invisible to the gate.
#
# This script closes the DISCOVERY half of that gap, mirroring
# scan-cancel-candidates.sh / scan-oom-candidates.sh for their axes. It is NOT a
# gate: it never fails the build. It sweeps every :app/:data/:domain main source
# that is NOT already whitelisted, runs the SAME awk the gate uses (with the same
# `hot_flows` names), and ranks the files by how many unmarked hot-flow
# point-reads they contain — so a file that just grew a stale read floats up.
#
# WHEN TO RUN: after adding a `.first()` / `.firstOrNull()` on a favorites / order
# / fab flow, especially from a context that may run without a warm Home
# subscriber (a separate Activity, a background write) — i.e. the AUDIT-13 shape.
#
# HOW TO TRIAGE the output (same lens as AUDIT-13):
#   - read from a context WITHOUT a guaranteed warm subscriber (separate Activity,
#     background write)  ->  convert it to the repository's authoritative
#     getXSnapshot() (fresh `dataStore.data.first()`), which bypasses the replay
#     cache. This is the fix the two favorites UI consumers got.
#   - read that provably runs only with a warm subscriber / writes nothing
#     relevant  ->  add a `stale-replay ok` marker within ±5 lines AND add the
#     file to `stale_files` in check-stale-replay-read.sh, so the gate enforces
#     the marker from then on.
#
# Run via `./gradlew scanStaleReplayRead` or invoke this script directly.
# Exit code is always 0 — printing is informational only.
# =============================================================================
set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
gate="$script_dir/check-stale-replay-read.sh"
awkf="$script_dir/check-stale-replay-read.awk"

for f in "$gate" "$awkf"; do
  if [ ! -f "$f" ]; then
    echo "ERROR: required file not found: $f" >&2
    exit 2
  fi
done

# Whitelisted basenames + the hot-flow names — parsed live from the gate so this
# tool can never drift from the enforced list.
mapfile -t whitelisted < <(
  sed -n '/^stale_files=(/,/^)/p' "$gate" | grep -oE '[A-Za-z0-9_]+\.kt' | sort -u
)
hot_alt="$(
  sed -n '/^hot_flows=(/,/^)/p' "$gate" \
    | grep -oE '[A-Za-z0-9_]+' | grep -vxF 'hot_flows' | paste -sd'|' -
)"

is_whitelisted() {
  local base="$1" w
  for w in "${whitelisted[@]}"; do [ "$base" = "$w" ] && return 0; done
  return 1
}

sources=()
for m in app data domain; do
  [ -d "$repo_root/$m/src/main" ] && sources+=("$repo_root/$m/src/main")
done

declare -a rows=()
declare -a detail=()
while IFS= read -r -d '' file; do
  base="$(basename "$file")"
  is_whitelisted "$base" && continue

  hits="$(awk -v hot="$hot_alt" -f "$awkf" "$file" 2>/dev/null)" || true
  [ -z "$hits" ] && continue

  n_hits="$(printf '%s\n' "$hits" | grep -c . || true)"
  rel="${file#"$repo_root"/}"
  rows+=("${n_hits}	${rel}")
  detail+=("$(printf '%s\n' "$hits" | sed "s#${repo_root}/##")")
done < <(find "${sources[@]}" -name '*.kt' -print0 2>/dev/null)

echo "════════════════════════════════════════════════════════════════════════"
echo " stale_files candidate scan (report-only — never fails the build)"
echo "════════════════════════════════════════════════════════════════════════"
echo " Whitelisted (skipped): ${#whitelisted[@]} files    Hot flows: ${hot_alt//|/, }"
echo

if [ "${#rows[@]}" -eq 0 ]; then
  echo " No non-whitelisted file has an unmarked hot-flow point-read. Nothing to triage."
  echo
  exit 0
fi

echo " Non-whitelisted files with an unmarked hot-flow \`.first()\` / \`.firstOrNull()\`,"
echo " ranked by hit count (each is a read the gate cannot see):"
echo
printf ' %-6s %s\n' "HITS" "FILE"
printf ' %-6s %s\n' "----" "----"
printf '%s\n' "${rows[@]}" | sort -t$'\t' -k1,1nr -k2,2 | \
  while IFS=$'\t' read -r n_hits rel; do
    printf ' %-6s %s\n' "$n_hits" "$rel"
  done
echo
echo " Sites:"
printf '%s\n' "${detail[@]}" | sed 's/^/   /'
echo
echo " Triage: read from a non-Home context -> convert to getXSnapshot(). Read that"
echo " provably runs warm / writes nothing -> add a \`stale-replay ok\` marker and add"
echo " the file to stale_files in check-stale-replay-read.sh."
echo "════════════════════════════════════════════════════════════════════════"
exit 0
