# =============================================================================
# Cancellation-rethrow discipline check (awk core)
# =============================================================================
#
# Reports cancellation swallowers: broad catches (Throwable / Exception) and
# `runCatching` blocks that neither rethrow CancellationException nor carry an
# explicit "no suspension point" escape marker. Used by:
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
# switch filed a bogus ACRA report, one per layer). The codebase-wide survey
# in CANCELLATION_AUDIT.md found nine further live instances.
#
# == THE RULE (aggressive within the whitelist) ==
#
# In an opted-in file, every broad catch and every `runCatching` must declare
# which case it is:
#
#   a) It follows a CancellationException arm that rethrows      → satisfied
#      structurally, no comment needed.
#   c) Its body rethrows via an `is CancellationException` guard → likewise.
#   b) Its block genuinely cannot suspend                        → must say so
#      with the marker phrase `no suspension point`.
#
# Deliberately aggressive rather than trying to detect suspension points:
# a suspend call has no syntactic tell (`bitmapLoader.load(uri)` looks
# exactly like a blocking call), so any detector keyed on `withContext` /
# `.await()` / `delay` would have missed the very bug that motivated this
# check. Making the author state the case is the only reliable form.
#
# == WHY runCatching IS INCLUDED ==
#
# `runCatching { }` catches Throwable, CancellationException included, and
# contains no `catch` keyword at all — so it is the one cancellation
# swallower a catch-shaped linter cannot see. The project already states the
# policy in prose (TimeBasedEventsRepositoryImpl's KDoc: "Wir verwenden hier
# KEIN runCatching, damit CancellationExceptions sauber durchgereicht
# werden"); this makes it enforceable. There are zero occurrences in
# production code today, so the check is a drift guard, not a cleanup.
#
# == DETECTING CASE (a) ==
#
# Walk upward from the broad catch using indentation to stay inside the same
# try's catch-chain. All arms of one try share the broad catch's indentation
# (X); arm bodies and the try body are indented deeper and are skipped, as are
# trivia and brace-only arm closings. At level X a sibling `} catch (T) {`
# continues the chain; the first level-X line that is neither a catch arm nor
# trivia — the `try {`, or the enclosing scope — ends it. A CancellationException
# arm found before that end satisfies the broad catch.
#
# Skipping whole sibling arms this way is what lets a cancellation-first arm
# satisfy an umbrella `catch (Throwable)` sitting several TYPED arms below it
# (the stacked-catch shape in BackupRepositoryImpl.saveBackupToFile: Cancellation
# → BackupException → SecurityException → IOException → Throwable). The earlier
# "first substantive line decides" walk stopped at the first typed arm's
# `throw Wrapped(...)` body and false-flagged the umbrella. A CancellationException
# arm belonging to an EARLIER, unrelated try still cannot satisfy a later catch,
# because that statement's own `try {` line sits at level X between them and ends
# the walk (fixture CASE 6).
#
# == DETECTING CASE (c) ==
#
# Walk the catch body forward by brace balance and look for a line that both
# tests `is CancellationException` and throws (on that line or the two after,
# covering the multi-line `if (…) {\n throw e\n }` form). This is the idiom
# SettingsRepositoryImpl uses:
#
#     if (e is CancellationException || e !is Exception) throw e
#
# Correct code, so it must not be forced into a marker comment.
#
# Output format (matches check-conventions.sh expectations):
#   FILENAME:LINE: <offending line content>
#
# Exit code: always 0 (printing alone signals violations to the caller).
# =============================================================================
{ lines[NR] = $0 }
END {
    for (n = 1; n <= NR; n++) {
        is_catch = (lines[n] ~ /catch \([a-zA-Z_]+: (Throwable|Exception)\)/)
        is_run   = (lines[n] ~ /(^|[^A-Za-z0-9_.])runCatching[[:space:]]*[{(]/)

        # Narrowed catch types cannot swallow a CancellationException —
        # it is not a subtype of IOException & co.
        if (!is_catch && !is_run) continue

        # Skip matches that only appear inside a comment or KDoc block.
        if (lines[n] ~ /^[[:space:]]*[*\/]/) continue

        satisfied = 0

        # --- Case (a): a CancellationException arm in the SAME chain -----
        # Catch arms of one try share the broad catch's indentation (X);
        # arm bodies and the try body are indented deeper. Walk upward:
        # skip trivia, brace-only arm closings, and anything indented deeper
        # than X (arm/try bodies). At level X a sibling `} catch (T) {`
        # continues the chain and is skipped; the first level-X line that is
        # neither a catch arm nor trivia (the `try {`, or outer code) ends
        # the chain. This lets a cancellation-first arm satisfy an umbrella
        # `catch (Throwable)` sitting several typed arms below it — the
        # stacked-catch pattern in BackupRepositoryImpl.saveBackupToFile —
        # while the intervening `try {` of an unrelated earlier statement
        # still stops the walk (CASE 6).
        if (is_catch) {
            match(lines[n], /^[[:space:]]*/)
            base_indent = RLENGTH
            for (i = n - 1; i >= 1; i--) {
                line = lines[i]

                if (line ~ /^[[:space:]]*$/) continue                  # blank
                if (line ~ /^[[:space:]]*(\/\/|\*|\/\*)/) continue      # comment
                if (line ~ /^[[:space:]]*\}[[:space:]]*$/) continue     # brace-only arm close

                match(line, /^[[:space:]]*/)
                if (RLENGTH > base_indent) continue                    # arm/try body — skip

                code = line
                gsub(/\/\/.*$/, "", code)
                if (code ~ /catch \([a-zA-Z_]+: CancellationException\)/) { satisfied = 1; break }
                if (code ~ /catch \([a-zA-Z_]+: [A-Za-z0-9_.<>]+\)/) continue  # sibling arm — keep walking

                break   # level-X non-catch line (try {, outer code) ends the chain
            }
        }

        # --- Case (c): in-body `is CancellationException` rethrow guard --
        if (is_catch && !satisfied) {
            depth = 0
            counted = 0
            for (i = n; i <= NR; i++) {
                body = lines[i]
                gsub(/\/\/.*$/, "", body)

                if (counted && body ~ /is CancellationException/) {
                    # `throw` on the same line, or within the next two
                    # (multi-line `if (…) {\n throw e\n }` form).
                    for (j = i; j <= i + 2 && j <= NR; j++) {
                        if (lines[j] ~ /(^|[^A-Za-z0-9_])throw([^A-Za-z0-9_]|$)/) {
                            satisfied = 1
                            break
                        }
                    }
                    if (satisfied) break
                }

                nopen  = gsub(/\{/, "{", body)
                nclose = gsub(/\}/, "}", body)
                depth += nopen - nclose
                if (nopen > 0) counted = 1
                if (counted && depth <= 0) break
            }
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
