#!/usr/bin/env bash
# =============================================================================
# Shared test-convention checks (A7, A12) — called by BOTH app orchestrators
# =============================================================================
# Usage: check-test-conventions.sh <module-dir>...
#   Scans every *.kt under <module-dir>/src/<set>/ except src/main (test,
#   testDebug, testFixtures, androidTest). Prints one "═══ title ═══" block per
#   violated rule; the caller counts the blocks. Exit 0 = ran (with or without
#   findings), 2 = environment problem.
#
# A12 ratchet: files listed in tools/test-assertions-allowlist.txt (paths
# relative to the monorepo root) are skipped. An allowlisted file under the
# scanned modules that is clean or gone is reported too, so the list can only
# shrink. The list must be empty by the end of Phase 1 (SPEC_NYX_REWRITE).
# =============================================================================
set -u
det="$(cd "$(dirname "$0")" && pwd)"
mono="$(cd "$det/.." && pwd)"
a7="$det/check-test-dispatcher.awk"
a12="$det/check-test-assertions.awk"
allow="$det/test-assertions-allowlist.txt"
for f in "$a7" "$a12" "$allow"; do
  [ -f "$f" ] || { echo "ERROR: not found: $f" >&2; exit 2; }
done
[ "$#" -gt 0 ] || { echo "ERROR: no module dirs given" >&2; exit 2; }

files=$(for m in "$@"; do
  [ -d "$m/src" ] || continue
  find "$m/src" -mindepth 1 -maxdepth 1 -type d ! -name main -exec find {} -name '*.kt' \;
done | sort -u)

rel() { local p; p="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"; echo "${p#"$mono"/}"; }

a7_hits=""; a12_hits=""; stale=""
declare -A allowed=()
while IFS= read -r l; do
  l="${l%%#*}"; l="${l// /}"; [ -n "$l" ] && allowed["$l"]=1
done < "$allow"
declare -A seen=()
while IFS= read -r f; do
  [ -n "$f" ] || continue
  h=$(awk -f "$a7" "$f"); [ -n "$h" ] && a7_hits="${a7_hits}${h}"$'\n'
  r=$(rel "$f")
  h=$(awk -f "$a12" "$f")
  if [ -n "${allowed[$r]:-}" ]; then
    seen["$r"]=1
    [ -z "$h" ] && stale="${stale}${r}: allowlisted but clean — remove it from tools/test-assertions-allowlist.txt"$'\n'
  elif [ -n "$h" ]; then
    a12_hits="${a12_hits}${h}"$'\n'
  fi
done <<< "$files"

# allowlisted files that vanished (only judged inside the scanned modules)
for r in "${!allowed[@]}"; do
  [ -n "${seen[$r]:-}" ] && continue
  for m in "$@"; do
    mr=$(rel "$m"); case "$r" in "$mr"/*) [ -f "$mono/$r" ] || stale="${stale}${r}: allowlisted but missing — remove it"$'\n';; esac
  done
done

[ -n "$a7_hits" ] && { echo; echo "═══ A7 — one test dispatcher (TESTING_CONVENTIONS.kt; MainDispatcherRule, §5/§6, exceptions 1–3) ═══"; printf '%s' "${a7_hits%$'\n'}"; echo; }
[ -n "$a12_hits" ] && { echo; echo "═══ A12 — Truth only; exceptions kotlin.test.assertFailsWith and kotlin.test.assertIs ═══"; printf '%s' "${a12_hits%$'\n'}"; echo; }
[ -n "$stale" ] && { echo; echo "═══ A12 allowlist — ratchet: stale entries ═══"; printf '%s' "$(printf '%s' "$stale" | sort)"; echo; }
exit 0
