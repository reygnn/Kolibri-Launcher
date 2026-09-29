#!/usr/bin/env bash
# =============================================================================
# Generic shrink-only ratchet (A8 mirror comments, A13 hard-coded dispatchers)
# =============================================================================
# Usage: check-ratchet.sh <detector.awk> <allowlist> <title> <source-root>...
#   The detector prints one line per finding: path<TAB>line-text<TAB>line-number.
#   The allowlist holds known findings as path<TAB>line-text (path relative to the
#   monorepo root; '#' lines are comments). Entries are COUNTED: a line text that
#   occurs n times in a file needs n entries, so an (n+1)-th identical line is new.
#
#   Reports (as "═══ … ═══" blocks, the orchestrator counts them):
#     * findings beyond their allowlisted count        → new, fails the build
#     * allowlisted entries (under the scanned roots) that no longer occur
#                                                      → stale, remove them
#   Exit 0 = ran (with or without findings), 2 = environment problem.
# =============================================================================
set -u
det="$(cd "$(dirname "$0")" && pwd)"; mono="$(cd "$det/.." && pwd)"
awkf="${1:-}"; allow="${2:-}"; title="${3:-}"; shift 3 2>/dev/null || true
[ -f "$awkf" ] && [ -f "$allow" ] && [ -n "$title" ] && [ "$#" -gt 0 ] || {
  echo "ERROR: usage: check-ratchet.sh <detector.awk> <allowlist> <title> <root>..." >&2; exit 2; }

cur=$(for r in "$@"; do [ -d "$r" ] && find "$r" -name '*.kt'; done | sort -u | while IFS= read -r f; do
        awk -f "$awkf" "$f"; done | while IFS=$'\t' read -r p t n; do
        [ -n "$p" ] || continue
        a="$(cd "$(dirname "$p")" && pwd)/$(basename "$p")"; printf '%s\t%s\t%s\n' "${a#"$mono"/}" "$t" "$n"; done)
roots=$(for r in "$@"; do [ -d "$r" ] && { a="$(cd "$r" && pwd)"; printf '%s\n' "${a#"$mono"/}"; }; done)

out=$(awk -F'\t' -v roots="$roots" '
  BEGIN { nr = split(roots, R, "\n") }
  FNR == NR { if ($0 ~ /^#/ || $0 == "") next; k = $1 "\t" $2; allow[k]++; next }   # allowlist
  { k = $1 "\t" $2; cur[k]++; where[k] = where[k] (where[k] == "" ? "" : ",") $3; order[++no] = k }
  END {
    for (i = 1; i <= no; i++) { k = order[i]; if (k in done) continue; done[k] = 1
      d = cur[k] - allow[k]
      if (d > 0) { split(k, P, "\t"); printf "NEW\t%s:%s: %s%s\n", P[1], where[k], P[2], (allow[k] ? "  (" d " more than allowlisted)" : "") } }
    for (k in allow) { d = allow[k] - cur[k]; if (d <= 0) continue
      split(k, P, "\t"); inroot = 0
      for (r = 1; r <= nr; r++) if (R[r] != "" && index(P[1], R[r] "/") == 1) inroot = 1
      if (inroot) printf "STALE\t%s: %s%s\n", P[1], P[2], (d > 1 ? "  (" d " entries)" : "") } }
' "$allow" <(printf '%s\n' "$cur" | sed '/^$/d'))

new=$(printf '%s\n' "$out" | sed -n 's/^NEW\t//p')
stale=$(printf '%s\n' "$out" | sed -n 's/^STALE\t//p' | sort)
[ -n "$new" ] && { echo; echo "═══ $title ═══"; printf '%s\n' "$new"; }
[ -n "$stale" ] && { echo; echo "═══ ratchet $(basename "$allow"): stale entries — remove them ═══"; printf '%s\n' "$stale"; }
exit 0
