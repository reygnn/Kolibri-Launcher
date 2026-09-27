#!/usr/bin/env bash
# =============================================================================
# Init-order launch hazard — DISCOVERY (report-only, repo-wide)
# =============================================================================
#
# The init-order gate (tools/check-init-order-launch.awk, driven by the
# `initorder_files` positive list in each orchestrator) enforces only the files
# it lists — a regression lock on reviewed-clean files. This script is the
# DISCOVERY half: it sweeps EVERY module's main source (shared modules included,
# since the shared-code refactors move launch/init code between them) with the
# SAME awk and lists any file exhibiting the shape:
#
#   an `init { }` block that launches a coroutine, followed by a class-level
#   property initializer in the same class (the FolderIconRenderer NPE shape).
#
# It is NOT a gate: it never fails the build (exit 0). A clean, listed file
# produces no awk hit, so no whitelist-exclusion is needed — anything printed
# here is an unreviewed occurrence to triage.
#
# WHEN TO RUN: after adding/moving a coroutine launch into an init block, or
# after a refactor that reorders class members / moves code between modules.
#
# HOW TO TRIAGE: open the file; if the init-launched coroutine can touch the
# later-declared property (directly or via a method like clear()), it is a real
# init-order race — move the state ABOVE the init block. Then add the file to
# the relevant `initorder_files` list to lock it. If the launch provably cannot
# touch the later state, reorder anyway (harmless) or leave it and do not list.
#
# Run via `./gradlew scanInitOrderLaunch` or invoke this script directly.
# =============================================================================
set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"   # tools/ -> repo root
awkf="$script_dir/check-init-order-launch.awk"

if [ ! -f "$awkf" ]; then
  echo "ERROR: required file not found: $awkf" >&2
  exit 2
fi

declare -a rows=()
while IFS= read -r file; do
  hits="$(awk -f "$awkf" "$file")"
  [ -z "$hits" ] && continue
  n_hits="$(printf '%s\n' "$hits" | grep -c ':' || true)"
  rel="${file#"$repo_root"/}"
  rows+=("${n_hits}	${rel}")
done < <(find "$repo_root" -type d -name build -prune -o \
              -path '*/src/main/*' -name '*.kt' -print 2>/dev/null)

echo "════════════════════════════════════════════════════════════════════════"
echo " init-order launch hazard scan (report-only — never fails the build)"
echo "════════════════════════════════════════════════════════════════════════"
echo

if [ "${#rows[@]}" -eq 0 ]; then
  echo " No file launches a coroutine in an init block followed by a class-level"
  echo " property initializer. Nothing to triage."
  echo
  exit 0
fi

echo " Files with an init { launch … } followed by a later property initializer"
echo " (the FolderIconRenderer init-order NPE shape) — triage each:"
echo
printf ' %-6s %s\n' "HITS" "FILE"
printf ' %-6s %s\n' "----" "----"
printf '%s\n' "${rows[@]}" | sort -t$'\t' -k1,1nr | \
  while IFS=$'\t' read -r n_hits rel; do
    printf ' %-6s %s\n' "$n_hits" "$rel"
  done
echo
echo " Triage: if the init-launched coroutine can reach the later property (e.g."
echo " via a method it calls), it is a real init-order race — move the state ABOVE"
echo " the init block, then add the file to the initorder_files list to lock it."
echo " Detail per file:  awk -f kolibri/tools/check-init-order-launch.awk <file>"
echo "════════════════════════════════════════════════════════════════════════"
exit 0
