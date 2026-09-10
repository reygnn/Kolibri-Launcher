# =============================================================================
# check-stale-replay-read.awk  —  marker-aware detector
# =============================================================================
# Flags a terminal point-read (`.first()` / `.firstOrNull()`) taken on a
# HOT-SHARED flow that carries NO review marker. Such a read can return the
# replay cache's last *observed* value instead of the current one whenever it
# runs without a warm subscriber (separate Activity / background write). This is
# the AUDIT-13 favorites-stale-replay class; the two ways to clear a hit are to
# read the repository's authoritative `getXSnapshot()` (fresh
# `dataStore.data.first()`) instead, or — if the read provably runs only with a
# warm subscriber / performs no relevant write — to justify it with a
# `stale-replay ok` marker within ±5 lines (symmetric window, same convention as
# the cancellation / breadth checks). Ingredient 3 ("no warm subscriber") has no
# syntactic tell and is NOT decided here; the marker IS that human judgement.
#
# Driver passes the hot-flow property names as -v hot="a|b|c" (alternation).
# Emits `file:line: <flow> …` per UNMARKED hit. Exit code always 0.
# =============================================================================
BEGIN {
    if (hot != "") {
        n_names = split(hot, names, "|")
        re = "(^|[^A-Za-z0-9_])(" hot ")\\.(first|firstOrNull)\\("
    }
}

{ lines[NR] = $0 }

END {
    if (hot == "") exit 0
    for (n = 1; n <= NR; n++) {
        line = lines[n]

        # Skip full-line / KDoc comments: a hit there is documentation, not code.
        stripped = line
        sub(/^[ \t]*/, "", stripped)
        if (stripped ~ /^(\/\/|\*|\/\*)/) continue

        if (line !~ re) continue

        # ±5-line symmetric window for the review marker.
        found = 0
        for (i = n - 5; i <= n + 5; i++) {
            if (i >= 1 && i <= NR && lines[i] ~ /stale-replay ok/) { found = 1; break }
        }
        if (found) continue

        # Recover which alternative matched, for a precise message.
        for (k = 1; k <= n_names; k++) {
            nm = names[k]
            if (line ~ ("(^|[^A-Za-z0-9_])" nm "\\.(first|firstOrNull)\\(")) {
                printf "%s:%d: point-read `.first()` on hot flow `%s` — convert to getXSnapshot() or justify with a `stale-replay ok` marker\n", FILENAME, n, nm
                break
            }
        }
    }
}
