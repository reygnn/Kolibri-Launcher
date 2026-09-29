#!/usr/bin/env bash
# =============================================================================
# A8 — mirror comments ratchet (SPEC_NYX_REWRITE A8), called by the orchestrator
# =============================================================================
# Usage: check-mirror-comments.sh <source-root>...
#   Flags every mirror/parity/port-of comment naming the other app that is NOT in
#   tools/mirror-allowlist.txt, and every allowlist entry under the scanned roots
#   that no longer exists (stale → remove it). Entries are "path<TAB>line text"
#   (path relative to the monorepo root), so they survive line-number shifts.
#
#   Removing an entry is only allowed together with the replacement of the
#   hand-kept copy by the shared implementation — the commit must name the
#   contract test or forbidden-import rule that proves it (spec, A8).
#   Prints "═══ … ═══" blocks; exit 0 = ran, 2 = environment problem.
# =============================================================================
set -u
det="$(cd "$(dirname "$0")" && pwd)"; mono="$(cd "$det/.." && pwd)"
allow="$det/mirror-allowlist.txt"
[ -f "$allow" ] && [ -f "$det/check-mirror-comments.awk" ] || { echo "ERROR: A8 files missing" >&2; exit 2; }
[ "$#" -gt 0 ] || { echo "ERROR: no roots given" >&2; exit 2; }
cur=$(for r in "$@"; do [ -d "$r" ] && find "$r" -name '*.kt'; done | sort -u | while IFS= read -r f; do
        awk -f "$det/check-mirror-comments.awk" "$f"; done)
# normalize paths to monorepo-relative
cur=$(printf '%s\n' "$cur" | sed '/^$/d' | while IFS=$'\t' read -r p t n; do
        a="$(cd "$(dirname "$p")" && pwd)/$(basename "$p")"; printf '%s\t%s\t%s\n' "${a#"$mono"/}" "$t" "$n"; done)
keys=$(printf '%s\n' "$cur" | sed '/^$/d' | cut -f1,2 | sort)
allowed=$(grep -v '^#' "$allow" | sed '/^$/d' | sort)
new=$(comm -23 <(printf '%s\n' "$keys" | sed '/^$/d') <(printf '%s\n' "$allowed" | sed '/^$/d'))
roots_rel=$(for r in "$@"; do [ -d "$r" ] && { a="$(cd "$r" && pwd)"; echo "${a#"$mono"/}"; }; done)
stale=$(comm -13 <(printf '%s\n' "$keys" | sed '/^$/d') <(printf '%s\n' "$allowed" | sed '/^$/d') | while IFS=$'\t' read -r p t; do
          for rr in $roots_rel; do case "$p" in "$rr"/*) printf '%s: %s\n' "$p" "$t"; break ;; esac; done; done)
if [ -n "$new" ]; then
  echo; echo "═══ A8 — new mirror comment (a hand-kept copy of the other app): replace the copy with the shared implementation instead ═══"
  printf '%s\n' "$new" | while IFS=$'\t' read -r p t; do
    n=$(printf '%s\n' "$cur" | awk -F'\t' -v p="$p" -v t="$t" '$1==p && $2==t {print $3; exit}'); printf '%s:%s: %s\n' "$p" "$n" "$t"; done
fi
if [ -n "$stale" ]; then
  echo; echo "═══ A8 allowlist — ratchet: stale entries (remove them, naming the contract/forbidden-import rule that proves the replacement) ═══"
  printf '%s\n' "$stale"
fi
exit 0
