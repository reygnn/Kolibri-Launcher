# =============================================================================
# Rule 11 annotation-discipline check (awk core)
# =============================================================================
#
# Reports broad catches (Throwable / Exception) that lack a four-category-
# frame justification marker within ±5 lines. Used by:
#
#   - tools/check-conventions.sh         (production linter)
#   - tools/check-conventions-test.sh    (regression test against
#                                         synthetic fixtures)
#
# Both call this file so the regex logic stays single-source.
#
# Output format (matches check-conventions.sh expectations):
#   FILENAME:LINE: <catch line content>
#
# Exit code: always 0 (printing alone signals violations to the caller).
# =============================================================================
{ lines[NR] = $0 }
END {
    for (n = 1; n <= NR; n++) {
        # Only check broad catches (Throwable / Exception). Narrowed
        # exception types follow Rule 11 by being narrow.
        if (lines[n] ~ /catch \([a-zA-Z_]+: (Throwable|Exception)\)/ \
            && lines[n] !~ /^[[:space:]]*[*\/]/) {
            found = 0
            # Symmetric ±5-line window. Marker phrases stay on a
            # single line by convention, so per-line checking is
            # enough — no concatenation tricks needed.
            for (i = n - 5; i <= n + 5; i++) {
                if (i >= 1 && i <= NR) {
                    if (lines[i] ~ /[Cc]atch[a-z]* kept/) { found = 1; break }
                    if (lines[i] ~ /[Rr]ethrow per canonical/) { found = 1; break }
                }
            }
            if (!found) print FILENAME ":" n ": " lines[n]
        }
    }
}
