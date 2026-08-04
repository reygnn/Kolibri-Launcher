# =============================================================================
# Cancellation-rethrow discipline check (awk core)
# =============================================================================
#
# Reports broad catches (Throwable / Exception) that neither sit behind a
# `catch (…: CancellationException)` arm on the same try-statement nor carry
# an explicit "no suspension point" escape marker. Used by:
#
#   - tools/check-conventions.sh                  (production linter)
#   - tools/check-cancellation-rethrow-test.sh    (regression test against
#                                                  synthetic fixtures)
#
# Both call this file so the regex logic stays single-source.
#
# == WHY THIS EXISTS ==
#
# The defect it catches is invisible in a diff. A `catch (e: Throwable)` is
# harmless for as long as its try-block contains no suspension point — no
# CancellationException can reach it. The day someone makes a call inside
# that block `suspend`, the untouched catch silently becomes a cancellation
# swallower: normal coroutine control flow gets logged as a crash, and the
# already-cancelled job keeps working. The catch line itself never changes,
# so review sees nothing.
#
# Kolibri hit this three times (e1ef671d, fb62b88d, and 4c09c30b →
# WallpaperViewBinder.applyFullRebuild, where every latest-wins wallpaper
# switch filed a bogus ACRA report, one per layer).
#
# == THE RULE (aggressive within the whitelist) ==
#
# In an opted-in file, EVERY broad catch must declare which case it is:
#
#   a) It follows a CancellationException arm that rethrows      → satisfied
#      structurally, no comment needed.
#   b) Its try-block genuinely cannot suspend                    → must say so
#      with the marker phrase `no suspension point`.
#
# Deliberately aggressive rather than trying to detect suspension points:
# a suspend call has no syntactic tell (`bitmapLoader.load(uri)` looks
# exactly like a blocking call), so any detector keyed on `withContext` /
# `.await()` / `delay` would have missed the very bug that motivated this
# check. Making the author state the case is the only reliable form.
#
# == DETECTING CASE (a) ==
#
# Walk upward from the broad catch, skipping the lines that legitimately sit
# between two arms of the same try-statement: comments, a bare `throw e`, and
# brace-only lines. The first substantive line decides. That keeps a
# CancellationException arm belonging to an EARLIER, unrelated try-statement
# from accidentally satisfying a later catch, because real code would stand
# between them.
#
# Output format (matches check-conventions.sh expectations):
#   FILENAME:LINE: <catch line content>
#
# Exit code: always 0 (printing alone signals violations to the caller).
# =============================================================================
{ lines[NR] = $0 }
END {
    for (n = 1; n <= NR; n++) {
        # Only broad catches. Narrowed types cannot swallow a
        # CancellationException in the first place — CancellationException
        # is not a subtype of IOException & co.
        if (lines[n] !~ /catch \([a-zA-Z_]+: (Throwable|Exception)\)/) continue

        # Skip catches that only appear inside a comment or KDoc block.
        if (lines[n] ~ /^[[:space:]]*[*\/]/) continue

        satisfied = 0

        # --- Case (a): preceding CancellationException arm ---------------
        for (i = n - 1; i >= 1; i--) {
            line = lines[i]

            # Lines that may legitimately separate two catch arms.
            if (line ~ /^[[:space:]]*$/) continue                  # blank
            if (line ~ /^[[:space:]]*(\/\/|\*|\/\*)/) continue      # comment
            if (line ~ /^[[:space:]]*throw[[:space:]]+[a-zA-Z_]+[[:space:]]*$/) continue
            if (line ~ /^[[:space:]]*\}[[:space:]]*$/) continue     # brace only

            # First substantive line decides.
            if (line ~ /catch \([a-zA-Z_]+: CancellationException\)/) satisfied = 1
            break
        }

        # --- Case (b): explicit "no suspension point" escape marker ------
        # Symmetric +/-5-line window, same convention as the Rule 11
        # annotation check.
        if (!satisfied) {
            for (i = n - 5; i <= n + 5; i++) {
                if (i >= 1 && i <= NR && lines[i] ~ /[Nn]o suspension point/) {
                    satisfied = 1
                    break
                }
            }
        }

        if (!satisfied) print FILENAME ":" n ": " lines[n]
    }
}
